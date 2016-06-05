// Copyright 2016 Carl Pulley

package cakesolutions.docker.testkit

import java.io.File.createTempFile
import java.io.PrintWriter
import java.time.format.DateTimeFormatter
import java.time.{ZoneId, ZonedDateTime}
import java.util.concurrent.{ExecutorService, Executors}

import org.json4s._
import org.json4s.native.JsonMethods._
import org.scalatest.FreeSpecLike
import org.scalatest.concurrent.PatienceConfiguration
import org.scalatest.exceptions.TestFailedException
import org.scalatest.matchers.{MatchResult, Matcher}
import org.scalatest.time.{Seconds, Span}
import rx.lang.scala.Notification.{OnCompleted, OnError, OnNext}
import rx.lang.scala.Observable

import scala.concurrent._
import scala.concurrent.duration._
import scala.sys.process._
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

// TODO: add in commands to inspect containers and to bimap between container and image IDs?
// TODO: upgrade logging to use contextual logging?
// TODO: write tests with 100% coverage
// TODO: abstract out command line arguments (and so promote re-targetting drivers to other implementations)
// TODO: add in configuration to allow logs to be printed out during a test run

object DockerComposeTestKit {
  /**
   * TODO:
   */
  final case class LogEvent(time: ZonedDateTime, image: Option[String], message: String)
  // {"service": "example", "time": "2016-06-03T10:33:43.105938", "action": "create", "attributes": {"image": "hello-world", "name": "t_example_1"}, "type": "container", "id": "5681ca7bf9741e1798c27f73f2c1c61b0870b493e52fae02e2a899d0ab518e7e"}
  final case class DockerEvent(/*time: ZonedDateTime,*/ service: String, action: String, attributes: Map[String, String], `type`: String, id: String)

  /**
   * TODO:
   */
  sealed trait NetworkAction
  final case class ConnectNetwork(network: String, container: String) extends NetworkAction
  final case class DisconnectNetwork(network: String, container: String) extends NetworkAction
  final case class RemoveNetwork(network: String, networks: String*) extends NetworkAction

  /**
   * TODO:
   */
  sealed trait VolumeAction
  final case class RemoveVolume(volume: String, volumes: String*) extends VolumeAction

  trait Driver {
    def docker: DockerCommand

    def compose: DockerComposeCommand
  }

  final case class DockerCommand(command: String) {
    def execute(args: String*): String = s"$command ${args.mkString(" ")}"
  }
  final case class DockerComposeCommand(command: String) {
    def execute(args: String*): String = s"$command ${args.mkString(" ")}"
  }

  implicit val testDuration: FiniteDuration = 30.seconds

  implicit val shellDriver = new Driver {
    val docker = DockerCommand("docker")
    val compose = DockerComposeCommand("docker-compose")
  }

  implicit class ObservableMatchFilter(obs: Observable[LogEvent]) {
    def matchFilter(eventMatches: (LogEvent => Boolean)*): Observable[Vector[LogEvent]] = {
      obs
        .scan(Vector.empty[LogEvent]) {
          case (state, entry: LogEvent) if eventMatches(state.length)(entry) =>
            state :+ entry
          case (state, _) =>
            state
        }
        .filter(_.length == eventMatches.length)
        .first
    }
  }
}

/**
 * TODO:
 */
trait DockerComposeTestKit extends PatienceConfiguration {
  this: FreeSpecLike =>

  import DockerComposeTestKit._

  implicit override val patienceConfig = super.patienceConfig.copy(timeout = Span(testDuration.toSeconds, Seconds))

  private final case class Version(major: Int, minor: Int, patch: Int) extends Ordered[Version] {
    override def compare(that: Version): Int = {
      if (this == that) {
        0
      } else if (
        major > that.major
          || major == that.major && minor > that.minor
          || major == that.major && minor == that.minor && patch > that.patch
      ) {
        1
      } else {
        -1
      }
    }
  }

  private def parseVersion(data: String): Option[Version] = {
    val versionRE = "^.*(\\d+)\\.(\\d+)\\.(\\d+).*$".r
    try {
      val versionRE(major, minor, patch) = data.stripLineEnd

      Some(Version(major.toInt, minor.toInt, patch.toInt))
    } catch {
      case NonFatal(exn) =>
        None
    }
  }

