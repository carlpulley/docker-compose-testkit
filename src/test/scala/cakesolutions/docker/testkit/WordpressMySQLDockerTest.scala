package cakesolutions.docker.testkit

import akka.actor.ActorSystem
import akka.actor.FSM.Event
import akka.http.scaladsl.model.{HttpEntity, HttpHeader, Multipart, StatusCodes}
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import cakesolutions.docker.testkit.DockerComposeTestKit.LogEvent
import cakesolutions.docker.testkit.clients.RestAPIClient
import cakesolutions.docker.testkit.logging.TestLogger
import cakesolutions.docker.testkit.matchers._
import monix.execution.Scheduler
import monix.reactive.Notification.{OnComplete, OnError, OnNext}
import monix.reactive.{Notification, Observable}
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.Promise
import scala.concurrent.duration._

object WordpressLogEvents {
  val startedEvent =
    (entry: LogEvent) => entry.message.endsWith("[core:notice] [pid 1] AH00094: Command line: 'apache2 -D FOREGROUND'")

  val accessEvent =
    (entry: LogEvent) => entry.message.matches(".* \"GET /wp-admin/install.php HTTP/1.1\" .*")

  val setupEvents = Seq(
    (entry: LogEvent) => entry.message.matches(".* \"POST /wp-admin/install.php\\?step=1 HTTP/1.1\" .*"),
    (entry: LogEvent) => entry.message.matches(".* \"POST /wp-admin/install.php\\?step=2 HTTP/1.1\" .*"),
    (entry: LogEvent) => entry.message.matches(".* \"GET / HTTP/1.1\" .*")
  )
}

class WordpressMySQLDockerTest extends FreeSpec with Matchers with Inside with ScalatestRouteTest with DockerComposeTestKit with TestLogger with ScalaFutures {
  import DockerComposeTestKit._
  import ObservableMatcher._
  import RestAPIClient._
  import WordpressLogEvents._

  implicit val testDuration = 60.seconds
  implicit val routeTestTimeout = RouteTestTimeout(testDuration)
  implicit val actorSystem = ActorSystem("WordpressMySQLDockerTest")
  implicit val scheduler = Scheduler(actorSystem.dispatcher)
  override implicit val patienceConfig = super.patienceConfig.copy(timeout = testDuration)

  val webHost = "127.0.0.1"
  val webPort = 8080

  val yaml = DockerComposeString(
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
     |    depends_on:
     |      - db
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

  var compose: DockerCompose = _
  var wordpress: DockerImage = _
  var mysql: DockerImage = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    compose = up("wordpress", yaml)
    wordpress = compose.service("wordpress").docker.head
    mysql = compose.service("db").docker.head
  }

  override def afterAll(): Unit = {
    compose.down()
    super.afterAll()
  }

  alert("WARNING: the underlying MySQL container implementation can consume Docker volume resources")
  alert("- use `docker volume rm $(docker volume ls -qf dangling=true)` to avoid out of disk space errors")

  "Wordpress and MySQL networked application" - {

    "wordpress site is up and responding" in {
      val checkSiteUp = Observable.defer {
        Observable(
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
        )
      }

      wordpress.logging() should observe[LogEvent, Int, Unit](
        InitialState(0, ()),
        When(0) {
          case Event(event: LogEvent, _) if startedEvent(event) =>
            checkSiteUp.runAsyncGetLast
            Goto(1)
        },
        When(1) {
          case Event(event: LogEvent, _) if accessEvent(event) =>
            Accept
        }
      )
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
      val configureSite = Observable.defer {
        Observable(
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
        )
      }

      wordpress.logging() should observe[LogEvent, Int, Unit](
        InitialState(0, ()),
        When(0) {
          case Event(event: LogEvent, _) if startedEvent(event) =>
            configureSite.runAsyncGetLast
            Goto(1)
        },
        When(1) {
          case Event(event: LogEvent, _) if setupEvents(0)(event) =>
            Goto(2)
        },
        When(2) {
          case Event(event: LogEvent, _) if setupEvents(1)(event) =>
            Goto(3)
        },
        When(3) {
          case Event(event: LogEvent, _) if setupEvents(2)(event) =>
            Accept
        }
      )
    }
  }
}
