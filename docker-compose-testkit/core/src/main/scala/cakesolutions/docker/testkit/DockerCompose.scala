// Copyright 2016 Carl Pulley

package cakesolutions.docker.testkit

import java.io.File

import cakesolutions.docker.testkit.DockerComposeTestKit._
import cakesolutions.docker.testkit.logging.Logger
import cakesolutions.docker.testkit.yaml.DockerComposeProtocol
import monix.execution.{Cancelable, Scheduler}
import monix.reactive.{Observable, OverflowStrategy}
import net.jcazevedo.moultingyaml._
import org.json4s._
import org.json4s.native.JsonMethods._

import scala.concurrent._
import scala.sys.process._
import scala.util.control.NonFatal

final class DockerCompose private[testkit] (projectName: String, projectId: ProjectId, yamlFile: String, config: YamlObject, imagesToDelete: Seq[String] = Seq.empty)(implicit driver: Driver, log: Logger) {
  require(Set("services", "networks", "volumes").subsetOf(config.fields.keySet.map(_.asInstanceOf[YamlString].value)))

  val pool = Scheduler.io(projectId.toString)
  val protocol = new DockerComposeProtocol(projectId, yamlFile)(pool, driver, log)

  import protocol._

  lazy val service: Map[String, protocol.Service] =
    config.fields(YamlString("services")).asYamlObject.fields.map { case (section, defn) =>
      val name = YamlString("name")
      val value = section.asInstanceOf[YamlString]
      value.value -> YamlObject(defn.asYamlObject.fields + (name -> value)).convertTo[protocol.Service]
    }
  lazy val network: Map[String, protocol.Network] =
    config.fields(YamlString("networks")).asYamlObject.fields.map { case (section, defn) =>
      val name = YamlString("name")
      val value = section.asInstanceOf[YamlString]
      value.value -> YamlObject(defn.asYamlObject.fields + (name -> value)).convertTo[protocol.Network]
    }
  lazy val volume: Map[String, protocol.Volume] =
    config.fields(YamlString("volumes")).asYamlObject.fields.map { case (section, defn) =>
      val name = YamlString("name")
      val value = section.asInstanceOf[YamlString]
      value.value -> YamlObject(defn.asYamlObject.fields + (name -> value)).convertTo[protocol.Volume]
    }
  lazy val docker: Map[String, DockerImage] =
    Map(driver.docker.execute("ps", "-qa").!!(log.devNull).split("\n").map(id => id -> new DockerImage(projectId, id, pool)(driver, log)): _*)

  def up(services: String*): Unit = {
    log.info(s"Up $projectName [$projectId] ${services.mkString(",")}")

    driver.compose.execute("-p" +: projectId.toString +: "-f" +: yamlFile +: "up" +: "--build" +: "--remove-orphans" +: "-d" +: services: _*) !! log.stderr
  }

  // TODO: handle paused containers
  def down(): Unit = {
    driver.compose.execute("-p", projectId.toString, "-f", yamlFile, "logs", "--no-color") #> new File(s"target/$projectId/$projectName/docker-compose.log") !! log.devNull
    for {
      name <- service.keys
      image <- service(name).docker
    } {
      driver.docker.execute("logs", image.id) #> new File(s"target/$projectId/$projectName/$name-${image.id}.log") !! log.devNull
    }
    driver.compose.execute("-p", projectId.toString, "-f", yamlFile, "down").!!(log.stderr)
    if (imagesToDelete.nonEmpty) {
      log.debug(s"Deleting temporary images: $imagesToDelete")
      for (image <- imagesToDelete) {
        driver.docker.execute("rmi", "-f", image).!!(log.stderr)
      }
    }
    log.info(s"Down $projectName [$projectId]")
  }

  def scale(services: (String, Int)*): Unit = {
    require(services.nonEmpty)

    driver.compose.execute("-p" +: projectId.toString +: "-f" +: yamlFile +: "scale" +: services.map { case (name, num) => s"$name=$num" }: _*).!!(log.stderr)
  }

  def events()(implicit scheduler: Scheduler): Observable[DockerEvent] = {
    Observable.create[DockerEvent](OverflowStrategy.Unbounded) { subscriber =>
      val cancelP = Promise[Unit]

      try {
        pool.execute(new Runnable {
          def run(): Unit = {
            blocking {
              val process =
                driver
                  .compose
                  .execute("-p", projectId.toString, "-f", yamlFile, "events", "--json")
                  .run(ProcessLogger(out => subscriber.onNext(toDockerEvent(out)), err => subscriber.onNext(toDockerEvent(err))))

              cancelP.future.foreach(_ => process.destroy())

              process.exitValue()
              if (! cancelP.isCompleted) {
                cancelP.failure(new CancellationException)
              }
              subscriber.onComplete()
            }
          }
        })
      } catch {
        case NonFatal(exn) =>
          log.error("Event parsing error", exn)
          if (! cancelP.isCompleted) {
            cancelP.failure(exn)
          }
          subscriber.onError(exn)
      }

      new Cancelable {
        override def cancel(): Unit = {
          if (! cancelP.isCompleted) {
            cancelP.success(())
          }
        }
      }
    }
  }

  private def toDockerEvent(line: String): DockerEvent = {
    log.debug(line)
    parse(line).extract[DockerEvent]
  }

}
