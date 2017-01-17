// Copyright 2016 Carl Pulley

package cakesolutions.docker

import akka.actor.ActorSystem
import cakesolutions.docker.testkit.DockerImage
import cats.Now
import org.atnos.eff.ErrorEffect._
import org.atnos.eff._
import org.atnos.eff.interpret._

package object jvm {
  sealed trait JvmGCEvent
  case object JvmGCStart extends JvmGCEvent
  case object JvmGCEnd extends JvmGCEvent

  sealed trait JvmGCAction[Result]
  final case class JvmGC(name: String, others: Seq[String], data: JvmGCEvent) extends JvmGCAction[Unit]

  type _jvm[Model] = JvmGCAction |= Model

  def jvmGC[Model: _jvm](data: JvmGCEvent)(name: String, others: String*): Eff[Model, Unit] =
    Eff.send[JvmGCAction, Model, Unit](JvmGC(name, others, data))

  implicit class JvmRun[R, A](effects: Eff[R, A]) {
    def runJvm[U](
      db: Map[String, DockerImage]
    )(implicit member: Member.Aux[JvmGCAction, R, U],
      error: _errorOrOk[U],
      system: ActorSystem
    ): Eff[U, A] = {
      translate(effects)(new Translate[JvmGCAction, U] {
        def apply[X](jvm: JvmGCAction[X]): Eff[U, X] = {
          jvm match {
            case JvmGC(name, others, JvmGCStart) =>
              ErrorEffect.eval(Now {
                (name +: others).foreach(nm => db(nm).pause()).asInstanceOf[X]
              })

            case JvmGC(name, others, JvmGCEnd) =>
              ErrorEffect.eval(Now {
                (name +: others).foreach(nm => db(nm).unpause()).asInstanceOf[X]
              })
          }
        }
      })
    }
  }
}
