// Copyright 2016 Carl Pulley

package cakesolutions.docker.testkit.clients

import akka.actor.{Address, AddressFromURIString}
import akka.cluster.MemberStatus
import akka.cluster.MemberStatus._
import cakesolutions.docker.testkit.DockerImage
import monix.execution.Scheduler
import monix.reactive.Notification.{OnError, OnNext}
import monix.reactive.Observable
import org.json4s.JsonAST.JString
import org.json4s._
import org.json4s.jackson.JsonMethods._

import scala.concurrent.duration._

// References:
// [1] https://github.com/akka/akka/blob/master/akka-cluster/src/main/scala/akka/cluster/ClusterJmx.scala
// [2] https://github.com/akka/akka/tree/master/akka-kernel/src/main/dist/bin
object AkkaClusterClient {
  final case class AkkaClusterMember(address: Address, status: MemberStatus, roles: List[String])
  final case class AkkaClusterState(selfAddress: Address, members: Set[AkkaClusterMember], unreachable: Set[UnreachableObservation])
  final case class UnreachableObservation(node: Address, observedBy: List[Address])

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
    private val clusterConsole = "/usr/local/bin/cluster-console"
    private val jmxHost = "127.0.0.1"
    // TODO: make port configurable
    private val jmxPort = "9999"

    def down(member: AkkaClusterMember)(implicit scheduler: Scheduler): Observable[Unit] = {
      image
        .exec(clusterConsole, jmxHost, jmxPort, "down", member.address.toString)
        .withErrorChecking
        .dropWhile(line => ! line.startsWith(s"Marking ${member.address} as DOWN"))
        .drop(1)
        .headF
        .map(_ => ())
    }

    def isAvailable()(implicit scheduler: Scheduler): Observable[Boolean] = {
      repeatedEval {
        image
          .exec(clusterConsole, jmxHost, jmxPort, "is-available")
          .withErrorChecking
          .dropWhile(line => ! line.startsWith(s"Checking if member node on $jmxHost is AVAILABLE"))
          .drop(1)
          .headF
          .map(line => parse(line).extract[Boolean])
      }
    }

    def isSingleton()(implicit scheduler: Scheduler): Observable[Boolean] = {
      repeatedEval {
        image
          .exec(clusterConsole, jmxHost, jmxPort, "is-singleton")
          .withErrorChecking
          .dropWhile(line => ! line.startsWith("Checking for singleton cluster"))
          .drop(1)
          .headF
          .map(line => parse(line).extract[Boolean])
      }
    }

    def join(member: AkkaClusterMember)(implicit scheduler: Scheduler): Observable[Unit] = {
      image
        .exec(clusterConsole, jmxHost, jmxPort, "join", member.address.toString)
        .withErrorChecking
        .dropWhile(line => ! line.startsWith(s"$jmxHost is JOINING cluster node ${member.address}"))
        .drop(1)
        .headF
        .map(_ => ())
    }

    def leader()(implicit scheduler: Scheduler): Observable[Address] = {
      repeatedEval {
        image
          .exec(clusterConsole, jmxHost, jmxPort, "leader")
          .withErrorChecking
          .dropWhile(line => ! line.startsWith("Checking leader status"))
          .drop(1)
          .headF
          .map(AddressFromURIString.parse)
      }
    }

    def leave(member: AkkaClusterMember)(implicit scheduler: Scheduler): Observable[Unit] = {
      image
        .exec(clusterConsole, jmxHost, jmxPort, "leave", member.address.toString)
        .withErrorChecking
        .dropWhile(line => ! line.startsWith(s"Scheduling ${member.address} to LEAVE cluster"))
        .drop(1)
        .headF
        .map(_ => ())
    }

    def members()(implicit scheduler: Scheduler): Observable[AkkaClusterState] = {
      repeatedEval {
        image
          .exec(clusterConsole, jmxHost, jmxPort, "cluster-status")
          .withErrorChecking
          .dropWhile(line => ! line.startsWith("Querying cluster status"))
          .drop(1)
          .foldLeftF(Vector.empty[String]) { case (matches, value) => matches :+ value }
          .collect {
            case lines if lines.nonEmpty =>
              val json = parse(lines.mkString("\n")).transformField {
                case ("self-address", value) =>
                  ("selfAddress", value)
              }

              json.extract[AkkaClusterState]
          }
      }
    }

    def status()(implicit scheduler: Scheduler): Observable[MemberStatus] = {
      repeatedEval {
        image
          .exec(clusterConsole, jmxHost, jmxPort, "member-status")
          .withErrorChecking
          .dropWhile(line => ! line.startsWith(s"Querying member status for $jmxHost"))
          .drop(1)
          .headF
          .map(line => parse(line).extract[MemberStatus])
      }
    }

    def unreachable()(implicit scheduler: Scheduler): Observable[List[Address]] = {
      repeatedEval {
        image
          .exec(clusterConsole, jmxHost, jmxPort, "unreachable")
          .withErrorChecking
          .dropWhile(line => ! line.startsWith("Querying unreachable members"))
          .drop(1)
          .foldLeftF(Vector.empty[String]) { case (matches, value) => matches :+ value }
          .map { lines =>
            lines.map(_.trim).filterNot(_.isEmpty).toList.flatMap(_.split(",").map(AddressFromURIString.parse))
          }
      }
    }

    // TODO: configure these constants!
    private def repeatedEval[Data](obs: => Observable[Data])(implicit scheduler: Scheduler): Observable[Data] = {
      logError {
        Observable
          .repeatEval(obs)
          .flatten
          .sample(1.second)
          .timeoutOnSlowUpstream(10.seconds)
      }.onErrorRestart(3)
    }

    private def logError[Data](obs: => Observable[Data]): Observable[Data] = {
      obs
        .materialize
        .map {
          case event @ OnError(exn) =>
            image.log.error(image.id, exn)
            event
          case event =>
            event
        }
        .dematerialize
    }

    private implicit class HelperOperations(obs: Observable[String]) {
      def withErrorChecking: Observable[String] = {
        obs
          .map {
            case reason if reason.startsWith("rpc error") =>
              OnError(new RuntimeException(reason))
            case reason if reason.startsWith("Akka cluster MBean is not available") =>
              OnError(new RuntimeException(reason))
            case data =>
              OnNext(data)
          }
          .dematerialize
      }
    }
  }
}
