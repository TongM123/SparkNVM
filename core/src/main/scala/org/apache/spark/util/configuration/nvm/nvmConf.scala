package org.apache.spark.util.configuration.nvm

import org.apache.spark.SparkConf

class nvmConf(conf: SparkConf) {
  val enablePmem: Boolean = conf.getBoolean("spark.shuffle.nvm.enable_pmem", defaultValue = true)
  val path_list: List[String] = conf.get("spark.shuffle.nvm.pmem_list", defaultValue = "/root/Spark/tmp").split(",").map(_.trim).toList
  val maxPoolSize: Long = conf.getLong("spark.shuffle.nvm.pmpool_size", defaultValue = 1073741824)
  // val maxStages: Int = conf.getInt("spark.shuffle.nvm.max_stage_num", defaultValue = 1000)
  // val maxMaps: Int = conf.getInt("spark.shuffle.nvm.max_map_num", defaultValue = 1000)
  val spill_throttle: Long = conf.getLong("spark.shuffle.nvm.spill_throttle", defaultValue = 4194304)
  val inMemoryCollectionSizeThreshold: Long =
    conf.getLong("spark.shuffle.spill.nvm.MemoryThreshold", 5 * 1024 * 1024)
  // // val networkBufferSize: Int = conf.getInt("spark.shuffle.nvm.network_buffer_size", 4096 * 3)
  // // val driverHost: String = conf.get("spark.driver.rhost", defaultValue = "172.168.0.43")
  // val driverPort: Int = conf.getInt("spark.driver.rport", defaultValue = 61000)
  // val serverBufferNums: Int = conf.getInt("spark.shuffle.nvm.server_buffer_nums", 256)
  // val serverWorkerNums = conf.getInt("spark.shuffle.nvm.server_pool_size", 1)
  // val clientBufferNums: Int = conf.getInt("spark.shuffle.nvm.client_buffer_nums", 16)
  // val clientWorkerNums = conf.getInt("spark.shuffle.nvm.server_pool_size", 1)
  // val shuffleNodes: Array[Array[String]] =
  //   conf.get("spark.shuffle.nvm.node", defaultValue = "").split(",").map(_.split("-"))
  // val map_serializer_buffer_size = conf.getLong("spark.shuffle.nvm.map_serializer_buffer_size", 16 * 1024)
  // val reduce_serializer_buffer_size = conf.getLong("spark.shuffle.nvm.reduce_serializer_buffer_size", 16 * 1024)
  // val metadataCompress: Boolean = conf.getBoolean("spark.shuffle.nvm.metadata_compress", defaultValue = false)
  // val shuffleBlockSize: Int = conf.getInt("spark.shuffle.nvm.shuffle_block_size", defaultValue = 2048)
  // val pmemCapacity: Long = conf.getLong("spark.shuffle.nvm.pmem_capacity", defaultValue = 264239054848L)
  // val pmemCoreMap = conf.get("spark.shuffle.nvm.dev_core_set", defaultValue = "/dev/dax0.0:0-17,36-53").split(";").map(_.trim).map(_.split(":")).map(arr => arr(0) -> arr(1)).toMap
}
