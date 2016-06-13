package cakesolutions.docker.testkit

import cakesolutions.docker.testkit.DockerComposeTestKit.Driver
import cakesolutions.docker.testkit.logging.Logger
import org.json4s.JsonAST.JArray
import org.json4s._
import org.json4s.native.JsonParser

import scala.sys.process._
import scala.util.{Success, Try}

private[testkit] abstract class DockerInspection(id: String)(implicit driver: Driver, log: Logger) {
  def get(path: String): Option[JValue] = {
    Try(JsonParser.parse(driver.docker.execute("inspect", "-f", s"{{ json $path }}", id).!!(log.stderr))).toOption
  }

  def get(collection: String, path: String): Option[List[JValue]] = {
    Try(JsonParser.parse(driver.docker.execute("inspect", "-f", s"{{ range $collection }}{{ $path }}{{ end }}", id).!!(log.stderr))) match {
      case Success(JArray(array)) =>
        Some(array)
      case _ =>
        None
    }
  }
}
