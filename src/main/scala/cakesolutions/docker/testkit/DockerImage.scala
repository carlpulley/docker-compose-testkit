package cakesolutions.docker.testkit

import java.time.format.DateTimeFormatter
import java.time.{ZoneId, ZonedDateTime}
import java.util.concurrent.ExecutorService

import cakesolutions.docker.testkit.DockerComposeTestKit.{Driver, LogEvent}
import cakesolutions.docker.testkit.logging.Logger
import monix.execution.{Scheduler, Cancelable}
import monix.reactive.{Observable, OverflowStrategy}

import scala.concurrent._
import scala.sys.process._
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

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

  def logging()(implicit scheduler: Scheduler): Observable[LogEvent] = {
    Observable.create[LogEvent](OverflowStrategy.Unbounded) { subscriber =>
      val cancelP = Promise[Unit]

      try {
        pool.execute(new Runnable {
          def run(): Unit = {
            blocking {
              val process =
                driver
                  .docker
                  .execute("logs", "-f", "-t", id)
                  .run(ProcessLogger(
                    out => toLogEvent(out) match {
                      case Success(value: LogEvent) =>
                        try {
                          subscriber.onNext(value)
                        } catch {
                          case exn: Throwable =>
                            log.error(s"DEBUGGY: onNext($value)")
                            exn.printStackTrace()
                        }
                      case Failure(exn) =>
                        subscriber.onError(exn)
                    },
                    err => toLogEvent(err) match {
                      case Success(value: LogEvent) =>
                        try {
                          subscriber.onNext(value)
                        } catch {
                          case exn: Throwable =>
                            log.error(s"DEBUGGY: onError($value)")
                            exn.printStackTrace()
                        }
                      case Failure(exn) =>
                        subscriber.onError(exn)
                    }
                  ))

              cancelP.future.foreach(_ => process.destroy())

              val exit = process.exitValue()

              if (! cancelP.isCompleted) {
                cancelP.failure(new CancellationException)
              }
              subscriber.onNext(LogEvent(ZonedDateTime.now(ZoneId.of("UTC")), s"sys.exit: $exit"))
              subscriber.onComplete()
            }
          }
        })
      } catch {
        case NonFatal(exn) =>
          log.error("Log parsing error", exn)
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
    }.cache
  }

  def exec(command: String*)(implicit scehduler: Scheduler): Observable[String] = {
    Observable.create[String](OverflowStrategy.Unbounded) { subscriber =>
      val cancelP = Promise[Unit]

      try {
        pool.execute(new Runnable {
          def run(): Unit = {
            blocking {
              val process =
                driver
                  .docker
                  .execute("exec" +: "-t" +: id +: command: _*)
                  .run(ProcessLogger(out => subscriber.onNext(out), err => subscriber.onNext(err)))

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
          log.error("Command processing error", exn)
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
    }.cache
  }

  def stats(command: String)(implicit scheduler: Scheduler): Observable[String] = {
    Observable.create[String](OverflowStrategy.Unbounded) { subscriber =>
      val cancelP = Promise[Unit]

      try {
        pool.execute(new Runnable {
          def run(): Unit = {
            blocking {
              // TODO: parse line into a StatisticEvent instance
              val process =
                driver
                  .docker
                  .execute("stats", id)
                  .run(ProcessLogger(out => subscriber.onNext(out), err => subscriber.onNext(err)))

              cancelP.future.foreach(_ => process.destroy())

              val exit = process.exitValue()
              if (! cancelP.isCompleted) {
                cancelP.failure(new CancellationException)
              }
              subscriber.onNext(s"sys.exit: $exit")
              subscriber.onComplete()
            }
          }
        })
      } catch {
        case NonFatal(exn) =>
          log.error("Statistics processing error", exn)
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
    }.cache
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
