package cakesolutions.docker.testkit

package yaml

import cakesolutions.docker.testkit.DockerComposeTestKit.{Driver, ProjectId}
import cakesolutions.docker.testkit.logging.Logger
import cakesolutions.docker.testkit.network.ImpairmentSpec._
import monix.execution.Scheduler
import net.jcazevedo.moultingyaml._
import org.json4s._
import org.json4s.native.JsonMethods._

import scala.sys.process._

object DockerComposeProtocol {
  final case class ConfigDescription(Subnet: String, Gateway: String)
  final case class IpamDescription(Driver: String, Options: Option[String], Config: List[ConfigDescription])
  final case class ContainerNetworkDescription(Name: String, EndpointID: String, MacAddress: String, IPv4Address: String, IPv6Address: String)
  final case class NetworkDescription(Name: String, Id: String, Scope: String, Driver: String, EnableIPv6: Boolean, IPAM: IpamDescription, Internal: Boolean, Containers: Map[String, ContainerNetworkDescription], Options: Map[String, String], Labels: Map[String, String])

  trait NetworkControl {
    def impair(impairments: Impairment*)(implicit driver: Driver): Unit

    def reset()(implicit driver: Driver): Unit
  }

  trait NetworkInteraction {
    def nic(container: String): Int

    def inspect: NetworkDescription
  }

  object Linux {
    implicit class IPTableControl(network: NetworkInteraction) extends NetworkControl {
      private def eval(impairment: Impairment): Seq[String] = impairment match {
        // limit packets
        case Limit(spec) =>
          "limit" +: spec.split(" ")
        // delay TIME [ JITTER [ CORRELATION ]]] [ distribution { uniform | normal | pareto |  paretonormal } ]
        case Delay(spec) =>
          "delay" +: spec.split(" ")
        // loss { random PERCENT [ CORRELATION ]  | state p13 [ p31 [ p32 [ p23 [ p14]]]] | gemodel p [ r [ 1-h [ 1-k ]]] }  [ ecn ]
        case Loss(spec, _ @ _*) =>
          "loss" +: spec.split(" ")
        // corrupt PERCENT [ CORRELATION ]]
        case Corrupt(spec) =>
          "corrupt" +: spec.split(" ")
        // duplicate PERCENT [ CORRELATION ]]
        case Duplicate(spec) =>
          "duplicate" +: spec.split(" ")
        // reorder PERCENT [ CORRELATION ] [ gap DISTANCE ]
        case Reorder(spec) =>
          "reorder" +: spec.split(" ")
        // rate RATE [ PACKETOVERHEAD [ CELLSIZE [ CELLOVERHEAD ]]]]
        case Rate(spec) =>
          "rate" +: spec.split(" ")
      }

      // TODO: only allow if NET_ADMIN capability is enabled
      def impair(impairments: Impairment*)(implicit driver: Driver): Unit = {
        network.inspect.Containers.keys.foreach { container =>
          val spec = impairments.flatMap(eval)

          driver
            .docker
            .execute(Seq("exec", "--user", "root", "-t", container, "tc", "qdisc", "replace", "dev", s"eth${network.nic(container)}", "root", "netem") ++ spec: _*).!!
        }
      }

      def reset()(implicit driver: Driver): Unit = {
        network.inspect.Containers.keys.foreach { container =>
          driver
            .docker
            .execute("exec", "--user", "root", "-t", container, "tc", "qdisc", "del", "dev", s"eth${network.nic(container)}", "root", "netem").!!
        }
      }
    }
  }

  object FreeBSD {
    implicit class DummyNetControl(network: NetworkControl) extends NetworkControl {
      // TODO: provide an implementation based on dummynet (http://info.iet.unipi.it/~luigi/dummynet/) for OSX/FreeBSD
      def impair(impairments: Impairment*)(implicit driver: Driver): Unit = ???

      def reset()(implicit driver: Driver): Unit = ???
    }
  }
}

private[testkit] final class DockerComposeProtocol(projectId: ProjectId, yamlFile: String)(implicit pool: Scheduler, driver: Driver, log: Logger) extends DefaultYamlProtocol {
  import DockerComposeProtocol._

  implicit val formats = DefaultFormats

  case class Service(name: String) {
    def docker: Vector[DockerImage] = {
      driver
        .compose
        .execute("-p", projectId.toString, "-f", yamlFile, "ps", "-q", name)
        .lineStream_!(log.devNull)
        .toVector
        .map(new DockerImage(projectId, _, pool)(driver, log))
    }
  }

  case class Network(name: String) extends NetworkInteraction {
    val fqNetworkName = s"${projectId.toString.replaceAll("-", "")}_${name}"

    def disconnect(): Seq[String] = {
      inspect.Containers.keys.map { container =>
        driver
          .docker
          .execute("network", "disconnect", fqNetworkName, container).!!

        container
      }.toSeq
    }

    def connect(containers: String*): Unit = {
      containers.foreach { container =>
        driver
          .docker
          .execute("network", "connect", fqNetworkName, container).!!
      }
    }

    def nic(container: String): Int = {
      driver.docker.execute("inspect", "-f", "'{{ range $key, $value := .NetworkSettings.Networks }}{{ $key }} {{end}}'", container).!!.trim.tail.dropRight(1).split(" ").map(_.trim).indexOf(fqNetworkName)
    }

    def inspect: NetworkDescription = {
      parse(driver.docker.execute("network", "inspect", fqNetworkName).!!).extract[List[NetworkDescription]].head
    }
  }

  // TODO: unreliable volume management
  case class Volume(name: String)

  implicit val serviceFormat = yamlFormat1(Service)
  implicit val networkFormat = yamlFormat1(Network)
  implicit val volumeFormat = yamlFormat1(Volume)

}
