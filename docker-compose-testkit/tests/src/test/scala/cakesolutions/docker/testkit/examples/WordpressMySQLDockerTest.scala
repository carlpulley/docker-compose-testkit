// Copyright 2016 Carl Pulley

package cakesolutions.docker.testkit.examples

import akka.http.scaladsl.model.{HttpEntity, HttpHeader, Multipart, StatusCodes}
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import cakesolutions.docker._
import cakesolutions.docker.testkit.DockerComposeTestKit.LogEvent
import cakesolutions.docker.testkit._
import cakesolutions.docker.testkit.clients.RestAPIClient
import cakesolutions.docker.testkit.logging.TestLogger
import cats.Now
import monix.execution.Scheduler
import monix.reactive.Observable
import org.atnos.eff.ErrorEffect._
import org.atnos.eff._
import org.atnos.eff.all._
import org.atnos.eff.syntax.all._
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.sys.process._

object WordpressMySQLDockerTest {
  val mysqlStarted =
    (entry: LogEvent) => entry.message.endsWith("mysqld: ready for connections.")

  val apacheStarted =
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
  import RestAPIClient._
  import WordpressMySQLDockerTest._

  implicit val testDuration = 2.minutes
  implicit val routeTestTimeout = RouteTestTimeout(testDuration)
  implicit val scheduler = Scheduler(system.dispatcher)
  override implicit val patienceConfig = super.patienceConfig.copy(timeout = testDuration)

  val webHost =
    if (System.getProperty("os.name") == "Mac OS X") {
      "192.168.99.100"
    } else {
      "127.0.0.1"
    }
  val webPort = 8080

  val yaml = DockerComposeString(
    s"""version: '2'
     |
     |services:
     |  wordpress:
     |    image: wordpress:4.6.1
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
     |    image: mariadb:10.1.19
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
    // The following is a HACK to avoid Docker from consuming unnecessary volume resources
    val docker = implicitly[Driver].docker
    val volumes = docker.execute("volume", "ls", "-qf", "dangling=true").!!.split("\n").toSeq
    docker.execute("volume" +: "rm" +: volumes: _*).!!(log.stderr)
    super.afterAll()
  }

  type _validate[Model] = Validate[String, ?] |= Model
  type WordpressMysqlModel = Fx.fx3[DockerAction, Validate[String, ?], ErrorOrOk]

  def check[Model: _validate: _errorOrOk](obs: Observable[Boolean])(implicit scheduler: Scheduler): Eff[Model, Unit] = {
    for {
      isTrue <- ErrorEffect.eval(Now(Await.result(obs.runAsyncGetFirst, Duration.Inf)))
      result <- validateCheck(isTrue.contains(true), "Observable should produce a true initial value")
    } yield result
  }

  "Wordpress and MySQL networked application" - {
    "wordpress site is up and responding" in {
      def checkSiteUp[Model: _errorOrOk]: Eff[Model, Assertion] = {
        ErrorEffect.eval(Now {
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
        })
      }
      val fsm1 = Monitor[Unit, LogEvent](()) {
        case _ => {
          case Observe(event: LogEvent) if mysqlStarted(event) =>
            Stop(Accept())
        }
      }
      val fsm2 = Monitor[Unit, LogEvent](()) {
        case _ => {
          case Observe(event: LogEvent) if apacheStarted(event) =>
            Stop(Accept())
        }
      }
      val fsm3 = Monitor[Unit, LogEvent](()) {
        case _ => {
          case Observe(event: LogEvent) if accessEvent(event) =>
            Stop(Accept())
        }
      }
      def expt[Model: _docker: _validate: _errorOrOk]: Eff[Model, Notify] = for {
        obs1 <- docker.logs(fsm1)(Observable(_))("mysql")
        _ <- check(isAccepting(obs1))
        obs2 <- docker.logs(fsm2)(Observable(_))("wordpress")
        _ <- check(isAccepting(obs2))
        assert <- checkSiteUp
        _ <- validateCheck(assert == Succeeded, "HTTP client failed to load Wordpress start page")
        obs3 <- docker.logs(fsm3)(Observable(_))("wordpress")
        _ <- check(isAccepting(obs3))
      } yield Accept()

      inside(expt[WordpressMysqlModel].runDocker(Map("wordpress" -> wordpress, "mysql" -> mysql)).runError.runNel) {
        case Pure(Right(Right(Accept())), _) =>
          assert(true)
      }
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
      def configureSite[Model: _errorOrOk]: Eff[Model, Assertion] = {
        ErrorEffect.eval(Now {
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
        })
      }
      val fsm1 = Monitor[Unit, LogEvent](()) {
        case _ => {
          case Observe(event: LogEvent) if apacheStarted(event) =>
            Stop(Accept())
        }
      }
      val fsm2 = Monitor[Int, LogEvent](1) {
        case 1 => {
          case Observe(event: LogEvent) if setupEvents(0)(event) =>
            Goto(2)
        }
        case 2 => {
          case Observe(event: LogEvent) if setupEvents(1)(event) =>
            Goto(3)
        }
        case 3 => {
          case Observe(event: LogEvent) if setupEvents(2)(event) =>
            Stop(Accept())
        }
      }
      def expt[Model: _docker: _validate: _errorOrOk]: Eff[Model, Notify] = for {
        obs1 <- docker.logs(fsm1)(Observable(_))("wordpress")
        _ <- check(isAccepting(obs1))
        assert <- configureSite
        _ <- validateCheck(assert == Succeeded, "HTTP client failed to configure Wordpress site")
        obs2 <- docker.logs(fsm2)(Observable(_))("wordpress")
        _ <- check(isAccepting(obs2))
      } yield Accept()

      inside(expt[WordpressMysqlModel].runDocker(Map("wordpress" -> wordpress, "mysql" -> mysql)).runError.runNel) {
        case Pure(Right(Right(Accept())), _) =>
          assert(true)
      }
    }
  }
}
