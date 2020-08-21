package org.apache.spark.shuffle.nvm

import java.util.concurrent.ConcurrentHashMap

import org.apache.spark.internal.Logging
import org.apache.spark.shuffle._
import org.apache.spark.util.configuration.nvm.nvmConf
import org.apache.spark.{ShuffleDependency, SparkConf, SparkEnv, TaskContext}

private[spark] class nvmShuffleManager(conf: SparkConf) extends ShuffleManager with Logging {
  logInfo("Initialize nvmShuffleManager")

  if (!conf.getBoolean("spark.shuffle.spill", defaultValue = true)) {
    logWarning("spark.shuffle.spill was set to false")
  }

  private[this] val numMapsForShuffle = new ConcurrentHashMap[Int, Int]()
  private[this] val nvmconf = new nvmConf(conf)

  override def registerShuffle[K, V, C](shuffleId: Int, numMaps: Int, dependency: ShuffleDependency[K, V, C]): ShuffleHandle = {
    val env: SparkEnv = SparkEnv.get

    new BaseShuffleHandle(shuffleId, numMaps, dependency)
  }

  override def getWriter[K, V](handle: ShuffleHandle, mapId: Int, context: TaskContext): ShuffleWriter[K, V] = {
    assert(handle.isInstanceOf[BaseShuffleHandle[_, _, _]])

    val env: SparkEnv = SparkEnv.get
    val blockManager = SparkEnv.get.blockManager
    val serializerManager = SparkEnv.get.serializerManager
    val numMaps = handle.asInstanceOf[BaseShuffleHandle[_, _, _]].numMaps

    numMapsForShuffle.putIfAbsent(handle.shuffleId, numMaps)

    if (nvmconf.enablePmem) {
      new nvmShuffleWriter(shuffleBlockResolver.asInstanceOf[nvmShuffleBlockResolver], blockManager, serializerManager, 
        handle.asInstanceOf[BaseShuffleHandle[K, V, _]], mapId, context, env.conf, nvmconf)
    } else {
      new BaseShuffleWriter(shuffleBlockResolver.asInstanceOf[IndexShuffleBlockResolver], blockManager, serializerManager, 
        handle.asInstanceOf[BaseShuffleHandle[K, V, _]], mapId, context, nvmconf)
    }
  }

  override def getReader[K, C](handle: _root_.org.apache.spark.shuffle.ShuffleHandle, startPartition: Int, endPartition: Int, context: _root_.org.apache.spark.TaskContext): _root_.org.apache.spark.shuffle.ShuffleReader[K, C] = {
    new BaseShuffleReader(
      handle.asInstanceOf[BaseShuffleHandle[K, _, C]], startPartition, endPartition, context, nvmconf)
  }

  override def unregisterShuffle(shuffleId: Int): Boolean = {
    Option(numMapsForShuffle.remove(shuffleId)).foreach { numMaps =>
      (0 until numMaps).foreach { mapId =>
        shuffleBlockResolver.asInstanceOf[IndexShuffleBlockResolver].removeDataByMap(shuffleId, mapId)
      }
    }
    true
  }

  override def stop(): Unit = {
    shuffleBlockResolver.stop()
  }

  override val shuffleBlockResolver: ShuffleBlockResolver = {
    if (nvmconf.enablePmem)
      new nvmShuffleBlockResolver(conf)
    else
      new IndexShuffleBlockResolver(conf)
  }
}
