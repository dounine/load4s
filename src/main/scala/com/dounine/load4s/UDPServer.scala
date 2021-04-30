package com.dounine.load4s

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.stream.{OverflowStrategy, SystemMaterializer}
import akka.stream.alpakka.udp.Datagram
import akka.stream.alpakka.udp.scaladsl.Udp
import akka.stream.scaladsl.{Flow, Sink, Source}
import com.dounine.load4s.tools.json.JsonParse
import org.slf4j.LoggerFactory
import redis.clients.jedis.{JedisPool, JedisPoolConfig, Protocol}

import scala.concurrent.duration._
import java.net.InetSocketAddress
import java.time.{Instant, LocalDateTime, ZoneId, ZoneOffset}
import scala.concurrent.Future

object UDPServer extends JsonParse {

  private val logger = LoggerFactory.getLogger(UDPServer.getClass)

  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem(Behaviors.empty, "load4s")
    val config = system.settings.config.getConfig("app")
    val appName = config.getString("name")
    implicit val materialize = SystemMaterializer(system).materializer
    implicit val executionContext = system.executionContext
    val sharding = ClusterSharding(system)

    val host = config.getString("client.host")
    val port = config.getInt("client.port")

    val bindToLocal = new InetSocketAddress(
      host,
      port
    )
    val bindFlow: Flow[Datagram, Datagram, Future[InetSocketAddress]] =
      Udp.bindFlow(bindToLocal)

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

    val pre = config.getInt("client.duration")
    val expire = config.getInt("client.expire")
    val cpu = config.getInt("client.cpu")

    val withinElements = config.getInt("client.elements")
    val withinTime = config.getDuration("client.time").toMillis.milliseconds
    val debug = config.getBoolean("client.debug")

    logger.info(s"""
        |-------  bind host: ${host}   -------
        |-------  bind port: ${port}   -------
        |-------  redis host: ${redisHost}  -------
        |-------  redis port: ${redisPort}   -------
        |-------  redis password: ${redisPassword}   -------
        |-------  pre time save to redis: ${pre}s   -------
        |-------  redis key expire time: ${expire}s   -------
        |-------  cpu while computer: ${cpu}  -------
        |-------  groupedWithin elements: ${withinElements}  -------
        |-------  groupedWithinTime: ${withinTime}  -------
        |-------  CPU Core: ${Runtime
      .getRuntime()
      .availableProcessors}   -------
        |""".stripMargin)

    Source.maybe
      .via(bindFlow)
      .map(_.getData().utf8String)
      .mapAsync(Runtime.getRuntime().availableProcessors()) { item =>
        {
          Future {
            item.split("\\|") match {
              case Array(uid, dateTime, _*) => {
                val time = LocalDateTime
                  .parse(dateTime)
                val timeMills =
                  time
                    .toInstant(ZoneOffset.of("+8"))
                    .toEpochMilli() / 1000 / pre
                val dt = Instant
                  .ofEpochMilli(timeMills * 1000 * pre)
                  .atZone(ZoneId.systemDefault())
                  .toLocalDateTime
                (dt, uid)
              }
            }
          }
        }
      }
      .async
      .groupedWithin(withinElements, withinTime)
      .mapAsync(Runtime.getRuntime().availableProcessors()) { tp2 =>
        {
          Future {
            val redis = jedisPool.getResource
            tp2
              .groupBy(_._1)
              .foreach(list => {
                redis.expire(list._1.toString, expire)
                redis.sadd(list._1.toString, list._2.map(_._2): _*)
              })
            redis.close()
          }
        }
      }
      .run()

  }
}
