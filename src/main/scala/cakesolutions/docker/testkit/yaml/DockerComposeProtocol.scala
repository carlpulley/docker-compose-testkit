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

private[testkit] class DockerComposeProtocol(projectId: ProjectId, yamlFile: String)(implicit pool: ExecutorService, driver: Driver, log: Logger) extends DefaultYamlProtocol {
  import ImpairmentSpec._

  implicit val formats = DefaultFormats

  final case class Service(name: String) {
    def docker: Vector[DockerImage] = {
      driver
        .compose
        .execute("-p", projectId.toString, "-f", yamlFile, "ps", "-q", name)
        .lineStream_!(log.devNull)
        .toVector
        .map(new DockerImage(projectId, _))
    }
  }

  final case class ConfigDescription(subnet: String, gateway: String)
  final case class IpamDescription(driver: String, options: Option[String], config: List[ConfigDescription])
  final case class ContainerNetworkDescription(name: String, endpointId: String, macAddress: String, ipv4Address: String, ipv6Address: String)
  final case class NetworkDescription(name: String, id: String, scope: String, driver: String, enableIPv6: Boolean, ipam: IpamDescription, internal: Boolean, containers: Map[String, ContainerNetworkDescription], options: Map[String, String], labels: Map[String, String])

  final case class Network(name: String) {
    private[this] val fqNetworkName = s"${projectId.toString.replaceAll("-", "")}_${name}"

    // TODO: only allow if NET_ADMIN capability is enabled
    def qdisc(impairments: Impairment*): Unit = {
      inspect.containers.keys.foreach { container =>
        val nic = driver.docker.execute("inspect", "-f", "'{{ range $key, $value := .NetworkSettings.Networks }}{{ $key }} {{end}}'", container).!!.split(" ").indexOf(fqNetworkName)
        val spec = impairments.map(_.command).mkString(" ")

        driver
          .docker
          .execute("exec", "-t", container, "tc", "qdisc", "replace", "dev", s"eth$nic", "root", "netem", spec).!!
      }
    }

    def partition(): Unit = {
      inspect.containers.keys.foreach { container =>
        driver
          .docker
          .execute("network", fqNetworkName, "disconnect", container).!!
      }
    }

    def reset(): Unit = {
      inspect.containers.keys.foreach { container =>
        val nic = driver.docker.execute("inspect", "-f", "'{{ range $key, $value := .NetworkSettings.Networks }}{{ $key }} {{end}}'", container).!!.split(" ").indexOf(fqNetworkName)

        driver
          .docker
          .execute("network", fqNetworkName, "connect", container).!!
        driver
          .docker
          .execute("exec", "-t", container, "tc", "qdisc", "del", "dev", s"eth$nic", "root", "netem").!!
      }
    }

    private def inspect: NetworkDescription = {
      parse(driver.docker.execute("network", "inspect", fqNetworkName).!!).extract[List[NetworkDescription]].head
    }
  }

  // TODO: unreliable volume management
  final case class Volume(name: String)

  implicit val serviceFormat = yamlFormat1(Service)
  implicit val networkFormat = yamlFormat1(Network)
  implicit val volumeFormat = yamlFormat1(Volume)

}
