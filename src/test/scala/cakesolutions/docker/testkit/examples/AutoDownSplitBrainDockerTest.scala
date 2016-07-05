package cakesolutions.docker.testkit.examples

import akka.actor.FSM.{Event, StateTimeout}
import akka.actor.{ActorSystem, Address}
import akka.cluster.MemberStatus
import cakesolutions.docker.testkit.DockerComposeTestKit.LogEvent
import cakesolutions.docker.testkit.clients.AkkaClusterClient
import cakesolutions.docker.testkit.clients.AkkaClusterClient.AkkaClusterState
import cakesolutions.docker.testkit.logging.TestLogger
import cakesolutions.docker.testkit.matchers.ObservableMatcher
import cakesolutions.docker.testkit.{DockerCompose, DockerComposeTestKit, DockerImage}
import monix.execution.Scheduler
import monix.reactive.Observable
import org.scalatest.{BeforeAndAfterAll, FreeSpec, Inside, Matchers}

import scala.concurrent.duration._

object AutoDownSplitBrainDockerTest {
  import AkkaClusterClient._

  val akkaPort = 2552
  val autoDown = 10.seconds
  val etcdPort = 2379
  val leaderNode = "left-node-A"
  val version = "0.0.3-SNAPSHOT"

  def clusterJoin(node: String): LogEvent => Boolean = { event =>
    event.message.endsWith(s"Leader is moving node [akka.tcp://SBRTestCluster@$node:$akkaPort] to [Up]")
  }

  val welcomeMessage: LogEvent => Boolean = { event =>
    event.message.endsWith(s"Welcome from [akka.tcp://SBRTestCluster@$leaderNode:$akkaPort]")
  }

  final case class AkkaNodeSensors(log: Observable[LogEvent], available: Observable[Boolean], singleton: Observable[Boolean], leader: Observable[Address], members: Observable[AkkaClusterState], status: Observable[MemberStatus], unreachable: Observable[List[Address]])
  object AkkaSensors {
    def apply(image: DockerImage)(implicit scheduler: Scheduler): AkkaNodeSensors = {
      AkkaNodeSensors(
        image.logging(),
        image.isAvailable(),
        image.isSingleton(),
        image.leader(),
        image.members(),
        image.status(),
        image.unreachable()
      )
    }
  }
  final case class InstrumentedCluster(focus: String, sensors: Map[String, AkkaNodeSensors])

  sealed trait MatchingState
  case object ReceiveWelcomeMessages extends MatchingState
  case object StableCluster extends MatchingState
  case object WaitForLeaderElection extends MatchingState
  case object WaitToBeAvailable extends MatchingState
  case object WaitToJoinCluster extends MatchingState
}

class AutoDownSplitBrainDockerTest extends FreeSpec with Matchers with Inside with BeforeAndAfterAll with DockerComposeTestKit with TestLogger {
  import AutoDownSplitBrainDockerTest._
  import DockerComposeTestKit._
  import ObservableMatcher._

  implicit val testDuration = 30.seconds
  implicit val actorSystem = ActorSystem("LossyNetworkDockerTest")
  implicit val scheduler = Scheduler(actorSystem.dispatcher)

  def clusterNode(name: String, network1: String, network2: String): (String, DockerComposeYaml) =
    name -> DockerComposeYaml(
      Map(
        "image" -> s"akka-cluster-node:$version",
        "environment" -> Map(
          "AKKA_HOST" -> name,
          "AKKA_PORT" -> akkaPort,
          "CLUSTER_AUTO_DOWN" -> autoDown,
          "CLUSTER_SEED_NODE" -> s"akka.tcp://SBRTestCluster@$leaderNode:$akkaPort"
        ),
        "cap_add" -> List("NET_ADMIN"),
        "expose" -> List(akkaPort),
        "networks" -> List(network1, network2)
      )
    )

  val yaml = DockerComposeYaml(
    Map(
      "version" -> "2",
      "services" -> Map(
        clusterNode("left-node-A", "left", "middle"),
        clusterNode("left-node-B", "left", "middle"),
        clusterNode("right-node-A", "right", "middle"),
        clusterNode("right-node-B", "right", "middle")
      ),
      "networks" -> Map(
        "left" -> Map.empty,
        "middle" -> Map.empty,
        "right" -> Map.empty
      )
    )
  )

