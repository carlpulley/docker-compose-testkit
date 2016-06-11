package cakesolutions.docker.testkit

import java.io.{File, PrintWriter}
import java.time.ZonedDateTime
import java.util.concurrent.{ExecutorService, Executors}

import net.jcazevedo.moultingyaml._

import scala.sys.process._
import scala.util.Try
import scala.util.control.NonFatal

object DockerComposeTestKit {

  sealed trait State
  case object Running extends State
  case object Paused extends State
  case object Stopped extends State

  final case class ImageState(state: State, isRunning: Boolean, isPaused: Boolean, isRestarting: Boolean, isOOMKilled: Boolean, isDead: Boolean, exitCode: Option[Int], error: Option[String], startedAt: ZonedDateTime, finishedAt: ZonedDateTime)

  /////////////////////////

  final case class LogEvent(time: ZonedDateTime, message: String)

  final case class DockerEvent(/*time: ZonedDateTime,*/ service: String, action: String, attributes: Map[String, String], `type`: String, id: String)

  /////////////////////////

  final class DockerCommand(command: String) {
    def execute(args: String*): String = s"$command ${args.mkString(" ")}"
  }
  final class DockerComposeCommand(command: String) {
    def execute(args: String*): String = s"$command ${args.mkString(" ")}"
  }

  trait Driver {
    def docker: DockerCommand

    def compose: DockerComposeCommand
  }

  implicit val shellDriver = new Driver {
    val docker = new DockerCommand("docker")
    val compose = new DockerComposeCommand("docker-compose")
  }

  implicit val log: TestLogger = new TestLogger {
    override def debug(message: String): Unit = {
      println(s"DEBUG: $message")
    }

    override def warn(message: String): Unit = {
      println(s"WARN: $message")
    }

    override def error(message: String, reason: Throwable): Unit = {
      if (reason == null) {
        println(s"ERROR: $message")
      } else {
        println(s"ERROR: $message - reason: $reason")
      }
    }

    override def info(message: String): Unit = {
      println(s"INFO: $message")
    }
  }

}

trait DockerComposeTestKit {
  import DockerComposeTestKit._

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

    override def toString: String = s"$major.$minor.$patch"
  }
  private object Version {
    def unapply(data: String): Option[(Int, Int, Int)] = {
      val versionRE = "^.*(\\d+)\\.(\\d+)\\.(\\d+).*$".r
      try {
        val versionRE(major, minor, patch) = data.stripLineEnd

        Some((major.toInt, minor.toInt, patch.toInt))
      } catch {
        case NonFatal(exn) =>
          None
      }
    }
  }

  val pool: ExecutorService = Executors.newWorkStealingPool()

  def up(project: String, yaml: String)(implicit driver: Driver, log: TestLogger): DockerCompose = {
    val composeVersion = Try(driver.compose.execute("--version").!!(log.devNull)).toOption.flatMap(Version.unapply)
    require(
      composeVersion.exists(v => (Version.apply _).tupled(v) >= Version(1, 7, 0)),
      s"Need docker-compose version >= 1.7.X (have version $composeVersion)"
    )

    val dockerVersion = Try(driver.docker.execute("--version").!!(log.devNull)).toOption.flatMap(Version.unapply)
    require(
      dockerVersion.exists(v => (Version.apply _).tupled(v) >= Version(1, 11, 0)),
      s"Need docker version >= 1.11.X (have version $dockerVersion)"
    )

    val projectDir = s"target/$project"
    val yamlFile = s"$projectDir/docker-compose.yaml"
    new File(projectDir).mkdirs()
    val output = new PrintWriter(yamlFile)
    try {
      output.print(yaml)
    } finally {
      output.close()
    }
    val yamlConfig = Try(driver.compose.execute("-p", project, "-f", yamlFile, "config").!!(log.devNull).parseYaml.asYamlObject)
    require(yamlConfig.isSuccess, yamlConfig.toString)

    driver.compose.execute("-p", project, "-f", yamlFile, "up", "-d").!!(log.stderr)

    new DockerCompose(project, yamlFile, yamlConfig.get)(pool, driver, log)
  }
}