  protected def debug(message: String): Unit = {
    if (false) {
      info(message)
    }
  }

  /**
   * TODO:
   *
   * @param expected
   * @param timeout
   * @return
   */
  def observe(expected: Int)(implicit timeout: FiniteDuration) = Matcher { (obs: Observable[Vector[LogEvent]]) =>
    require(expected >= 0)

    val result = Promise[Boolean]

    obs
      .first
      .timeout(timeout)
      .materialize
      .foreach {
        case OnCompleted if result.isCompleted =>
          // No work to do
          debug("Received OnCompleted and promise is completed")

        case OnCompleted =>
          val errMsg = "Received OnCompleted with no other events emitted"
          alert(errMsg)
          new TestFailedException(Some(errMsg), None, 0)

        case event if result.isCompleted =>
          val errMsg = s"Received $event after the test completed"
          alert(errMsg)
          new TestFailedException(Some(errMsg), None, 0)

        case OnNext(events) =>
          events.zipWithIndex.foreach { case (item, index) => info(s"Observation $index: $item") }
          result.success(events.length == expected)

        case OnError(_: TimeoutException) =>
          debug("Test timed out - completing promise with false")
          result.success(false)

        case OnError(_: NoSuchElementException) =>
          info("Observed no elements")
          result.success(expected == 0)

        case OnError(exn) =>
          alert("Received OnError", Some(exn))
          result.failure(exn)
      }

    MatchResult(Await.result(result.future, Duration.Inf), s"failed to observe $expected events", "observed unexpected events")
  }

  trait DockerContainer {
    def pause(container: String)(implicit driver: Driver): Unit

    def unpause(container: String)(implicit driver: Driver): Unit

    def stop()(implicit driver: Driver): Unit

    def logging(implicit driver: Driver): Observable[LogEvent]

    def events(implicit driver: Driver): Observable[DockerEvent]

    def run(container: String, command: String)(implicit driver: Driver): Observable[String]

    def network(action: NetworkAction)(implicit driver: Driver): Unit

    def volume(action: VolumeAction)(implicit driver: Driver): Unit
  }

