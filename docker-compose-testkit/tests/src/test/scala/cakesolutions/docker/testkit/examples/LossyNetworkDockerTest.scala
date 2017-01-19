// Copyright 2016 Carl Pulley

package cakesolutions.docker.testkit.examples

import java.time.ZonedDateTime
import java.util.NoSuchElementException

import akka.actor.ActorSystem
import cakesolutions.docker._
import cakesolutions.docker.network.NetworkControl._
import cakesolutions.docker.network.default.linux._
import cakesolutions.docker.testkit._
import cakesolutions.docker.testkit.logging.{Logger, TestLogger}
import cats.Now
import monix.execution.Scheduler
import monix.reactive.Observable
import org.atnos.eff.ErrorEffect._
import org.atnos.eff._
import org.atnos.eff.all._
import org.atnos.eff.syntax.all._
import org.scalatest._
import org.scalatest.concurrent.Eventually

import scala.concurrent.Await
import scala.concurrent.duration._

object LossyNetworkDockerTest {
  import DockerComposeTestKit.LogEvent

  val title = s"${Console.MAGENTA}${Console.UNDERLINED}"
  val highlight = s"${Console.WHITE}"

  sealed trait TestState
  final case class FillBuffer(buffer: Vector[LogEvent]) extends TestState
  final case class BufferWidow(buffer: Vector[LogEvent]) extends TestState

  final case class Ping(id: Int, time: FiniteDuration)
  object Ping {
    // 64 bytes from target_c1_1.target_common (172.22.0.4): icmp_seq=15 ttl=64 time=0.063 ms
    private val ping = "^.*: icmp_seq=(\\d+) ttl=\\d+ time=(.+) ms$".r

    def parse(entry: LogEvent): Option[Ping] = {
      try {
        val ping(icmp_seq, time) = entry.message
        val duration = (time.toDouble * 1000000).toInt.nanoseconds

        Some(Ping(icmp_seq.toInt, duration))
      } catch {
        case _: NumberFormatException =>
          None
        case _: NoSuchElementException =>
          None
        case _: MatchError =>
          None
      }
    }
  }
}

class LossyNetworkDockerTest extends FreeSpec with Matchers with Inside with BeforeAndAfterAll with Eventually with DockerComposeTestKit with TestLogger {
  import DockerComposeTestKit._
  import LossyNetworkDockerTest._

  implicit val testDuration = 5.minutes
  implicit val actorSystem = ActorSystem("LossyNetworkDockerTest")
  implicit val scheduler = Scheduler(actorSystem.dispatcher)
  override implicit val patienceConfig = super.patienceConfig.copy(timeout = testDuration)

  val yaml = DockerComposeString(
    """version: '2'
      |
      |services:
      |  server:
      |    template:
      |      resources:
      |        - cakesolutions.docker.network.default.linux
      |      image: ubuntu:trusty
      |    command: /bin/sleep 300000
      |    networks:
      |      - common
      |  client:
      |    template:
      |      resources:
      |        - cakesolutions.docker.network.default.linux
      |      image: ubuntu:trusty
      |    command: sh -c "ping server"
      |    networks:
      |      - common
      |
      |networks:
      |  common:
    """.stripMargin
  )

