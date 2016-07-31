package cakesolutions.docker.testkit.examples

import java.time.ZonedDateTime
import java.util.NoSuchElementException

import akka.actor.ActorSystem
import cakesolutions.docker.testkit.automata.MatchingAutomata
import cakesolutions.docker.testkit.logging.TestLogger
import cakesolutions.docker.testkit.matchers.ObservableMatcher._
import cakesolutions.docker.testkit.network.ImpairmentSpec.{Delay, Loss}
import cakesolutions.docker.testkit.{DockerCompose, DockerComposeTestKit, DockerImage, TimedObservable}
import monix.execution.Scheduler
import monix.reactive.{Pipe, Observable}
import org.scalatest._
import org.scalatest.concurrent.{AsyncAssertions, Eventually}

import scala.concurrent.duration._

object LossyNetworkDockerTest {
  import DockerComposeTestKit.LogEvent

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

class LossyNetworkDockerTest extends FreeSpec with Matchers with Inside with BeforeAndAfterAll with Eventually with AsyncAssertions with DockerComposeTestKit with TestLogger {
  import DockerComposeTestKit._
  import LossyNetworkDockerTest._
  import MatchingAutomata._

  implicit val testDuration = 5.minutes
  implicit val actorSystem = ActorSystem("LossyNetworkDockerTest")
  implicit val scheduler = Scheduler(actorSystem.dispatcher)
  override implicit val patienceConfig = super.patienceConfig.copy(timeout = testDuration)

  val yaml = DockerComposeString(
    """version: '2'
      |
      |services:
      |  server:
      |    image: ubuntu:trusty
      |    command: /bin/sleep 300000
      |    networks:
      |      - common
      |    cap_add:
      |      - NET_ADMIN
      |  client:
      |    image: ubuntu:trusty
      |    command: sh -c "ping server"
      |    networks:
      |      - common
      |    cap_add:
      |      - NET_ADMIN
      |
      |networks:
      |  common:
    """.stripMargin
  )

  var compose: DockerCompose = _
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

    note(s"Stats: mean icmp_seq difference is $meanSeqDiff; mean time is $meanTime")
    assertion(meanSeqDiff)(meanTime)
  }

  def fsm(assertion: Double => FiniteDuration => Notify)(implicit dataSize: Int): MatchingAutomata[Vector[Ping], Ping] = {
    MatchingAutomata[Vector[Ping], Ping](Vector.empty) {
      case data if data.length < dataSize => {
        case event: Ping =>
          Goto(data :+ event)
      }
      case data => {
        case event: Ping =>
          Stop(check(data :+ event, assertion))
      }
    }
  }

  "Ping parsing" - {
    "success" in {
      Ping.parse(LogEvent(ZonedDateTime.now(), "64 bytes from 5da290c2becf4eafb8ffff1fb3e372ec_c1_1.5da290c2becf4eafb8ffff1fb3e372ec_common (172.22.0.2): icmp_seq=1 ttl=64 time=0.060 ms")) shouldEqual Some(Ping(1, 60000.nanoseconds))
      Ping.parse(LogEvent(ZonedDateTime.now(), "64 bytes from 5da290c2becf4eafb8ffff1fb3e372ec_c1_1.5da290c2becf4eafb8ffff1fb3e372ec_common (172.22.0.2): icmp_seq=2 ttl=64 time=74 ms")) shouldEqual Some(Ping(2, 74.milliseconds))
    }

    "failure" in {
      Ping.parse(LogEvent(ZonedDateTime.now(), "PING server (172.22.0.2) 56(84) bytes of data.")) shouldEqual None
    }
  }

  "Networked containers with hot ping observable" in {
    implicit val dataSize = 19

    val pingSource = TimedObservable.hot(
      client.logging().map(Ping.parse).collect { case Some(ping) => ping }.publish
    )

    val warmup = fsm { meanSeqDiff => meanTime =>
      note("warmup")
      Accept
    }

    val normal = fsm { meanSeqDiff => meanTime =>
      if (0 <= meanSeqDiff && meanSeqDiff <= 1.5 && meanTime <= 500000.nanosecond) {
        note("normal network")
        Accept
      } else {
        Fail(s"$meanSeqDiff and $meanTime")
      }
    }

    val delayed = fsm { meanSeqDiff => meanTime =>
      if (100.milliseconds <= meanTime) {
        note("delayed network")
        Accept
      } else {
        Fail(s"$meanSeqDiff and $meanTime")
      }
    }

    val lossy = fsm { meanSeqDiff => meanTime =>
      if (2 <= meanSeqDiff) {
        note("lossy network")
        Accept
      } else {
        Fail(s"$meanSeqDiff and $meanTime")
      }
    }

    pingSource.observable.connect()

    val testSimulation = for {
      _ <- warmup.run(pingSource).outcome
      _ <- normal.run(pingSource).outcome
      _ = compose.network("common").qdisc(Delay())
      _ <- delayed.run(pingSource).outcome
      _ = compose.network("common").qdisc(Loss("random 50%"))
      _ <- lossy.run(pingSource).outcome
      _ = compose.network("common").reset()
      _ <- normal.run(pingSource).outcome
    } yield Accept

    testSimulation should observe(Accept)
  }

}
