// Copyright 2016 Carl Pulley

package cakesolutions.docker.network.default

import cakesolutions.docker.testkit.DockerCompose
import cakesolutions.docker.testkit.network.ImpairmentSpec.Impairment
import cakesolutions.docker.testkit.yaml.DockerComposeProtocol
import cats.Now
import org.atnos.eff.ErrorEffect._
import org.atnos.eff._
import org.atnos.eff.interpret._

package object linux {
  import DockerComposeProtocol.Linux._

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
}
