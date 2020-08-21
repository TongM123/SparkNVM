package org.apache.spark.shuffle.nvm

import org.apache.spark._
import org.apache.spark.internal.Logging
import org.apache.spark.scheduler.MapStatus
import org.apache.spark.shuffle.{BaseShuffleHandle, ShuffleWriter}
import org.apache.spark.storage._
import org.apache.spark.util.collection.nvm.nvmExternalSorter
import org.apache.spark.storage.nvm._
import org.apache.spark.util.configuration.nvm.nvmConf
import org.apache.spark.serializer.SerializerManager
import org.apache.spark.storage.BlockManager

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

private[spark] class nvmShuffleWriter[K, V, C](shuffleBlockResolver: nvmShuffleBlockResolver,
                                                // metadataResolver: MetadataResolver, ???
                                                blockManager: BlockManager,
                                                serializerManager: SerializerManager,
                                                handle: BaseShuffleHandle[K, V, C],
                                                mapId: Int,
                                                context: TaskContext,
                                                conf: SparkConf,
                                                nvmconf: nvmConf)
  extends ShuffleWriter[K, V] with Logging {
  // handle（即BaseShuffleHandle）的dependency属性（类型为ShuffleDependency）
  private[this] val dep = handle.dependency
  private[this] var mapStatus: MapStatus = _
  private[this] val stageId = dep.shuffleId
  private[this] val partitioner = dep.partitioner
  private[this] val numPartitions = partitioner.numPartitions
  private[this] val numMaps = handle.numMaps
  private[this] val writeMetrics = context.taskMetrics().shuffleWriteMetrics
  private[this] val partitionLengths: Array[Long] = Array.fill[Long](numPartitions)(0)
  private[this] var sorter: nvmExternalSorter[K, V, _] = _

  /**
   * Are we in the process of stopping? Because map tasks can call stop() with success = true
   * and then call stop() with success = false if they get an exception, we want to make sure
   * we don't try deleting files, etc twice.
   */
  private var stopping = false

  /**
  * Call PMDK to write data to persistent memory
  * Original Spark writer will do write and mergesort in this function,
  * while by using pmdk, we can do that once since pmdk supports transaction.
  */
  override def write(records: Iterator[Product2[K, V]]): Unit = {
    logDebug("nvmShuffleWriter.write DEBUG: ShuffleBlockId="+stageId+", "+mapId+", numMaps="+numMaps+", numPartitions="+numPartitions)
    // 生成 [partitionId => nvmBlockOutputStream] 的映射
    val PmemBlockOutputStreamArray = (0 until numPartitions).toArray.map(partitionId =>
      new nvmBlockOutputStream(
        context.taskMetrics(),
        ShuffleBlockId(stageId, mapId, partitionId),
        serializerManager,
        dep.serializer,
        conf,
        nvmconf,
        numMaps,
        numPartitions))

    logDebug("PmemBlockOutputStreamArray initialized done")
    if (dep.mapSideCombine) { // do aggregation
      if (dep.aggregator.isDefined) {
        sorter = new nvmExternalSorter[K, V, C](context, handle, nvmconf, dep.aggregator, Some(dep.partitioner),
          dep.keyOrdering, dep.serializer)
        // 合并所有PmemBlockOutputStream，保存到sorter.pmemBlockOutputStreamArray
				sorter.setPartitionByteBufferArray(PmemBlockOutputStreamArray)
        // ExternalSoter的insertAll————将map任务的数据插入缓存map/buffer
        sorter.insertAll(records)
        // 调用spill->spillMemoryIteratorToPmem，把map/buffer中的数据调用pmemBlockOutputStreamArray.write写入PM
        sorter.forceSpillToPmem()
      } else {
        throw new IllegalStateException("Aggregator is empty for map-side combine")
      }
    } else { // no aggregation
      while (records.hasNext) {
        // since we need to write same partition (key, value) together, do a partition index here
        val elem = records.next()
        val partitionId: Int = partitioner.getPartition(elem._1)
        PmemBlockOutputStreamArray(partitionId).write(elem._1, elem._2)
      }
      for (partitionId <- 0 until numPartitions) {
        PmemBlockOutputStreamArray(partitionId).maybeSpill(force = true) // 强制刷入NVM
      }
    }

    // spillPartitionArray记录了所有spill的partition
    var spilledPartition = 0
    val spillPartitionArray: ArrayBuffer[Int] = ArrayBuffer[Int]()
    while (spilledPartition < numPartitions) {
      if (PmemBlockOutputStreamArray(spilledPartition).ifSpilled()) {
        spillPartitionArray.append(spilledPartition)
      }
      spilledPartition += 1
    }

    logDebug("nvmShuffleWriter DEBUG: spilledPartition=" + spilledPartition)

    val pmemBlockInfoMap = mutable.HashMap.empty[Int, Array[(Long, Int)]]
    var output_str : String = ""

    for (i <- spillPartitionArray) {
      partitionLengths(i) = PmemBlockOutputStreamArray(i).size
      output_str += "\tPartition " + i + ": " + partitionLengths(i) + ", records: " + PmemBlockOutputStreamArray(i).records + "\n"
    }

    for (i <- 0 until numPartitions) {
      PmemBlockOutputStreamArray(i).close()
    }

    val shuffleServerId = blockManager.shuffleServerId
    mapStatus = MapStatus(shuffleServerId, partitionLengths)
  }

  /** Close this writer, passing along whether the map completed */
  override def stop(success: Boolean): Option[MapStatus] = {
    try {
      if (stopping) {
        return None
      }
      stopping = true
      if (success) {
        Option(mapStatus)
      } else {
        None
      }
    } finally {
      // Clean up our sorter, which may have its own intermediate files
      if (sorter != null) {
        val startTime = System.nanoTime()
        sorter.stop()
        writeMetrics.incWriteTime(System.nanoTime - startTime)
        sorter = null
      }
    }
  }
}
