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

    val expire = config.getInt("client.expire")
    val cpu = config.getInt("client.cpu")

    val withinElements = config.getInt("client.elements")
    val withinTime = config.getDuration("client.time").toMillis.milliseconds
    val clientTime = config.getBoolean("client.clientTime")
    val overTime = config.getDuration("client.overTime").toMillis.milliseconds
    val debug = config.getBoolean("client.debug")

    logger.info(s"""
        |-------  bind host: ${host}   -------
        |-------  bind port: ${port}   -------
        |-------  redis host: ${redisHost}  -------
        |-------  redis port: ${redisPort}   -------
        |-------  redis password: ${redisPassword}   -------
        |-------  redis key expire time: ${expire}s   -------
        |-------  use client time: ${clientTime}   -------
        |-------  client over time: ${overTime}   -------
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
      .mapAsync(Runtime.getRuntime().availableProcessors() * 2) { item =>
        {
          Future {
            item.split("\\|") match {
              case Array(uid, dateTime, preStr, _*) => {
                var index = 0
                val sysTime = System.currentTimeMillis()
                while ((System.currentTimeMillis() - sysTime) < cpu) {}
                val time =
                  if (clientTime)
                    LocalDateTime
                      .parse(dateTime)
                  else LocalDateTime.now()
                if (clientTime) {
                  val timeout = java.time.Duration
                    .between(
                      time,
                      LocalDateTime.now()
                    )

                  if (timeout.toMillis > overTime.toMillis) {
                    logger.info(s"element receive over time -> ${timeout}")
                  }
                }
                val pre = Duration(preStr).toMillis
                val timeMills =
                  time
                    .toInstant(ZoneOffset.of("+8"))
                    .toEpochMilli() / pre
                val dt = Instant
                  .ofEpochMilli(timeMills * pre)
                  .atZone(ZoneId.systemDefault())
                  .toLocalDateTime
                Option((dt, uid))
              }
              case _ => Option.empty
            }
          }
        }
      }
      .filter(_.isDefined)
      .map(_.get)
      .async
      .groupedWithin(withinElements, withinTime)
      .mapAsync(Runtime.getRuntime().availableProcessors()) { tp2 =>
        {
          Future {
            val redis = jedisPool.getResource
            tp2
              .groupBy(_._1)
              .foreach(list => {
                if (debug) {
                  logger.info(
                    s"${list._1} set values -> ${list._2.map(_._2).mkString(",")}"
                  )
                }
                redis.expire(list._1.toString, expire)
                redis.sadd(list._1.toString, list._2.map(_._2): _*)
              })
            redis.close()
          }
        }
      }
      .recover {
        case e: Throwable => {
          logger.error(e.getMessage)
          e.printStackTrace()
        }
      }
      .run()

  }
}
