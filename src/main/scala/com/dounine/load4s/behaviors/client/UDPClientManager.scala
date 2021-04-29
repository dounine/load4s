package com.dounine.load4s.behaviors.client

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.{
  ClusterSharding,
  Entity,
  EntityTypeKey
}
import akka.persistence.typed.PersistenceId
import akka.stream.{
  KillSwitches,
  OverflowStrategy,
  SourceRef,
  SystemMaterializer,
  UniqueKillSwitch
}
import akka.stream.alpakka.udp.Datagram
import akka.stream.alpakka.udp.scaladsl.Udp
import akka.stream.scaladsl.{BroadcastHub, Keep, Source, StreamRefs}
import akka.util.ByteString
import com.dounine.load4s.behaviors.client.UDPClientManager.Statistic
import com.dounine.load4s.model.models.BaseSerializer
import com.dounine.load4s.tools.json.JsonParse
import org.slf4j.LoggerFactory
import redis.clients.jedis.{JedisPool, JedisPoolConfig, Protocol}

import java.net.InetSocketAddress
import java.time.{Instant, LocalDateTime, ZoneId}
import java.time.temporal.ChronoUnit
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._

object UDPClientManager extends JsonParse {

  private val logger = LoggerFactory.getLogger(UDPClientManager.getClass)

  val typeKey: EntityTypeKey[BaseSerializer] =
    EntityTypeKey[BaseSerializer]("UDPClientManager")

  trait Event extends BaseSerializer

  final case class InitUpdate(
      clients: Int,
      loadTime: FiniteDuration
  ) extends BaseSerializer

  final case class InitRun(run: Boolean) extends BaseSerializer

  final case class PressingUpdate(
      host: String,
      port: Int,
      betweenTime: FiniteDuration = 1.seconds,
      sendElements: Int = 1,
      loadTime: FiniteDuration = 1.seconds,
      dataLength: Int = 0
  ) extends BaseSerializer

  final case class PressingRun(run: Boolean) extends BaseSerializer

  final case class ReleaseUpdate(
      clients: Int,
      time: FiniteDuration
  ) extends BaseSerializer

  final case class ReleaseRun(run: Boolean) extends BaseSerializer

  final case class Query()(val replyTo: ActorRef[BaseSerializer])
      extends BaseSerializer

  final case class Sub()(val replyTo: ActorRef[BaseSerializer])
      extends BaseSerializer

  final case class SubOk(source: SourceRef[SubInfo]) extends BaseSerializer

  final case class SubInfo(
      standbys: Option[Int] = None,
      workings: Option[Int] = None,
      online: Option[Int] = None,
      onlineLimit: Option[Int] = None,
      initInfo: Option[InitInfo] = None,
      pressingInfo: Option[PressingInfo] = None,
      releaseInfo: Option[ReleaseInfo] = None,
      status: Option[String] = None,
      statistic: Option[Statistic] = None
  ) extends BaseSerializer

  final case class SubPush(
      info: SubInfo
  ) extends BaseSerializer

  final case class QueryOk(
      standbys: Int,
      workings: Int,
      infos: Infos,
      status: String,
      statistics: Seq[Statistic],
      online: Int,
      onlineLimit: Int
  ) extends BaseSerializer

  final case class StatisticInfo(
      client: Int,
      server: Int
  ) extends BaseSerializer

  final case class Statistic(
      time: LocalDateTime,
      client: Int,
      server: Int
  ) extends BaseSerializer

  case class InitInfo(
      clients: Int,
      loadTime: FiniteDuration
  ) extends BaseSerializer

  case class PressingInfo(
      host: String,
      port: Int,
      loadTime: FiniteDuration,
      dataLength: Int,
      betweenTime: FiniteDuration,
      sendElements: Int
  ) extends BaseSerializer

  case class ReleaseInfo(
      time: FiniteDuration,
      clients: Int
  ) extends BaseSerializer

  final case class Infos(
      initInfo: InitInfo,
      pressingInfo: PressingInfo,
      releaseInfo: ReleaseInfo
  ) extends BaseSerializer

  final case class OnlineCount() extends BaseSerializer

