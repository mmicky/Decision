/*
 * Copyright 2014 Stratio.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.stratio.streaming.integration

import org.scalatest._
import com.stratio.streaming.commons.exceptions.{StratioEngineOperationException, StratioStreamingException, StratioEngineStatusException, StratioAPISecurityException}
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.curator.framework.CuratorFrameworkFactory
import com.stratio.streaming.commons.constants.STREAMING._
import com.stratio.streaming.messaging.{ColumnNameValue, ColumnNameType}
import com.stratio.streaming.commons.constants.ColumnType
import scala.collection.JavaConversions._
import util.control.Breaks._
import com.stratio.streaming.api.{StratioStreamingAPIConfig, StratioStreamingAPIFactory}
import scalaj.http.Http
import com.stratio.streaming.zookeeper.ZookeeperConsumer
import com.stratio.streaming.commons.constants._;

class StratioStreamingIntegrationTests
  extends  FunSpec
  with StratioStreamingAPIConfig
  with ShouldMatchers
  with BeforeAndAfterEach
  with BeforeAndAfterAll {

  var zookeeperCluster = "localhost:2181"
  val retryPolicy = new ExponentialBackoffRetry(1000, 3)
  var zookeeperClient = CuratorFrameworkFactory.newClient(zookeeperCluster, retryPolicy)
  var zookeeperConsumer = new ZookeeperConsumer(zookeeperClient)
  var streamingAPI = StratioStreamingAPIFactory.create()
  val testStreamName = "unitTestsStream"
  val internalTestStreamName = "stratio_"
  val elasticSearchIndex = "stratiostreaming"
  var elasticSearchHost = ""
  var elasticSearchPort = ""

  override def beforeAll(conf: ConfigMap) {
    if (configurationHasBeenDefinedThroughCommandLine(conf)) {
      val zookeeperHost = conf.get("zookeeperHost").get.toString
      val zookeeperPort = conf.get("zookeeperPort").get.toString
      val kafkaHost = conf.get("kafkaHost").get.toString
      val kafkaPort = conf.get("kafkaPort").get.toString
      elasticSearchHost = conf.get("elasticSearchHost").get.toString
      elasticSearchPort = conf.get("elasticSearchPort").get.toString
      streamingAPI.initializeWithServerConfig(kafkaHost,
        kafkaPort.toInt,
        zookeeperHost,
        zookeeperPort.toInt)
      zookeeperCluster = s"$zookeeperHost:$zookeeperPort"
      zookeeperClient = CuratorFrameworkFactory.newClient(zookeeperCluster, retryPolicy)
      zookeeperConsumer = new ZookeeperConsumer(zookeeperClient)
    } else {
      //Pickup the config from stratio-streaming.conf
      elasticSearchHost = config.getString("elasticsearch.server")
      elasticSearchPort = config.getString("elasticsearch.port")
      streamingAPI.initialize()
    }
    zookeeperClient.start()
    checkStatusAndCleanTheEngine()
  }

  def configurationHasBeenDefinedThroughCommandLine(conf: ConfigMap) = {
    conf.get("zookeeperHost").isDefined &&
    conf.get("zookeeperPort").isDefined &&
    conf.get("kafkaHost").isDefined &&
    conf.get("kafkaPort").isDefined &&
    conf.get("elasticSearchHost").isDefined &&
    conf.get("elasticSearchPort").isDefined
  }


  override def beforeEach() {
    checkStatusAndCleanTheEngine()
  }

  def checkStatusAndCleanTheEngine() {
    if (!zookeeperConsumer.zNodeExists(ZK_EPHEMERAL_NODE_PATH)) {
      zookeeperClient.create().forPath(ZK_EPHEMERAL_NODE_PATH)
      //Delay to get rid of flakiness
      Thread.sleep(2000)
    }
    cleanStratioStreamingEngine()
  }

  describe("The create operation") {
    it("should create a new stream when the stream does not exist") {
      val firstStreamColumn = new ColumnNameType("column1", ColumnType.INTEGER)
      val secondStreamColumn = new ColumnNameType("column2", ColumnType.STRING)
      val thirdStreamColumn = new ColumnNameType("column3", ColumnType.BOOLEAN)
      val fourthStreamColumn = new ColumnNameType("column4", ColumnType.DOUBLE)
      val fifthStreamColumn = new ColumnNameType("column5", ColumnType.FLOAT)
      val sixthStreamColumn = new ColumnNameType("column6", ColumnType.LONG)
      val columnList = Seq(firstStreamColumn,
        secondStreamColumn,
        thirdStreamColumn,
        fourthStreamColumn,
        fifthStreamColumn,
        sixthStreamColumn)
      try {
        streamingAPI.createStream(testStreamName, columnList)
      } catch {
        case ssEx: StratioStreamingException => fail()
      }

      theNumberOfUserDefinedStreams should be(1)
      theNumberOfColumnsOfTheStream(testStreamName) should be(6)
    }

    it("should throw a StratioEngineOperationException when creating a stream that already exists") {
      val firstStreamColumn = new ColumnNameType("column1", ColumnType.INTEGER)
      val secondStreamColumn = new ColumnNameType("column2", ColumnType.STRING)
      val columnList = Seq(firstStreamColumn, secondStreamColumn)
      intercept [StratioEngineOperationException] {
        streamingAPI.createStream(testStreamName, columnList)
        streamingAPI.createStream(testStreamName, columnList)
      }
    }

    it("should throw a StratioAPISecurityException when creating an internal stream") {
      val firstStreamColumn = new ColumnNameType("column1", ColumnType.INTEGER)
      val secondStreamColumn = new ColumnNameType("column2", ColumnType.STRING)
      val columnList = Seq(firstStreamColumn, secondStreamColumn)
      val statStream = STREAMING.STATS_NAMES.STATS_STREAMS(0)
      intercept [StratioAPISecurityException] {
        streamingAPI.createStream(statStream, columnList)
      }
    }
  }

  describe("The insert operation") {
    it("should throw a StratioAPISecurityException when insert data into a stream with the stratio_ prefix") {
      val firstColumnValue = new ColumnNameValue("column1", new Integer(111111))
      val secondColumnValue = new ColumnNameValue("column2", "testString")
      val columnValues = Seq(firstColumnValue, secondColumnValue)
      intercept [StratioAPISecurityException] {
        streamingAPI.insertData(internalTestStreamName, columnValues)
      }
    }
  }

  describe("The alter operation") {
    it("should add new columns to an existing stream") {
      val firstStreamColumn = new ColumnNameType("column1", ColumnType.INTEGER)
      val secondStreamColumn = new ColumnNameType("column2", ColumnType.STRING)
      val columnList = Seq(firstStreamColumn, secondStreamColumn)
      val thirdStreamColumn = new ColumnNameType("column3", ColumnType.INTEGER)
      val newColumnList = Seq(thirdStreamColumn)
      try {
        streamingAPI.createStream(testStreamName, columnList)
        streamingAPI.alterStream(testStreamName, newColumnList)
      } catch {
        case ssEx: StratioStreamingException => fail()
      }

      theNumberOfUserDefinedStreams should be(1)
      theNumberOfColumnsOfTheStream(testStreamName) should be(3)
    }

    it("should throw a StratioEngineOperationException when adding a column that already exists") {
      val firstStreamColumn = new ColumnNameType("column1", ColumnType.INTEGER)
      val secondStreamColumn = new ColumnNameType("column2", ColumnType.STRING)
      val columnList = Seq(firstStreamColumn, secondStreamColumn)
      val thirdStreamColumn = new ColumnNameType("column1", ColumnType.INTEGER)
      val newColumnList = Seq(thirdStreamColumn)
      streamingAPI.createStream(testStreamName, columnList)
      intercept [StratioEngineOperationException] {
        streamingAPI.alterStream(testStreamName, newColumnList)
      }
    }

    it("should throw a StratioEngineOperationException when adding a column to a non existing stream") {
      val firstStreamColumn = new ColumnNameType("column1", ColumnType.INTEGER)
      val secondStreamColumn = new ColumnNameType("column2", ColumnType.STRING)
      val columnList = Seq(firstStreamColumn, secondStreamColumn)
      intercept [StratioEngineOperationException] {
        streamingAPI.alterStream(testStreamName, columnList)
      }
    }

    it("should throw a StratioAPISecurityException when adding a column to an internal stream") {
      val thirdStreamColumn = new ColumnNameType("newColumn", ColumnType.INTEGER)
      val newColumnList = Seq(thirdStreamColumn)
      val internalStream = STREAMING.STATS_NAMES.STATS_STREAMS(0)
      intercept [StratioAPISecurityException] {
        streamingAPI.alterStream(internalStream, newColumnList)
      }
    }
  }

  describe("The add query operation") {
    it("should add new queries to an existing stream") {
      val alarmsStream = "alarms"
      val firstStreamColumn = new ColumnNameType("column1", ColumnType.INTEGER)
      val secondStreamColumn = new ColumnNameType("column2", ColumnType.STRING)
      val thirdStreamColumn = new ColumnNameType("column3", ColumnType.BOOLEAN)
      val fourthStreamColumn = new ColumnNameType("column4", ColumnType.DOUBLE)
      val fifthStreamColumn = new ColumnNameType("column5", ColumnType.FLOAT)
      val sixthStreamColumn = new ColumnNameType("column6", ColumnType.LONG)
      val columnList = Seq(firstStreamColumn,
        secondStreamColumn,
        thirdStreamColumn,
        fourthStreamColumn,
        fifthStreamColumn,
        sixthStreamColumn)
      val theFirstQuery = s"from $testStreamName select column1, column2, column3, column4, column5, column6 insert into $alarmsStream for current-events"
      try {
        streamingAPI.createStream(alarmsStream, columnList)
        streamingAPI.createStream(testStreamName, columnList)
        streamingAPI.addQuery(testStreamName, theFirstQuery)
      } catch {
        case ssEx: StratioStreamingException => fail()
      }
      Thread.sleep(2000)
      theNumberOfQueriesOfTheStream(testStreamName) should be(1)
    }

    it("should throw a StratioEngineOperationException when creating a wrong query") {
      val alarmsStream = "alarms"
      val firstStreamColumn = new ColumnNameType("column1", ColumnType.INTEGER)
      val secondStreamColumn = new ColumnNameType("column2", ColumnType.STRING)
      val columnList = Seq(firstStreamColumn, secondStreamColumn)
      val theQuery = s"from $testStreamName select column1, column2, column3"
      streamingAPI.createStream(alarmsStream, columnList)
      streamingAPI.createStream(testStreamName, columnList)
      intercept [StratioEngineOperationException] {
          streamingAPI.addQuery(testStreamName, theQuery)
      }
    }

    it("should throw a StratioAPISecurityException when adding a query to a non user-defined stream") {
      val internalStream = "stratio_stats_base"
      val firstStreamColumn = new ColumnNameType("column1", ColumnType.INTEGER)
      val secondStreamColumn = new ColumnNameType("column2", ColumnType.STRING)
      val columnList = Seq(firstStreamColumn, secondStreamColumn)
      val theQuery = s"from $testStreamName select column1, column2 insert into $internalStream for current-events"
      streamingAPI.createStream(testStreamName, columnList)
      intercept [StratioAPISecurityException] {
        streamingAPI.addQuery(internalStream, theQuery)
      }
    }
  }

  describe("The remove query operation") {
    it("should remove the queries from an existing stream") {
      val alarmsStream = "alarms"
      val firstStreamColumn = new ColumnNameType("column1", ColumnType.INTEGER)
      val secondStreamColumn = new ColumnNameType("column2", ColumnType.STRING)
      val columnList = Seq(firstStreamColumn, secondStreamColumn)
      val theQuery = s"from $testStreamName select column1, column2 insert into $alarmsStream for current-events"
      try {
        streamingAPI.createStream(alarmsStream, columnList)
        streamingAPI.createStream(testStreamName, columnList)
        val queryId = streamingAPI.addQuery(testStreamName, theQuery)
        streamingAPI.removeQuery(testStreamName, queryId)
      } catch {
        case ssEx: StratioStreamingException => fail()
      }

      theNumberOfQueriesOfTheStream(testStreamName) should be(0)
    }

    it("should throw a StratioEngineOperationException when removing a non existing query") {
      val firstStreamColumn = new ColumnNameType("column1", ColumnType.INTEGER)
      val secondStreamColumn = new ColumnNameType("column2", ColumnType.STRING)
      val columnList = Seq(firstStreamColumn, secondStreamColumn)
      val nonExistingQueryId = "1234"
      intercept [StratioEngineOperationException] {
        streamingAPI.createStream(testStreamName, columnList)
        streamingAPI.removeQuery(testStreamName, nonExistingQueryId)
      }
    }
  }

  describe("The drop operation") {
    it("should remove an existing stream") {
      val firstStreamColumn = new ColumnNameType("column1", ColumnType.INTEGER)
      val secondStreamColumn = new ColumnNameType("column2", ColumnType.STRING)

      val columnList = Seq(firstStreamColumn, secondStreamColumn)
      try {
        streamingAPI.createStream(testStreamName, columnList)
        streamingAPI.dropStream(testStreamName)
      } catch {
        case ssEx: StratioStreamingException => fail()
      }
      theNumberOfUserDefinedStreams should be(0)
    }

    it("should throw a StratioEngineOperationException when removing a stream that does not exist") {
      val nonExistingStream = "nonExistingStream"
      intercept [StratioEngineOperationException] {
        streamingAPI.dropStream(nonExistingStream)
      }
    }

    it("should throw a StratioAPISecurityException when removing an internal stream") {
      intercept [StratioAPISecurityException] {
        streamingAPI.dropStream(STREAMING.STATS_NAMES.STATS_STREAMS(1))
      }
    }
  }

  describe("The listen operation") {
    it("should return the stream flow") {
      val firstStreamColumn = new ColumnNameType("column1", ColumnType.INTEGER)
      val secondStreamColumn = new ColumnNameType("column2", ColumnType.STRING)
      val columnList = Seq(firstStreamColumn, secondStreamColumn)
      val firstColumnValue = new ColumnNameValue("column1", new Integer(1))
      val secondColumnValue = new ColumnNameValue("column2", "testValue")
      val streamData = Seq(firstColumnValue, secondColumnValue)
      try {
        streamingAPI.createStream(testStreamName, columnList)
        val streams = streamingAPI.listenStream(testStreamName)
        Thread.sleep(2000)
        streamingAPI.insertData(testStreamName, streamData)
        for (stream <- streams) {
          val firstColumn = stream.message.getColumns.get(0)
          firstColumn.getColumn should be("column1")
          firstColumn.getValue should be(1)
          firstColumn.getType should be("INT")
          val secondColumn = stream.message.getColumns.get(1)
          secondColumn.getColumn should be("column2")
          secondColumn.getValue should be("testValue")
          secondColumn.getType should be("STRING")
          break
        }
      } catch {
        case ssEx: StratioStreamingException => fail()
        case _ => assert(true)
      } finally {
        streamingAPI.stopListenStream(testStreamName)
      }
    }

    it("should throw a StratioAPISecurityException when listening to an internal stream") {
      intercept [StratioAPISecurityException] {
        streamingAPI.listenStream(internalTestStreamName)
      }
    }
  }

  describe("The stop listen operation") {
    it("should stop the stream flow") {
      val firstStreamColumn = new ColumnNameType("column1", ColumnType.INTEGER)
      val secondStreamColumn = new ColumnNameType("column2", ColumnType.STRING)
      val columnList = Seq(firstStreamColumn, secondStreamColumn)
      val firstColumnValue = new ColumnNameValue("column1", new Integer(1))
      val secondColumnValue = new ColumnNameValue("column2", "testValue")
      val streamData = Seq(firstColumnValue, secondColumnValue)
      streamingAPI.createStream(testStreamName, columnList)
      val streams = streamingAPI.listenStream(testStreamName)
      Thread.sleep(2000)
      streamingAPI.insertData(testStreamName, streamData)
      streamingAPI.stopListenStream(testStreamName)
      for (stream <- streams) {
        fail()
      }
      assert(true)
    }

    it("should throw a StratioAPISecurityException when stop listening to an internal status") {
      intercept [StratioAPISecurityException] {
        streamingAPI.stopListenStream(internalTestStreamName)
      }
    }
  }

  describe("The index operation") {
    it("should index the stream to elasticsearch") {
      val firstStreamColumn = new ColumnNameType("column1", ColumnType.STRING)
      val columnList = Seq(firstStreamColumn)
      val firstColumnValue = new ColumnNameValue("column1", "testValue")
      val streamData = Seq(firstColumnValue)
      try {
        streamingAPI.createStream(testStreamName, columnList)
        streamingAPI.indexStream(testStreamName)
        streamingAPI.insertData(testStreamName, streamData)
        Thread.sleep(3000)
      } catch {
        case ssEx: StratioStreamingException => fail()
      }
      theStreamIsIndexed(testStreamName) should be(true)
    }
  }

  /*
  describe("The add to cassandra operation") {
    it("should add a row to cassandra when insert data in a stream with the SAVE_TO_CASSANDRA operation defined", Tag("wip")) {
      val firstStreamColumn = new ColumnNameType("column1", ColumnType.STRING)
      val columnList = Seq(firstStreamColumn)
      val firstColumnValue = new ColumnNameValue("column1", "testValue")
      val streamData = Seq(firstColumnValue)
      try {
        streamingAPI.createStream(testStreamName, columnList)
        streamingAPI.saveToCassandra(testStreamName)
        streamingAPI.insertData(testStreamName, streamData)
        Thread.sleep(3000)
      } catch {
        case ssEx: StratioStreamingException => fail()
      }
      theRowHasBeenStored(testStreamName) should be(true)
    }
  }*/

  describe("The Streaming Engine") {
    it("should throw a StratioEngineStatusException when streaming engine is not running") {
      val firstStreamColumn = new ColumnNameType("column1", ColumnType.INTEGER)
      val secondStreamColumn = new ColumnNameType("column2", ColumnType.STRING)
      val columnList = Seq(firstStreamColumn, secondStreamColumn)
      removeEphemeralNode()
      //Add some delay to wait for the event to be triggered
      Thread.sleep(1000)
      intercept [StratioEngineStatusException] {
        streamingAPI.createStream(testStreamName, columnList)
      }
    }
  }

  def removeEphemeralNode() {
    zookeeperConsumer.removeZNode(ZK_EPHEMERAL_NODE_PATH)
  }

  def cleanStratioStreamingEngine() {
    userDefinedStreams.foreach(stream => streamingAPI.dropStream(stream.getStreamName))
  }

  def userDefinedStreams() = {
    streamingAPI.listStreams().filterNot(stream => stream.getStreamName.startsWith("stratio_"))
  }

  def theNumberOfUserDefinedStreams() = {
    userDefinedStreams.size
  }

  def theNumberOfColumnsOfTheStream(streamName: String) = {
    streamingAPI.columnsFromStream(streamName).size
  }

  def theNumberOfQueriesOfTheStream(streamName: String) = {
    streamingAPI.queriesFromStream(streamName).size
  }

  def theStreamIsIndexed(streamName: String) = {
    Http(s"http://$elasticSearchHost:$elasticSearchPort/$elasticSearchIndex/_mapping/$streamName").asString.contains(streamName)
  }
}
