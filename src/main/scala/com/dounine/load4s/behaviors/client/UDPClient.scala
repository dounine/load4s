package com.dounine.load4s.behaviors.client

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityTypeKey}
import akka.persistence.typed.PersistenceId
import akka.stream.{KillSwitches, SystemMaterializer, UniqueKillSwitch}
import akka.stream.alpakka.udp.Datagram
import akka.stream.alpakka.udp.scaladsl.Udp
import akka.stream.scaladsl.{Keep, Source}
import akka.util.ByteString
import com.dounine.load4s.model.models.BaseSerializer
import com.dounine.load4s.tools.json.JsonParse
import org.slf4j.LoggerFactory

import java.net.InetSocketAddress
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._
object UDPClient extends JsonParse {

  private val logger = LoggerFactory.getLogger(UDPClient.getClass)

  val typeKey: EntityTypeKey[BaseSerializer] =
    EntityTypeKey[BaseSerializer]("UDPClient")

  trait Event extends BaseSerializer

  final case class SendMessage(
      data: String
  ) extends BaseSerializer

  final case class AutoFire(
      hostName: String,
      port: Int,
      elements: Int,
      pre: FiniteDuration,
      datastream: Int = 0
  ) extends BaseSerializer

  final case class Init()(val replyTo: ActorRef[BaseSerializer])
      extends BaseSerializer

  final case class InitOk(clientId: String) extends BaseSerializer

  final case class Stop() extends BaseSerializer

  def apply(
      persistenceId: PersistenceId,
      shard: ActorRef[ClusterSharding.ShardCommand]
  ): Behavior[BaseSerializer] =
    Behaviors.setup { context =>
      {
        implicit val materializer =
          SystemMaterializer(context.system).materializer
        val clientId = persistenceId.id.split("\\|", -1).last

        def standby(): Behavior[BaseSerializer] =
          Behaviors.receiveMessage {
            case e @ Stop() => {
              logger.info(e.logJson)
              Behaviors.stopped
            }
            case e @ Init() => {
              logger.info(e.logJson)
              e.replyTo.tell(InitOk(clientId))
              Behaviors.same
            }
            case e @ SendMessage(data) => {
              logger.info(e.logJson)
              Behaviors.same
            }
            case e @ AutoFire(_, _, _, _, _) => {
              logger.info(e.logJson)
              context.self.tell(e)
              attack(Option.empty)
            }
          }

        def attack(kill: Option[UniqueKillSwitch]): Behavior[BaseSerializer] =
          Behaviors.receiveMessage {
            case e @ Init() => {
              logger.info(e.logJson)
              kill.foreach(_.shutdown())
              standby()
            }
            case e @ Stop() => {
              logger.info(e.logJson)
              kill.foreach(_.shutdown())
              Behaviors.stopped
            }
            case e @ AutoFire(hostName, port, elements, pre, datastream) => {
              logger.info(e.logJson)
              val datastreamValue = (0 to datastream).map(_ => "").mkString(" ")
              val destination = new InetSocketAddress(hostName, port)
              val result = Source(1 to Int.MaxValue)
                .viaMat(KillSwitches.single)(Keep.both)
                .throttle(elements, pre)
                .map(i => ByteString(s"${clientId}|${datastreamValue}|${i}"))
                .map(Datagram(_, destination))
                .preMaterialize()

              result._2.runWith(Udp.sendSink()(context.system))
              val kill = result._1._2
              attack(Option(kill))
            }
          }
        standby()
      }
    }

}
