// Copyright 2016 Carl Pulley

package cakesolutions.docker.testkit.examples

import akka.actor.ActorSystem
import cakesolutions.BuildInfo
import cakesolutions.docker._
import cakesolutions.docker.libfiu._
import cakesolutions.docker.testkit._
import cakesolutions.docker.testkit.clients.LibFiuClient
import cakesolutions.docker.testkit.logging.{Logger, TestLogger}
import cats.Now
import monix.execution.Scheduler
import monix.reactive.Observable
import org.atnos.eff.ErrorEffect._
import org.atnos.eff._
import org.atnos.eff.all._
import org.atnos.eff.syntax.all._
import org.scalatest._

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Random

class LibPreloadFaultInjectionDockerTest extends FreeSpec with Matchers with Inside with BeforeAndAfter with DockerComposeTestKit with TestLogger {
  import DockerComposeTestKit._
  import LibFiuClient._

  val initialDelay = 10.seconds
  val maxWait = 40.seconds
  val version = BuildInfo.version
  val title = s"${Console.MAGENTA}${Console.UNDERLINED}"
  val highlight = s"${Console.WHITE}"

  implicit val testDuration: FiniteDuration = initialDelay + (2 * maxWait)
  implicit val actorSystem = ActorSystem("LibPreloadFaultInjectionDockerTest")
  implicit val scheduler = Scheduler(actorSystem.dispatcher)

  val yaml = DockerComposeString(
    s"""version: '2'
      |
      |services:
      |  akka-node:
      |    template:
      |      resources:
      |        - cakesolutions.docker.libfiu
      |      image: docker-compose-testkit-tests:$version
      |    environment:
      |      AKKA_HOST: akka-node
      |      AKKA_PORT: 2552
      |      CLUSTER_SEED_NODE: akka.tcp://TestCluster@akka-node:2552
      |    networks:
      |      - private
      |
      |networks:
      |  private:
     """.stripMargin
  )

  var compose: DockerCompose = _
  var akkaNode: DockerImage = _

  before {
    compose = up("fault-injection", yaml)
    akkaNode = compose.service("akka-node").docker.head
  }

  after {
    compose.down()
  }

  type _validate[Model] = Validate[String, ?] |= Model
  type LibPreloadFaultInjectionModel = Fx.fx4[DockerAction, LibfiuAction, Validate[String, ?], ErrorOrOk]

  def check[Model: _validate: _errorOrOk](obs: Observable[Boolean])(implicit scheduler: Scheduler): Eff[Model, Unit] = {
    for {
      isTrue <- ErrorEffect.eval(Now(Await.result(obs.runAsyncGetFirst, Duration.Inf)))
      result <- validateCheck(isTrue.contains(true), "Observable should produce a true initial value")
    } yield result
  }

  def note[Model: _errorOrOk](msg: String)(implicit log: Logger): Eff[Model, Unit] = {
    ErrorEffect.eval(Now(log.info(s"${Console.WHITE}$msg")))
  }

  def stable(period: FiniteDuration) = Monitor[Unit, String]((), period) {
    case _ => {
      case Observe(event: String) =>
        Stop(Fail(s"Detected container death event: $event"))
      case StateTimeout =>
        Stop(Accept())
    }
  }

  s"${title}libfiu instrumented containers may be fault injected" - {
    s"${title}memory management fault injection" in {
      def expt[Model: _docker: _libfiu: _validate :_errorOrOk]: Eff[Model, Notify] = for {
        obs1 <- docker.events(stable(initialDelay + Random.nextInt(maxWait.toSeconds.toInt).seconds))(Observable(_))("event=die")("akka-node")
        _ <- check(isAccepting(obs1))
        _ <- note(s"${highlight}Stable running container")
        _ <- random(posix.mm(), 0.2)("akka-node")
        _ <- note(s"${highlight}Random fault injection enabled")
        obs2 <- docker.events(stable(maxWait))(Observable(_))("event=die")("akka-node").invert
        _ <- check(isAccepting(obs2))
        _ <- note(s"${highlight}Container died")
      } yield Accept()

      inside(expt[LibPreloadFaultInjectionModel].runDocker(Map("akka-node" -> akkaNode)).runLibfiu(Map("akka-node" -> akkaNode)).runError.runNel) {
        case Pure(Right(Right(Accept())), _) =>
          assert(true)
      }
    }
  }
}
