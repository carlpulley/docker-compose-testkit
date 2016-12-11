// Copyright 2016 Carl Pulley

package cakesolutions.docker.testkit

import java.io.{File, PrintWriter}
import java.net.URI
import java.nio.file._
import java.time.ZonedDateTime
import java.util.{TimeZone, UUID}

import cakesolutions.docker.testkit.logging.Logger
import net.jcazevedo.moultingyaml._
import org.joda.time.{DateTime, DateTimeZone}
import org.json4s.JsonAST.{JArray, JNull, JString}
import org.json4s.native.JsonParser

import scala.collection.JavaConversions._
import scala.compat.java8.StreamConverters._
import scala.concurrent.duration.FiniteDuration
import scala.reflect.runtime.currentMirror
import scala.sys.process._
import scala.tools.reflect.ToolBox
import scala.util.control.NonFatal
import scala.util.{Success, Try}

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

  final case class LogEvent(time: ZonedDateTime, image: String, message: String)

  final case class DockerEvent(/*time: ZonedDateTime,*/ service: String, action: String, attributes: Map[String, String], `type`: String, id: String)

  final case class DockerFile(entrypoint: Seq[String], cmd: Seq[String], user: Option[String], from: String, rawContents: Seq[String])

  final case class ServiceBuilder(baseDockerfile: DockerFile, properties: Map[YamlValue, YamlValue] = Map.empty)

  /////////////////////////

  final class ProjectId(val id: UUID) {
    override def toString: String = id.toString
  }
  final class DockerCommand(command: String) {
    def execute(args: String*): Seq[String] = command +: args.toSeq
  }
  final class DockerComposeCommand(command: String) {
    def execute(args: String*): Seq[String] = command +: args.toSeq
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

  def up(projectName: String, yaml: DockerComposeDefinition, initialServices: String*)(implicit driver: Driver, log: Logger): DockerCompose = {
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
    log.info(s"Up $projectName [$projectId] ${initialServices.mkString(",")}")
    val project = s"$projectId/$projectName"
    val projectDir = s"target/$project"
    new File(projectDir).mkdirs()
    for (path <- Files.newDirectoryStream(Paths.get(projectDir))) {
      assert(Files.deleteIfExists(path))
    }

    log.debug(driver.docker.execute("images").!!(log.stderr))

    val parsedYaml = Try(yaml.contents.parseYaml)
    assert(parsedYaml.isSuccess, s"Failed to parse docker compose YAML - reason: $parsedYaml")
    parsedYaml.foreach {
      case YamlObject(obj) =>
        assert(obj.containsKey(YamlString("version")) && obj(YamlString("version")) == YamlString("2"), "Docker compose YAML should be version 2")
        assert(obj.containsKey(YamlString("services")), "Docker compose YAML needs a `services` key")
        obj(YamlString("services")) match {
          case YamlObject(services) =>
            services.values.foreach {
              case YamlObject(service) =>
                if (service.containsKey(YamlString("template"))) {
                  assert(! service.containsKey(YamlString("image")) && ! service.containsKey(YamlString("build")), "Docker compose `template` key is not usable with `image` and `build` keys")
                  service(YamlString("template")) match {
                    case YamlObject(template) =>
                      assert(template.containsKey(YamlString("resources")), "Docker compose YAML templates must contain a `resources` key")
                      assert(template.containsKey(YamlString("image")), "Docker compose YAML templates must contain an `image` key")
                      // FIXME: following needs fixing!!
                      assert(template(YamlString("image")).isInstanceOf[YamlString], "`image` key should be a string")
                      assert(template(YamlString("resources")).isInstanceOf[YamlArray], "`resources` key should be an array")
                      template(YamlString("resources")).asInstanceOf[YamlArray].elements.foreach { value =>
                        assert(value.isInstanceOf[YamlString], "All `resources` template values should be strings")
                      }
                      assert(template(YamlString("image")).asInstanceOf[YamlString].value.matches("^([^:]+)(:[^:]*)?$"), "`image` should match the regular expression `^([^:]+)(:[^:]*)?$`")
                    case _ =>
                      assert(assertion = false, "Docker compose YAML `template` key should be an object")
                  }
                }
              case _ =>
                assert(assertion = false, "Each docker compose `services` member should be an object")
            }
          case _ =>
            assert(assertion = false, "Docker compose YAML `services` key should be an object")
        }
      case _ =>
        assert(assertion = false, "Docker compose YAML should be an object")
    }
    val templatedServicesWithImageResources = parsedYaml.get.asYamlObject.fields(YamlString("services")).asYamlObject.fields.filter {
      case (_, service) =>
        service.asYamlObject.fields.containsKey(YamlString("template"))
    }.map { kv =>
      kv.asInstanceOf[(YamlString, YamlObject)] match {
        case (YamlString(name), YamlObject(service)) =>
          val imagePattern = "^([^:]+)(:[^:]*)?$".r
          val template = service(YamlString("template")).asYamlObject.fields
          val baseImage = template(YamlString("image")).asInstanceOf[YamlString].value
          if (driver.docker.execute("images", "-q", baseImage).!!(log.stderr).trim == "") {
            try {
              driver.docker.execute("pull", baseImage).!!(log.stderr)
            } catch {
              case NonFatal(exn) =>
                log.error(s"Failed to pull docker image $baseImage", exn)
            }
          }
          val imagePattern(repository, _) = baseImage
          // TODO: implement some real error handling here!
          val resources = (template(YamlString("resources")): @unchecked) match {
            case YamlArray(elements) =>
              elements.map(_.asInstanceOf[YamlString].value)
          }

          val rawEntryPoint = Try(driver.docker.execute("inspect", "--format", "{{json .Config.Entrypoint}}", baseImage).!!(log.stderr).trim)
          assert(rawEntryPoint.isSuccess, s"$baseImage does not exist in the docker registry")
          val entryPoint =
            if (rawEntryPoint.get == "null") {
              Seq.empty[String]
            } else {
              (Try(JsonParser.parse(rawEntryPoint.get)): @unchecked) match {
                case Success(JArray(list)) =>
                  list.map(json => (json: @unchecked) match {
                    case JString(data) => data
                  }).toSeq
                case Success(JString(data)) =>
                  data.split("\\s+").toSeq
                case Success(JNull) =>
                  Seq.empty[String]
              }
            }
          val rawCmd = driver.docker.execute("inspect", "--format", "{{json .Config.Cmd}}", baseImage).!!(log.stderr).trim
          val cmd =
            if (rawCmd == "null") {
              Seq.empty[String]
            } else {
              (Try(JsonParser.parse(rawCmd)): @unchecked) match {
                case Success(JArray(list)) =>
                  list.map(json => (json: @unchecked) match { case JString(data) => data }).toSeq
                case Success(JString(data)) =>
                  data.split("\\s+").toSeq
                case Success(JNull) =>
                  Seq.empty[String]
              }
            }
          val rawUser = driver.docker.execute("inspect", "--format", "{{json .Config.User}}", baseImage).!!(log.stderr).trim
          val user =
            if (rawUser == "null") {
              None
            } else {
              (rawUser.drop(1).dropRight(1): @unchecked) match {
                case "" =>
                  None
                case data =>
                  Some(data)
              }
            }

          val baseDockerfile = DockerFile(entryPoint, cmd, user, from = baseImage, rawContents = Seq(s"FROM $baseImage"))
          assert(resources.nonEmpty)
          assert(resources.length == resources.distinct.length)
          val expandedBuild = Try(resources.foldLeft(ServiceBuilder(baseDockerfile))(evaluateTemplate(projectDir, name, log)))
          expandedBuild.recover { case NonFatal(exn) => exn.printStackTrace() }

          Files.write(Paths.get(s"$projectDir/$name/docker/Dockerfile"), expandedBuild.get.baseDockerfile.rawContents.mkString("", "\n", "\n").getBytes)

          (s"$repository:$name.$projectId", Map(YamlString(name) -> YamlObject(service - YamlString("template") + (YamlString("image") -> YamlString(s"$repository:$name.$projectId")) + (YamlString("build") -> YamlObject(YamlString("context") -> YamlString(s"./$name/docker"))) ++ expandedBuild.get.properties)))
      }
    }
    val imagesToDelete = templatedServicesWithImageResources.keys.toSeq
    val templatedServices = templatedServicesWithImageResources.flatMap(_._2)
    val nonTemplatedServices = parsedYaml.get.asYamlObject.fields(YamlString("services")).asYamlObject.fields.filter {
      case (_, service) =>
        ! service.asYamlObject.fields.containsKey(YamlString("template"))
    }
    val transformedYaml = YamlObject(
      parsedYaml.get.asYamlObject.fields.updated(
        YamlString("services"),
        YamlObject(nonTemplatedServices ++ templatedServices)
      )
    )

    val yamlFile = s"$projectDir/docker-compose.yaml"
    val output = new PrintWriter(yamlFile)
    try {
      output.print(transformedYaml.prettyPrint)
    } finally {
      output.close()
    }
    val yamlConfig = Try(driver.compose.execute("-p", projectId.toString, "-f", yamlFile, "config").!!(log.stderr).parseYaml.asYamlObject)
    require(yamlConfig.isSuccess, yamlConfig.toString)

    driver.compose.execute("-p" +: projectId.toString +: "-f" +: yamlFile +: "up" +: "--build" +: "--remove-orphans" +: "-d" +: initialServices: _*).!!(log.stderr)

    new DockerCompose(projectName, projectId, yamlFile, yamlConfig.get, imagesToDelete)(driver, log)
  }

  private def evaluateTemplate(projectDir: String, serviceName: String, log: Logger)(builder: ServiceBuilder, resource: String): ServiceBuilder = {
    val dockerDir = getClass.getResource(s"/${resource.replace(".", "/")}").toString
    assert(dockerDir.startsWith("jar:") && dockerDir.contains("!"), s"Expected $dockerDir to reference a path within a jar file")
    val jarName = dockerDir.split("!")(0)
    val jarPath = dockerDir.split("!")(1)
    val jarFS = FileSystems.newFileSystem(URI.create(jarName), Map.empty[String, Any])

    try {
      val dockerPath = jarFS.getPath(jarPath)

      val originalDockerfile = builder.baseDockerfile
      copyTemplateJarResources(projectDir, serviceName, dockerDir, dockerPath, resource, originalDockerfile, log)
      val updatedDockerfile = evaluateDockerfile(resource, originalDockerfile).getOrElse(originalDockerfile)
      val updatedProperties = evaluateService(resource).getOrElse(Map.empty[YamlValue, YamlValue])

      ServiceBuilder(updatedDockerfile, builder.properties ++ updatedProperties)
    } finally {
      jarFS.close()
    }
  }

  private def copyTemplateJarResources(projectDir: String, serviceName: String, dockerDir: String, dockerPath: Path, resource: String, baseDockerfile: DockerFile, log: Logger): Unit = {
    for (path <- Files.walk(dockerPath).toScala[List]) {
      if (path.toString.endsWith(".template")) {
        // Intentionally ignore template files
        log.debug(s"Ignoring template file: $path")
      } else if (path.toString.matches(".*/template/[^/]*$")) {
        // Intentionally ignore template directories and files
        log.debug(s"Ignoring template file: $path")
      } else {
        val targetFile = Paths.get(path.toString.replace(dockerPath.toString, s"$projectDir/$serviceName/docker"))
        val targetDir = new File(targetFile.getParent.toString)
        if (!targetDir.exists()) {
          targetDir.mkdirs()
        }
        if (!new File(targetFile.toString).exists()) {
          Files.copy(path, targetFile, StandardCopyOption.COPY_ATTRIBUTES)
        }
      }
    }
  }

  private def eval[A](string: String): A = {
    val toolbox = currentMirror.mkToolBox()
    val tree = toolbox.parse(string)
    toolbox.eval(tree).asInstanceOf[A]
  }

  private def evaluateDockerfile(resource: String, oldDockerfile: DockerFile): Option[DockerFile] = {
    try {
      val templateFactory = eval[DockerFile => String](s"(dockerfile: cakesolutions.docker.testkit.DockerComposeTestKit.DockerFile) => $resource.template.Dockerfile(dockerfile).body")
      val contents: Array[String] = templateFactory(oldDockerfile).split("\n")

      val Entrypoint = """^ENTRYPOINT\s+(.+)$""".r
      val Cmd = """^CMD\s+(.+)$""".r
      val User = """^USER\s+(.+)$""".r
      val entryPoint = {
        val result = contents.collect { case Entrypoint(value) => value.trim }.toSeq
        if (result.isEmpty) {
          oldDockerfile.entrypoint
        } else {
          result
        }
      }
      val cmd = {
        val result = contents.collect { case Cmd(value) => value.trim }.toSeq
        if (result.isEmpty) {
          oldDockerfile.cmd
        } else {
          result
        }
      }
      val user = contents.collect { case User(value) => value.trim }.lastOption.orElse(oldDockerfile.user)

      Some(DockerFile(entryPoint, cmd, user, oldDockerfile.from, oldDockerfile.rawContents ++ contents.filterNot(_.matches("^FROM\\s+.*$"))))
    } catch {
      case NonFatal(exn) =>
        None
    }
  }

  private def evaluateService(resource: String): Option[Map[YamlValue, YamlValue]] = {
    try {
      val templateFactory = eval[String](s"$resource.template.Service().body")
      Some(templateFactory.parseYaml.asYamlObject.fields)
    } catch {
      case NonFatal(_) =>
        None
    }
  }
}
