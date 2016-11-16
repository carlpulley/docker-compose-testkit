// Copyright 2016 Carl Pulley

package cakesolutions.docker.testkit.examples

import akka.actor.ActorSystem
import cakesolutions.BuildInfo
import cakesolutions.docker.testkit.automata.MatchingAutomata
import cakesolutions.docker.testkit.clients.LibFiuClient
import cakesolutions.docker.testkit.logging.TestLogger
import cakesolutions.docker.testkit.matchers.ObservableMatcher
import cakesolutions.docker.testkit.{DockerCompose, DockerComposeTestKit, DockerImage, TimedObservable}
import monix.execution.Scheduler
import org.scalatest._

import scala.concurrent.duration._
import scala.util.Random

class LibPreloadFaultInjectionDockerTest extends FreeSpec with Matchers with Inside with BeforeAndAfter with DockerComposeTestKit with TestLogger {
  import DockerComposeTestKit._
  import LibFiuClient._
  import MatchingAutomata.{not => negation, _}
  import ObservableMatcher._

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

  def events(image: DockerImage) = TimedObservable.hot(image.events("event=die").publish)

  def stable(period: FiniteDuration) = MatchingAutomata[Unit, String]((), period) {
    case _ => {
      case event: String =>
        Stop(Fail(s"Detected container death event: $event"))
      case StateTimeout =>
        Stop(Accept())
    }
  }

  s"${title}libfiu instrumented containers may be fault injected" - {
    s"${title}memory management fault injection" in {
      val eventStream = events(akkaNode)
      eventStream.observable.connect()

      val testSimulation = for {
        _ <- stable(initialDelay + Random.nextInt(maxWait.toSeconds.toInt).seconds).run(eventStream).outcome
        _ = note(s"${highlight}Stable running container")
        _ = akkaNode.random(posix.mm(), 0.2)
        _ = note(s"${highlight}Random fault injection enabled")
        _ <- negation(stable(maxWait).run(eventStream)).outcome
        _ = note(s"${highlight}Container died")
      } yield Accept()

      testSimulation should observe(Accept())
    }
  }
}
