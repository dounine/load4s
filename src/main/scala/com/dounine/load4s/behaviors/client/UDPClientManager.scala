package com.dounine.load4s.behaviors.client

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.{
  ClusterSharding,
  Entity,
  EntityTypeKey
}
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
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._

object UDPClientManager extends JsonParse {

  private val logger = LoggerFactory.getLogger(UDPClientManager.getClass)

  val typeKey: EntityTypeKey[BaseSerializer] =
    EntityTypeKey[BaseSerializer]("UDPClientManager")

  trait Event extends BaseSerializer

  final case class CreateClient(
      size: Int,
      time: FiniteDuration
  ) extends BaseSerializer

  final case class LoadTest(
      hostName: String,
      port: Int,
      between: FiniteDuration = 1.seconds,
      elements: Int = 1,
      pre: FiniteDuration = 1.seconds,
      datastream: Int = 0
  ) extends BaseSerializer

  final case class Strategy(
      element: Int,
      pre: FiniteDuration
  ) extends BaseSerializer

  final case class StrategyRun() extends BaseSerializer

  final case class StrategyStop() extends BaseSerializer

  final case class Stop() extends BaseSerializer

  final case class Query()(val replyTo: ActorRef[BaseSerializer])
      extends BaseSerializer

  final case class QueryOk(
      initSize: Int = 0,
      standbySize: Int = 0,
      strategy: Strategy,
      status: String
  ) extends BaseSerializer

  def apply(
      persistenceId: PersistenceId,
      shard: ActorRef[ClusterSharding.ShardCommand]
  ): Behavior[BaseSerializer] =
    Behaviors.setup { context =>
      Behaviors.withTimers(timers => {
        implicit val materializer =
          SystemMaterializer(context.system).materializer

        val sharding = ClusterSharding(context.system)

        def strategyRun(
            initSize: Int,
            standbys: Set[String],
            strategy: Strategy,
            loadKill: Option[UniqueKillSwitch],
            strategyKill: Option[UniqueKillSwitch]
        ): Behavior[BaseSerializer] =
          Behaviors.receiveMessage {
            case e @ Query() => {
              logger.info(e.logJson)
              e.replyTo.tell(
                QueryOk(
                  initSize = initSize,
                  standbySize = standbys.size,
                  strategy = strategy,
                  status = "strategyRun"
                )
              )
              Behaviors.same
            }

            case e @ StrategyStop() => {
              logger.info(e.logJson)
              strategyKill.foreach(_.shutdown())
              loadTest(
                initSize = initSize,
                standbys = Set.empty,
                strategy = strategy,
                loadKill = loadKill
              )
            }
          }

        def loadTest(
            initSize: Int,
            standbys: Set[String],
            strategy: Strategy,
            loadKill: Option[UniqueKillSwitch]
        ): Behavior[BaseSerializer] =
          Behaviors.receiveMessage {
            case e @ Query() => {
              logger.info(e.logJson)
              e.replyTo.tell(
                QueryOk(
                  initSize = initSize,
                  standbySize = standbys.size,
                  strategy = strategy,
                  status = "loadTest"
                )
              )
              Behaviors.same
            }

            case e @ StrategyRun() => {
              logger.info(e.logJson)
              val source =
                Source(standbys)
                  .throttle(strategy.element, strategy.pre)
                  .viaMat(KillSwitches.single)(Keep.both)
                  .preMaterialize()

              source._2.runForeach(id => {
                val client = sharding.entityRefFor(
                  UDPClient.typeKey,
                  id
                )
                client.tell(UDPClient.Stop())
              })
              strategyRun(
                initSize = initSize,
                standbys = standbys,
                strategy = strategy,
                loadKill = loadKill,
                strategyKill = Option(source._1._2)
              )
            }
            case e @ Strategy(element, pre) => {
              logger.info(e.logJson)
              loadTest(
                initSize = initSize,
                standbys = standbys,
                strategy = e,
                loadKill = loadKill
              )
            }
            case e @ Stop() => {
              logger.info(e.logJson)
              loadKill.foreach(_.shutdown())
              Source(standbys)
                .runForeach(id => {
                  val client = sharding.entityRefFor(
                    UDPClient.typeKey,
                    id
                  )
                  client.tell(UDPClient.Stop())
                })
              standby(
                initSize = initSize,
                standbys = Set.empty,
                strategy = strategy
              )
            }
          }

        def standby(
            initSize: Int,
            standbys: Set[String],
            strategy: Strategy
        ): Behavior[BaseSerializer] =
          Behaviors.receiveMessage {
            case e @ Query() => {
              logger.info(e.logJson)
              e.replyTo.tell(
                QueryOk(
                  initSize = initSize,
                  standbySize = standbys.size,
                  strategy = strategy,
                  status = "standby"
                )
              )
              Behaviors.same
            }
            case e @ LoadTest(
                  hostName,
                  port,
                  between,
                  elements,
                  pre,
                  datastream
                ) => {
              logger.info(e.logJson)
              val source = Source(standbys)
                .throttle(standbys.size, between)
                .viaMat(KillSwitches.single)(Keep.both)
                .preMaterialize()

              source._2
                .runForeach(id => {
                  val client = sharding.entityRefFor(
                    UDPClient.typeKey,
                    id.toString
                  )
                  client.tell(
                    UDPClient.AutoFire(
                      hostName = hostName,
                      port = port,
                      elements = elements,
                      pre = pre,
                      datastream = datastream
                    )
                  )
                })

              loadTest(
                initSize = initSize,
                standbys = standbys,
                strategy = strategy,
                loadKill = Option(source._1._2)
              )
            }
            case e @ UDPClient.InitOk(clientId) => {
              logger.info(e.logJson)
              standby(
                initSize = initSize,
                standbys = standbys ++ Set(clientId),
                strategy = strategy
              )
            }
            case e @ CreateClient(size, time) => {
              logger.info(e.logJson)
              Source(1 to size)
                .throttle(size, time)
                .runForeach(id => {
                  val client = sharding.entityRefFor(
                    UDPClient.typeKey,
                    id.toString
                  )
                  client.tell(UDPClient.Init()(context.self))
                })
              standby(
                initSize = size,
                standbys = Set.empty,
                strategy = strategy
              )
            }
          }
        standby(
          initSize = 0,
          standbys = Set.empty,
          strategy = Strategy(
            element = 1,
            pre = 1.seconds
          )
        )
      })
    }

}
