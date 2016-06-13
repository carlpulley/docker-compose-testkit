package cakesolutions.docker.testkit

import java.time.format.DateTimeFormatter
import java.time.{ZoneId, ZonedDateTime}
import java.util.concurrent.ExecutorService

import cakesolutions.docker.testkit.DockerComposeTestKit.{Driver, LogEvent}
import cakesolutions.docker.testkit.logging.Logger
import rx.lang.scala.Observable

import scala.concurrent._
import scala.sys.process._
import scala.util.{Success, Failure, Try}
import scala.util.control.NonFatal

final class DockerImage private[testkit] (val id: String)(implicit pool: ExecutorService, driver: Driver, log: Logger) extends DockerInspection(id) {
  private def toLogEvent(rawLine: String): Try[LogEvent] = Try {
    val line = rawLine.trim
    log.debug(line)
    if (line.nonEmpty) {
      // 2016-06-11T10:10:00.154101534Z log-message
      val logLineRE = "^\\s*(\\d+\\-\\d+\\-\\d+T\\d+:\\d+:\\d+\\.\\d+Z)\\s+(.*)\\s*\\z".r
      val logLineMatch = logLineRE.findFirstMatchIn(line)
      // TODO: introduce ability to parse JSON out of log messages
      if (logLineMatch.isDefined) {
        val time = logLineMatch.get.group(1)
        val message = logLineMatch.get.group(2).trim
        LogEvent(ZonedDateTime.parse(time, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.nnnnnnnnnX")), message)
      } else {
        LogEvent(ZonedDateTime.now(ZoneId.of("UTC")), line)
      }
    } else {
      LogEvent(ZonedDateTime.now(ZoneId.of("UTC")), "")
    }
  }

  def logging(): Observable[LogEvent] = {
    Observable[LogEvent] { subscriber =>
      try {
        pool.execute(new Runnable {
          def run(): Unit = {
            blocking {
              val exit =
                driver
                  .docker
                  .execute("logs", "-f", "-t", id)
                  .run(ProcessLogger(
                    out => toLogEvent(out) match {
                      case Success(value) => subscriber.onNext(value)
                      case Failure(exn) => subscriber.onError(exn)
                    },
                    err => toLogEvent(err) match {
                      case Success(value) => subscriber.onNext(value)
                      case Failure(exn) => subscriber.onError(exn)
                    }
                  ))
                  .exitValue()
              subscriber.onNext(LogEvent(ZonedDateTime.now(ZoneId.of("UTC")), s"sys.exit: $exit"))
              subscriber.onCompleted()
            }
          }
        })
      } catch {
        case NonFatal(exn) =>
          log.error("Log parsing error", exn)
          subscriber.onError(exn)
      }
    }
  }

  def exec(command: String*): Observable[String] = {
    Observable[String] { subscriber =>
      try {
        pool.execute(new Runnable {
          def run(): Unit = {
            blocking {
              driver
                .docker
                .execute("exec" +: "-t" +: id +: command: _*)
                .run(ProcessLogger(out => subscriber.onNext(out), err => subscriber.onNext(err)))
                .exitValue()
              subscriber.onCompleted()
            }
          }
        })
      } catch {
        case NonFatal(exn) =>
          log.error("Command processing error", exn)
          subscriber.onError(exn)
      }
    }
  }

  def stats(command: String): Observable[String] = {
    Observable[String] { subscriber =>
      try {
        pool.execute(new Runnable {
          def run(): Unit = {
            blocking {
              // TODO: parse line into a StatisticEvent instance
              val exit =
                driver
                  .docker
                  .execute("stats", id)
                  .run(ProcessLogger(out => subscriber.onNext(out), err => subscriber.onNext(err)))
                  .exitValue()
              subscriber.onNext(s"sys.exit: $exit")
              subscriber.onCompleted()
            }
          }
        })
      } catch {
        case NonFatal(exn) =>
          log.error("Statistics processing error", exn)
          subscriber.onError(exn)
      }
    }
  }

  def export(out: String): Unit = {
    log.debug(driver.docker.execute("export", id, "-o", out).!!(log.stderr))
  }

  def cp(from: String, to: String): Unit = {
    log.debug(driver.docker.execute("cp", "-L", s"$id:$from", to).!!(log.stderr))
  }

  def pause(): Unit = {
    log.debug(driver.docker.execute("pause", id).!!(log.stderr))
  }

  def unpause(): Unit = {
    log.debug(driver.docker.execute("unpause", id).!!(log.stderr))
  }

  def stop(): Unit = {
    log.debug(driver.docker.execute("stop", id).!!(log.stderr))
  }

  def start(): Unit = {
    log.debug(driver.docker.execute("start", id).!!(log.stderr))
  }

  def restart(): Unit = {
    log.debug(driver.docker.execute("restart", id).!!(log.stderr))
  }
}
