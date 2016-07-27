package cakesolutions.docker.testkit.examples

import cakesolutions.docker.testkit.logging.TestLogger
import cakesolutions.docker.testkit.{DockerCompose, DockerComposeTestKit, DockerImage}
import org.scalatest._

class LibPreloadFaultInjectionDockerTest extends FreeSpec with Matchers with Inside with BeforeAndAfter with DockerComposeTestKit with TestLogger {
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
      |    template:
      |      resources: /docker/libfiu
      |      image: wordpress
      |    environment:
      |      WORDPRESS_DB_HOST: db:3306
      |      WORDPRESS_DB_PASSWORD: password
      |      FIU_ENABLE: ""
      |      FIU_CTRL_FIFO: "/tmp/fiu-ctrl"
      |      LD_PRELOAD: "/usr/lib/fiu/fiu_run_preload.so /usr/lib/fiu/fiu_posix_preload.so"
      |    ports:
      |      - $webPort:80
      |    networks:
      |      - public
      |      - private
      |    depends_on:
      |      - db
      |  db:
      |    template:
      |      resources: /docker/libfiu
      |      image: mariadb
      |    environment:
      |      MYSQL_ROOT_PASSWORD: password
      |      FIU_ENABLE: ""
      |      FIU_CTRL_FIFO: "/tmp/fiu-ctrl"
      |      LD_PRELOAD: "/usr/lib/fiu/fiu_run_preload.so /usr/lib/fiu/fiu_posix_preload.so"
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

  before {
    compose = up("fault-injection", yaml)
    wordpress = compose.service("wordpress").docker.head
    mysql = compose.service("db").docker.head
  }

  after {
    compose.down()
  }

  "libfiu instrumented containers" - {
    "" ignore {
      // TODO:
    }
  }
}
