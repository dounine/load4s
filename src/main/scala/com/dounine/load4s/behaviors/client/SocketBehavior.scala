package com.dounine.load4s.behaviors.client

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.http.scaladsl.model.RemoteAddress
import akka.stream.scaladsl.{Keep, Source}
import akka.stream.{
  KillSwitches,
  OverflowStrategy,
  SystemMaterializer,
  UniqueKillSwitch
}
import akka.util.Timeout
import com.dounine.load4s.model.models.BaseSerializer
import com.dounine.load4s.tools.json.JsonParse
import org.slf4j.LoggerFactory

import scala.concurrent.duration._

object SocketBehavior extends JsonParse {
  sealed trait Command extends BaseSerializer

  final case object Shutdown extends Command

  final case class StreamComplete() extends Command

  final case class StreamFail(e: Throwable) extends Command

  final case object Ack extends Command

  final case class MessageReceive(
      actor: ActorRef[Command],
      message: String
  ) extends Command

  final case class Fail(msg: String) extends Command

  final case class Message[T](
      `type`: String,
      data: T
  ) extends Command

  final case class Config(
      userId: String = "",
      updownId: String = ""
  ) extends BaseSerializer

  final case class SubConfig(
      updown: Option[UniqueKillSwitch] = Option.empty,
      rate: Option[UniqueKillSwitch] = Option.empty,
      slider: Option[UniqueKillSwitch] = Option.empty
  ) extends BaseSerializer

  case class UpdateInfo(
      init: Option[UDPClientManager.InitInfo],
      pressing: Option[UDPClientManager.PressingInfo],
      release: Option[UDPClientManager.ReleaseInfo],
      online: Option[Int],
      onlineLimit: Option[Int],
      initStart: Option[Boolean],
      pressingStart: Option[Boolean],
      releaseStart: Option[Boolean]
  ) extends BaseSerializer

  final case class InitActor(
      actor: ActorRef[BaseSerializer]
  ) extends Command

  final case class Connected(
      client: ActorRef[Command]
  ) extends Command

  final case class OutgoingMessage(
      `type`: String,
      data: Option[Any] = Option.empty,
      msg: Option[String] = Option.empty
  ) extends Command

  final case class DataStore(
      phone: Option[String],
      client: Option[ActorRef[BaseSerializer]],
      config: Config,
      subConfig: SubConfig = SubConfig()
  ) extends BaseSerializer

  private final val logger = LoggerFactory.getLogger(SocketBehavior.getClass)

  def apply(): Behavior[BaseSerializer] =
    Behaviors.setup { context =>
      {
        val sharding = ClusterSharding(context.system)
        implicit val materializer =
          SystemMaterializer(context.system).materializer

        def datas(data: DataStore): Behavior[BaseSerializer] =
          Behaviors.receiveMessage {
            case e @ Ack => {
              Behaviors.same
            }
            case e @ Connected(actor) => {
              logger.info(e.logJson)
              actor.tell(Ack)
              Source
                .future(
                  sharding
                    .entityRefFor(
                      UDPClientManager.typeKey,
                      UDPClientManager.typeKey.name
                    )
                    .ask(UDPClientManager.Sub()(_))(3.seconds)
                )
                .flatMapConcat {
                  case UDPClientManager.SubOk(source) => source
                }
                .runForeach(f => {
                  data.client.foreach(
                    _.tell(
                      OutgoingMessage(
                        `type` = "push",
                        data = Option(
                          f
                        )
                      )
                    )
                  )
                })

              Source
                .future(
                  sharding
                    .entityRefFor(
                      UDPClientManager.typeKey,
                      UDPClientManager.typeKey.name
                    )
                    .ask[BaseSerializer](UDPClientManager.Query()(_))(
                      3.seconds
                    )
                )
                .collect {
                  case e @ UDPClientManager.QueryOk(_, _, _, _, _, _) => e
                }
                .runForeach(result => {
                  data.client.foreach(
                    _.tell(
                      OutgoingMessage(
                        `type` = "init",
                        data = Option(
                          result
                        )
                      )
                    )
                  )
                })
              Behaviors.same
            }
            case e @ InitActor(actor) => {
              logger.info(e.logJson)
              datas(
                data.copy(
                  client = Option(actor)
                )
              )
            }
            case e @ MessageReceive(actor, message) => {
              logger.info(e.logJson)
              val messageData = message.jsonTo[Message[Map[String, Any]]]
              val udpClientManager = sharding.entityRefFor(
                UDPClientManager.typeKey,
                UDPClientManager.typeKey.name
              )
              messageData.`type` match {
                case "update" => {
                  val updateInfo = messageData.data.toJson.jsonTo[UpdateInfo]
                  if (
                    updateInfo.online.isDefined || updateInfo.onlineLimit.isDefined
                  ) {
                    udpClientManager.tell(
                      UDPClientManager.OnlineUpdate(
                        online = updateInfo.online,
                        onlineLimit = updateInfo.onlineLimit
                      )
                    )
                  }
                  if (
                    updateInfo.initStart.isDefined || updateInfo.pressingStart.isDefined || updateInfo.releaseStart.isDefined
                  ) {
                    val runOrStop = updateInfo.initStart
                      .map(UDPClientManager.InitRun)
                      .getOrElse(
                        updateInfo.pressingStart
                          .map(UDPClientManager.PressingRun)
                          .getOrElse(
                            updateInfo.releaseStart
                              .map(UDPClientManager.ReleaseRun)
                              .get
                          )
                      )
                    udpClientManager.tell(
                      runOrStop
                    )
                  } else {
                    updateInfo.init.foreach(init => {
                      udpClientManager.tell(
                        UDPClientManager.InitUpdate(
                          clients = init.clients,
                          loadTime = init.loadTime
                        )
                      )
                    })
                    updateInfo.pressing.foreach(pressing => {
                      udpClientManager.tell(
                        UDPClientManager.PressingUpdate(
                          host = pressing.host,
                          port = pressing.port,
                          betweenTime = pressing.betweenTime,
                          sendElements = pressing.sendElements,
                          loadTime = pressing.loadTime,
                          dataLength = pressing.dataLength
                        )
                      )
                    })
                    updateInfo.release.foreach(release => {
                      udpClientManager.tell(
                        UDPClientManager.ReleaseUpdate(
                          clients = release.clients,
                          time = release.time
                        )
                      )
                    })
                  }
                }
              }
              actor.tell(Ack)
              Behaviors.same
            }
            case e @ Shutdown => {
              logger.info(e.logJson)
              Behaviors.stopped
            }
          }

        datas(
          DataStore(
            phone = Option.empty,
            client = Option.empty,
            config = Config()
          )
        )
      }
    }

}
