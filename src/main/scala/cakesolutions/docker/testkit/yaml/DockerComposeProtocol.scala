package cakesolutions.docker.testkit

package yaml

import java.util.concurrent.ExecutorService

import cakesolutions.docker.testkit.DockerComposeTestKit.{Driver, ProjectId}
import cakesolutions.docker.testkit.logging.Logger
import cakesolutions.docker.testkit.network.ImpairmentSpec
import net.jcazevedo.moultingyaml._
import org.json4s._
import org.json4s.native.JsonMethods._

import scala.sys.process._

object DockerComposeProtocol {
  final case class ConfigDescription(Subnet: String, Gateway: String)
  final case class IpamDescription(Driver: String, Options: Option[String], Config: List[ConfigDescription])
  final case class ContainerNetworkDescription(Name: String, EndpointID: String, MacAddress: String, IPv4Address: String, IPv6Address: String)
  final case class NetworkDescription(Name: String, Id: String, Scope: String, Driver: String, EnableIPv6: Boolean, IPAM: IpamDescription, Internal: Boolean, Containers: Map[String, ContainerNetworkDescription], Options: Map[String, String], Labels: Map[String, String])
}

private[testkit] class DockerComposeProtocol(projectId: ProjectId, yamlFile: String)(implicit pool: ExecutorService, driver: Driver, log: Logger) extends DefaultYamlProtocol {
  import DockerComposeProtocol._
  import ImpairmentSpec._

  implicit val formats = DefaultFormats

  case class Service(name: String) {
    def docker: Vector[DockerImage] = {
      driver
        .compose
        .execute("-p", projectId.toString, "-f", yamlFile, "ps", "-q", name)
        .lineStream_!(log.devNull)
        .toVector
        .map(new DockerImage(projectId, _))
    }
  }

  case class Network(name: String) {
    private[this] val fqNetworkName = s"${projectId.toString.replaceAll("-", "")}_${name}"

    // TODO: only allow if NET_ADMIN capability is enabled
    def qdisc(impairments: Impairment*): Unit = {
      inspect.Containers.keys.foreach { container =>
        val spec = impairments.flatMap(_.command)

        driver
          .docker
          .execute(Seq("exec", "-t", container, "tc", "qdisc", "replace", "dev", s"eth${nic(container)}", "root", "netem") ++ spec: _*).!!
      }
    }

    def partition(): Unit = {
      inspect.Containers.keys.foreach { container =>
        driver
          .docker
          .execute("network", "disconnect", fqNetworkName, container).!!
      }
    }

    def reset(): Unit = {
      inspect.Containers.keys.foreach { container =>
        driver
          .docker
          .execute("network", "connect", fqNetworkName, container).!!
        driver
          .docker
          .execute("exec", "-t", container, "tc", "qdisc", "del", "dev", s"eth${nic(container)}", "root", "netem").!!
      }
    }

    private def nic(container: String): Int = {
      driver.docker.execute("inspect", "-f", "'{{ range $key, $value := .NetworkSettings.Networks }}{{ $key }} {{end}}'", container).!!.trim.tail.dropRight(1).split(" ").map(_.trim).indexOf(fqNetworkName)
    }

    private def inspect: NetworkDescription = {
      parse(driver.docker.execute("network", "inspect", fqNetworkName).!!).extract[List[NetworkDescription]].head
    }
  }

  // TODO: unreliable volume management
  case class Volume(name: String)

  implicit val serviceFormat = yamlFormat1(Service)
  implicit val networkFormat = yamlFormat1(Network)
  implicit val volumeFormat = yamlFormat1(Volume)

}
