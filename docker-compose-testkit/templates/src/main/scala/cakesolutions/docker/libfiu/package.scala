// Copyright 2016 Carl Pulley

package cakesolutions.docker

import cakesolutions.docker.libfiu.LibFiuClient
import cakesolutions.docker.testkit.DockerImage
import cats.Now
import monix.execution.Scheduler
import org.atnos.eff.ErrorEffect._
import org.atnos.eff._
import org.atnos.eff.interpret._

package object libfiu {
  import LibFiuClient._

  sealed trait LibfiuAction[Result]
  final case class EnableLibfiu(name: String, others: Seq[String], path: String) extends LibfiuAction[Unit]
  final case class DisableLibfiu(name: String, others: Seq[String], path: String) extends LibfiuAction[Unit]
  final case class RandomLibfiuAction(name: String, others: Seq[String], path: String, probability: Double) extends LibfiuAction[Unit]

  type _libfiu[Model] = LibfiuAction |= Model

  def enable[Model: _libfiu](path: String)(name: String, others: String*): Eff[Model, Unit] =
    Eff.send[LibfiuAction, Model, Unit](EnableLibfiu(name, others, path))

  def disable[Model: _libfiu](path: String)(name: String, others: String*): Eff[Model, Unit] =
    Eff.send[LibfiuAction, Model, Unit](DisableLibfiu(name, others, path))

  def random[Model: _libfiu](path: String, probability: Double)(name: String, others: String*): Eff[Model, Unit] =
    Eff.send[LibfiuAction, Model, Unit](RandomLibfiuAction(name, others, path, probability))

  implicit class LibFiuRun[R, A](effects: Eff[R, A]) {
    def runLibfiu[U](
      db: Map[String, DockerImage]
    )(implicit member: Member.Aux[LibfiuAction, R, U],
      error: _errorOrOk[U],
      scheduler: Scheduler
    ): Eff[U, A] = {
      translate(effects)(new Translate[LibfiuAction, U] {
        def apply[X](net: LibfiuAction[X]): Eff[U, X] = {
          net match {
            case EnableLibfiu(name, others, path) =>
              ErrorEffect.eval(Now {
                (name +: others).foreach(nm => db(nm).enable(path)).asInstanceOf[X]
              })
            case DisableLibfiu(name, others, path) =>
              ErrorEffect.eval(Now {
                (name +: others).foreach(nm => db(nm).disable(path)).asInstanceOf[X]
              })
            case RandomLibfiuAction(name, others, path, probability) =>
              ErrorEffect.eval(Now {
                (name +: others).foreach(nm => db(nm).random(path, probability)).asInstanceOf[X]
              })
          }
        }
      })
    }
  }
}
