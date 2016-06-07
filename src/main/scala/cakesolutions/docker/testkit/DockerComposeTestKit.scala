// Copyright 2016 Carl Pulley

package cakesolutions.docker.testkit

import java.io.{File, PrintWriter}
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

trait DockerContainer {
  import DockerComposeTestKit._

  def pause(container: String)(implicit driver: Driver): Unit

  def unpause(container: String)(implicit driver: Driver): Unit

  def stop()(implicit driver: Driver): Unit

  def logging(implicit driver: Driver): Observable[LogEvent]

  def events(implicit driver: Driver): Observable[DockerEvent]

  def run(container: String, command: String)(implicit driver: Driver): Observable[String]

  def network(action: NetworkAction)(implicit driver: Driver): Unit

  def volume(action: VolumeAction)(implicit driver: Driver): Unit
}

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

  implicit val shellDriver = new Driver {
    val docker = DockerCommand("docker")
    val compose = DockerComposeCommand("docker-compose")
  }

  implicit class ObservableMatchFilter(obs: Observable[LogEvent]) {
    def matchFirst(eventMatch: LogEvent => Boolean): Observable[(Int, LogEvent)] = {
      obs
        .map { entry =>
          if (eventMatch(entry)) {
            Some(entry)
          } else {
            None
          }
        }
        .collect { case Some(entry) => (0, entry) }
        .first
    }

    def matchFirstOrdered(eventMatches: (LogEvent => Boolean)*): Observable[(Int, LogEvent)] = {
      obs
        .scan[(Option[(Int, LogEvent)], Int)]((None, 0)) {
          case ((_, matchedEventsCount), entry) if matchedEventsCount < eventMatches.length && eventMatches(matchedEventsCount)(entry) =>
            (Some((matchedEventsCount, entry)), matchedEventsCount + 1)
          case ((_, matchedEventsCount), _) =>
            (None, matchedEventsCount)
        }
        .collect { case (Some(indexedEntry), _) => indexedEntry }
    }

    def matchFirstUnordered(eventMatches: (LogEvent => Boolean)*): Observable[(Int, LogEvent)] = {
      obs
        .scan[(Option[(Int, LogEvent)], Set[Int])]((None, Set.empty)) {
          case ((_, matchedEventIndexes), entry) =>
            val index = eventMatches.indexWhere(_(entry))
            if (index >= 0 && ! matchedEventIndexes.contains(index)) {
              (Some((index, entry)), matchedEventIndexes + index)
            } else {
              (None, matchedEventIndexes)
            }
        }
        .collect { case (Some(indexedEntry), _) => indexedEntry }
    }

    def matchUnordered(eventMatches: (LogEvent => Boolean)*): Observable[(Int, LogEvent)] = {
      obs
        .scan[(Option[(Int, LogEvent)], Set[Int])]((None, Set.empty)) {
          case ((_, matchedEventIndexes), entry) =>
            val index = eventMatches.indexWhere(_(entry))
            if (index >= 0) {
              (Some((index, entry)), matchedEventIndexes + index)
            } else {
              (None, matchedEventIndexes)
            }
        }
        .collect { case (Some(indexedEntry), _) => indexedEntry }
    }
  }
}

/**
 * TODO:
 */
trait DockerComposeTestKit extends PatienceConfiguration {
  this: FreeSpecLike =>

  import DockerComposeTestKit._

  implicit def testDuration: FiniteDuration = 30.seconds

  implicit override def patienceConfig = super.patienceConfig.copy(timeout = Span(testDuration.toSeconds, Seconds))

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
  def observe(expected: Int)(implicit timeout: FiniteDuration) = Matcher { (obs: Observable[(Int, LogEvent)]) =>
    require(expected >= 0)

    val result = Promise[Boolean]

    obs
      .take(timeout)
      .toVector
      .materialize
      .foreach {
        case OnCompleted =>
          // No work to do
        case OnNext(matches) if matches.isEmpty =>
          alert("Matched no elements")
          result.success(expected == 0)
        case OnNext(matches) =>
          matches.foreach {
            case (index, entry) =>
              alert(s"Matched at $index: $entry")
          }
          result.success(matches.length == expected)
        case OnError(_: NoSuchElementException) =>
          alert("Matched no elements")
          result.success(expected == 0)
        case OnError(exn) =>
          alert(s"Failed to observe matches - reason: $exn")
          result.failure(exn)
      }

    MatchResult(Await.result(result.future, Duration.Inf), s"failed to observe $expected events", "observed unexpected events")
  }

  /**
   * TODO:
   *
   * @param yaml
   * @return
   */
  def start(project: String, yaml: String)(implicit driver: Driver): DockerContainer = {
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

    val nullLogger: ProcessLogger = ProcessLogger { err => }
    val errorLogger: ProcessLogger = ProcessLogger { err =>
      if (err.length > 0 && err.charAt(0) != 27.toChar) {
        alert(err)
      }
    }
    val pool: ExecutorService = Executors.newWorkStealingPool()
    val yamlFile = s"target/$project.yaml"
    val output = new PrintWriter(yamlFile)
    try {
      output.print(yaml)
    } finally {
      output.close()
    }

    val yamlCheck = Try(driver.compose.execute("-p", project, "-f", yamlFile, "config", "-q").!!(errorLogger))
    require(yamlCheck.isSuccess, yamlCheck)

    implicit val formats = DefaultFormats

    new DockerContainer {
      private[this] var isRunning: Boolean = true

      debug(driver.compose.execute("-p", project, "-f", yamlFile, "up", "-d").!!(errorLogger))

      /**
       * TODO:
       *
       * @param container
       */
      def pause(container: String)(implicit driver: Driver): Unit = {
        require(isRunning)

        debug(driver.compose.execute("-p", project, "-f", yamlFile, "pause", container).!!(errorLogger))
      }

      /**
       * TODO:
       *
       * @param container
       */
      def unpause(container: String)(implicit driver: Driver): Unit = {
        require(isRunning)

        debug(driver.compose.execute("-p", project, "-f", yamlFile, "unpause", container).!!(errorLogger))
      }

      /**
       * TODO:
       */
      def stop()(implicit driver: Driver): Unit = {
        (driver.compose.execute("-p", project, "-f", yamlFile, "logs", "-t", "--no-color") #> new File(s"target/$project.log")).!
        driver.compose.execute("-p", project, "-f", yamlFile, "unpause").!(nullLogger)
        debug(driver.compose.execute("-p", project, "-f", yamlFile, "down").!!(errorLogger))
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

        Observable[LogEvent] { subscriber =>
          try {
            pool.execute(new Runnable {
              def run(): Unit = {
                blocking {
                  for (line <- driver.compose.execute("-p", project, "-f", yamlFile, "logs", "-f", "-t", "--no-color").lineStream_!(errorLogger)) {
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
        }
        .cache
      }

      /**
       * TODO:
       *
       * @param driver
       * @return
       */
      def events(implicit driver: Driver): Observable[DockerEvent] = {
        require(isRunning)

        Observable[DockerEvent] { subscriber =>
          try {
            pool.execute(new Runnable {
              def run(): Unit = {
                blocking {
                  for (line <- driver.compose.execute("-p", project, "-f", yamlFile, "events", "--json").lineStream_!(errorLogger)) {
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
        }
        .cache
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

          case RemoveNetwork(network, networks @ _*) =>
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
          case RemoveVolume(volume, volumes @ _*) =>
            debug(driver.docker.execute("volume", "rm", volume, volumes.mkString(" ")).!!(errorLogger))
        }
      }
    }
  }
}
