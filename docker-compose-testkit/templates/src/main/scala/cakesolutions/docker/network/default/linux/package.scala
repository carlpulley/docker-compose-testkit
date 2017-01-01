// Copyright 2016 Carl Pulley

package cakesolutions.docker.network.default

import cakesolutions.docker.testkit.DockerCompose
import cakesolutions.docker.testkit.network.ImpairmentSpec.Loss
import cakesolutions.docker.testkit.yaml.DockerComposeProtocol
import cats.Now
import org.atnos.eff.ErrorEffect._
import org.atnos.eff._
import org.atnos.eff.interpret._

package object linux {
  import DockerComposeProtocol.Linux._

  sealed trait NetworkAction[Result]
  final case class PartitionNetwork(name: String) extends NetworkAction[Unit]

  type _network[Model] = NetworkAction |= Model

  def partition[Model: _network](name: String): Eff[Model, Unit] =
    Eff.send[NetworkAction, Model, Unit](PartitionNetwork(name))

  implicit class NetworkingRun[R, A](effects: Eff[R, A]) {
    def runNetwork[U](
      implicit member: Member.Aux[NetworkAction, R, U],
      error: _errorOrOk[U],
      compose: DockerCompose
    ): Eff[U, A] = {
      translate(effects)(new Translate[NetworkAction, U] {
        def apply[X](net: NetworkAction[X]): Eff[U, X] = {
          net match {
            case PartitionNetwork(name) =>
              ErrorEffect.eval(Now {
                compose.network(name).impair(Loss("100%")).asInstanceOf[X]
              })
          }
        }
      })
    }
  }
}