  var compose: DockerCompose = _
  var leftNodeA: DockerImage = _
  var leftNodeB: DockerImage = _
  var rightNodeA: DockerImage = _
  var rightNodeB: DockerImage = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    compose = up("autodown-split-brain", yaml)
    leftNodeA = compose.service("left-node-A").docker.head
    leftNodeB = compose.service("left-node-B").docker.head
    rightNodeA = compose.service("right-node-A").docker.head
    rightNodeB = compose.service("right-node-B").docker.head
  }

  override def afterAll(): Unit = {
    compose.down()
    super.afterAll()
  }

  "Distributed Akka cluster with auto-downing" - {
    "should automatically seed and form" in {
      val clusterSensors = Map(
        "left.A" -> AkkaSensors(leftNodeA),
        "left.B" -> AkkaSensors(leftNodeB),
        "right.A" -> AkkaSensors(rightNodeA),
        "right.B" -> AkkaSensors(rightNodeB)
      )

      // FIXME: why do we have to duplicate type information here?
      shouldObserve[Any, MatchingState, Option[Int]](
        InitialState(WaitToJoinCluster, None, subscribeTo = Set(clusterSensors("left.A").log)),
        When[Any, MatchingState, Option[Int]](WaitToJoinCluster) {
          case Event(event: LogEvent, _) if clusterJoin("left-node-A")(event) =>
            Goto(WaitToBeAvailable, subscribeTo = Set(clusterSensors("left.A").available))
        },
        When[Any, MatchingState, Option[Int]](WaitToBeAvailable) {
          case Event(true, _) =>
            Goto(WaitForLeaderElection, subscribeTo = Set(clusterSensors("left.A").leader))
        },
        When[Any, MatchingState, Option[Int]](WaitForLeaderElection) {
          case Event(addr: Address, _) if addr.host.contains("left-node-A") =>
            Goto(ReceiveWelcomeMessages, using = Some(0), subscribeTo = Set(clusterSensors("left.B").log, clusterSensors("right.A").log, clusterSensors("right.B").log))
        },
        When[Any, MatchingState, Option[Int]](ReceiveWelcomeMessages) {
          case Event(data: LogEvent, Some(count)) if count == 2 && welcomeMessage(data) =>
            Goto(StableCluster, subscribeTo = Set(clusterSensors("left.A").unreachable))
          case event @ Event(data: LogEvent, Some(count)) if welcomeMessage(data) =>
            note(s"Matched in ReceiveWelcomeMessages: $event")
            Stay(using = Some(count + 1))
        },
        // FIXME: why does the state timeout fail to trigger here?
        When[Any, MatchingState, Option[Int]](StableCluster, 3.seconds) {
          case Event(addrs: List[_], _) if addrs.nonEmpty =>
            Fail(s"Detected $addrs as unreachable")
          case Event(StateTimeout, _) =>
            Accept
        }
      )
    }

    "GC simulation should auto-remove a node from cluster" ignore {
      val clusterSensors = Map(
        "left.A" -> AkkaSensors(leftNodeA),
        "left.B" -> AkkaSensors(leftNodeB),
        "right.A" -> AkkaSensors(rightNodeA),
        "right.B" -> AkkaSensors(rightNodeB)
      )

      // TODO: would it be useful to perform parallel matching here?
//      shouldObserve[Any, ObservationState, Int](
//        InitialState(WaitToJoinCluster, 0, subscribeTo = Set(clusterSensors("right.A").log)),
//        When[Any, ObservationState, Int](WaitToJoinCluster) {
//          case Event(event: LogEvent, _) if clusterJoin("right-node-A")(event) =>
//            rightNodeA.pause()
//            Goto(WaitForNodeDown)
//        },
//        When[Any, ObservationState, Int](WaitForNodeDown, autoDown) {
//          case Event(event: LogEvent, _) if nodeDown("right-node-A")(event) =>
//            Goto(WaitForNodeRemoval)
//        },
//        When[Any, ObservationState, Int](WaitForNodeRemoval) {
//          case Event(event: LogEvent, _) if nodeRemoval("right-node-A")(event) =>
//            rightNodeA.unpause()
//            Goto(WaitForNodeRemoval)
//        },
//        When[Any, ObservationState, Int](WaitForNodeRemoval) {
//          case Event(event: LogEvent, _) if clusterJoin("right-node-A")(event) =>
//            Accept
//        }
//      )
    }

    "network partition forms two clusters" ignore {
      compose.network("middle").partition()

      // TODO: validate formation of 2 clusters with 2 left and 1 right member each
    }

    "new nodes still allowed to join one side of a network partition" ignore {
      rightNodeA.start()

      // TODO: validate that rightNodeA joins right side of cluster (now with 2 members)
    }
  }

}
