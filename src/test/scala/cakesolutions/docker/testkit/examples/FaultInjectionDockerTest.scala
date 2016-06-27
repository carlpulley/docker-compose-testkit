package cakesolutions.docker.testkit.examples

import cakesolutions.docker.testkit.logging.TestLogger
import cakesolutions.docker.testkit.{DockerCompose, DockerComposeTestKit, DockerImage}
import org.scalatest.{BeforeAndAfterAll, FreeSpec, Inside, Matchers}

class FaultInjectionDockerTest extends FreeSpec with Matchers with Inside with BeforeAndAfterAll with DockerComposeTestKit with TestLogger {
  import DockerComposeTestKit._

  val webHost = "127.0.0.1"
  val webPort = 8080

  // FIXME: can then enable/disable container faults with:
  //   fiu-ctrl -c "enable name=posix/io/*" `pidof ???`
  //   fiu-ctrl -c "enable_random name=posix/io/*" `pidof ???`
  //   fiu-ctrl -c "disable name=posix/io/*" `pidof ???`
  val yaml = DockerComposeString(
    s"""version: '2'
        |
        |services:
        |  wordpress:
        |    build:
        |      context:
        |      dockerfile: fiu-wrapper
        |      args:
        |        - BASE=wordpress
        |    image: fiu-wordpress
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
        |    build:
        |      context:
        |      dockerfile: fiu-wrapper
        |      args:
        |        - BASE=mariadb
        |    image: fiu-db
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
    compose = up("fault-injection", yaml)
    wordpress = compose.service("wordpress").docker.head
    mysql = compose.service("db").docker.head
  }

  override def afterAll(): Unit = {
    compose.down()
    super.afterAll()
  }

  "libfiu instrumented containers" - {
    "" ignore {
      // TODO:
    }
  }
}
