// Copyright 2016 Carl Pulley

package cakesolutions.docker.testkit.examples

import akka.actor.{ActorSystem, Address}
import akka.cluster.MemberStatus
import cakesolutions.BuildInfo
import cakesolutions.docker.jmx.akka._
import cakesolutions.docker.jvm._
import cakesolutions.docker.network.default.linux._
import cakesolutions.docker.testkit.DockerComposeTestKit.DockerComposeYaml
import cakesolutions.docker.testkit._
import cakesolutions.docker.testkit.clients.AkkaClusterClient.AkkaClusterState
import cakesolutions.docker.testkit.logging.{Logger, TestLogger}
import cakesolutions.docker.{jmx => _, jvm => _, _}
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

object ChaosExperimentTest {
  val akkaProtocol = "akka.tcp"
  val akkaSystem = "TestCluster"
  val akkaPort = 2552
  val autoDown = 10.seconds
  val leftA = "left-node-A"
  val leftB = "left-node-B"
  val rightA = "right-node-A"
  val rightB = "right-node-B"
  val leaderNode = leftA
  val version = BuildInfo.version
}

class ChaosExperimentTest extends FreeSpec with Matchers with Inside with BeforeAndAfterAll with DockerComposeTestKit with TestLogger {
  import ChaosExperimentTest._

  implicit val testDuration: FiniteDuration = 3.minutes
  implicit val actorSystem = ActorSystem("AutoDownSplitBrainDockerTest")
  implicit val scheduler = Scheduler(actorSystem.dispatcher)

  def clusterNode(name: String, network1: String, network2: String): (String, DockerComposeYaml) =
    name -> DockerComposeYaml(
      Map(
        "template" -> Map(
          "resources" -> List(
            "cakesolutions.docker.jmx.akka",
            "cakesolutions.docker.network.default.linux"
          ),
          "image" -> s"docker-compose-testkit-tests:$version"
        ),
        "environment" -> Map(
          "AKKA_HOST" -> name,
          "AKKA_PORT" -> akkaPort,
          "CLUSTER_AUTO_DOWN" -> autoDown,
          "CLUSTER_SEED_NODE_1" -> s"$akkaProtocol://$akkaSystem@$leaderNode:$akkaPort"
        ),
        "expose" -> List(akkaPort),
        "networks" -> List(network1, network2)
      )
    )

  val yaml = DockerComposeYaml(
    Map(
      "version" -> "2",
      "services" -> Map(
        clusterNode(leftA, "left", "middle"),
        clusterNode(leftB, "left", "middle"),
        clusterNode(rightA, "right", "middle"),
        clusterNode(rightB, "right", "middle")
      ),
      "networks" -> Map(
        "left" -> Map.empty,
        "middle" -> Map.empty,
        "right" -> Map.empty
      )
    )
  )

  implicit val compose: DockerCompose = up("autodown-split-brain", yaml)

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

  s"${Console.MAGENTA}${Console.UNDERLINED}Akka cluster with auto-downing split brains when network is partitioned" in {
    type _validate[Model] = Validate[String, ?] |= Model
    type ExperimentalModel = Fx.fx5[JvmGCAction, JmxAction, NetworkAction, Validate[String, ?], ErrorOrOk]

    /**
     * State `false` iff we've not yet observed a formed cluster. Cluster is formed iff expected nodes are `Up` and are
     * all reachable. Monitor succeeds iff the cluster now remains formed for 3 seconds.
     *
     * @param names hostnames of cluster members
     * @return
     */
    def inCluster(names: String*): Monitor[Boolean, AkkaClusterState] = {
      val nodes = names.map { nm =>
        Address(akkaProtocol, akkaSystem, nm, akkaPort)
      }

      Monitor(false) {
        case false => {
          case Observe(AkkaClusterState(_, members, unreachable)) if unreachable.isEmpty && nodes.forall(node => members.filter(_.status == MemberStatus.Up).exists(_.address == node)) =>
            Goto(true, 3.seconds)
        }
        case true => {
          case Observe(AkkaClusterState(_, _, unreachable)) if unreachable.nonEmpty =>
            Stop(Fail())
          case Observe(AkkaClusterState(_, members, _)) if nodes.exists(node => members.filter(_.status == MemberStatus.Up).forall(_.address != node)) =>
            Stop(Fail())
          case StateTimeout =>
            Stop(Accept())
        }
      }
    }

    /**
     * Monitor succeeds iff we eventually observe node address as being reachable.
     *
     * @param name hostname of cluster member
     * @return
     */
    def unreachable(name: String): Monitor[Unit, AkkaClusterState] = {
      val node = Address(akkaProtocol, akkaSystem, name, akkaPort)

      Monitor(()) {
        case _ => {
          case Observe(AkkaClusterState(_, _, unreachable)) if unreachable.exists(_.node == node) =>
            Stop(Accept())
        }
      }
    }

    def checkDistinct[Model: _validate](nodes: String*): Eff[Model, Unit] = {
      validateCheck[Model, String](nodes == nodes.distinct, s"$nodes should be distinct addresses")
    }

    def check[Model: _validate: _errorOrOk](obs: Observable[Boolean])(implicit scheduler: Scheduler): Eff[Model, Unit] = {
      for {
        isTrue <- ErrorEffect.eval(Now(Await.result(obs.runAsyncGetFirst, Duration.Inf)))
        result <- validateCheck(isTrue.contains(true), "Observable should produce a true initial value")
      } yield result
    }

    def note[Model: _errorOrOk](msg: String)(implicit log: Logger): Eff[Model, Unit] = {
      ErrorEffect.eval(Now(log.info(s"${Console.WHITE}$msg")))
    }

    def experiment[Model: _jvm: _jmx: _network: _validate: _errorOrOk](implicit compose: DockerCompose, system: ActorSystem, scheduler: Scheduler, log: Logger): Eff[Model, Notify] =
      for {
        _ <- checkDistinct(leftA, leftB, rightA, rightB)
        obs1 <- jmx(inCluster(leftA, leftB, rightA, rightB))(leftA)
        _ <- check(isAccepting(obs1))
        _ <- note("cluster stable with members: left.A & left.B & right.A & right.B")
        _ <- jvmGC(JvmGCStart)(rightA)
        _ <- note("right.A GC pause starts")
        obs2 <- jmx(unreachable(rightA))(leftA)
        _ <- check(isAccepting(obs2))
        _ <- note("left.A sees right.A as unreachable")
        _ <- jvmGC(JvmGCEnd)(rightA)
        _ <- note("right.A GC pause ends")
        obs3 <- jmx(inCluster(leftA, leftB, rightA, rightB))(leftA)
        _ <- check(isAccepting(obs3))
        _ <- note("cluster stable and right.A is allowed to rejoin")
        _ <- partition("middle")
        _ <- note("partition into left and right networks")
        obs4 <- jmx(inCluster(leftA, leftB))(leftA) && jmx(inCluster(leftA, leftB))(leftB) && jmx(inCluster(rightA))(rightA) && jmx(inCluster(rightB))(rightB)
        _ <- check(isAccepting(obs4))
        _ <- note("cluster split brains into 3 clusters: left.A & left.B; right.A; right.B")
      } yield Accept()

    inside(experiment[ExperimentalModel].runJvm(cluster).runJmx(cluster).runNetwork.runError.runNel) {
      case Pure(Right(Right(Accept())), _) =>
        assert(true)
    }
  }
}
