package org.apache.spark.storage.nvm

import org.apache.spark.storage._
import org.apache.spark.serializer._
import org.apache.spark.executor.TaskMetrics
import org.apache.spark.internal.Logging
import org.apache.spark.{SparkConf, SparkEnv}
import org.apache.spark.util.Utils
import java.io.{File, OutputStream}

import org.apache.spark.util.configuration.nvm.nvmConf

import scala.collection.mutable.ArrayBuffer

class PmemBlockId (stageId: Int, tmpId: Int) extends ShuffleBlockId(stageId, 0, tmpId) {
  override def name: String = "reduce_spill_" + stageId + "_" + tmpId
  override def isShuffle: Boolean = false
}

object PmemBlockId {
  private var tempId: Int = 0
  def getTempBlockId(stageId: Int): PmemBlockId = synchronized {
    val cur_tempId = tempId
    tempId += 1
    new PmemBlockId (stageId, cur_tempId)
  }
}

private[spark] class nvmBlockOutputStream(
    taskMetrics: TaskMetrics,
    blockId: BlockId,
    serializerManager: SerializerManager,
    serializer: Serializer,
    conf: SparkConf,
    nvmconf: nvmConf,
    numMaps: Int = 0,
    numPartitions: Int = 1
) extends DiskBlockObjectWriter(new File(Utils.getConfiguredLocalDirs(conf).toList(0) + "/null"), null, null, 0, true, null, null) with Logging {
  logDebug("nvmBlockOutputStream to be initialized DEBUG")
  var size: Int = 0
  var records: Int = 0
  var recordsPerBlock: Int = 0
  val recordsArray: ArrayBuffer[Int] = ArrayBuffer()
  var spilled: Boolean = false
  var partitionMeta: Array[(Long, Int, Int)] = _
  val root_dir = Utils.getConfiguredLocalDirs(conf).toList(0)

  val persistentMemoryWriter: PersistentMemoryHandler = PersistentMemoryHandler.getPersistentMemoryHandler(nvmconf,
    root_dir, nvmconf.path_list, blockId.name, nvmconf.maxPoolSize)

  //disable metadata updating by default
  //persistentMemoryWriter.updateShuffleMeta(blockId.name)

  logDebug("nvmBlockOutputStream initialized DEBUG")
  val pmemOutputStream: nvmOutputStream = new nvmOutputStream(
    persistentMemoryWriter, numPartitions, blockId.name, numMaps)
  val serInstance = serializer.newInstance()
  var objStream: SerializationStream = serInstance.serializeStream(pmemOutputStream)

  // 调用serializeStream.writeKey/writeValue将key/value序列化写入————调用nvmOutputStream.write
  override def write(key: Any, value: Any): Unit = {
    logDebug("nvmBlockOutputStream.write DEBUG: records=" + records + ", recordsPerBlock=" + recordsPerBlock + ", blockId.name=" + blockId.name+", isShuffle=" + blockId.isShuffle)
    objStream.writeKey(key)       // key-对应于wordCount中每个word
    objStream.writeValue(value)   // value-对应于wordCount中每个word的次数
    logDebug("key=" + key)
    logDebug("value=" + value)
    records += 1
    recordsPerBlock += 1
		if (blockId.isShuffle == true) {
      taskMetrics.shuffleWriteMetrics.incRecordsWritten(1)
    }
    maybeSpill()
  }

  override def close() {
    pmemOutputStream.close()
    objStream = null
  }

  override def flush() {
    logDebug("nvmBlockOutputStream.flush DEBUG")
    // SerializationStream.flush()进行序列化
    objStream.flush()
  }

  // 看什么时候flush到PM
  def maybeSpill(force: Boolean = false): Unit = {
    if (force == true) {
      flush()
    }
    if ((nvmconf.spill_throttle != -1 && pmemOutputStream.remainingSize >= nvmconf.spill_throttle) || force == true) {
      logDebug("pmemOutputStream.remainingSize=" + pmemOutputStream.remainingSize + ", nvmconf.spill_throttle=" + nvmconf.spill_throttle)
      val start = System.nanoTime()
      pmemOutputStream.flush()      // 调用setPartition，将缓存刷回NVM
      val bufSize = pmemOutputStream.flushedSize
      if (bufSize > 0) {
        recordsArray += recordsPerBlock
        recordsPerBlock = 0
        size += bufSize

        if (blockId.isShuffle == true) {
          val writeMetrics = taskMetrics.shuffleWriteMetrics
          writeMetrics.incWriteTime(System.nanoTime() - start)
          writeMetrics.incBytesWritten(bufSize)
        } else {
          taskMetrics.incDiskBytesSpilled(bufSize)
        }
        pmemOutputStream.reset()
        spilled = true
      }
    }
  }

  def ifSpilled(): Boolean = {
    spilled
  }

  def getPartitionMeta(): Array[(Long, Int, Int)] = {
    if (partitionMeta == null) {
      var i = -1
      partitionMeta = persistentMemoryWriter.getPartitionBlockInfo(blockId.name).map{ x=> i+=1; (x._1, x._2, recordsArray(i))}
    }
    partitionMeta
  }

  def getBlockId(): BlockId = {
    blockId
  }

  def getRkey(): Long = {
    persistentMemoryWriter.rkey
  }

  def getTotalRecords(): Long = {
    records    
  }

  def getSize(): Long = {
    size
  }

  def getPersistentMemoryHandler: PersistentMemoryHandler = {
    persistentMemoryWriter
  }
}
