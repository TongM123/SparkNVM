package org.apache.spark.shuffle.nvm

import org.mockito.Mockito._
import org.mockito.{Mock, MockitoAnnotations}
import org.mockito.Answers.RETURNS_SMART_NULLS
import org.scalatest.Matchers
import org.apache.spark._
import org.apache.spark.executor.TaskMetrics
import org.apache.spark.serializer._
import org.apache.spark.storage._
import org.apache.spark.storage.nvm._
import org.apache.spark.shuffle.BaseShuffleHandle
import org.apache.spark.util.Utils
import org.apache.spark.util.configuration.nvm.nvmConf

class nvmShuffleWriterSuite extends SparkFunSuite with SharedSparkContext with Matchers {
  private val shuffleId = 0
  private val numMaps = 5
  private var shuffleBlockResolver: nvmShuffleBlockResolver = _
  private var serializer: JavaSerializer = _
  private var nvmconf: nvmConf = _
  private var taskMetrics: TaskMetrics = _
  private var serializerManager: SerializerManager = _
  @Mock(answer = RETURNS_SMART_NULLS) private var taskContext: TaskContext = _
  @Mock(answer = RETURNS_SMART_NULLS) private var blockManager: BlockManager = _
  @Mock(answer = RETURNS_SMART_NULLS) private var shuffleServerId: BlockManagerId = _

  override def beforeEach(): Unit = {
    super.beforeEach()
    MockitoAnnotations.initMocks(this)
    conf.set("spark.shuffle.nvm.enable_rdma", "false")
    conf.set("spark.shuffle.nvm.enable_pmem", "true")
    conf.set("spark.shuffle.nvm.pmem_list", "/dev/dax0.0")
    shuffleBlockResolver = new nvmShuffleBlockResolver(conf)
    serializer = new JavaSerializer(conf)
    nvmconf = new nvmConf(conf)
    taskMetrics = new TaskMetrics()
    serializerManager = new SerializerManager(serializer, conf)

    when(taskContext.taskMetrics()).thenReturn(taskMetrics)
    when(blockManager.shuffleServerId).thenReturn(shuffleServerId)
  }

  override def afterEach(): Unit = {
    try {
      shuffleBlockResolver.stop()
    } finally {
      super.afterAll()
    }
  }

  test("write with some records into one partition") {
    var partitioner: Partitioner = new Partitioner() {
      def numPartitions = 1
      def getPartition(key: Any) = Utils.nonNegativeMod(key.hashCode, numPartitions)
    }

    val shuffleHandle: BaseShuffleHandle[Int, Int, Int] = {
      val dependency = mock(classOf[ShuffleDependency[Int, Int, Int]])
      when(dependency.partitioner).thenReturn(partitioner)
      when(dependency.serializer).thenReturn(serializer)
      when(dependency.aggregator).thenReturn(None)
      when(dependency.keyOrdering).thenReturn(None)

      new BaseShuffleHandle(shuffleId, numMaps = numMaps, dependency)
    }

    def records: Iterator[(Int, Int)] =
      Iterator((1, 1), (5, 5)) ++ (0 until 100000).iterator.map(x => (2, 2))
    val expected: Iterator[(Int, Int)] =
      Iterator((1, 1), (5, 5)) ++ (0 until 100000).iterator.map(x => (2, 2))

    val writer = new nvmShuffleWriter[Int, Int, Int](
      shuffleBlockResolver,
      blockManager,
      serializerManager,
      shuffleHandle,
      mapId = 2,
      taskContext,
      conf,
      nvmconf)
    writer.write(records.toIterator)
    writer.stop(success = true)

    // Veiry
    val blockId = new ShuffleBlockId(shuffleId, 2, 0)
    val shuffleWriteMetrics = taskContext.taskMetrics().shuffleWriteMetrics
    assert(shuffleWriteMetrics.recordsWritten == records.size)
    val buf: nvmManagedBuffer = shuffleBlockResolver.getBlockData(blockId).asInstanceOf[nvmManagedBuffer]
    val inStream = buf.createInputStream()
    val inObjStream = serializer.newInstance().deserializeStream(inStream)
    while (expected.hasNext) {
      val k = inObjStream.readObject().asInstanceOf[Int]
      val v = inObjStream.readObject().asInstanceOf[Int]
      val record = expected.next()
      assert(k.equals(record._1))
      assert(v.equals(record._2))
    }
    inObjStream.close
  }

  test("write with some records into multiple partitions") {
    var partitioner: Partitioner = new Partitioner() {
      def numPartitions = 100
      def getPartition(key: Any) = Utils.nonNegativeMod(key.hashCode, numPartitions)
    }

    val shuffleHandle: BaseShuffleHandle[Int, Int, Int] = {
      val dependency = mock(classOf[ShuffleDependency[Int, Int, Int]])
      when(dependency.partitioner).thenReturn(partitioner)
      when(dependency.serializer).thenReturn(serializer)
      when(dependency.aggregator).thenReturn(None)
      when(dependency.keyOrdering).thenReturn(None)

      new BaseShuffleHandle(shuffleId, numMaps = numMaps, dependency)
    }

    def records: Iterator[(Int, Int)] =
      Iterator((1, 1), (5, 5)) ++ (0 until 100000).iterator.map(x => (2, 2))
    val expected: Iterator[(Int, Int)] =
      Iterator((1, 1), (5, 5)) ++ (0 until 100000).iterator.map(x => (2, 2))
    val writer = new nvmShuffleWriter[Int, Int, Int](
      shuffleBlockResolver,
      blockManager,
      serializerManager,
      shuffleHandle,
      mapId = 2,
      taskContext,
      conf,
      nvmconf)
    writer.write(records.toIterator)
    writer.stop(success = true)

    // Veiry
    val shuffleWriteMetrics = taskContext.taskMetrics().shuffleWriteMetrics
    assert(shuffleWriteMetrics.recordsWritten == records.size)
  }
}
