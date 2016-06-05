package cakesolutions.docker.testkit

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpEntity, HttpHeader, Multipart, StatusCodes}
import akka.http.scaladsl.server.{Route, RouteResult}
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import akka.stream.ActorMaterializer
import cakesolutions.docker.testkit.DockerComposeTestKit.LogEvent
import org.scalatest._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Success}

trait RestAPIUtils {
  this: FreeSpecLike =>

  def restClient(implicit system: ActorSystem, materializer: ActorMaterializer, ec: ExecutionContext): Route = { ctx =>
    val httpRequest = ctx.request
    val response =
      Http()
        .singleRequest(httpRequest)
        .map(RouteResult.Complete)

    response.onComplete {
      case Success(http) if false =>
        info(s"Successful HTTP query:\n      $httpRequest\n      ${http.response}")

      case Success(_) =>
        // Intentionally ignore

      case Failure(exn) =>
        alert(s"Failed to receive response to $httpRequest", Some(exn))
    }

    response
  }
}

object WordpressLogEvents {
  val startedEvent =
    (entry: LogEvent) => entry.image.contains("wordpress_1") && entry.message.endsWith("[core:notice] [pid 1] AH00094: Command line: 'apache2 -D FOREGROUND'")

  val accessEvent =
    (entry: LogEvent) => entry.image.contains("wordpress_1") && entry.message.matches(".* \"GET /wp-admin/install.php HTTP/1.1\" .*")

  val setupEvents = Seq(
    (entry: LogEvent) => entry.image.contains("wordpress_1") && entry.message.matches(".* \"POST /wp-admin/install.php\\?step=1 HTTP/1.1\" .*"),
    (entry: LogEvent) => entry.image.contains("wordpress_1") && entry.message.matches(".* \"POST /wp-admin/install.php\\?step=2 HTTP/1.1\" .*"),
    (entry: LogEvent) => entry.image.contains("wordpress_1") && entry.message.matches(".* \"GET / HTTP/1.1\" .*")
  )
}

class WordpressMySQLDockerTest extends FreeSpec with Matchers with Inside with ScalatestRouteTest with DockerComposeTestKit with RestAPIUtils {
  import DockerComposeTestKit._
  import WordpressLogEvents._


  implicit override val testDuration: FiniteDuration = 60.seconds

  implicit val routeTestTimeout = RouteTestTimeout(testDuration)

  val webHost = "127.0.0.1"
  val webPort = 8080

  var container: DockerContainer = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    container = start(
      s"""version: '2'
      |
      |services:
      |  wordpress:
      |    image: wordpress
      |    environment:
      |      WORDPRESS_DB_HOST: db:3306
      |      WORDPRESS_DB_PASSWORD: password
      |    ports:
      |      - $webPort:80
      |    networks:
      |      - public
      |      - private
      |  db:
      |    image: mariadb
      |    environment:
      |      MYSQL_ROOT_PASSWORD: password
      |    networks:
      |      - private
      |
      |networks:
      |  public:
      |  private:
      """.stripMargin
    )
  }

  override def afterAll(): Unit = {
    container.stop()
    super.afterAll()
  }

  alert("WARNING: the underlying MySQL container implementation can consume Docker volume resources")
  alert("- use `docker volume rm $(docker volume ls -qf dangling=true)` to avoid out of disk space errors")

  "Wordpress and MySQL networked application" - {

    "wordpress site is up and responding" in {
      container.logging.matchFilter(startedEvent) should observe(1)

      Get(s"http://$webHost:$webPort/") ~> restClient ~> check {
        status shouldEqual StatusCodes.Found
        inside(header("Location")) {
          case Some(HttpHeader(_, location)) if location == s"http://$webHost:$webPort/wp-admin/install.php" =>
            Get(s"http://$webHost:$webPort/wp-admin/install.php") ~> restClient ~> check {
              status shouldEqual StatusCodes.OK
              responseAs[String] should include("<title>WordPress &rsaquo; Installation</title>")
            }
        }
      }

      container.logging.matchFilter(accessEvent) should observe(1)
    }

    "wordpress hello-world site setup" in {
      val languageForm = Multipart.FormData {
        Map("language" -> HttpEntity("en_GB"))
      }
      val step1Form = Multipart.FormData {
        Map(
          "admin_email" -> HttpEntity("jo.bloggs@nowhere.com"),
          "admin_password" -> HttpEntity("e1gn)ZsD(kp$r$LQPU"),
          "admin_password2" -> HttpEntity("e1gn)ZsD(kp$r$LQPU"),
          "language" -> HttpEntity("en_GB"),
          "pass1-text" -> HttpEntity("e1gn)ZsD(kp$r$LQPU"),
          "Submit" -> HttpEntity("Install WordPress"),
          "user_name" -> HttpEntity("Jo Bloggs"),
          "weblog_title" -> HttpEntity("Hello World")
        )
      }

      Post(s"http://$webHost:$webPort/wp-admin/install.php?step=1", languageForm) ~> restClient ~> check {
        status shouldEqual StatusCodes.OK

        Post(s"http://$webHost:$webPort/wp-admin/install.php?step=2", step1Form) ~> restClient ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[String] should include("<h1>Success!</h1>")

          Get(s"http://$webHost:$webPort/") ~> restClient ~> check {
            status shouldEqual StatusCodes.OK
            responseAs[String] should include("Hello world!")
          }
        }
      }

      container.logging.matchFilter(setupEvents: _*) should observe(setupEvents.length)
    }
  }
}
