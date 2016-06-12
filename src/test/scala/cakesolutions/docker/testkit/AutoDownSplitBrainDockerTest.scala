package cakesolutions.docker.testkit

import akka.actor.{Address, AddressFromURIString}
import akka.cluster.MemberStatus
import akka.cluster.MemberStatus._
import cakesolutions.docker.testkit.DockerComposeTestKit.LogEvent
import cakesolutions.docker.testkit.filters.ObservableFilter
import cakesolutions.docker.testkit.matchers.ObservableMatcher
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.scalatest.{BeforeAndAfterAll, FreeSpec, Inside, Matchers}
import rx.lang.scala.Observable

import scala.concurrent.duration._

object ClusterUtils {
  case class AkkaClusterMember(address: Address, status: MemberStatus, roles: List[String])
  case class AkkaClusterState(selfAddress: Address, members: Set[AkkaClusterMember], unreachable: Set[AkkaClusterMember])

  object AddressFormat extends CustomSerializer[Address](format => ({
      case JString(addr) =>
        AddressFromURIString.parse(addr)
      case _: JValue =>
        throw new MappingException("Address has an invalid JSON format")
    }, {
      case value: Address =>
        JString(value.toString)
  }))

  object MemberStatusFormat extends CustomSerializer[MemberStatus](format => ({
      case JString("Joining") =>
        Joining
      case JString("WeaklyUp") =>
        WeaklyUp
      case JString("Up") =>
        Up
      case JString("Leaving") =>
        Leaving
      case JString("Exiting") =>
        Exiting
      case JString("Down") =>
        Down
      case JString("Removed") =>
        Removed
      case _: JValue =>
        throw new MappingException("MemberStatus has an invalid JSON format")
    }, {
      case value: MemberStatus =>
        JString(value.toString)
  }))

  implicit val formats = DefaultFormats + AddressFormat + MemberStatusFormat

  implicit class AkkaClusterUtil(image: DockerImage) {
    private val clusterConsole = "/opt/docker/bin/cluster-console"
    private val jmxHost = "127.0.0.1"
    private val jmxPort = "9999"

    def down(member: AkkaClusterMember): Observable[Unit] = {
      image
        .exec(clusterConsole, jmxHost, jmxPort, "down", member.address.toString)
        .tail
        .first
        .map(line => parse(line).extract[Unit]) // FIXME: empty?
    }

    def isAvailable: Observable[Boolean] = {
      image
        .exec(clusterConsole, jmxHost, jmxPort, "is-available")
        .tail
        .first
        .map(line => parse(line).extract[Boolean])
    }

    def isSingleton: Observable[Boolean] = {
      image
        .exec(clusterConsole, jmxHost, jmxPort, "is-singleton")
        .tail
        .first
        .map(line => parse(line).extract[Boolean])
    }

    def join(member: AkkaClusterMember): Observable[Unit] = {
      image
        .exec(clusterConsole, jmxHost, jmxPort, "join", member.address.toString)
        .tail
        .first
        .map(line => parse(line).extract[Unit]) // FIXME: empty?
    }

    def leader: Observable[Address] = {
      image
        .exec(clusterConsole, jmxHost, jmxPort, "leader")
        .tail
        .first
        .map(AddressFromURIString.parse)
    }

    def leave(member: AkkaClusterMember): Observable[Unit] = {
      image
        .exec(clusterConsole, jmxHost, jmxPort, "leave", member.address.toString)
        .tail
        .first
        .map(line => parse(line).extract[Unit]) // FIXME: empty?
    }

    def members: Observable[AkkaClusterState] = {
      image
        .exec(clusterConsole, jmxHost, jmxPort, "cluster-status")
        .tail
        .toVector
        .map { lines =>
          val json = parse(lines.mkString("\n")).transformField {
            case ("self-address", value) =>
              ("selfAddress", value)
          }

          json.extract[AkkaClusterState]
        }
    }

    def status: Observable[MemberStatus] = {
      image
        .exec(clusterConsole, jmxHost, jmxPort, "member-status")
        .tail
        .first
        .map(line => parse(line).extract[MemberStatus])
    }

    def unreachable: Observable[List[AkkaClusterMember]] = {
      image
        .exec(clusterConsole, jmxHost, jmxPort, "unreachable")
        .tail
        .toVector
        .map { lines =>
          parse(lines.mkString("\n")).extract[List[AkkaClusterMember]] // FIXME: comma separated list of addresses?
        }
    }
  }
}

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

class AutoDownSplitBrainDockerTest extends FreeSpec with Matchers with Inside with BeforeAndAfterAll with DockerComposeTestKit {
  import AutoDownSplitBrainDockerTest._
  import ClusterUtils._
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
      leftNodeA.logging().matchFirst(clusterJoin("left-node-A")) should observe[LogEvent](1)

      leftNodeA.isAvailable.matchFirst(_ == true) should observe[Boolean](1)
      leftNodeA.leader.matchFirst(_.host.contains("left-node-A")) should observe[Address](1)
      //leftNodeA.unreachable.matchFirst(_.isEmpty) should observe[List[AkkaClusterMember]](1)
      leftNodeA.members.matchFirstUnordered { st =>
        Set("left-node-A", "left-node-B", "right-node-A", "right-node-B").subsetOf(st.members.flatMap(_.address.host))
      } should observe[AkkaClusterState](1)

      List(leftNodeB, rightNodeA, rightNodeB)
        .map(_.logging())
        .fold(Observable.empty) { case (obs1, obs2) => obs1.mergeDelayError(obs2) }
        .matchUnordered(welcomeMessage) should observe[LogEvent](3)
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