  /**
   * TODO:
   *
   * @param yaml
   * @return
   */
  protected def start(yaml: String)(implicit driver: Driver): DockerContainer = {
    val composeVersion = Try(driver.compose.execute("--version").!!).toOption.flatMap(parseVersion)
    require(
      composeVersion.exists(_ >= Version(1, 7, 0)),
      s"Need docker-compose version >= 1.7.X (have $composeVersion)"
    )
    val dockerVersion = Try(driver.docker.execute("--version").!!).toOption.flatMap(parseVersion)
    require(
      dockerVersion.exists(_ >= Version(1, 11, 0)),
      s"Need docker version >= 1.11.X (have $dockerVersion)"
    )

    val errorLogger: ProcessLogger = ProcessLogger { err =>
      if (err.length > 0 && err.charAt(0) != 27.toChar) {
        alert(err)
      }
    }
    val pool: ExecutorService = Executors.newWorkStealingPool()
    val dockerCompose = createTempFile("docker-compose-", ".yaml")
    val yamlFile = s"${System.getProperty("java.io.tmpdir")}${dockerCompose.getName}"
    val output = new PrintWriter(yamlFile)
    try {
      output.print(yaml)
    } finally {
      output.close()
    }

    val yamlCheck = Try(driver.compose.execute("-f", yamlFile, "config", "-q").!!(errorLogger))
    require(yamlCheck.isSuccess, yamlCheck)

    implicit val formats = DefaultFormats

    new DockerContainer {
      private[this] var isRunning: Boolean = true

      debug(driver.compose.execute("-f", yamlFile, "up", "-d").!!(errorLogger))

      /**
       * TODO:
       *
       * @param container
       */
      def pause(container: String)(implicit driver: Driver): Unit = {
        require(isRunning)

        debug(driver.compose.execute("-f", yamlFile, "pause", container).!!(errorLogger))
      }

      /**
       * TODO:
       *
       * @param container
       */
      def unpause(container: String)(implicit driver: Driver): Unit = {
        require(isRunning)

        debug(driver.compose.execute("-f", yamlFile, "unpause", container).!!(errorLogger))
      }

      /**
       * TODO:
       */
      def stop()(implicit driver: Driver): Unit = {
        debug(driver.compose.execute("-f", yamlFile, "down").!!(errorLogger))
        assert(dockerCompose.delete())
        pool.shutdown()
        isRunning = false
      }

      /**
       * TODO:
       *
       * @return
       */
      def logging(implicit driver: Driver): Observable[LogEvent] = {
        require(isRunning)

        Observable.defer(Observable[LogEvent] { subscriber =>
          try {
            pool.execute(new Runnable {
              def run(): Unit = {
                blocking {
                  for (line <- driver.compose.execute("-f", yamlFile, "logs", "-f", "-t", "--no-color").lineStream_!(errorLogger)) {
                    debug(line)
                    val logLineRE = "^\\s*([a-zA-Z0-9_-]+)\\s+\\|\\s+(\\d+-\\d+-\\d+T\\d+:\\d+:\\d+.\\d+Z)\\s*(.*)\\z".r
                    val logLineMatch = logLineRE.findFirstMatchIn(line)
                    // TODO: introduce ability to parse JSON out of log messages
                    if (logLineMatch.isDefined) {
                      val image = logLineMatch.get.group(1)
                      val time = logLineMatch.get.group(2)
                      val message = logLineMatch.get.group(3)
                      subscriber.onNext(LogEvent(ZonedDateTime.parse(time, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.nnnnnnnnnX")), Some(image), message))
                    } else {
                      subscriber.onNext(LogEvent(ZonedDateTime.now(ZoneId.of("UTC")), None, line))
                    }
                  }
                  subscriber.onCompleted()
                }
              }
            })
          } catch {
            case NonFatal(exn) =>
              alert("Log parsing error", Some(exn))
              subscriber.onError(exn)
          }
        })
      }

      /**
       * TODO:
       *
       * @param driver
       * @return
       */
      def events(implicit driver: Driver): Observable[DockerEvent] = {
        require(isRunning)

        Observable.defer(Observable[DockerEvent] { subscriber =>
          try {
            pool.execute(new Runnable {
              def run(): Unit = {
                blocking {
                  for (line <- driver.compose.execute("-f", yamlFile, "events", "--json").lineStream_!(errorLogger)) {
                    debug(line)
                    Try(parse(line)) match {
                      case Success(json) =>
                        subscriber.onNext(json.extract[DockerEvent])

                      case Failure(exn) =>
                        subscriber.onError(exn)
                    }
                  }
                  subscriber.onCompleted()
                }
              }
            })
          } catch {
            case NonFatal(exn) =>
              alert("Event parsing error", Some(exn))
              subscriber.onError(exn)
          }
        })
      }

      /**
       * TODO:
       *
       * @param container
       * @param command
       * @param driver
       * @return
       */
      def run(container: String, command: String)(implicit driver: Driver): Observable[String] = {
        require(isRunning)

        Observable.defer(Observable[String] { subscriber =>
          try {
            pool.execute(new Runnable {
              def run(): Unit = {
                blocking {
                  for (line <- driver.docker.execute("exec", "-it", container, command).lineStream_!(errorLogger)) {
                    subscriber.onNext(line)
                  }
                  subscriber.onCompleted()
                }
              }
            })
          } catch {
            case NonFatal(exn) =>
              alert("Command processing error", Some(exn))
              subscriber.onError(exn)
          }
        })
      }

      /**
       * TODO:
       *
       * @param action
       */
      def network(action: NetworkAction)(implicit driver: Driver): Unit = {
        require(isRunning)

        action match {
          case ConnectNetwork(network, container) =>
            debug(driver.docker.execute("network", "connect", network, container).!!(errorLogger))

          case DisconnectNetwork(network, container) =>
            debug(driver.docker.execute("network", "disconnect", network, container).!!(errorLogger))

          case RemoveNetwork(network, networks) =>
            debug(driver.docker.execute("network", "rm", network, networks.mkString(" ")).!!(errorLogger))
        }
      }

      /**
       * TODO:
       *
       * @param action
       */
      def volume(action: VolumeAction)(implicit driver: Driver): Unit = {
        require(isRunning)

        action match {
          case RemoveVolume(volume, volumes) =>
            debug(driver.docker.execute("volume", "rm", volume, volumes.mkString(" ")).!!(errorLogger))
        }
      }
    }
  }
}
