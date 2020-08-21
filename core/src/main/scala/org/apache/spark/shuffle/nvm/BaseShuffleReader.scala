package org.apache.spark.shuffle

import org.apache.spark._
import org.apache.spark.internal.{Logging, config}
import org.apache.spark.serializer.SerializerManager
import org.apache.spark.storage.{BlockManager, ShuffleBlockFetcherIterator}
import org.apache.spark.util.CompletionIterator
import org.apache.spark.util.collection.nvm.nvmExternalSorter
import org.apache.spark.util.configuration.nvm.nvmConf
import org.apache.spark.storage.nvm._    // 用于PmemShuffleBlockFetcherIterator

/**
  * Fetches and reads the partitions in range [startPartition, endPartition) from a shuffle by
  * requesting them from other nodes' block stores.
  */
private[spark] class BaseShuffleReader[K, C](handle: BaseShuffleHandle[K, _, C],
                                             startPartition: Int,
                                             endPartition: Int,
                                             context: TaskContext,
                                             nvmconf: nvmConf,
                                             serializerManager: SerializerManager = SparkEnv.get.serializerManager,
                                             blockManager: BlockManager = SparkEnv.get.blockManager,
                                             mapOutputTracker: MapOutputTracker = SparkEnv.get.mapOutputTracker)
  extends ShuffleReader[K, C] with Logging {

  private[this] val dep = handle.dependency

  logDebug("BaseShuffleReader DEBUG: Initialize startPartition="+startPartition+", endPartition="+endPartition)
  /** Read the combined key-values for this reduce task */
  override def read(): Iterator[Product2[K, C]] = {
    // 这个Iterator中包含的数据都是进行了加密和压缩的
    val wrappedStreams = new ShuffleBlockFetcherIterator(
      context,
      blockManager.shuffleClient,
      blockManager,
      // 获取当前reduce任务所需的map任务中间输出数据的BlockManager的BlockManagerId及每个数据块的BlockId与大小
      mapOutputTracker.getMapSizesByExecutorId(handle.shuffleId, startPartition, endPartition),
      serializerManager.wrapStream, // 在ShuffleBlockFetcherIterator.next()中的加密函数streamWrapper(blockId, in)
      // Note: we use getSizeAsMb when no suffix is provided for backwards compatibility
      SparkEnv.get.conf.getSizeAsMb("spark.reducer.maxSizeInFlight", "48m") * 1024 * 1024,
      SparkEnv.get.conf.getInt("spark.reducer.maxReqsInFlight", Int.MaxValue),
      SparkEnv.get.conf.get(config.REDUCER_MAX_BLOCKS_IN_FLIGHT_PER_ADDRESS),
      SparkEnv.get.conf.get(config.MAX_REMOTE_BLOCK_SIZE_FETCH_TO_MEM),
      SparkEnv.get.conf.getBoolean("spark.shuffle.detectCorrupt", true))

    // 序列化器
    val serializerInstance = dep.serializer.newInstance()

    // 给每个流创建一个key/value的迭代器
    // 通过ShuffleBlockFetcherIterator()可以拿到所有ShuffleMapTask输出的文件数据(并且是当前partition的数据)，把这些数据反序列化放到可迭代变量recordIter中
    val recordIter = wrappedStreams.flatMap { case (blockId, wrappedStream) =>
      // Note: the asKeyValueIterator below wraps a key/value iterator inside of a
      // NextIterator. The NextIterator makes sure that close() is called on the
      // underlying InputStream when all records have been read.
      // 进行反序列化
      serializerInstance.deserializeStream(wrappedStream).asKeyValueIterator
    }

    // Update the context task metrics for each record read.
    // 对任务的度量信息进行更新
    val readMetrics = context.taskMetrics.createTempShuffleReadMetrics()
    // 把recordIter 放到 metricIter中(ShuffleMapTask中的输出数据文件都在这里边)
    // CompletionIterator相比普通的Iterator的区别就是在结束之后会调用一个completion函数
    // CompletionIterator通过它伴生对象的apply方法创建，传入第二个参数即completionFunction
    val metricIter = CompletionIterator[(Any, Any), Iterator[(Any, Any)]](
      recordIter.map { record =>
        readMetrics.incRecordsRead(1)
        record   // 这里的record就是 next()返回的 PMEMBufferReleasingInputStream(input, this)
      },
      context.taskMetrics().mergeShuffleReadMetrics())

    // An interruptible iterator must be used here in order to support task cancellation
    // 创建可中断的迭代器，能够支持TaskAttempt的取消操作
    // 把metricIter作为实例化参数传给InterruptibleIterator，赋值给变量interruptibleIter
    val interruptibleIter = new InterruptibleIterator[(Any, Any)](context, metricIter)

    // 处理可能存在的聚合迭代
    val aggregatedIter: Iterator[Product2[K, C]] = if (dep.aggregator.isDefined) {
      if (dep.mapSideCombine) {
        // 如果指定了聚合函数且允许在map端进行合并，在reduce端对数据进行聚合
        // 把interruptibleIter转化为可迭代的变量 combinedKeyValuesIterator
        // We are reading values that are already combined
        val combinedKeyValuesIterator = interruptibleIter.asInstanceOf[Iterator[(K, C)]]

        // 使用聚合器进行聚合
        // 在 combine 的过程中借助了ExternalAppendOnlyMap
        // 通过调用insertAll方法能够将interruptibleIter内部的数据添加到ExternalAppendOnlyMap中
        dep.aggregator.get.combineCombinersByKey(combinedKeyValuesIterator, context)
      } else {
        // We don't know the value type, but also don't care -- the dependency *should*
        // have made sure its compatible w/ this aggregator, which will convert the value
        // type to the combined type C
        val keyValuesIterator = interruptibleIter.asInstanceOf[Iterator[(K, Nothing)]]
        dep.aggregator.get.combineValuesByKey(keyValuesIterator, context)
      }
    } else {
      require(!dep.mapSideCombine, "Map-side combine without Aggregator specified!")
      interruptibleIter.asInstanceOf[Iterator[Product2[K, C]]]
    }

    // Sort the output if there is a sort ordering defined.
    val resultIter = dep.keyOrdering match {
      // 如果dep.keyOrdering 有有排序，则通过ExternalSorter 算法进行排序处理，再返回结果
      case Some(keyOrd: Ordering[K]) =>
        assert(nvmconf.enablePmem == true)
        // Create an ExternalSorter to sort the data.
        val sorter =
          new nvmExternalSorter[K, C, C](context, handle, nvmconf, ordering = Some(keyOrd), serializer = dep.serializer)
        logDebug("call nvmExternalSorter.insertAll for shuffle_0_" + handle.shuffleId + "_[" + startPartition + "," + endPartition + "]")
        sorter.insertAll(aggregatedIter)
        // Use completion callback to stop sorter if task was finished/cancelled.
        context.addTaskCompletionListener(_ => {
          sorter.stop()
        })
        CompletionIterator[Product2[K, C], Iterator[Product2[K, C]]](sorter.iterator, sorter.stop())
      // 判断 dep.keyOrdering 有没有排序，如果没有，直接返回 aggregatedIter
      case None =>
        aggregatedIter
    }

    resultIter match {
      case _: InterruptibleIterator[Product2[K, C]] => resultIter
      case _ =>
        // Use another interruptible iterator here to support task cancellation as aggregator
        // or(and) sorter may have consumed previous interruptible iterator.
        new InterruptibleIterator[Product2[K, C]](context, resultIter)
    }
  }
}
