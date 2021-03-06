package redis

import akka.actor._
import akka.util.Helpers
import redis.commands._
import scala.concurrent._
import java.net.InetSocketAddress
import redis.actors.{RedisSubscriberActorWithCallback, RedisClientActor}
import redis.api.pubsub._
import java.util.concurrent.atomic.AtomicLong
import akka.event.Logging

trait RedisCommands
  extends Keys
  with Strings
  with Hashes
  with Lists
  with Sets
  with SortedSets
  with Publish
  with Scripting
  with Connection
  with Server

abstract class RedisClientActorLike(system: ActorSystem) extends ActorRequest {
  var host: String
  var port: Int
  val name: String
  val password: Option[String] = None
  val db: Option[Int] = None
  implicit val executionContext = system.dispatcher

  val redisConnection: ActorRef = system.actorOf(
    Props(classOf[RedisClientActor], new InetSocketAddress(host, port), getConnectOperations)
      .withDispatcher(Redis.dispatcher),
    name + '-' + Redis.tempName()
  )

  def reconnect(host: String = host, port: Int = port) = {
    if (this.host != host || this.port != port) {
      this.host = host
      this.port = port
      redisConnection ! new InetSocketAddress(host, port)
    }
  }

  def onConnect(redis: RedisCommands): Unit = {
    password.foreach(redis.auth(_)) // TODO log on auth failure
    db.foreach(redis.select(_))
  }

  def getConnectOperations: () => Seq[Operation[_, _]] = () => {
    val self = this
    val redis = new BufferedRequest with RedisCommands {
      implicit val executionContext: ExecutionContext = self.executionContext
    }
    onConnect(redis)
    redis.operations.result()
  }

  /**
   * Disconnect from the server (stop the actor)
   */
  def stop() {
    system stop redisConnection
  }
}

case class RedisClient(var host: String = "localhost",
                       var port: Int = 6379,
                       override val password: Option[String] = None,
                       override val db: Option[Int] = None,
                       name: String = "RedisClient")
                      (implicit _system: ActorSystem) extends RedisClientActorLike(_system) with RedisCommands with Transactions {

}

case class RedisBlockingClient(var host: String = "localhost",
                               var port: Int = 6379,
                               override val password: Option[String] = None,
                               override val db: Option[Int] = None,
                               name: String = "RedisBlockingClient")
                              (implicit _system: ActorSystem) extends RedisClientActorLike(_system) with BLists {
}

case class RedisPubSub(
                        host: String = "localhost",
                        port: Int = 6379,
                        channels: Seq[String],
                        patterns: Seq[String],
                        onMessage: Message => Unit = _ => {},
                        onPMessage: PMessage => Unit = _ => {},
                        authPassword: Option[String] = None,
                        name: String = "RedisPubSub"
                        )(implicit system: ActorSystem) {

  val redisConnection: ActorRef = system.actorOf(
    Props(classOf[RedisSubscriberActorWithCallback],
      new InetSocketAddress(host, port), channels, patterns, onMessage, onPMessage, authPassword)
      .withDispatcher(Redis.dispatcher),
    name + '-' + Redis.tempName()
  )

  /**
   * Disconnect from the server (stop the actor)
   */
  def stop() {
    system stop redisConnection
  }

  def subscribe(channels: String*) {
    redisConnection ! SUBSCRIBE(channels: _*)
  }

  def unsubscribe(channels: String*) {
    redisConnection ! UNSUBSCRIBE(channels: _*)
  }

  def psubscribe(patterns: String*) {
    redisConnection ! PSUBSCRIBE(patterns: _*)
  }

  def punsubscribe(patterns: String*) {
    redisConnection ! PUNSUBSCRIBE(patterns: _*)
  }
}

case class SentinelMonitoredRedisClient( sentinels: Seq[(String, Int)] = Seq(("localhost", 26379)),
                                         master: String)
                                       (implicit system: ActorSystem) extends SentinelMonitoredRedisClientLike(system) with RedisCommands with Transactions {

  val redisClient: RedisClient = withMasterAddr((ip, port) => {
    new RedisClient(ip, port, name = "SMRedisClient")
  })

}


case class SentinelMonitoredRedisBlockingClient( sentinels: Seq[(String, Int)] = Seq(("localhost", 26379)),
                                                 master: String)
                                               (implicit system: ActorSystem) extends SentinelMonitoredRedisClientLike(system) with BLists {
  val redisClient: RedisBlockingClient = withMasterAddr((ip, port) => {
    new RedisBlockingClient(ip, port, name = "SMRedisBlockingClient")
  })
}

private[redis] object Redis {

  val dispatcher = "rediscala.rediscala-client-worker-dispatcher"

  val tempNumber = new AtomicLong

  def tempName() = Helpers.base64(tempNumber.getAndIncrement())

}