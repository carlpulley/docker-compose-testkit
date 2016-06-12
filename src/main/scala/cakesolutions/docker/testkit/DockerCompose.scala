package cakesolutions.docker.testkit

import java.io.File
import java.util.concurrent.ExecutorService

import cakesolutions.docker.testkit.DockerComposeTestKit._
import cakesolutions.docker.testkit.logging.TestLogger
import cakesolutions.docker.testkit.yaml.DockerComposeProtocol
import net.jcazevedo.moultingyaml._
import org.json4s._
import org.json4s.native.JsonMethods._
import rx.lang.scala.Observable

import scala.concurrent._
import scala.sys.process._
import scala.util.control.NonFatal

final class DockerCompose private[testkit] (projectName: String, projectId: ProjectId, yamlFile: String, config: YamlObject)(implicit pool: ExecutorService, driver: Driver, log: TestLogger) {
  require(Set("services", "networks", "volumes").subsetOf(config.fields.keySet.map(_.asInstanceOf[YamlString].value)))

  val protocol = new DockerComposeProtocol(projectId, yamlFile)

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

  def down(): Unit = {
    driver.compose.execute("-p", projectId.toString, "-f", yamlFile, "logs", "--no-color") #> new File(s"target/$projectId/$projectName/docker-compose.log") !! log.devNull
    for {
      name <- service.keys
      image <- service(name).docker
    } {
      driver.docker.execute("logs", image.id) #> new File(s"target/$projectId/$projectName/$name-${image.id}.log") !! log.devNull
    }
    driver.compose.execute("-p", projectId.toString, "-f", yamlFile, "down").!!(log.stderr)
    log.info(s"Down $projectName [$projectId]")
  }

  private def toDockerEvent(line: String): DockerEvent = {
    log.debug(line)
    parse(line).extract[DockerEvent]
  }

  def events(): Observable[DockerEvent] = {
    Observable[DockerEvent] { subscriber =>
      try {
        pool.execute(new Runnable {
          def run(): Unit = {
            blocking {
              driver
                .compose
                .execute("-p", projectId.toString, "-f", yamlFile, "events", "--json")
                .run(ProcessLogger(out => subscriber.onNext(toDockerEvent(out)), err => subscriber.onNext(toDockerEvent(err))))
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
