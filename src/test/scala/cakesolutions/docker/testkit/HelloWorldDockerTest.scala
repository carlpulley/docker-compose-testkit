package cakesolutions.docker.testkit

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfter, FreeSpec, Matchers}

import scala.concurrent.duration._

class HelloWorldDockerTest extends FreeSpec with ScalaFutures with Matchers with BeforeAndAfter with DockerComposeTestKit {
  import DockerComposeTestKit._
  import LoggingMatchers._

  implicit val testDuration = 30.seconds

  var compose: DockerCompose = _
  var helloworld: DockerImage = _

  before {
    compose = up(
      "helloworld",
      """basic:
        |  image: hello-world
        |""".stripMargin
    )
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

      events should observe(1)
    }

    "unexpected log line" in {
      val events =
        helloworld
          .logging()
          .matchFirst(entry => entry.message.startsWith("Invalid message"))

      events should observe(0)(3.seconds, implicitly[TestLogger])
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

      events should observe(4)
    }
  }

}
