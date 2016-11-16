package cakesolutions.docker.testkit.examples

import akka.actor.ActorSystem
import akka.cluster.MemberStatus.Up
import cakesolutions.BuildInfo
import cakesolutions.docker.testkit.DockerComposeTestKit.LogEvent
import cakesolutions.docker.testkit.automata.MatchingAutomata
import cakesolutions.docker.testkit.logging.TestLogger
import cakesolutions.docker.testkit.matchers.ObservableMatcher._
import cakesolutions.docker.testkit.{DockerCompose, DockerComposeTestKit, DockerImage, TimedObservable}
import cakesolutions.docker.testkit.clients.AkkaClusterClient
import cakesolutions.docker.testkit.clients.AkkaClusterClient.AkkaClusterState
import monix.execution.Scheduler
import org.scalatest.{BeforeAndAfterAll, Inside, Matchers, FreeSpec}

import scala.concurrent.duration._

object SplitBrain2SeedTest {
  import AkkaClusterClient._

  val akkaPort = 2552
  val autoDown = 10.seconds
  val leaderNode = "left-node-A"
  val seedNode1 = leaderNode
  val seedNode2 = "right-node-A"
  val version = BuildInfo.version
  val title = s"${Console.MAGENTA}${Console.UNDERLINED}"
  val highlight = s"${Console.WHITE}"

  def akkaNodeStarted(node: String): LogEvent => Boolean = { event =>
    event.message.endsWith(s"Cluster Node [akka.tcp://TestCluster@$node:$akkaPort] - Started up successfully")
  }

  final case class AkkaNodeSensors(
    log: TimedObservable.cold[LogEvent],
    members: TimedObservable.cold[AkkaClusterState]
  )
  object AkkaSensors {
    def apply(image: DockerImage)(implicit scheduler: Scheduler): AkkaNodeSensors = {
      AkkaNodeSensors(
        TimedObservable.cold(image.logging()),
        TimedObservable.cold(image.members())
      )
    }
  }

  sealed trait MatchingState
  case object NodeStarted extends MatchingState
  final case class MemberUpCount(left: Int, right: Int) extends MatchingState
}

class SplitBrain2SeedTest extends FreeSpec with Matchers with Inside with BeforeAndAfterAll with DockerComposeTestKit with TestLogger {
  import SplitBrain2SeedTest._
  import DockerComposeTestKit._
  import MatchingAutomata._

  implicit val testDuration: FiniteDuration = 3.minutes
  implicit val actorSystem = ActorSystem("AutoDownSplitBrainDocker2SeedTest")
  implicit val scheduler = Scheduler(actorSystem.dispatcher)

  def clusterNode(name: String, seed1: String, seed2: String, network1: String, network2: String): String =
    s"""$name:
       |    template:
       |      resources:
       |        - cakesolutions.docker.jmx.akka
       |        - cakesolutions.docker.network.default.linux
       |      image: docker-compose-testkit-tests:$version
       |    environment:
       |      AKKA_HOST: $name
       |      AKKA_PORT: $akkaPort
       |      CLUSTER_SEED_NODE_1: "akka.tcp://TestCluster@$seed1:$akkaPort"
       |      CLUSTER_SEED_NODE_2: "akka.tcp://TestCluster@$seed2:$akkaPort"
       |    expose:
       |      - $akkaPort
       |    networks:
       |      - $network1
       |      - $network2
     """.stripMargin

  val yaml = DockerComposeString(
    s"""version: '2'
        |
        |services:
        |  ${clusterNode("left-node-A", seedNode1, seedNode2, "left", "middle")}
        |  ${clusterNode("left-node-B", seedNode1, seedNode2, "left", "middle")}
        |  ${clusterNode("right-node-A", seedNode2, seedNode1, "right", "middle")}
        |  ${clusterNode("right-node-B", seedNode2, seedNode1, "right", "middle")}
        |
        |networks:
        |  left:
        |  middle:
        |  right:
    """.stripMargin
  )

  val compose: DockerCompose = up("split-brain-2-seed", yaml)
  lazy val leftNodeA: DockerImage = compose.service("left-node-A").docker.head
  lazy val leftNodeB: DockerImage = compose.service("left-node-B").docker.head
  lazy val rightNodeA: DockerImage = compose.service("right-node-A").docker.head
  lazy val rightNodeB: DockerImage = compose.service("right-node-B").docker.head

  override def afterAll(): Unit = {
    compose.down()
    super.afterAll()
  }

  s"${title}Distributed Akka cluster with inconsistent seed order" - {
    def nodeStarted(node: String) = MatchingAutomata[NodeStarted.type, LogEvent](NodeStarted) {
      case _ => {
        case event: LogEvent if akkaNodeStarted(node)(event) =>
          Stop(Accept())
      }
    }

    def observedClusterMembers(total: Int) = MatchingAutomata[MemberUpCount, Either[AkkaClusterState, AkkaClusterState]](MemberUpCount(0, 0)) {
      case MemberUpCount(left, right) => {
        case Left(AkkaClusterState(_, members, _)) =>
          val leftUp = members.count(_.status == Up)
          if (leftUp + right < total) {
            Goto(MemberUpCount(leftUp, right))
          } else {
            Stop(Accept())
          }
        case Right(AkkaClusterState(_, members, _)) =>
          val rightUp = members.count(_.status == Up)
          if (left + rightUp < total) {
            Goto(MemberUpCount(left, rightUp))
          } else {
            Stop(Accept())
          }
      }
    }

    s"${title}should split brain into 2 clusters" in {
      val testSimulation = for {
        _ <- (nodeStarted(seedNode1).run(AkkaSensors(leftNodeA).log) && nodeStarted(seedNode2).run(AkkaSensors(rightNodeA).log)).outcome
        _ = note(s"${highlight}$seedNode1 and $seedNode2 cluster seeds are up")
        _ <- observedClusterMembers(4).run(AkkaSensors(leftNodeA).members.map(Left(_)), AkkaSensors(rightNodeA).members.map(Right(_))).outcome
        _ = note(s"${highlight}cluster split brains into 2 clusters")
      } yield Accept()

      testSimulation should observe(Accept())
    }
  }
}
