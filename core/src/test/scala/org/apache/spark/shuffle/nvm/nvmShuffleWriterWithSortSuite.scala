package org.apache.spark.shuffle.nvm

import org.mockito.Mockito._
import org.mockito.{Mock, MockitoAnnotations}
import org.mockito.Answers.RETURNS_SMART_NULLS
import org.scalatest.Matchers
import org.apache.spark._
import org.apache.spark.executor.{ShuffleWriteMetrics, TaskMetrics}
import org.apache.spark.memory.MemoryTestingUtils
import org.apache.spark.serializer._
import org.apache.spark.storage._
import org.apache.spark.storage.nvm._
import org.apache.spark.shuffle.{BaseShuffleHandle, IndexShuffleBlockResolver}
import org.apache.spark.util.Utils
import org.apache.spark.util.configuration.nvm.nvmConf

class nvmShuffleWriterWithSortSuite extends SparkFunSuite with SharedSparkContext with Matchers {

  private val shuffleId = 0
  private val numMaps = 5
  val blockId = new ShuffleBlockId(shuffleId, 2, 0)

  private var shuffleBlockResolver: nvmShuffleBlockResolver = _
  private var serializer: JavaSerializer = _
  private var nvmconf: nvmConf = _
  private var taskMetrics: TaskMetrics = _
  private var partitioner: Partitioner = _
  private var serializerManager: SerializerManager = _
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

    when(blockManager.shuffleServerId).thenReturn(shuffleServerId)

    partitioner = new Partitioner() {
      def numPartitions = 1
      def getPartition(key: Any) = Utils.nonNegativeMod(key.hashCode, numPartitions)
    }
  }

  override def afterEach(): Unit = {
    try {
      shuffleBlockResolver.stop()
    } finally {
      super.afterAll()
    }
  }

  def verify(buf: nvmManagedBuffer, expected: List[(Int, Int)]): Unit = {
    val inStream = buf.createInputStream()
    val inObjStream = serializer.newInstance().deserializeStream(inStream)
    for (kv <- expected) {
      val k = inObjStream.readObject().asInstanceOf[Int]
      val v = inObjStream.readObject().asInstanceOf[Int]
      logDebug(s"$k->$v")
      assert(k.equals(kv._1))
      assert(v.equals(kv._2))
    }
    inObjStream.close()
  }

  test("write with some records with mapSideCombine") {
    val context = MemoryTestingUtils.fakeTaskContext(sc.env)
    def records: Iterator[(Int, Int)] =
      Iterator((1, 1), (5, 5)) ++ (0 until 100000).iterator.map(x => (2, 2))
    val agg = new Aggregator[Int, Int, Int](i => i, (i, j) => i + j, (i, j) => i + j)
    val ord = implicitly[Ordering[Int]]
    val shuffleHandle: BaseShuffleHandle[Int, Int, Int] = {
      val dependency = mock(classOf[ShuffleDependency[Int, Int, Int]])
      when(dependency.partitioner).thenReturn(partitioner)
      when(dependency.serializer).thenReturn(serializer)
      when(dependency.aggregator).thenReturn(Some(agg))
      when(dependency.keyOrdering).thenReturn(Some(ord))
      when(dependency.mapSideCombine).thenReturn(true)

      new BaseShuffleHandle(shuffleId, numMaps = numMaps, dependency)
    }
    val writer = new nvmShuffleWriter[Int, Int, Int](
      shuffleBlockResolver,
      blockManager,
      serializerManager,
      shuffleHandle,
      mapId = 2,
      context,
      conf,
      nvmconf)
    writer.write(records.toIterator)
    writer.stop(success = true)
    val buf = shuffleBlockResolver.getBlockData(blockId).asInstanceOf[nvmManagedBuffer]
    val expected: Iterator[(Int, Int)] = Iterator((1, 1), (2, 200000), (5, 5))
    val inStream = buf.createInputStream()
    val inObjStream = serializer.newInstance().deserializeStream(inStream)
    while (expected.hasNext) {
      val k = inObjStream.readObject().asInstanceOf[Int]
      val v = inObjStream.readObject().asInstanceOf[Int]
      val record = expected.next()
      assert(k.equals(record._1))
      assert(v.equals(record._2))
      println(k + " " + v + " " + " " + record._1 + " " + record._2)
    }
    inObjStream.close()
  }
}
