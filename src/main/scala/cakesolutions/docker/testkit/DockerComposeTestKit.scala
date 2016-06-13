package cakesolutions.docker.testkit

import java.io.{File, PrintWriter}
import java.nio.file.{Files, Paths}
import java.time.ZonedDateTime
import java.util.concurrent.{ExecutorService, Executors}
import java.util.{TimeZone, UUID}

import cakesolutions.docker.testkit.logging.Logger
import net.jcazevedo.moultingyaml._
import org.joda.time.{DateTime, DateTimeZone}

import scala.collection.JavaConversions._
import scala.concurrent.duration.FiniteDuration
import scala.sys.process._
import scala.util.Try

object DockerComposeTestKit {

  sealed trait DockerComposeDefinition {
    def contents: String
  }
  final case class DockerComposeString(contents: String) extends DockerComposeDefinition
  final case class DockerComposeYaml(yaml: Map[Any, Any]) extends DockerComposeDefinition {
    lazy val contents = toYamlValue(yaml).prettyPrint

    private def toYamlValue(a: Any): YamlValue = a match {
      case y: DockerComposeYaml =>
        toYamlValue(y.yaml)
      case n: Int =>
        YamlNumber(n)
      case n: Long =>
        YamlNumber(n)
      case n: Double =>
        YamlNumber(n)
      case n: Float =>
        YamlNumber(n)
      case n: Byte =>
        YamlNumber(n)
      case n: Short =>
        YamlNumber(n)
      case n: BigInt =>
        YamlNumber(n)
      case s: String =>
        YamlString(s)
      case d: FiniteDuration =>
        YamlString(d.toString)
      case d: ZonedDateTime =>
        YamlDate(
          new DateTime(
            d.toInstant.toEpochMilli,
            DateTimeZone.forTimeZone(TimeZone.getTimeZone(d.getZone))
          )
        )
      case b: Boolean =>
        YamlBoolean(b)
      case s: Set[_] =>
        YamlSet(s.map(toYamlValue).toSeq: _*)
      case l: List[_] =>
        YamlArray(l.map(toYamlValue): _*)
      case m: Map[_, _] =>
        YamlObject(m.map { case (k, v) => (toYamlValue(k), toYamlValue(v)) }.toSeq: _*)
      case _: Any =>
        YamlString(a.toString)
    }
  }
  final case class DockerComposeFile(filename: String) extends DockerComposeDefinition {
    lazy val contents = new String(Files.readAllBytes(Paths.get(filename)))
  }

  sealed trait State
  case object Running extends State
  case object Paused extends State
  case object Stopped extends State

  final case class ImageState(state: State, isRunning: Boolean, isPaused: Boolean, isRestarting: Boolean, isOOMKilled: Boolean, isDead: Boolean, exitCode: Option[Int], error: Option[String], startedAt: ZonedDateTime, finishedAt: ZonedDateTime)

  /////////////////////////

  final case class LogEvent(time: ZonedDateTime, message: String)

  final case class DockerEvent(/*time: ZonedDateTime,*/ service: String, action: String, attributes: Map[String, String], `type`: String, id: String)

  /////////////////////////

  final class ProjectId(val id: UUID) {
    override def toString: String = id.toString
  }
  final class DockerCommand(command: String) {
    def execute(args: String*): String = s"$command ${args.mkString(" ")}"
  }
  final class DockerComposeCommand(command: String) {
    def execute(args: String*): String = s"$command ${args.mkString(" ")}"
  }

  trait Driver {
    def docker: DockerCommand

    def compose: DockerComposeCommand

    def newId: ProjectId
  }

  implicit val shellDriver = new Driver {
    val docker = new DockerCommand("docker")
    val compose = new DockerComposeCommand("docker-compose")

    def newId = new ProjectId(UUID.randomUUID())
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
      Try({
        val versionRE(major, minor, patch) = data.stripLineEnd

        (major.toInt, minor.toInt, patch.toInt)
      }).toOption
    }
  }

  val pool: ExecutorService = Executors.newWorkStealingPool()

  def up(projectName: String, yaml: DockerComposeDefinition)(implicit driver: Driver, log: Logger): DockerCompose = {
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

    val projectId = driver.newId
    log.info(s"Up $projectName [$projectId]")
    val project = s"$projectId/$projectName"
    val projectDir = s"target/$project"
    new File(projectDir).mkdirs()
    for (path <- Files.newDirectoryStream(Paths.get(projectDir))) {
      assert(Files.deleteIfExists(path))
    }

    val yamlFile = s"$projectDir/docker-compose.yaml"
    val output = new PrintWriter(yamlFile)
    try {
      output.print(yaml.contents)
    } finally {
      output.close()
    }
    val yamlConfig = Try(driver.compose.execute("-p", projectId.toString, "-f", yamlFile, "config").!!(log.devNull).parseYaml.asYamlObject)
    require(yamlConfig.isSuccess, yamlConfig.toString)

    driver.compose.execute("-p", projectId.toString, "-f", yamlFile, "up", "-d").!!(log.stderr)

    new DockerCompose(projectName, projectId, yamlFile, yamlConfig.get)(pool, driver, log)
  }
}
