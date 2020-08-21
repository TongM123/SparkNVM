# SparkNVM

## 集群配置

当时采用了阿里云三个节点，配置了Hadoop集群和Spark集群。

	spark：2.3.0
	Jdk：1.8.0_241
	maven：3.5.4
	scala：2.11.8
	hadoop：2.6.3

### Hadoop搭建过程

创建hadoop用户

	su - root
	mkdir -p /usr/local/hadoop
	mkdir -p /var/local/hadoop
	chmod -R 777 /usr/local/hadoop   #设置权限
	chmod -R 777 /var/local/hadoop   #设置权限
	mkdir -p /home/hadoop/hadoop/hdfs/tmp
	mkdir -p /home/hadoop/hadoop/hdfs/name
	mkdir -p /home/hadoop/hadoop/hdfs/data

修改core-site.xml
```html
<configuration>
   <property>
        <name>hadoop.tmp.dir</name>
        <value>/home/hadoop/hadoop/hdfs/tmp</value>
        <description>Abase for other temporary directories.</description>
   </property>
   <property>
        <name>fs.defaultFS</name>
        <value>hdfs://MASS-master:9000</value>
   </property>
   <property>
        <name>io.file.buffer.size</name>
        <value>131072</value>
  </property>
</configuration>
```

修改hdfs-site.xml
```html
<configuration>
<property>
  <name>dfs.namenode.secondary.http-address</name>
  <value>MASS-master:9001</value>
 </property>
  <property>
   <name>dfs.namenode.name.dir</name>
   <value>/home/hadoop/hadoop/hdfs/name</value>
   <final>true</final>
 </property>

 <property>
  <name>dfs.datanode.data.dir</name>
  <value>/home/hadoop/hadoop/hdfs/data</value>
  <final>true</final>
  </property>

 <property>
  <name>dfs.replication</name>
  <value>1</value>
 </property>

 <property>
  <name>dfs.webhdfs.enabled</name>
  <value>true</value>
 </property>

<property>
  <name>dfs.permissions</name>
  <value>false</value>
</property>

</configuration>
```

配置yarn-site.xml

```html
<configuration>

<!-- Site specific YARN configuration properties -->
<property>
  <name>yarn.resourcemanager.address</name>
  <value>MASS-master:18040</value>
</property>
<property>
  <name>yarn.resourcemanager.scheduler.address</name>
  <value>MASS-master:18030</value>
</property>
<property>
  <name>yarn.resourcemanager.webapp.address</name>
  <value>MASS-master:8088</value>
</property>
<property>
  <name>yarn.resourcemanager.resource-tracker.address</name>
  <value>MASS-master:18025</value>
</property>
<property>
  <name>yarn.resourcemanager.admin.address</name>
  <value>MASS-master:18141</value>
</property>
<property>
  <name>yarn.nodemanager.aux-services</name>
  <value>mapreduce_shuffle</value>
</property>
<property>
  <name>yarn.nodemanager.auxservices.mapreduce.shuffle.class</name>
  <value>org.apache.hadoop.mapred.ShuffleHandler</value>
</property>

</configuration>
```

配置mapred-site.xml

```html
<configuration>
<property>
   <name>mapreduce.framework.name</name>
   <value>yarn</value>
   <final>true</final>
 </property>
 <property>
   <name>mapreduce.jobtracker.http.address</name>
   <value>MASS-master:50030</value>
 </property>
 <property>
  <name>mapreduce.jobhistory.address</name>
  <value>MASS-master:10020</value>
 </property>
 <property>
  <name>mapreduce.jobhistory.webapp.address</name>
  <value>MASS-master:19888</value>
 </property>
 <property>
  <name>mapred.job.tracker</name>
  <value>http://MASS-master:9001</value>
 </property>
</configuration>
```

修改hadoop-env.sh

	export JAVA_HOME=/usr/local/java/jdk1.8.0_241

修改yarn-env.sh 

	export JAVA_HOME=/usr/local/java/jdk1.8.0_241

修改hosts：/etc/hostname和/etc/host文件

配好hosts之间SSH无密码登录节点

在master节点执行namenode的格式化：

	hdfs namenode -format

	start-dfs.sh && start-yarn.sh

jps查看状态

### Spark集群搭建

