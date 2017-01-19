// Copyright 2016 Carl Pulley

package cakesolutions.docker.network.default

import cakesolutions.docker.network.NetworkControl
import cakesolutions.docker.network.NetworkControl._
import cakesolutions.docker.testkit.DockerCompose
import cakesolutions.docker.testkit.DockerComposeTestKit.Driver
import cakesolutions.docker.testkit.yaml.DockerComposeProtocol.NetworkInteraction
import cats.Now
import org.atnos.eff.ErrorEffect._
import org.atnos.eff._
import org.atnos.eff.interpret._

import scala.sys.process._

package object linux {
  sealed trait NetworkAction[Result]
  final case class ImpairNetwork(name: String, others: Seq[String], spec: Impairment*) extends NetworkAction[Unit]

  type _network[Model] = NetworkAction |= Model

  def impair[Model: _network](spec: Impairment*)(name: String, others: String*): Eff[Model, Unit] =
    Eff.send[NetworkAction, Model, Unit](ImpairNetwork(name, others, spec: _*))

  implicit class NetworkingRun[R, A](effects: Eff[R, A]) {
    def runNetwork[U](
      implicit member: Member.Aux[NetworkAction, R, U],
      error: _errorOrOk[U],
      compose: DockerCompose
    ): Eff[U, A] = {
      translate(effects)(new Translate[NetworkAction, U] {
        def apply[X](net: NetworkAction[X]): Eff[U, X] = {
          net match {
            case ImpairNetwork(name, others, spec @ _*) =>
              ErrorEffect.eval(Now {
                (name +: others).foreach(nm => compose.network(nm).impair(spec: _*)).asInstanceOf[X]
              })
          }
        }
      })
    }
  }

  implicit class IPTableControl(network: NetworkInteraction) extends NetworkControl {
    private def eval(impairment: Impairment): Seq[String] = impairment match {
      // limit packets
      case Limit(spec) =>
        "limit" +: spec.split(" ")
      // delay TIME [ JITTER [ CORRELATION ]]] [ distribution { uniform | normal | pareto |  paretonormal } ]
      case Delay(spec) =>
        "delay" +: spec.split(" ")
      // loss { random PERCENT [ CORRELATION ]  | state p13 [ p31 [ p32 [ p23 [ p14]]]] | gemodel p [ r [ 1-h [ 1-k ]]] }  [ ecn ]
      case Loss(spec, _ @ _*) =>
        "loss" +: spec.split(" ")
      // corrupt PERCENT [ CORRELATION ]]
      case Corrupt(spec) =>
        "corrupt" +: spec.split(" ")
      // duplicate PERCENT [ CORRELATION ]]
      case Duplicate(spec) =>
        "duplicate" +: spec.split(" ")
      // reorder PERCENT [ CORRELATION ] [ gap DISTANCE ]
      case Reorder(spec) =>
        "reorder" +: spec.split(" ")
      // rate RATE [ PACKETOVERHEAD [ CELLSIZE [ CELLOVERHEAD ]]]]
      case Rate(spec) =>
        "rate" +: spec.split(" ")
    }

    // TODO: make this action be persistent (so newly launched containers inherit these networking properties)!!
    // TODO: only allow if NET_ADMIN capability is enabled
    def impair(impairments: Impairment*)(implicit driver: Driver): Unit = {
      network.inspect.Containers.keys.foreach { container =>
        val spec = impairments.flatMap(eval)

        driver
          .docker
          .execute(Seq("exec", "--user", "root", "-t", container, "tc", "qdisc", "replace", "dev", s"eth${network.nic(container)}", "root", "netem") ++ spec: _*).!!
      }
    }

    def reset()(implicit driver: Driver): Unit = {
      network.inspect.Containers.keys.foreach { container =>
        driver
          .docker
          .execute("exec", "--user", "root", "-t", container, "tc", "qdisc", "del", "dev", s"eth${network.nic(container)}", "root", "netem").!!
      }
    }
  }
}
