package cakesolutions.docker.testkit.examples

import akka.actor.ActorSystem
import cakesolutions.docker.testkit.automata.MatchingAutomata
import cakesolutions.docker.testkit.logging.TestLogger
import cakesolutions.docker.testkit.matchers.ObservableMatcher
import cakesolutions.docker.testkit.{DockerCompose, DockerComposeTestKit, DockerImage, TimedObservable}
import monix.execution.Scheduler
import org.scalatest.{BeforeAndAfter, FreeSpec, Matchers}

import scala.concurrent.duration._

class HelloWorldDockerTest extends FreeSpec with Matchers with BeforeAndAfter with DockerComposeTestKit with TestLogger {
  import DockerComposeTestKit._
  import MatchingAutomata._
  import ObservableMatcher._

  implicit val testDuration = 60.seconds
  implicit val actorSystem = ActorSystem("HelloWorldDockerTest")
  implicit val scheduler = Scheduler(actorSystem.dispatcher)

  val yaml = DockerComposeString(
    """version: '2'
    |
    |services:
    |  basic:
    |    image: hello-world
    """.stripMargin
  )

  var compose: DockerCompose = _
  var helloworld: DockerImage = _

  before {
    compose = up("helloworld", yaml)
    helloworld = compose.service("basic").docker.head
  }

  after {
    compose.down()
  }

  "Hello-world Docker Container" - {
    "expected greeting" in {
      val fsm = MatchingAutomata[Unit, LogEvent](()) {
        case _ => {
          case event: LogEvent if event.message.startsWith("Hello from Docker") =>
            Stop(Accept())
        }
      }

      fsm.run(TimedObservable.cold(helloworld.logging())) should observe(Accept())
    }

    "unexpected log line at start" in {
      val fsm = MatchingAutomata[Unit, LogEvent]((), 3.seconds) {
        case _ => {
          case StateTimeout =>
            Stop(Accept())
        }
      }

      fsm.run(TimedObservable.cold(helloworld.logging())) should observe(Accept())
    }

    "unexpected log line on entering a state" in {
      val fsm = MatchingAutomata[Int, LogEvent](0) {
        case 0 => {
          case event: LogEvent if event.message.startsWith("Hello from Docker") =>
            Goto(1, 3.seconds)
        }
        case 1 => {
          case StateTimeout =>
            Stop(Accept())
        }
      }

      fsm.run(TimedObservable.cold(helloworld.logging())) should observe(Accept())
    }

    "multiple consecutive logging lines" in {
      val fsm = MatchingAutomata[Int, LogEvent](1) {
        case 1 => {
          case event: LogEvent if event.message.startsWith("1. The Docker client contacted the Docker daemon") =>
            Goto(2)
        }
        case 2 => {
          case event: LogEvent if event.message.startsWith("2. The Docker daemon pulled the \"hello-world\" image") =>
            Goto(3)
        }
        case 3 => {
          case event: LogEvent if event.message.startsWith("3. The Docker daemon created a new container") =>
            Goto(4)
        }
        case 4 => {
          case event: LogEvent if event.message.startsWith("4. The Docker daemon streamed that output to the Docker client") =>
            Stop(Accept())
        }
      }

      fsm.run(TimedObservable.cold(helloworld.logging())) should observe(Accept())
    }
  }

}
