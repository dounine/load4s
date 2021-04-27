package test.com.dounine.load4s.net

import akka.actor.testkit.typed.scaladsl.{
  LogCapturing,
  ScalaTestWithActorTestKit
}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import akka.cluster.typed.{Cluster, Join}
import akka.persistence.typed.PersistenceId
import akka.stream.alpakka.udp.Datagram
import akka.stream.alpakka.udp.scaladsl.Udp
import akka.stream.scaladsl.{Flow, Sink, Source}
import com.dounine.load4s.behaviors.client.{UDPClient, UDPClientManager}
import com.dounine.load4s.model.models.BaseSerializer
import com.dounine.load4s.store.EnumMappers
import com.dounine.load4s.tools.json.JsonParse
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.mockito.MockitoSugar

import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import scala.concurrent.Future
import scala.concurrent.duration._

class UDPClientManagerTest
    extends ScalaTestWithActorTestKit(
      ConfigFactory
        .parseString(s"""
           |akka.remote.artery.canonical.port = 25520
           |akka.persistence.journal.leveldb.dir = "/tmp/journal_${classOf[
          UDPClientManagerTest
        ].getSimpleName}"
           |akka.persistence.snapshot-store.local.dir = "/tmp/snapshot_${classOf[
          UDPClientManagerTest
        ].getSimpleName}"
           |""".stripMargin)
        .withFallback(
          ConfigFactory.parseResources("application-test.conf")
        )
        .resolve()
    )
    with Matchers
    with AnyWordSpecLike
    with LogCapturing
    with EnumMappers
    with MockitoSugar
    with JsonParse {
  val sharding = ClusterSharding(system)

  override def beforeAll(): Unit = {
    super.beforeAll()
    val cluster = Cluster.get(system)
    cluster.manager.tell(Join.create(cluster.selfMember.address))

    sharding.init(
      Entity(
        typeKey = UDPClient.typeKey
      )(
        createBehavior = entityContext =>
          UDPClient(
            PersistenceId.of(
              UDPClient.typeKey.name,
              entityContext.entityId
            ),
            entityContext.shard
          )
      )
    )

    sharding.init(
      Entity(
        typeKey = UDPClientManager.typeKey
      )(
        createBehavior = entityContext =>
          UDPClientManager(
            PersistenceId.of(
              UDPClientManager.typeKey.name,
              entityContext.entityId
            ),
            entityContext.shard
          )
      )
    )

  }

  "udp client manager " should {
    "test" in {
      val manager = sharding.entityRefFor(
        UDPClientManager.typeKey,
        UDPClientManager.typeKey.name
      )

      manager.tell(
        UDPClientManager.CreateClient(
          size = 10,
          time = 1.milliseconds
        )
      )

      TimeUnit.SECONDS.sleep(1)
      val queryProbe = testKit.createTestProbe[BaseSerializer]()
      manager.tell(UDPClientManager.Query()(queryProbe.ref))
      queryProbe
        .receiveMessage()
        .asInstanceOf[UDPClientManager.QueryOk] shouldBe UDPClientManager
        .QueryOk(
          initSize = 10,
          standbySize = 10,
          strategy = UDPClientManager.Strategy(
            element = 1,
            pre = 1.seconds
          ),
          status = "standby"
        )

      val bindToLocal = new InetSocketAddress("127.0.0.1", 8080)
      val bindFlow: Flow[Datagram, Datagram, Future[InetSocketAddress]] =
        Udp.bindFlow(bindToLocal)

      manager.tell(
        UDPClientManager.LoadTest(
          hostName = "127.0.0.1",
          port = bindToLocal.getPort,
          between = 1.seconds,
          elements = 2,
          pre = 1.seconds,
          datastream = 0
        )
      )

      manager.tell(UDPClientManager.Strategy(
        element = 1,
        pre = 1.seconds
      ))

      manager.tell(UDPClientManager.StrategyRun())

      val reslt = Source.maybe
        .via(bindFlow)
        .map(_.getData().utf8String)
        .to(Sink.foreach(message => {
          println(message)
        }))
        .run()

      TimeUnit.SECONDS.sleep(5)


    }

  }
}
