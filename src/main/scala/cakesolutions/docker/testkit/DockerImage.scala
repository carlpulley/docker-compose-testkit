package cakesolutions.docker.testkit

import java.time.format.DateTimeFormatter
import java.time.{ZoneId, ZonedDateTime}

import cakesolutions.docker.testkit.DockerComposeTestKit.{Driver, LogEvent, ProjectId}
import cakesolutions.docker.testkit.logging.Logger
import cakesolutions.docker.testkit.network.ImpairmentSpec
import monix.execution.{Cancelable, Scheduler}
import monix.reactive.{Observable, OverflowStrategy}

import scala.concurrent._
import scala.sys.process._
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

final class DockerImage private[testkit] (projectId: ProjectId, val id: String, pool: Scheduler)(implicit driver: Driver, val log: Logger) extends DockerInspection(id) {
  import ImpairmentSpec._

  private def toLogEvent(rawLine: String): Try[LogEvent] = Try {
    val line = rawLine.trim
    log.debug(s"$id $line")
    if (line.nonEmpty) {
      // 2016-06-11T10:10:00.154101534Z log-message
      val logLineRE = "^\\s*(\\d+\\-\\d+\\-\\d+T\\d+:\\d+:\\d+\\.\\d+Z)\\s+(.*)\\s*\\z".r
      val logLineMatch = logLineRE.findFirstMatchIn(line)
      // TODO: introduce ability to parse JSON out of log messages
      if (logLineMatch.isDefined) {
        val time = logLineMatch.get.group(1)
        val message = logLineMatch.get.group(2).trim
        LogEvent(ZonedDateTime.parse(time, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.nnnnnnnnnX")), id, message)
      } else {
        LogEvent(ZonedDateTime.now(ZoneId.of("UTC")), id, line)
      }
    } else {
      LogEvent(ZonedDateTime.now(ZoneId.of("UTC")), id, "")
    }
  }

  def logging(since: ZonedDateTime = null)(implicit scheduler: Scheduler): Observable[LogEvent] = {
    Observable.create[LogEvent](OverflowStrategy.Unbounded) { subscriber =>
      val cancelP = Promise[Unit]

      try {
        pool.execute(new Runnable {
          def run(): Unit = {
            blocking {
              val sinceOption = Option(since).fold(Seq.empty[String])(ts => Seq("--since", ts.format(DateTimeFormatter.ISO_INSTANT)))
              val process =
                driver
                  .docker
                  .execute(Seq("logs", "-f") ++ sinceOption ++ Seq("-t", id): _*)
                  .run(ProcessLogger(
                    { out =>
                      if (! cancelP.isCompleted) {
                        toLogEvent(out) match {
                          case Success(value: LogEvent) =>
                            try {
                              subscriber.onNext(value)
                            } catch {
                              case exn: Throwable =>
                                exn.printStackTrace()
                            }
                          case Failure(exn) =>
                            subscriber.onError(exn)
                        }
                      }
                    },
                    { err =>
                      if (! cancelP.isCompleted) {
                        toLogEvent(err) match {
                          case Success(value: LogEvent) =>
                            try {
                              subscriber.onNext(value)
                            } catch {
                              case exn: Throwable =>
                                exn.printStackTrace()
                            }
                          case Failure(exn) =>
                            subscriber.onError(exn)
                        }
                      }
                    }
                  ))

              cancelP.future.onComplete(_ => process.destroy())(pool)

              // 143 = 128 + SIGTERM
              val exit = process.exitValue()
              if (exit != 0 && exit != 143) {
                throw new RuntimeException(s"Logging exited with value $exit")
              }
              if (! cancelP.isCompleted) {
                cancelP.success(())
                subscriber.onComplete()
              }
            }
          }
        })
      } catch {
        case NonFatal(exn) =>
          log.error("Log parsing error", exn)
          if (! cancelP.isCompleted) {
            cancelP.failure(exn)
            subscriber.onError(exn)
          }
      }

      new Cancelable {
        override def cancel(): Unit = {
          if (! cancelP.isCompleted) {
            cancelP.success(())
            subscriber.onComplete()
          }
        }
      }
    }
  }

  def exec(command: String*)(implicit scheduler: Scheduler): Observable[String] = {
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
                  .run(ProcessLogger(
                    { out =>
                      if (! cancelP.isCompleted) {
                        subscriber.onNext(out)
                      }
                    },
                    { err =>
                      if (! cancelP.isCompleted) {
                        subscriber.onNext(err)
                      }
                    }
                  ))

              cancelP.future.onComplete(_ => process.destroy())(pool)

              // 143 = 128 + SIGTERM
              val exit = process.exitValue()
              if (exit != 0 && exit != 143) {
                throw new RuntimeException(s"$command exited with value $exit")
              }
              if (! cancelP.isCompleted) {
                cancelP.success(())
                subscriber.onComplete()
              }
            }
          }
        })
      } catch {
        case NonFatal(exn) =>
          log.error(s"$command processing error", exn)
          if (! cancelP.isCompleted) {
            cancelP.failure(exn)
            subscriber.onError(exn)
          }
      }

      new Cancelable {
        override def cancel(): Unit = {
          if (! cancelP.isCompleted) {
            cancelP.success(())
            subscriber.onComplete()
          }
        }
      }
    }
  }

  def stats()(implicit scheduler: Scheduler): Observable[String] = {
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
                  .run(ProcessLogger(
                    { out =>
                      if (! cancelP.isCompleted) {
                        subscriber.onNext(out)
                      }
                    },
                    { err =>
                      if (! cancelP.isCompleted) {
                        subscriber.onNext(err)
                      }
                    }
                  ))

              cancelP.future.onComplete(_ => process.destroy())(pool)

              // 143 = 128 + SIGTERM
              val exit = process.exitValue()
              if (exit != 0 && exit != 143) {
                throw new RuntimeException(s"Statistics exited with value $exit")
              }
              if (! cancelP.isCompleted) {
                cancelP.success(())
                subscriber.onComplete()
              }
            }
          }
        })
      } catch {
        case NonFatal(exn) =>
          log.error("Statistics processing error", exn)
          if (! cancelP.isCompleted) {
            cancelP.failure(exn)
            subscriber.onError(exn)
          }
      }

      new Cancelable {
        override def cancel(): Unit = {
          if (! cancelP.isCompleted) {
            cancelP.success(())
            subscriber.onComplete()
          }
        }
      }
    }
  }

  def events(filters: String*)(implicit scheduler: Scheduler): Observable[String] = {
    Observable.create[String](OverflowStrategy.Unbounded) { subscriber =>
      val cancelP = Promise[Unit]

      try {
        pool.execute(new Runnable {
          def run(): Unit = {
            blocking {
              // TODO: parse line into a Events instance
              val process =
                driver
                  .docker
                  .execute("events" +: "--filter" +: s"container=$id" +: filters.flatMap(kv => Seq("--filter", kv)): _*)
                  .run(ProcessLogger(
                    { out =>
                      if (! cancelP.isCompleted) {
                        subscriber.onNext(out)
                      }
                    },
                    { err =>
                      if (! cancelP.isCompleted) {
                        subscriber.onNext(err)
                      }
                    }
                  ))

              cancelP.future.onComplete(_ => process.destroy())(pool)

              // 143 = 128 + SIGTERM
              val exit = process.exitValue()
              if (exit != 0 && exit != 143) {
                throw new RuntimeException(s"Events exited with value $exit")
              }
              if (! cancelP.isCompleted) {
                cancelP.success(())
                subscriber.onComplete()
              }
            }
          }
        })
      } catch {
        case NonFatal(exn) =>
          log.error("Events processing error", exn)
          if (! cancelP.isCompleted) {
            cancelP.failure(exn)
            subscriber.onError(exn)
          }
      }

      new Cancelable {
        override def cancel(): Unit = {
          if (! cancelP.isCompleted) {
            cancelP.success(())
            subscriber.onComplete()
          }
        }
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

  def network(networks: String*) = new {
    // TODO: only allow if NET_ADMIN capability is enabled
    def qdisc(impairments: Impairment*): Unit = {
      val spec = impairments.map(_.command).mkString(" ")

      networks.foreach { name =>
        val fqNetworkName = s"${projectId.toString.replaceAll("-", "")}_${name}"
        val dockerContainers = driver.docker.execute("network", "inspect", "-f", "'{{ range $key, $value := .Containers }}{{ $key }} {{end}}'", fqNetworkName).!!.split(" ")
        dockerContainers.foreach { container =>
          val nic = driver.docker.execute("inspect", "-f", "'{{ range $key, $value := .NetworkSettings.Networks }}{{ $key }} {{end}}'", container).!!.split(" ").indexOf(fqNetworkName)

          driver
            .docker
            .execute("exec", "-t", id, "tc", "qdisc", "replace", "dev", s"eth$nic", "root", "netem", spec).!!
        }
      }
    }

    def partition(): Unit = {
      networks.foreach { name =>
        driver
          .docker
          .execute("network", s"${projectId.toString.replaceAll("-", "")}_${name}", "disconnect", id).!!
      }
    }

    def reset(): Unit = {
      networks.foreach { name =>
        driver
          .docker
          .execute("network", s"${projectId.toString.replaceAll("-", "")}_${name}", "connect", id).!!
      }
      // TODO: determine NICs correctly!
      driver
        .docker
        .execute("exec", "-t", id, "tc", "qdisc", "del", "dev", "eth0", "root").!!
    }
  }
}
