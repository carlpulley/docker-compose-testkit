package cakesolutions.docker.testkit.clients

import akka.actor.{AddressFromURIString, Address}
import akka.cluster.MemberStatus
import akka.cluster.MemberStatus._
import cakesolutions.docker.testkit.DockerImage
import org.json4s._
import org.json4s.JsonAST.JString
import org.json4s.jackson.JsonMethods._
import rx.lang.scala.Observable

// References:
// [1] https://github.com/akka/akka/blob/master/akka-cluster/src/main/scala/akka/cluster/ClusterJmx.scala
// [2] https://github.com/akka/akka/tree/master/akka-kernel/src/main/dist/bin
object AkkaClusterClient {
  case class AkkaClusterMember(address: Address, status: MemberStatus, roles: List[String])
  case class UnreachableObservation(node: Address, observedBy: List[Address])
  case class AkkaClusterState(selfAddress: Address, members: Set[AkkaClusterMember], unreachable: Set[UnreachableObservation])

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
        .map(_ => ())
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
        .map(_ => ())
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
        .map(_ => ())
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

    def unreachable: Observable[List[Address]] = {
      image
        .exec(clusterConsole, jmxHost, jmxPort, "unreachable")
        .tail
        .toVector
        .map { lines =>
          lines.map(_.trim).filterNot(_.isEmpty).toList.flatMap(_.split(",").map(AddressFromURIString.parse))
        }
    }
  }
}