  final case class OnlineUpdate(
      online: Option[Int],
      onlineLimit: Option[Int]
  ) extends BaseSerializer

  final case class KillInfos(
      pressing: Option[UniqueKillSwitch],
      release: Option[UniqueKillSwitch]
  ) extends BaseSerializer

  final case class DataStore(
      standbys: Set[String],
      workings: Set[String],
      online: Int,
      onlineLimit: Int,
      kills: KillInfos,
      statistics: Map[LocalDateTime, StatisticInfo],
      infos: Infos
  ) extends BaseSerializer

  def apply(
      persistenceId: PersistenceId,
      shard: ActorRef[ClusterSharding.ShardCommand]
  ): Behavior[BaseSerializer] =
    Behaviors.withTimers(timers =>
      Behaviors.setup { context =>
        implicit val materializer =
          SystemMaterializer(context.system).materializer

        val sharding = ClusterSharding(context.system)
        val (subInfoQueue, subInfoSource) = Source
          .queue[SubInfo](
            2,
            OverflowStrategy.dropHead
          )
          .preMaterialize()(materializer)
        val subInfoBrocastHub =
          subInfoSource.runWith(BroadcastHub.sink)(materializer)

        val config = context.system.settings.config.getConfig("app")
        val c: JedisPoolConfig = new JedisPoolConfig
        c.setMaxIdle(config.getInt("redis.maxIdle"))
        c.setMaxTotal(config.getInt("redis.maxTotal"))
        c.setTestOnReturn(true)
        c.setTestWhileIdle(true)
        c.setTestOnBorrow(true)
        c.setMaxWaitMillis(
          config.getLong("redis.maxWaitMillis")
        )
        val redisHost: String = config.getString("redis.host")
        val redisPort: Int = config.getInt("redis.port")
        val redisPassword: String = config.getString("redis.password")
        val jedisPool = if (redisPassword != "") {
          new JedisPool(
            c,
            redisHost,
            redisPort,
            0,
            redisPassword,
            Protocol.DEFAULT_DATABASE
          )
        } else new JedisPool(c, redisHost, redisPort, 0)

        def release(
            data: DataStore
        ): Behavior[BaseSerializer] =
          Behaviors.receiveMessage {
            case e @ OnlineUpdate(online, onlineLimit) => {
              logger.info(e.logJson)
              subInfoQueue.offer(
                SubInfo(
                  online = online,
                  onlineLimit = onlineLimit
                )
              )
              timers.startTimerAtFixedRate(
                "online",
                OnlineCount(),
                data.online.seconds
              )
              release(
                data.copy(
                  online = online.getOrElse(data.online),
                  onlineLimit = onlineLimit.getOrElse(data.onlineLimit)
                )
              )
            }
            case e @ UDPClient.WaitOk(clientId, key) => {
              logger.info(e.logJson)
              if (key.getOrElse("") == "release") {
                subInfoQueue.offer(
                  SubInfo(
                    workings = Option(data.workings.size - 1)
                  )
                )
              } else {
                timers.startSingleTimer(
                  "waitOk",
                  SubPush(
                    info = SubInfo(
                      workings = Option(data.workings.size - 1)
                    )
                  ),
                  1.seconds
                )
              }
              release(
                data.copy(
                  workings = data.workings.filterNot(_ == clientId)
                )
              )
            }
            case e @ OnlineCount() => {
              logger.info(e.logJson)
              val time = System.currentTimeMillis() / 1000 / data.online
              val dateTime = Instant
                .ofEpochMilli(time * 1000 * data.online)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime
              val redis = jedisPool.getResource
              val server = redis.scard(dateTime.toString).toInt
              subInfoQueue.offer(
                SubInfo(
                  statistic = Option(
                    Statistic(
                      time = dateTime,
                      client = data.workings.size,
                      server = server
                    )
                  )
                )
              )
              redis.close()
              val limitStatistics =
                if (data.statistics.size > data.onlineLimit) {
                  data.statistics
                    .map(i => {
                      Statistic(
                        time = i._1,
                        client = i._2.client,
                        server = i._2.server
                      )
                    })
                    .toList
                    .sortBy(_.time)
                    .takeRight(data.statistics.size - 1)
                    .map(i => {
                      (
                        i.time,
                        StatisticInfo(client = i.client, server = i.server)
                      )
                    })
                    .toMap
                } else {
                  data.statistics
                }
              release(
                data.copy(
                  statistics = limitStatistics ++ Map(
                    dateTime -> StatisticInfo(
                      client = data.workings.size,
                      server = server
                    )
                  )
                )
              )
            }
            case e @ Sub() => {
              logger.info(e.logJson)
              val source = subInfoBrocastHub.runWith(StreamRefs.sourceRef())
              e.replyTo.tell(SubOk(source))
              Behaviors.same
            }
            case e @ Query() => {
              logger.info(e.logJson)
              e.replyTo.tell(
                QueryOk(
                  standbys = data.standbys.size,
                  workings = data.workings.size,
                  infos = data.infos,
                  status = "release",
                  online = data.online,
                  onlineLimit = data.onlineLimit,
                  statistics = data.statistics
                    .map(ii => {
                      Statistic(
                        time = ii._1,
                        client = ii._2.client,
                        server = ii._2.server
                      )
                    })
                    .toList
                    .sortBy(_.time)
                )
              )
              Behaviors.same
            }

            case e @ ReleaseRun(run) => {
              logger.info(e.logJson)
              if (!run) {
                data.kills.release.foreach(_.shutdown())
                subInfoQueue.offer(
                  SubInfo(
                    status = Option("pressing")
                  )
                )
                pressing(
                  data = data.copy(
                    kills = data.kills.copy(
                      release = Option.empty
                    )
                  )
                )
              } else {
                throw new Exception("状态错误")
              }
            }
            case e @ SubPush(info) => {
              logger.info(e.logJson)
              subInfoQueue.offer(info)
              Behaviors.same
            }

          }

        def pressing(
            data: DataStore
        ): Behavior[BaseSerializer] =
          Behaviors.receiveMessage {
            case e @ OnlineUpdate(online, onlineLimit) => {
              logger.info(e.logJson)
              subInfoQueue.offer(
                SubInfo(
                  online = online,
                  onlineLimit = onlineLimit
                )
              )
              timers.startTimerAtFixedRate(
                "online",
                OnlineCount(),
                data.online.seconds
              )
              pressing(
                data.copy(
                  online = online.getOrElse(data.online),
                  onlineLimit = onlineLimit.getOrElse(data.onlineLimit)
                )
              )
            }
            case e @ OnlineCount() => {
              logger.info(e.logJson)
              val time = System.currentTimeMillis() / 1000 / data.online
              val dateTime = Instant
                .ofEpochMilli(time * 1000 * data.online)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime
              val redis = jedisPool.getResource
              val server = redis.scard(dateTime.toString).toInt
              subInfoQueue.offer(
                SubInfo(
                  statistic = Option(
                    Statistic(
                      time = dateTime,
                      client = data.workings.size,
                      server = server
                    )
                  )
                )
              )
              redis.close()
              val limitStatistics =
                if (data.statistics.size > data.onlineLimit) {
                  data.statistics
                    .map(i => {
                      Statistic(
                        time = i._1,
                        client = i._2.client,
                        server = i._2.server
                      )
                    })
                    .toList
                    .sortBy(_.time)
                    .takeRight(data.statistics.size - 1)
                    .map(i => {
                      (
                        i.time,
                        StatisticInfo(client = i.client, server = i.server)
                      )
                    })
                    .toMap
                } else {
                  data.statistics
                }
              pressing(
                data.copy(
                  statistics = limitStatistics ++ Map(
                    dateTime -> StatisticInfo(
                      client = data.workings.size,
                      server = server
                    )
                  )
                )
              )
            }
            case e @ Sub() => {
              logger.info(e.logJson)
              val source = subInfoBrocastHub.runWith(StreamRefs.sourceRef())
              e.replyTo.tell(SubOk(source))
              Behaviors.same
            }
            case e @ Statistic(time, client, server) => {
              logger.info(e.logJson)
              pressing(
                data.copy(
                  statistics = data.statistics ++ Map(
                    time ->
                      StatisticInfo(
                        client = client,
                        server = server
                      )
                  )
                )
              )
            }
            case e @ Query() => {
              logger.info(e.logJson)
              e.replyTo.tell(
                QueryOk(
                  standbys = data.standbys.size,
                  workings = data.workings.size,
                  infos = data.infos,
                  status = "pressing",
                  online = data.online,
                  onlineLimit = data.onlineLimit,
                  statistics = data.statistics
                    .map(ii => {
                      Statistic(
                        time = ii._1,
                        client = ii._2.client,
                        server = ii._2.server
                      )
                    })
                    .toList
                    .sortBy(_.time)
                )
              )
              Behaviors.same
            }
            case e @ PressingRun(run) => {
              logger.info(e.logJson)
              if (!run) {
                timers.cancel("online")
                data.kills.pressing.foreach(_.shutdown())
                Source(data.workings)
                  .runForeach(id => {
                    val client = sharding.entityRefFor(
                      UDPClient.typeKey,
                      id
                    )
                    client.tell(UDPClient.Wait()(context.self))
                  })
                subInfoQueue.offer(
                  SubInfo(
                    status = Option("init")
                  )
                )
                init(
                  data.copy(
                    kills = data.kills.copy(
                      pressing = Option.empty
                    )
                  )
                )
              } else {
                throw new Exception("状态错误")
              }
            }
            case e @ ReleaseRun(run) => {
              logger.info(e.logJson)
              if (run) {
                val source =
                  Source(data.workings)
                    .throttle(
                      data.infos.releaseInfo.clients,
                      data.infos.releaseInfo.time
                    )
                    .viaMat(KillSwitches.single)(Keep.right)
                    .preMaterialize()

                source._2.runForeach(id => {
                  val client = sharding.entityRefFor(
                    UDPClient.typeKey,
                    id
                  )
                  client.tell(UDPClient.Wait(Option("release"))(context.self))
                })
                subInfoQueue.offer(
                  SubInfo(
                    status = Option("release")
                  )
                )

                timers.startTimerAtFixedRate(
                  "online",
                  OnlineCount(),
                  data.online.seconds
                )

                release(
                  data = data.copy(
                    kills = data.kills.copy(
                      release = Option(source._1)
                    )
                  )
                )
              } else {
                throw new Exception("状态错误")
              }
            }
            case e @ UDPClient.FireOk(clientId) => {
              logger.info(e.logJson)
              timers.startSingleTimer(
                "fireOk",
                SubPush(
                  info = SubInfo(
                    workings = Option(data.workings.size + 1)
                  )
                ),
                1.seconds
              )

              pressing(
                data.copy(
                  workings = data.workings ++ Set(clientId)
                )
              )
            }
            case e @ ReleaseUpdate(clients, time) => {
              logger.info(e.logJson)
              subInfoQueue.offer(
                SubInfo(
                  releaseInfo = Option(
                    ReleaseInfo(
                      time = time,
                      clients = clients
                    )
                  )
                )
              )
              pressing(
                data = data.copy(
                  infos = data.infos.copy(
                    releaseInfo = data.infos.releaseInfo.copy(
                      time = time,
                      clients = clients
                    )
                  )
                )
              )
            }
            case e @ SubPush(info) => {
              logger.info(e.logJson)
              subInfoQueue.offer(info)
              Behaviors.same
            }

          }

        def init(
            data: DataStore
        ): Behavior[BaseSerializer] =
          Behaviors.receiveMessage {
            case e @ OnlineUpdate(online, onlineLimit) => {
              logger.info(e.logJson)
              subInfoQueue.offer(
                SubInfo(
                  online = online,
                  onlineLimit = onlineLimit
                )
              )
              init(
                data.copy(
                  online = online.getOrElse(data.online),
                  onlineLimit = onlineLimit.getOrElse(data.onlineLimit)
                )
              )
            }

            case OnlineCount() => {
              Behaviors.same
            }
            case e @ Sub() => {
              logger.info(e.logJson)
              val source = subInfoBrocastHub.runWith(StreamRefs.sourceRef())
              e.replyTo.tell(SubOk(source))
              Behaviors.same
            }

            case e @ UDPClient.WaitOk(clientId, key) => {
              logger.info(e.logJson)
              if (key.getOrElse("") == "release") {
                subInfoQueue.offer(
                  SubInfo(
                    workings = Option(data.workings.size - 1)
                  )
                )
              } else {
                timers.startSingleTimer(
                  "waitOk",
                  SubPush(
                    info = SubInfo(
                      workings = Option(data.workings.size - 1)
                    )
                  ),
                  1.seconds
                )
              }
              init(
                data.copy(
                  workings = data.workings.filterNot(_ == clientId)
                )
              )
            }
            case e @ InitRun(run) => {
              logger.info(e.logJson)
              if (!run) {
                timers.cancel("online")
                Source(data.standbys)
                  .runForeach(id => {
                    val client = sharding.entityRefFor(
                      UDPClient.typeKey,
                      id
                    )
                    client.tell(UDPClient.Stop())
                  })
                subInfoQueue.offer(
                  SubInfo(
                    status = Option("stop")
                  )
                )
                subInfoQueue.offer(
                  SubInfo(
                    standbys = Option(0)
                  )
                )
                stop(
                  data.copy(
                    standbys = Set.empty,
                    workings = Set.empty,
                    statistics = Map.empty,
                    kills = data.kills.copy(
                      pressing = Option.empty,
                      release = Option.empty
                    )
                  )
                )
              } else {
                throw new Exception("状态错误")
              }
            }
            case e @ Statistic(time, client, server) => {
              logger.info(e.logJson)
              init(
                data.copy(
                  statistics = data.statistics ++ Map(
                    time ->
                      StatisticInfo(
                        client = client,
                        server = server
                      )
                  )
                )
              )
            }
            case e @ Query() => {
              logger.info(e.logJson)
              e.replyTo.tell(
                QueryOk(
                  standbys = data.standbys.size,
                  workings = data.workings.size,
                  infos = data.infos,
                  status = "init",
                  online = data.online,
                  onlineLimit = data.onlineLimit,
                  statistics = data.statistics
                    .map(ii => {
                      Statistic(
                        time = ii._1,
                        client = ii._2.client,
                        server = ii._2.server
                      )
                    })
                    .toList
                    .sortBy(_.time)
                )
              )
              Behaviors.same
            }
            case e @ PressingRun(run) => {
              logger.info(e.logJson)
              if (run) {
                val source = Source(data.standbys)
                  .throttle(
                    data.standbys.size,
                    data.infos.pressingInfo.betweenTime
                  )
                  .viaMat(KillSwitches.single)(Keep.right)
                  .preMaterialize()

                source._2
                  .runForeach(id => {
                    val client = sharding.entityRefFor(
                      UDPClient.typeKey,
                      id
                    )
                    client.tell(
                      UDPClient.AutoFire(
                        hostName = data.infos.pressingInfo.host,
                        port = data.infos.pressingInfo.port,
                        elements = data.infos.pressingInfo.sendElements,
                        pre = data.infos.pressingInfo.loadTime,
                        datastream = data.infos.pressingInfo.dataLength
                      )(context.self)
                    )
                  })
                subInfoQueue.offer(
                  SubInfo(
                    status = Option("pressing")
                  )
                )
                timers.startTimerAtFixedRate(
                  "online",
                  OnlineCount(),
                  data.online.seconds
                )
                pressing(
                  data.copy(
                    kills = data.kills.copy(pressing = Option(source._1))
                  )
                )
              } else {
                throw new Exception("状态错误")
              }
            }
            case e @ PressingUpdate(
                  hostName,
                  port,
                  betweenTime,
                  sendElements,
                  loadTime,
                  dataLength
                ) => {
              logger.info(e.logJson)
              subInfoQueue.offer(
                SubInfo(
                  pressingInfo = Option(
                    PressingInfo(
                      host = hostName,
                      port = port,
                      loadTime = loadTime,
                      dataLength = dataLength,
                      betweenTime = betweenTime,
                      sendElements = sendElements
                    )
                  )
                )
              )

              init(
                data.copy(
                  infos = data.infos.copy(
                    pressingInfo = data.infos.pressingInfo.copy(
                      host = hostName,
                      port = port,
                      loadTime = loadTime,
                      dataLength = dataLength,
                      betweenTime = betweenTime,
                      sendElements = sendElements
                    )
                  )
                )
              )
            }
            case e @ SubPush(info) => {
              logger.info(e.logJson)
              subInfoQueue.offer(info)
              Behaviors.same
            }
            case e @ UDPClient.InitOk(clientId) => {
              logger.info(e.logJson)
              timers.startSingleTimer(
                "initOk",
                SubPush(
                  info = SubInfo(
                    standbys = Option(data.standbys.size + 1)
                  )
                ),
                1.seconds
              )
              init(
                data.copy(
                  standbys = data.standbys ++ Set(clientId)
                )
              )
            }
          }

        def stop(data: DataStore): Behavior[BaseSerializer] =
          Behaviors.receiveMessage {
            case e @ OnlineUpdate(online, onlineLimit) => {
              logger.info(e.logJson)
              subInfoQueue.offer(
                SubInfo(
                  online = online,
                  onlineLimit = onlineLimit
                )
              )
              stop(
                data.copy(
                  online = online.getOrElse(data.online),
                  onlineLimit = onlineLimit.getOrElse(data.onlineLimit)
                )
              )
            }

            case e @ Sub() => {
              logger.info(e.logJson)
              val source = subInfoBrocastHub.runWith(StreamRefs.sourceRef())
              e.replyTo.tell(SubOk(source))
              Behaviors.same
            }
            case e @ SubPush(info) => {
              logger.info(e.logJson)
              subInfoQueue.offer(info)
              Behaviors.same
            }
            case e @ Query() => {
              logger.info(e.logJson)
              e.replyTo.tell(
                QueryOk(
                  standbys = data.standbys.size,
                  workings = data.workings.size,
                  infos = data.infos,
                  status = "stop",
                  online = data.online,
                  onlineLimit = data.onlineLimit,
                  statistics = data.statistics
                    .map(ii => {
                      Statistic(
                        time = ii._1,
                        client = ii._2.client,
                        server = ii._2.server
                      )
                    })
                    .toList
                    .sortBy(_.time)
                )
              )
              Behaviors.same
            }
            case e @ InitRun(run) => {
              logger.info(e.logJson)
              if (run) {
                Source(1 to data.infos.initInfo.clients)
                  .throttle(
                    data.infos.initInfo.clients,
                    data.infos.initInfo.loadTime
                  )
                  .runForeach(id => {
                    val client = sharding.entityRefFor(
                      UDPClient.typeKey,
                      id.toString
                    )
                    client.tell(UDPClient.Init()(context.self))
                  })
                subInfoQueue.offer(
                  SubInfo(
                    status = Option("init")
                  )
                )
                init(data)
              } else {
                throw new Exception("状态错误")
              }
            }
            case e @ InitUpdate(clients, time) => {
              logger.info(e.logJson)
              subInfoQueue.offer(
                SubInfo(
                  initInfo = Option(
                    InitInfo(
                      clients = clients,
                      loadTime = time
                    )
                  )
                )
              )
              stop(
                data.copy(
                  infos = data.infos.copy(
                    initInfo = data.infos.initInfo.copy(
                      clients = clients,
                      loadTime = time
                    )
                  )
                )
              )
            }
          }

        stop(
          DataStore(
            standbys = Set.empty,
            workings = Set.empty,
            statistics = Map.empty,
            online = 3,
            onlineLimit = 1000,
            kills = KillInfos(
              pressing = Option.empty,
              release = Option.empty
            ),
            infos = Infos(
              initInfo = InitInfo(
                clients = 1,
                loadTime = 1.seconds
              ),
              pressingInfo = PressingInfo(
                host = "localhost",
                port = 8080,
                loadTime = 1.seconds,
                dataLength = 0,
                betweenTime = 1.seconds,
                sendElements = 1
              ),
              releaseInfo = ReleaseInfo(
                time = 1.seconds,
                clients = 1
              )
            )
          )
        )
      }
    )

}
