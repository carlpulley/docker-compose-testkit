package cakesolutions.docker.testkit

import cakesolutions.docker.testkit.filters.ObservableFilter
import cakesolutions.docker.testkit.logging.TestLogger
import cakesolutions.docker.testkit.matchers.ObservableMatcher
import monix.execution.Scheduler
import monix.execution.Scheduler.Implicits.global
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfter, FreeSpec, Matchers}

import scala.concurrent.duration._

class HelloWorldDockerTest extends FreeSpec with ScalaFutures with Matchers with BeforeAndAfter with DockerComposeTestKit with TestLogger {
  import DockerComposeTestKit._
  import ObservableFilter._
  import ObservableMatcher._

  implicit val testDuration = 30.seconds

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
      val events =
        helloworld
          .logging()
          .matchFirst(entry => entry.message.startsWith("Hello from Docker"))

      events should legacyObserve[LogEvent](1)
    }

    "unexpected log line" in {
      val events =
        helloworld
          .logging()
          .matchFirst(entry => entry.message.startsWith("Invalid message"))

      events should legacyObserve[LogEvent](0)(implicitly[Manifest[LogEvent]], 3.seconds, implicitly[Scheduler], log)
    }

    "can match multiple consecutive logging lines" in {
      val events =
        helloworld
          .logging()
          .matchFirstOrdered(
            entry => entry.message.startsWith("1. The Docker client contacted the Docker daemon"),
            entry => entry.message.startsWith("2. The Docker daemon pulled the \"hello-world\" image"),
            entry => entry.message.startsWith("3. The Docker daemon created a new container"),
            entry => entry.message.startsWith("4. The Docker daemon streamed that output to the Docker client")
          )

      events should legacyObserve[LogEvent](4)
    }
  }

}
