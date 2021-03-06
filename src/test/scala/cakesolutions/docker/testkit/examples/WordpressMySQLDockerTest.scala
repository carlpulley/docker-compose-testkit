// Copyright 2016 Carl Pulley

package cakesolutions.docker.testkit.examples

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{HttpEntity, HttpHeader, Multipart, StatusCodes}
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import cakesolutions.docker.testkit.DockerComposeTestKit.LogEvent
import cakesolutions.docker.testkit.automata.MatchingAutomata
import cakesolutions.docker.testkit.clients.RestAPIClient
import cakesolutions.docker.testkit.logging.TestLogger
import cakesolutions.docker.testkit.matchers.ObservableMatcher._
import cakesolutions.docker.testkit.{DockerCompose, DockerComposeTestKit, DockerImage, TimedObservable}
import monix.execution.Scheduler
import monix.reactive.Observable
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures

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
  import MatchingAutomata._
  import RestAPIClient._
  import WordpressLogEvents._

  implicit val testDuration = 2.minutes
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

      val fsm = MatchingAutomata[Int, LogEvent](0) {
        case 0 => {
          case event: LogEvent if startedEvent(event) =>
            checkSiteUp.runAsyncGetLast
            Goto(1)
        }
        case 1 => {
          case event: LogEvent if accessEvent(event) =>
            Stop(Accept())
        }
      }

      fsm.run(TimedObservable.cold(wordpress.logging())) should observe(Accept())
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

      val fsm = MatchingAutomata[Int, LogEvent](0) {
        case 0 => {
          case event: LogEvent if startedEvent(event) =>
            configureSite.runAsyncGetLast
            Goto(1)
        }
        case 1 => {
          case event: LogEvent if setupEvents(0)(event) =>
            Goto(2)
        }
        case 2 => {
          case event: LogEvent if setupEvents(1)(event) =>
            Goto(3)
        }
        case 3 => {
          case event: LogEvent if setupEvents(2)(event) =>
            Stop(Accept())
        }
      }

      fsm.run(TimedObservable.cold(wordpress.logging())) should observe(Accept())
    }
  }
}
