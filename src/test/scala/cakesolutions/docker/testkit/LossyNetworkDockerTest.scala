package cakesolutions.docker.testkit

import cakesolutions.docker.testkit.logging.TestLogger
import cakesolutions.docker.testkit.network.ImpairmentSpec.{Loss, Delay}
import org.scalatest.{FreeSpec, Matchers, Inside, BeforeAndAfterAll}

class LossyNetworkDockerTest extends FreeSpec with Matchers with Inside with BeforeAndAfterAll with DockerComposeTestKit with TestLogger {
  import DockerComposeTestKit._

  val yaml = DockerComposeString(
    """version: '2'
      |
      |services:
      |  c1:
      |    image: ubuntu:trusty
      |    command: /bin/sleep 300000
      |    networks:
      |      - common
      |    cap_add:
      |      - NET_ADMIN
      |  c2:
      |    image: ubuntu:trusty
      |    command: sh -c "ping c1"
      |    networks:
      |      - common
      |    cap_add:
      |      - NET_ADMIN
      |  c3:
      |    image: ubuntu:trusty
      |    command: sh -c "ping c1"
      |    networks:
      |      - common
      |    cap_add:
      |      - NET_ADMIN
      |
      |networks:
      |  common:
    """.stripMargin
  )

  var compose: DockerCompose = _
  var c1: DockerImage = _
  var c2: DockerImage = _
  var c3: DockerImage = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    compose = up("lossy-network", yaml)
    c1 = compose.service("c1").docker.head
    c2 = compose.service("c2").docker.head
    c3 = compose.service("c3").docker.head
  }

  override def afterAll(): Unit = {
    compose.down()
    super.afterAll()
  }

  "Networked containers" - {
    "normal network" ignore {
      // TODO: sliding window statistical assertions on icmp_seq and time
    }

    "slow network" ignore {
      compose.network("common").qdisc(Delay())

      // TODO: sliding window statistical assertions on icmp_seq
    }

    "flaky network" ignore {
      compose.network("common").qdisc(Loss())

      // TODO: sliding window statistical assertions on time
    }

    "fast network" ignore {
      compose.network("common").reset()

      // TODO: sliding window statistical assertions on icmp_seq and time
    }
  }

}
