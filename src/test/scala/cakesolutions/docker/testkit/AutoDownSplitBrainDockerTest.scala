package cakesolutions.docker.testkit
/*
import cakesolutions.docker.testkit.DockerComposeTestKit.LogEvent
import cakesolutions.docker.testkit.clients.AkkaClusterClient
import cakesolutions.docker.testkit.filters.ObservableFilter
import cakesolutions.docker.testkit.logging.TestLogger
import cakesolutions.docker.testkit.matchers.ObservableMatcher
import org.scalatest.{BeforeAndAfterAll, FreeSpec, Inside, Matchers}
import monix.reactive.Observable

import scala.concurrent.duration._

object AutoDownSplitBrainDockerTest {
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
}

class AutoDownSplitBrainDockerTest extends FreeSpec with Matchers with Inside with BeforeAndAfterAll with DockerComposeTestKit with TestLogger {
  import AkkaClusterClient._
  import AutoDownSplitBrainDockerTest._
  import DockerComposeTestKit._
  import ObservableFilter._
  import ObservableMatcher._

  implicit val testDuration = 30.seconds

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

  // TODO: add scaling commands to DockerComposeTestKit
  "Distributed Akka cluster with auto-downing" - {
    "should automatically seed and form" in {
      leftNodeA.logging().matchFirst(clusterJoin("left-node-A"))
        .andThen(leftNodeA.isAvailable.matchFirst(_ == true))
        .andThen(leftNodeA.leader.matchFirst(_.host.contains("left-node-A")))
        .andThen(leftNodeA.unreachable.matchFirst(_.isEmpty))
        .andThen(leftNodeA.members.matchFirstUnordered { st =>
          Set("left-node-A", "left-node-B", "right-node-A", "right-node-B").subsetOf(st.members.flatMap(_.address.host))
        })
        .andThen( // FIXME:
          List(leftNodeB, rightNodeA, rightNodeB)
            .map(_.logging())
            .fold(Observable.empty) { case (obs1, obs2) => obs1.mergeDelayError(obs2) }
            .matchUnordered(welcomeMessage)
        )
    }

    "GC simulation should remove a node from cluster" ignore {
      rightNodeA.pause()
      // TODO: wait for auto-downing timeout period
      rightNodeA.unpause()

      // TODO: validate downing and removal of nodeE
      //container.scale("right-node", -1)
    }

    "network partition forms two clusters" ignore {
      //container.network(RemoveNetwork("middle"))

      // TODO: validate formation of 2 clusters with 2 members each
    }

    "new nodes still allowed to join one side of a network partition" ignore {
      //container.scale("right-node", 1)

      // TODO: validate that nodeE joins right side of cluster (now with 3 members)
    }
  }

}
*/