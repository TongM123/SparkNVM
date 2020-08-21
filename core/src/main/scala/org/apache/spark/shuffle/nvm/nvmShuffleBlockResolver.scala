package org.apache.spark.shuffle.nvm

import org.apache.spark._
import org.apache.spark.internal.Logging
import org.apache.spark.shuffle.IndexShuffleBlockResolver
import org.apache.spark.storage.{BlockManager, ShuffleBlockId}
import org.apache.spark.storage.nvm.{nvmBlockOutputStream, PersistentMemoryHandler}
import org.apache.spark.network.buffer.ManagedBuffer

private[spark] class nvmShuffleBlockResolver(
    conf: SparkConf,
    _blockManager: BlockManager = null)
  extends IndexShuffleBlockResolver(conf, _blockManager) with Logging {
  // create ShuffleHandler here, so multiple executors can share
  var partitionBufferArray: Array[nvmBlockOutputStream] = _

  override def getBlockData(blockId: ShuffleBlockId): ManagedBuffer = {
    // return BlockId corresponding ManagedBuffer
    // 新建一个nvmManagedBuffer并返回
    val persistentMemoryHandler = PersistentMemoryHandler.getPersistentMemoryHandler
    persistentMemoryHandler.getPartitionManagedBuffer(blockId.name)
  }

  override def stop() {
    PersistentMemoryHandler.stop()
    super.stop()
  }
}
