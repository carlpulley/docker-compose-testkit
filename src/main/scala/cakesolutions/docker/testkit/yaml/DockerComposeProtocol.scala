package cakesolutions.docker.testkit

package yaml

import java.util.concurrent.ExecutorService

import cakesolutions.docker.testkit.DockerComposeTestKit.{Driver, ProjectId}
import cakesolutions.docker.testkit.logging.Logger
import net.jcazevedo.moultingyaml._

import scala.sys.process._

private[testkit] class DockerComposeProtocol(projectId: ProjectId, yamlFile: String)(implicit pool: ExecutorService, driver: Driver, log: Logger) extends DefaultYamlProtocol {

  final case class Service(name: String) {
    def docker: Vector[DockerImage] = {
      driver
        .compose
        .execute("-p", projectId.toString, "-f", yamlFile, "ps", "-q", name)
        .lineStream_!(log.devNull)
        .toVector
        .map(new DockerImage(_))
    }
  }
  implicit val serviceFormat = yamlFormat1(Service)

  final case class Network(name: String)
  implicit val networkFormat = yamlFormat1(Network)

  final case class Volume(name: String)
  implicit val volumeFormat = yamlFormat1(Volume)

}
