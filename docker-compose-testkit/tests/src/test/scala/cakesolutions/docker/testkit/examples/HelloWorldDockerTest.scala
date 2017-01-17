// Copyright 2016 Carl Pulley

package cakesolutions.docker.testkit.examples

import akka.actor.ActorSystem
import cakesolutions.docker._
import cakesolutions.docker.testkit.DockerComposeTestKit.{DockerComposeString, LogEvent}
import cakesolutions.docker.testkit._
import cakesolutions.docker.testkit.logging.TestLogger
import cats.Now
import monix.execution.Scheduler
import monix.reactive.Observable
import org.atnos.eff.ErrorEffect._
import org.atnos.eff._
import org.atnos.eff.all._
import org.atnos.eff.syntax.all._
import org.scalatest.{BeforeAndAfter, FreeSpec, Inside, Matchers}

import scala.concurrent.Await
import scala.concurrent.duration._

class HelloWorldDockerTest extends FreeSpec with Matchers with Inside with BeforeAndAfter with DockerComposeTestKit with TestLogger {

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

  type _validate[Model] = Validate[String, ?] |= Model
  type HelloWorldModel = Fx.fx3[DockerAction, Validate[String, ?], ErrorOrOk]

  def check[Model: _validate: _errorOrOk](obs: Observable[Boolean])(implicit scheduler: Scheduler): Eff[Model, Unit] = {
    for {
      isTrue <- ErrorEffect.eval(Now(Await.result(obs.runAsyncGetFirst, Duration.Inf)))
      result <- validateCheck(isTrue.contains(true), "Observable should produce a true initial value")
    } yield result
  }

  "Hello-world Docker Container" - {
    "expected greeting" in {
      val fsm = Monitor[Unit, LogEvent](()) {
        case _ => {
          case Observe(event: LogEvent) if event.message.startsWith("Hello from Docker") =>
            Stop(Accept())
        }
      }
      def expt[Model: _docker: _validate: _errorOrOk]: Eff[Model, Notify] = for {
        obs <- docker.logs(fsm)(Observable(_))("basic")
        _ <- check(isAccepting(obs))
      } yield Accept()

      inside(expt[HelloWorldModel].runDocker(Map("basic" -> helloworld)).runError.runNel) {
        case Pure(Right(Right(Accept())), _) =>
          assert(true)
      }
    }

    "unexpected log line at start" in {
      val fsm = Monitor[Unit, LogEvent]((), 3.seconds) {
        case _ => {
          case StateTimeout =>
            Stop(Accept())
        }
      }
      def expt[Model: _docker: _validate: _errorOrOk]: Eff[Model, Notify] = for {
        obs <- docker.logs(fsm)(Observable(_))("basic")
        _ <- check(isAccepting(obs))
      } yield Accept()

      inside(expt[HelloWorldModel].runDocker(Map("basic" -> helloworld)).runError.runNel) {
        case Pure(Right(Right(Accept())), _) =>
          assert(true)
      }
    }

    "unexpected log line on entering a state" in {
      val fsm = Monitor[Int, LogEvent](0) {
        case 0 => {
          case Observe(event: LogEvent) if event.message.startsWith("Hello from Docker") =>
            Goto(1, 3.seconds)
        }
        case 1 => {
          case StateTimeout =>
            Stop(Accept())
        }
      }
      def expt[Model: _docker: _validate: _errorOrOk]: Eff[Model, Notify] = for {
        obs <- docker.logs(fsm)(Observable(_))("basic")
        _ <- check(isAccepting(obs))
      } yield Accept()

      inside(expt[HelloWorldModel].runDocker(Map("basic" -> helloworld)).runError.runNel) {
        case Pure(Right(Right(Accept())), _) =>
          assert(true)
      }
    }

    "multiple consecutive logging lines" in {
      val fsm = Monitor[Int, LogEvent](1) {
        case 1 => {
          case Observe(event: LogEvent) if event.message.startsWith("1. The Docker client contacted the Docker daemon") =>
            Goto(2)
        }
        case 2 => {
          case Observe(event: LogEvent) if event.message.startsWith("2. The Docker daemon pulled the \"hello-world\" image") =>
            Goto(3)
        }
        case 3 => {
          case Observe(event: LogEvent) if event.message.startsWith("3. The Docker daemon created a new container") =>
            Goto(4)
        }
        case 4 => {
          case Observe(event: LogEvent) if event.message.startsWith("4. The Docker daemon streamed that output to the Docker client") =>
            Stop(Accept())
        }
      }
      def expt[Model: _docker: _validate: _errorOrOk]: Eff[Model, Notify] = for {
        obs <- docker.logs(fsm)(Observable(_))("basic")
        _ <- check(isAccepting(obs))
      } yield Accept()

      inside(expt[HelloWorldModel].runDocker(Map("basic" -> helloworld)).runError.runNel) {
        case Pure(Right(Right(Accept())), _) =>
          assert(true)
      }
    }
  }

}
