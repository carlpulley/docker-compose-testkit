package cakesolutions.docker.testkit

import java.io.File
import java.time.{ZoneId, ZonedDateTime}
import java.time.format.DateTimeFormatter

import cakesolutions.docker.testkit.DockerComposeTestKit._
import cakesolutions.docker.testkit.logging.Logger
import cakesolutions.docker.testkit.yaml.DockerComposeProtocol
import monix.execution.{Scheduler, Cancelable}
import monix.reactive.{Observable, OverflowStrategy}
import net.jcazevedo.moultingyaml._
import org.json4s._
import org.json4s.native.JsonMethods._

import scala.concurrent._
import scala.sys.process._
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

final class DockerCompose private[testkit] (projectName: String, projectId: ProjectId, yamlFile: String, config: YamlObject)(implicit driver: Driver, log: Logger) {
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

  // TODO: handle paused containers; removing built images
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

//  private def toLogEvent(rawLine: String): Try[LogEvent] = Try {
//    val line = rawLine.trim
//    val image = ???
//    log.debug(line)
//    if (line.nonEmpty) {
//      // 2016-06-11T10:10:00.154101534Z log-message
//      val logLineRE = "^\\s*(\\d+\\-\\d+\\-\\d+T\\d+:\\d+:\\d+\\.\\d+Z)\\s+(.*)\\s*\\z".r
//      val logLineMatch = logLineRE.findFirstMatchIn(line)
//      // TODO: introduce ability to parse JSON out of log messages
//      if (logLineMatch.isDefined) {
//        val time = logLineMatch.get.group(1)
//        val message = logLineMatch.get.group(2).trim
//        LogEvent(ZonedDateTime.parse(time, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.nnnnnnnnnX")), image, message)
//      } else {
//        LogEvent(ZonedDateTime.now(ZoneId.of("UTC")), image, line)
//      }
//    } else {
//      LogEvent(ZonedDateTime.now(ZoneId.of("UTC")), image, "")
//    }
//  }

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

//  def logging()(implicit scheduler: Scheduler): Observable[LogEvent] = {
//    Observable.create[LogEvent](OverflowStrategy.Unbounded) { subscriber =>
//      val cancelP = Promise[Unit]
//
//      try {
//        pool.execute(new Runnable {
//          def run(): Unit = {
//            blocking {
//              val process =
//                driver
//                  .compose
//                  .execute("-p", projectId.toString, "-f", yamlFile, "logs")
//                  .run(ProcessLogger(
//                    out => toLogEvent(out) match {
//                      case Success(value: LogEvent) =>
//                        try {
//                          subscriber.onNext(value)
//                        } catch {
//                          case exn: Throwable =>
//                            exn.printStackTrace()
//                        }
//                      case Failure(exn) =>
//                        subscriber.onError(exn)
//                    },
//                    err => toLogEvent(err) match {
//                      case Success(value: LogEvent) =>
//                        try {
//                          subscriber.onNext(value)
//                        } catch {
//                          case exn: Throwable =>
//                            exn.printStackTrace()
//                        }
//                      case Failure(exn) =>
//                        subscriber.onError(exn)
//                    }
//                  ))
//
//              cancelP.future.foreach(_ => process.destroy())
//
//              val exit = process.exitValue()
//              subscriber.onNext(LogEvent(ZonedDateTime.now(ZoneId.of("UTC")), ???, s"sys.exit: $exit"))
//
//              if (cancelP.isCompleted) {
//                subscriber.onComplete()
//              } else {
//                throw new CancellationException // FIXME: should we be doing this?
//              }
//            }
//          }
//        })
//      } catch {
//        case NonFatal(exn) =>
//          log.error("Log parsing error", exn)
//          if (! cancelP.isCompleted) {
//            cancelP.failure(exn)
//          }
//          subscriber.onError(exn)
//      }
//
//      new Cancelable {
//        override def cancel(): Unit = {
//          if (! cancelP.isCompleted) {
//            cancelP.success(())
//          }
//        }
//      }
//    }
//  }
}
