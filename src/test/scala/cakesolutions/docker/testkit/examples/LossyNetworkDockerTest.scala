package cakesolutions.docker.testkit.examples

import java.time.ZonedDateTime
import java.util.NoSuchElementException

import akka.actor.ActorSystem
import akka.actor.FSM.Event
import cakesolutions.docker.testkit.logging.TestLogger
import cakesolutions.docker.testkit.matchers.ObservableMatcher
import cakesolutions.docker.testkit.network.ImpairmentSpec.{Delay, Loss}
import cakesolutions.docker.testkit.{DockerCompose, DockerComposeTestKit, DockerImage}
import monix.execution.Scheduler
import monix.reactive.Observable
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
  import ObservableMatcher._

  implicit val testDuration = 90.seconds
  implicit val actorSystem = ActorSystem("LossyNetworkDockerTest")
  implicit val scheduler = Scheduler(actorSystem.dispatcher)
  override implicit val patienceConfig = super.patienceConfig.copy(timeout = testDuration)

  val yaml = DockerComposeString(
    """version: '2'
      |
      |services:
      |  c1:
      |    image: ubuntu:trusty
      |    command: /bin/sleep 300000
      |    networks:
      |      - common
      |    cap_add:
      |      - NET_ADMIN
      |  c2:
      |    image: ubuntu:trusty
      |    command: sh -c "ping c1"
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
  var c1: DockerImage = _
  var c2: DockerImage = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    compose = up("lossy-network", yaml)
    c1 = compose.service("c1").docker.head
    c2 = compose.service("c2").docker.head
  }

  override def afterAll(): Unit = {
    compose.down()
    super.afterAll()
  }

  def check(window: Vector[Ping])(assertion: Double => FiniteDuration => Action): Action = {
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

    assertion(meanSeqDiff)(meanTime)
  }

  "Ping parsing" - {
    "success" in {
      Ping.parse(LogEvent(ZonedDateTime.now(), "64 bytes from 5da290c2becf4eafb8ffff1fb3e372ec_c1_1.5da290c2becf4eafb8ffff1fb3e372ec_common (172.22.0.2): icmp_seq=1 ttl=64 time=0.060 ms")) shouldEqual Some(Ping(1, 60000.nanoseconds))
      Ping.parse(LogEvent(ZonedDateTime.now(), "64 bytes from 5da290c2becf4eafb8ffff1fb3e372ec_c1_1.5da290c2becf4eafb8ffff1fb3e372ec_common (172.22.0.2): icmp_seq=2 ttl=64 time=74 ms")) shouldEqual Some(Ping(2, 74.milliseconds))
    }

    "failure" in {
      Ping.parse(LogEvent(ZonedDateTime.now(), "PING c1 (172.22.0.2) 56(84) bytes of data.")) shouldEqual None
    }
  }

  "Networked containers" in {
    val dataSize = 9
    val displayExpectedNetworkBehaviour = observe[Ping, Int, Vector[Ping]](
      InitialState(0, Vector.empty[Ping]),
      When(0) {
        case Event(event: Ping, data) if data.length < dataSize =>
          Stay(using = data :+ event)

        case Event(event: Ping, data) =>
          check(data :+ event) { meanSeqDiff => meanTime =>
            if (0 <= meanSeqDiff &&
              meanSeqDiff <= 1.5 &&
              meanTime <= 500000.nanosecond
            ) {
              note("normal network")
              compose.network("common").qdisc(Delay())
              Goto(1, using = Vector.empty[Ping])
            } else {
              Fail(s"$meanSeqDiff and $meanTime")
            }
          }
      },
      When(1) {
        case Event(event: Ping, data) if data.length < dataSize =>
          Stay(using = data :+ event)

        case Event(event: Ping, data) =>
          check(data :+ event) { meanSeqDiff => meanTime =>
            if (100.milliseconds <= meanTime) {
              note("delayed network")
              compose.network("common").qdisc(Loss("random 50%"))
              Goto(2, using = Vector.empty[Ping])
            } else {
              Fail(s"$meanSeqDiff and $meanTime")
            }
          }
      },
      When(2) {
        case Event(event: Ping, data) if data.length < dataSize =>
          Stay(using = data :+ event)

        case Event(event: Ping, data) =>
          check(data :+ event) { meanSeqDiff => meanTime =>
            if (2 <= meanSeqDiff) {
              note("lossy network")
              compose.network("common").reset()
              Goto(3, using = Vector.empty[Ping])
            } else {
              Fail(s"$meanSeqDiff and $meanTime")
            }
          }
      },
      When(3) {
        case Event(event: Ping, data) if data.length < dataSize =>
          Stay(using = data :+ event)

        case Event(event: Ping, data) =>
          check(data :+ event) { meanSeqDiff => meanTime =>
            if (0 <= meanSeqDiff &&
              meanSeqDiff <= 1.5 &&
              meanTime <= 500000.nanosecond) {
              note("network reset")
              Accept
            } else {
              Fail(s"$meanSeqDiff and $meanTime")
            }
          }
      }
    )

    c2.logging().flatMap(line => Ping.parse(line).fold[Observable[Ping]](Observable.empty)(Observable.now)) should displayExpectedNetworkBehaviour
  }

}
