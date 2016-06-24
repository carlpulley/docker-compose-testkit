package cakesolutions.docker.testkit

import akka.actor.ActorSystem
import akka.actor.FSM.Event
import cakesolutions.docker.testkit.logging.TestLogger
import cakesolutions.docker.testkit.matchers.ObservableMatcher
import monix.execution.Scheduler
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfter, FreeSpec, Matchers}

import scala.concurrent.duration._

class HelloWorldDockerTest extends FreeSpec with ScalaFutures with Matchers with BeforeAndAfter with DockerComposeTestKit with TestLogger {
  import DockerComposeTestKit._
  import ObservableMatcher._

  implicit val testDuration = 30.seconds
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
      helloworld.logging() should observe[LogEvent, Int, Unit](
        InitialState(0, ()),
        When(0) {
          case Event(event: LogEvent, _) if event.message.startsWith("Hello from Docker") =>
            Accept
        }
      )
    }

    "unexpected log line" in {
      helloworld.logging() should observe[LogEvent, Int, Unit](
        InitialState(0, ()),
        When(0, 3.seconds) {
          case Event(event: LogEvent, _) if event.message.startsWith("Invalid message") =>
            Accept
        }
      )
    }

    "can match multiple consecutive logging lines" in {
      helloworld.logging() should observe[LogEvent, Int, Unit](
        InitialState(0, ()),
        When(0) {
          case Event(event: LogEvent, _) if event.message.startsWith("1. The Docker client contacted the Docker daemon") =>
            Goto(1)
        },
        When(1) {
          case Event(event: LogEvent, _) if event.message.startsWith("2. The Docker daemon pulled the \"hello-world\" image") =>
            Goto(2)
        },
        When(2) {
          case Event(event: LogEvent, _) if event.message.startsWith("3. The Docker daemon created a new container") =>
            Goto(3)
        },
        When(3) {
          case Event(event: LogEvent, _) if event.message.startsWith("4. The Docker daemon streamed that output to the Docker client") =>
            Accept
        }
      )
    }
  }

}
