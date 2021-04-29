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
import com.dounine.load4s.behaviors.client.{SocketBehavior, UDPClient}
import com.dounine.load4s.model.models.StockTimeSerieModel
import com.dounine.load4s.model.types.service.IntervalStatus
import com.dounine.load4s.store.EnumMappers
import com.dounine.load4s.tools.json.JsonParse
import com.sksamuel.elastic4s.http.JavaClient
import com.sksamuel.elastic4s._
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.mockito.MockitoSugar

import java.net.InetSocketAddress
import scala.concurrent.duration._
import java.time.{Instant, LocalDateTime, ZoneId}
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import scala.concurrent.Future
import scala.util.{Success, Try}

case class CC(
    clients: String
)
case class Info(
    init: Option[CC]
)
class Load4sTest
    extends ScalaTestWithActorTestKit(
      ConfigFactory
        .parseString(s"""
           |akka.remote.artery.canonical.port = 25520
           |akka.persistence.journal.leveldb.dir = "/tmp/journal_${classOf[
          Load4sTest
        ].getSimpleName}"
           |akka.persistence.snapshot-store.local.dir = "/tmp/snapshot_${classOf[
          Load4sTest
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

  }

  "load4s test" should {
    "sss" in {
//      val ccc =
//        """{"initStart":true}"""
//      val bb = ccc.jsonTo[SocketBehavior.UpdateInfo]
//      info(bb.toString)
//      info(3.seconds.toJson)
      val time = System.currentTimeMillis() / 1000 / 5
      info(
        Instant
          .ofEpochMilli(time * 1000 * 5)
          .atZone(ZoneId.systemDefault())
          .toLocalDateTime
          .toString
      )
      1
    }
    "4s" ignore {
      val cc = sharding.entityRefFor(
        UDPClient.typeKey,
        "uuid123"
      )
      val bindToLocal = new InetSocketAddress("127.0.0.1", 8080)
      val bindFlow: Flow[Datagram, Datagram, Future[InetSocketAddress]] =
        Udp.bindFlow(bindToLocal)

//      cc.tell(
//        UDPClient.AutoFire(
//          hostName = "127.0.0.1",
//          port = bindToLocal.getPort,
//          elements = 1,
//          pre = 500.milliseconds,
//          datastream = 1
//        )
//      )

      val reslt = Source.maybe
        .via(bindFlow)
        .map(_.getData().utf8String)
        .to(Sink.foreach(message => {
          println(message)
        }))
        .run()

      TimeUnit.SECONDS.sleep(1)
      reslt.success(Option.empty)

    }

  }
}
