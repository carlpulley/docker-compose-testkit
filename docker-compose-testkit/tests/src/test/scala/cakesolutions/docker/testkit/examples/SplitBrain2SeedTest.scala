package cakesolutions.docker.testkit.examples

import akka.actor.{ActorSystem, Address}
import akka.cluster.MemberStatus.Up
import cakesolutions.BuildInfo
import cakesolutions.docker.jmx.akka.AkkaClusterClient.AkkaClusterState
import cakesolutions.docker.jmx.akka._
import cakesolutions.docker.testkit.DockerComposeTestKit.LogEvent
import cakesolutions.docker.testkit._
import cakesolutions.docker.testkit.logging.{Logger, TestLogger}
import cakesolutions.docker.{jmx => _, _}
import cats.Now
import monix.execution.Scheduler
import monix.reactive.Observable
import org.atnos.eff.ErrorEffect._
import org.atnos.eff._
import org.atnos.eff.all._
import org.atnos.eff.syntax.all._
import org.scalatest.{BeforeAndAfterAll, FreeSpec, Inside, Matchers}

import scala.concurrent.Await
import scala.concurrent.duration._

object SplitBrain2SeedTest {
  val akkaProtocol = "akka.tcp"
  val akkaSystem = "TestCluster"
  val akkaPort = 2552
  val autoDown = 10.seconds
  val leftA = "left-node-A"
  val leftB = "left-node-B"
  val rightA = "right-node-A"
  val rightB = "right-node-B"
  val leaderNode = leftA
  val seedNode1 = leaderNode
  val seedNode2 = rightA
  val version = BuildInfo.version
  val title = s"${Console.MAGENTA}${Console.UNDERLINED}"
  val highlight = s"${Console.WHITE}"

  def akkaNodeStarted(node: String): LogEvent => Boolean = { event =>
    event.message.endsWith(s"Cluster Node [$akkaProtocol://$akkaSystem@$node:$akkaPort] - Started up successfully")
  }

  sealed trait MatchingState
  case object NodeStarted extends MatchingState
  final case class MemberUpCount(counts: Map[String, Int]) extends MatchingState
}

class SplitBrain2SeedTest extends FreeSpec with Matchers with Inside with BeforeAndAfterAll with DockerComposeTestKit with TestLogger {
  import DockerComposeTestKit._
  import SplitBrain2SeedTest._

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
       |      CLUSTER_SEED_NODE_1: "$akkaProtocol://$akkaSystem@$seed1:$akkaPort"
       |      CLUSTER_SEED_NODE_2: "$akkaProtocol://$akkaSystem@$seed2:$akkaPort"
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
        |  ${clusterNode(leftA, seedNode1, seedNode2, "left", "middle")}
        |  ${clusterNode(leftB, seedNode1, seedNode2, "left", "middle")}
        |  ${clusterNode(rightA, seedNode2, seedNode1, "right", "middle")}
        |  ${clusterNode(rightB, seedNode2, seedNode1, "right", "middle")}
        |
        |networks:
        |  left:
        |  middle:
        |  right:
    """.stripMargin
  )

  val compose: DockerCompose = up("split-brain-2-seed", yaml)
  val cluster = Map(
    leftA -> compose.service(leftA).docker.head,
    leftB -> compose.service(leftB).docker.head,
    rightA -> compose.service(rightA).docker.head,
    rightB -> compose.service(rightB).docker.head
  )

  override def afterAll(): Unit = {
    compose.down()
    super.afterAll()
  }

  type _validate[Model] = Validate[String, ?] |= Model
  type SplitBrain2SeedModel = Fx.fx4[DockerAction, JmxAction, Validate[String, ?], ErrorOrOk]

  def check[Model: _validate: _errorOrOk](obs: Observable[Boolean])(implicit scheduler: Scheduler): Eff[Model, Unit] = {
    for {
      isTrue <- ErrorEffect.eval(Now(Await.result(obs.runAsyncGetFirst, Duration.Inf)))
      result <- validateCheck(isTrue.contains(true), "Observable should produce a true initial value")
    } yield result
  }

  def note[Model: _errorOrOk](msg: String)(implicit log: Logger): Eff[Model, Unit] = {
    ErrorEffect.eval(Now(log.info(s"$highlight$msg")))
  }

  def observedClusterMembers(total: Int) = Monitor[MemberUpCount, (String, AkkaClusterState)](MemberUpCount(Map.empty)) {
    case MemberUpCount(counts) => {
      case Observe((name, AkkaClusterState(_, members, _))) =>
        val countUp = members.count(_.status == Up)
        if (countUp + (counts - name).values.sum < total) {
          Goto(MemberUpCount((counts - name) + (name -> countUp)))
        } else {
          Stop(Accept())
        }
    }
  }

  def notInCluster(name: String): Monitor[Unit, AkkaClusterState] = {
    val node = Address(akkaProtocol, akkaSystem, name, akkaPort)

    Monitor(()) {
      case _ => {
        case Observe(AkkaClusterState(_, members, _)) if members.exists(_.address == node) =>
          Stop(Fail(s"$name was observed as a member of the cluster"))
        case Observe(AkkaClusterState(_, members, _)) =>
          Stop(Accept())
      }
    }
  }

  s"${title}Distributed Akka cluster with inconsistent seed order" - {
    def nodeStarted(node: String) = Monitor[NodeStarted.type, LogEvent](NodeStarted) {
      case _ => {
        case Observe(event: LogEvent) if akkaNodeStarted(node)(event) =>
          Stop(Accept())
      }
    }

    s"${title}should split brain into 2 clusters" in {
      def expt[Model: _docker: _jmx: _validate :_errorOrOk]: Eff[Model, Notify] = for {
        obs1 <- docker.logs(nodeStarted(seedNode1))(Observable(_))(leftA) && docker.logs(nodeStarted(seedNode2))(Observable(_))(rightA)
        _ <- check(isAccepting(obs1))
        _ <- note(s"cluster seeds $seedNode1 and $seedNode2 are up")
        obs2 <- taggedJmx(observedClusterMembers(4))(leftA, rightA)
        _ <- check(isAccepting(obs2))
        _ <- note("all cluster nodes are up")
        obs3 <- jmx(notInCluster(rightA))(leftA) && jmx(notInCluster(leftA))(rightA)
        _ <- check(isAccepting(obs3))
        _ <- note("cluster split brains into 2 clusters")
      } yield Accept()

      inside(expt[SplitBrain2SeedModel].runDocker(cluster).runJmx(cluster).runError.runNel) {
        case Pure(Right(Right(Accept())), _) =>
          assert(true)
      }
    }
  }
}