参考：[网上教程](https://www.aitolearn.com/article/55ce96330cb14708ba6b5a12941a643e "这里")

解压spark，配置/etc/profile
配置spark-env.sh

	export SCALA_HOME=/usr/local/share/scala
	export JAVA_HOME=/usr/local/java/jdk1.8.0_241
	export HADOOP_HOME=/home/hadoop/hadoop
	export HADOOP_CONF_DIR=$HADOOP_HOME/etc/hadoop
	SPARK_MASTER_IP=47.92.7.153
	SPARK_LOCAL_DIRS=/opt/spark
	SPARK_WORKER_MEMORY=2g

配置conf/slaves

	MASS-master
	MASS-slave1
	MASS-slave2
	MASS-slave3

配置conf/log4j.properties

	log4j.rootCategory=WARN, console

启动Master节点，运行start-master.sh，可以看到 master 上多了一个新进程 Master
启动所有的worker节点，运行start-slaves.sh，在 master、slave01 和 slave02 上使用 jps 命令，可以发现都启动了一个 Worker 进程。

解除Hadoop的安全模式：

	bin/hadoop dfsadmin -safemode leave

#### 通过Web UI访问spark状态

添加历史记录，spark应用结束后也能访问，先修改conf/spark-defaults.conf：

	spark.eventLog.enabled           true
	spark.eventLog.dir               hdfs://MASS-master:9000/directory

调用

	hdfs dfs -mkdir /spark-logs

再修改vi conf/spark-env.sh：

	SPARK_HISTORY_OPTS="-Dspark.history.fs.logDirectory=hdfs://MASS-master:9000/spark-logs"

启动history：

	./sbin/start-history-server.sh

构建隧道转发：

	ssh -L 18080:localhost:18080 hadoop@47.92.7.153

浏览器访问：

	localhost:18080

> When you connect to port 8783 on your local system, that connection is tunneled through your ssh link to the ssh server on server.com. From there, the ssh server makes TCP connection to localhost port 8783 and relays data between the tunneled connection and the connection to target of the tunnel.


# 编译过程

**注意事项**：
- 不仅需要把java-1.0-jar-with-dependencies.jar给slave，还有libjnipmdk.so
- 需要将pmem_pool分配的空间调大！！！！！
- 编译需要root权限，`sudo -s`之后需要`source ~/.profile`
- 设置不压缩：`conf/spark-default.conf`中设置`spark.shuffle.compress false`
- 需要配置`/etc/profile`，`hadoop-env.sh`，`spark-env.sh`和`spark-default.sh`


# HiBench数据集

## HiBench配置

修改conf/spark.conf：

	hibench.spark.home      /home/hadoop/spark
	
	# Spark master
	#   standalone mode: spark://xxx:7077
	#   YARN mode: yarn-client
	hibench.spark.master    spark://MASS-master:7077

修改conf/hadoop.conf：

	# Hadoop home
	hibench.hadoop.home     /home/hadoop/hadoop
	
	# The path of hadoop executable
	hibench.hadoop.executable     ${hibench.hadoop.home}/bin/hadoop
	
	# Hadoop configraution directory
	hibench.hadoop.configure.dir  ${hibench.hadoop.home}/etc/hadoop
	
	# The root HDFS path to store HiBench data
	hibench.hdfs.master       hdfs://MASS-master:9000/user/hibench
	
	
	# Hadoop release provider. Supported value: apache, cdh5, hdp
	hibench.hadoop.release    apache

修改conf/hibench.conf：

	hibench.masters.hostnames MASS-master
	hibench.slaves.hostnames MASS-slave1 MASS-slave2 MASS-slave3

还需要将spark-default.conf复制到HiBench/conf文件夹中

修改/conf/spark.conf的内存配置：

	# executor and driver memory in standalone & YARN mode
	spark.executor.memory  2g
	spark.driver.memory    2g


## HiBench编译

编译HiBench：

	mvn -Phadoopbench -Psparkbench -Dscala=2.11 -Dspark=2.3 clean package


## HiBench测试

##### WorkCount

准备数据：执行生成数据脚本，生成数据规模在conf中规定

	bin/workloads/micro/wordcount/prepare/prepare.sh

执行Spark的wordcount工作负载：

	bin/workloads/micro/wordcount/spark/run.sh

**注意事项**：
- 需要先启动Hadoop集群和Spark集群：`/newdisk/spark/sbin/start-master.sh`和`/newdisk/spark/sbin/start-slaves.sh spark://MASS-master:7077`


在report目录下可以看到运行的结果