  implicit var compose: DockerCompose = _
  var server: DockerImage = _
  var client: DockerImage = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    compose = up("lossy-network", yaml)
    server = compose.service("server").docker.head
    client = compose.service("client").docker.head
  }

  override def afterAll(): Unit = {
    compose.down()
    super.afterAll()
  }

  type _validate[Model] = Validate[String, ?] |= Model
  type LossyNetworkModel = Fx.fx4[DockerAction, NetworkAction, Validate[String, ?], ErrorOrOk]

  def check[Model: _validate: _errorOrOk](obs: Observable[Boolean])(implicit scheduler: Scheduler): Eff[Model, Unit] = {
    for {
      isTrue <- ErrorEffect.eval(Now(Await.result(obs.runAsyncGetFirst, Duration.Inf)))
      result <- validateCheck(isTrue.contains(true), "Observable should produce a true initial value")
    } yield result
  }

  def note[Model: _errorOrOk](msg: String)(implicit log: Logger): Eff[Model, Unit] = {
    ErrorEffect.eval(Now(log.info(s"$highlight$msg")))
  }

  def check(window: Vector[Ping], assertion: Double => FiniteDuration => Notify): Notify = {
    val (totalSeqDiff, _, totalTime) =
      window
        .foldLeft[Option[(Int, Int, FiniteDuration)]](None) {
          case (None, ping) =>
            Some((1, ping.id, ping.time))

          case (Some((seqDiff, lastSeq, time)), ping) =>
            Some((seqDiff + (ping.id - lastSeq), ping.id, time + ping.time))
        }
        .get
    val meanSeqDiff = totalSeqDiff.toDouble / window.length
    val meanTime = totalTime / window.length

    note(s"${highlight}Stats: mean icmp_seq difference is $meanSeqDiff; mean time is $meanTime")
    assertion(meanSeqDiff)(meanTime)
  }

  def fsm(assertion: Double => FiniteDuration => Notify)(implicit dataSize: Int): Monitor[Vector[Ping], Ping] = {
    Monitor[Vector[Ping], Ping](Vector.empty) {
      case data if data.length < dataSize => {
        case Observe(event: Ping) =>
          Goto(data :+ event)
      }
      case data => {
        case Observe(event: Ping) =>
          Stop(check(data :+ event, assertion))
      }
    }
  }

  "Ping parsing" - {
    "success" in {
      Ping.parse(LogEvent(ZonedDateTime.now(), "1", "64 bytes from 5da290c2becf4eafb8ffff1fb3e372ec_c1_1.5da290c2becf4eafb8ffff1fb3e372ec_common (172.22.0.2): icmp_seq=1 ttl=64 time=0.060 ms")) shouldEqual Some(Ping(1, 60000.nanoseconds))
      Ping.parse(LogEvent(ZonedDateTime.now(), "2", "64 bytes from 5da290c2becf4eafb8ffff1fb3e372ec_c1_1.5da290c2becf4eafb8ffff1fb3e372ec_common (172.22.0.2): icmp_seq=2 ttl=64 time=74 ms")) shouldEqual Some(Ping(2, 74.milliseconds))
    }

    "failure" in {
      Ping.parse(LogEvent(ZonedDateTime.now(), "3", "PING server (172.22.0.2) 56(84) bytes of data.")) shouldEqual None
    }
  }

  s"${title}Network fault injection" - {
    s"${title}Networked containers with hot ping observable" in {
      implicit val dataSize = 19

      val pingSource = (event: LogEvent) =>
        Observable(event).map(Ping.parse).collect { case Some(ping) => ping }

      val warmup = fsm { meanSeqDiff => meanTime =>
        Accept()
      }

      val normal = fsm { meanSeqDiff => meanTime =>
        if (0 <= meanSeqDiff && meanSeqDiff <= 1.5 && meanTime <= 500000.nanosecond) {
          Accept()
        } else {
          Fail(s"$meanSeqDiff and $meanTime")
        }
      }

      val delayed = fsm { meanSeqDiff => meanTime =>
        if (100.milliseconds <= meanTime) {
          Accept()
        } else {
          Fail(s"$meanSeqDiff and $meanTime")
        }
      }

      val lossy = fsm { meanSeqDiff => meanTime =>
        if (2 <= meanSeqDiff) {
          Accept()
        } else {
          Fail(s"$meanSeqDiff and $meanTime")
        }
      }

      def expt[Model: _docker: _network: _validate: _errorOrOk]: Eff[Model, Notify] = for {
        obs1 <- docker.logs.hot(warmup)(pingSource)("client")
        _ <- check(isAccepting(obs1))
        _ <- note("warmup")
        obs2 <- docker.logs.hot(normal)(pingSource)("client")
        _ <- check(isAccepting(obs2))
        _ <- note("normal network")
        _ <- impair(Delay())("common")
        obs3 <- docker.logs.hot(delayed)(pingSource)("client")
        _ <- check(isAccepting(obs3))
        _ <- note("delayed network")
        _ <- impair(Loss("random 50%"))("common")
        obs4 <- docker.logs.hot(lossy)(pingSource)("client")
        _ <- check(isAccepting(obs4))
        _ <- note("lossy network")
        _ <- impair()("common")
        obs5 <- docker.logs.hot(normal)(pingSource)("client")
        _ <- check(isAccepting(obs5))
        _ <- note("normal network")
      } yield Accept()

      inside(expt[LossyNetworkModel].runDocker(Map("server" -> server, "client" -> client)).runNetwork.runError.runNel) {
        case Pure(Right(Right(Accept())), _) =>
          assert(true)
      }
    }
  }

}
