package cakesolutions.docker.testkit

import java.util.concurrent.ExecutorService

import cakesolutions.docker.testkit.DockerComposeTestKit._
import cakesolutions.docker.testkit.yaml.DockerComposeProtocol
import net.jcazevedo.moultingyaml._
import org.json4s._
import org.json4s.native.JsonMethods._
import rx.lang.scala.{Observable, Subscriber}

import scala.concurrent._
import scala.sys.process._
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

final class DockerCompose private[testkit] (project: String, yamlFile: String, config: YamlObject)(implicit pool: ExecutorService, driver: Driver, log: TestLogger) {
  require(Set("services", "networks", "volumes").subsetOf(config.fields.keySet.map(_.asInstanceOf[YamlString].value)))

  val protocol = new DockerComposeProtocol(project, yamlFile)

  import protocol._

  implicit val formats = DefaultFormats

  lazy val service: Map[String, Service] =
    config.fields(YamlString("services")).asYamlObject.fields.map { case (section, defn) =>
      val name = YamlString("name")
      val value = section.asInstanceOf[YamlString]
      value.value -> YamlObject(defn.asYamlObject.fields + (name -> value)).convertTo[Service]
    }
  lazy val network: Map[String, Network] =
    config.fields(YamlString("networks")).asYamlObject.fields.map { case (section, defn) =>
      val name = YamlString("name")
      val value = section.asInstanceOf[YamlString]
      value.value -> YamlObject(defn.asYamlObject.fields + (name -> value)).convertTo[Network]
    }
  lazy val volume: Map[String, Volume] =
    config.fields(YamlString("volumes")).asYamlObject.fields.map { case (section, defn) =>
      val name = YamlString("name")
      val value = section.asInstanceOf[YamlString]
      value.value -> YamlObject(defn.asYamlObject.fields + (name -> value)).convertTo[Volume]
    }
  lazy val docker: Map[String, DockerImage] =
    Map(driver.docker.execute("ps", "-qa").!!(log.devNull).split("\n").map(id => id -> new DockerImage(id)): _*)

  def scale(service: String, size: Int): Unit = ??? // TODO:

  def down(): Unit = {
    driver.compose.execute("-p", project, "-f", yamlFile, "down").!!(log.stderr)
  }

  private implicit class ToDockerEvent(sub: Subscriber[DockerEvent]) {
    def dockerEvent(line: String): Unit = {
      log.debug(line)
      Try(parse(line)) match {
        case Success(json) =>
          sub.onNext(json.extract[DockerEvent])
        case Failure(exn) =>
          sub.onError(exn)
      }
    }
  }

  def events(): Observable[DockerEvent] = {
    Observable[DockerEvent] { subscriber =>
      try {
        pool.execute(new Runnable {
          def run(): Unit = {
            blocking {
              driver
                .compose
                .execute("-p", project, "-f", yamlFile, "events", "--json")
                .run(ProcessLogger(out => subscriber.dockerEvent(out), err => subscriber.dockerEvent(err)))
                .exitValue()
              subscriber.onCompleted()
            }
          }
        })
      } catch {
        case NonFatal(exn) =>
          log.error("Event parsing error", exn)
          subscriber.onError(exn)
      }
    }
    .cache
  }
}
