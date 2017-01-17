// Copyright 2016 Carl Pulley

package cakesolutions

import _root_.akka.actor.ActorSystem
import cakesolutions.docker.testkit.DockerComposeTestKit.LogEvent
import cakesolutions.docker.testkit._
import cakesolutions.docker.testkit.logging.Logger
import cats.Now
import monix.execution.Scheduler
import monix.reactive.Observable
import org.atnos.eff.ErrorEffect._
import org.atnos.eff._
import org.atnos.eff.interpret._

package object docker {
  /**
   * Acceptance means we never fail and at least one `Accept` is observed.
   *
   * @param event observable notifications to test
   * @return
   */
  def isAccepting(event: Observable[Notify]): Observable[Boolean] =
    event
      .foldLeftF[Option[Boolean]](None) {
      case (Some(false), _) =>
        Some(false)
      case (_, _: Accept) =>
        Some(true)
      case (_, _: Fail) =>
        Some(false)
    }.map {
      case None =>
        false
      case Some(value) =>
        value
    }

  implicit class FanIn[X](left: Observable[X]) {
    def add[Y](right: Observable[Y]): Observable[Either[X, Y]] = {
      left.map(Left(_)) ++ right.map(Right(_))
    }
  }

  sealed trait DockerAction[Result]
  final case class LogMonitoring[State, Action](name: String, monitor: Monitor[State, Action], translate: LogEvent => Observable[Action]) extends DockerAction[Observable[Notify]]
  final case class TaggedLogMonitoring[State, Action](name: String, others: Seq[String], monitor: Monitor[State, (String, Action)], translate: LogEvent => Observable[Action]) extends DockerAction[Observable[Notify]]
  final case class HotLogMonitoring[State, Action](name: String, monitor: Monitor[State, Action], translate: LogEvent => Observable[Action]) extends DockerAction[Observable[Notify]]
  final case class TaggedHotLogMonitoring[State, Action](name: String, others: Seq[String], monitor: Monitor[State, (String, Action)], translate: LogEvent => Observable[Action]) extends DockerAction[Observable[Notify]]
  final case class ContainerEventMonitoring[State, Action](name: String, monitor: Monitor[State, Action], filters: Seq[String], translate: String => Observable[Action]) extends DockerAction[Observable[Notify]]
  final case class TaggedContainerEventMonitoring[State, Action](name: String, others: Seq[String], monitor: Monitor[State, (String, Action)], filters: Seq[String], translate: String => Observable[Action]) extends DockerAction[Observable[Notify]]
  final case class ContainerHotEventMonitoring[State, Action](name: String, monitor: Monitor[State, Action], filters: Seq[String], translate: String => Observable[Action]) extends DockerAction[Observable[Notify]]
  final case class TaggedContainerHotEventMonitoring[State, Action](name: String, others: Seq[String], monitor: Monitor[State, (String, Action)], filters: Seq[String], translate: String => Observable[Action]) extends DockerAction[Observable[Notify]]

  type _docker[Model] = DockerAction |= Model

  object docker {
    object logs {
      def hot[State, Action, Model: _docker](monitor: Monitor[State, Action])(translate: LogEvent => Observable[Action])(name: String): Eff[Model, Observable[Notify]] =
        Eff.send[DockerAction, Model, Observable[Notify]](HotLogMonitoring(name, monitor, translate))

      def taggedHot[State, Action, Model: _docker](monitor: Monitor[State, (String, Action)])(translate: LogEvent => Observable[Action])(name: String, others: String*): Eff[Model, Observable[Notify]] =
        Eff.send[DockerAction, Model, Observable[Notify]](TaggedHotLogMonitoring(name, others, monitor, translate))

      def apply[State, Action, Model: _docker](monitor: Monitor[State, Action])(translate: LogEvent => Observable[Action])(name: String): Eff[Model, Observable[Notify]] =
        Eff.send[DockerAction, Model, Observable[Notify]](LogMonitoring(name, monitor, translate))

      def tagged[State, Action, Model: _docker](monitor: Monitor[State, (String, Action)])(translate: LogEvent => Observable[Action])(name: String, others: String*): Eff[Model, Observable[Notify]] =
        Eff.send[DockerAction, Model, Observable[Notify]](TaggedLogMonitoring(name, others, monitor, translate))
    }

    object events {
      def hot[State, Action, Model: _docker](monitor: Monitor[State, Action])(translate: String => Observable[Action])(filters: String*)(name: String): Eff[Model, Observable[Notify]] =
        Eff.send[DockerAction, Model, Observable[Notify]](ContainerHotEventMonitoring(name, monitor, filters, translate))

      def taggedHot[State, Action, Model: _docker](monitor: Monitor[State, (String, Action)])(translate: String => Observable[Action])(filters: String*)(name: String, others: String*): Eff[Model, Observable[Notify]] =
        Eff.send[DockerAction, Model, Observable[Notify]](TaggedContainerHotEventMonitoring(name, others, monitor, filters, translate))

      def apply[State, Action, Model: _docker](monitor: Monitor[State, Action])(translate: String => Observable[Action])(filters: String*)(name: String): Eff[Model, Observable[Notify]] =
        Eff.send[DockerAction, Model, Observable[Notify]](ContainerEventMonitoring(name, monitor, filters, translate))

      def tagged[State, Action, Model: _docker](monitor: Monitor[State, (String, Action)])(translate: String => Observable[Action])(filters: String*)(name: String, others: String*): Eff[Model, Observable[Notify]] =
        Eff.send[DockerAction, Model, Observable[Notify]](TaggedContainerEventMonitoring(name, others, monitor, filters, translate))
    }
  }

  implicit class DockerRun[R, A](effects: Eff[R, A]) {
    def runDocker[U](
      db: Map[String, DockerImage]
    )(
      implicit member: Member.Aux[DockerAction, R, U],
      error: _errorOrOk[U],
      system: ActorSystem,
      scheduler: Scheduler,
      log: Logger
    ): Eff[U, A] = {
      translate(effects)(new Translate[DockerAction, U] {
        def apply[X](docker: DockerAction[X]): Eff[U, X] = {
          docker match {
            case LogMonitoring(name, monitor, translate) =>
              ErrorEffect.eval(Now {
                monitor
                  .run(db(name).logging.cold().flatMap(translate))
                  .asInstanceOf[X]
              })
            case TaggedLogMonitoring(name, others, monitor, translate) =>
              ErrorEffect.eval(Now {
                val obs =
                  (name +: others)
                    .map { nm =>
                      db(nm).logging.cold().flatMap(translate).map(st => nm -> st)
                    }
                    .foldLeft(Observable.empty[(String, Any)])(_ ++ _)

                monitor
                  .run(obs)
                  .asInstanceOf[X]
              })
            case HotLogMonitoring(name, monitor, translate) =>
              ErrorEffect.eval(Now {
                monitor
                  .run(db(name).logging.hot().flatMap(translate))
                  .asInstanceOf[X]
              })
            case TaggedHotLogMonitoring(name, others, monitor, translate) =>
              ErrorEffect.eval(Now {
                val obs =
                  (name +: others)
                    .map { nm =>
                      db(nm).logging.hot().flatMap(translate).map(st => nm -> st)
                    }
                    .foldLeft(Observable.empty[(String, Any)])(_ ++ _)

                monitor
                  .run(obs)
                  .asInstanceOf[X]
              })
            case ContainerEventMonitoring(name, monitor, filters, translate) =>
              ErrorEffect.eval(Now {
                monitor
                  .run(db(name).events.cold(filters: _*).flatMap(translate))
                  .asInstanceOf[X]
              })
            case TaggedContainerEventMonitoring(name, others, monitor, filters, translate) =>
              ErrorEffect.eval(Now {
                val obs =
                  (name +: others)
                    .map { nm =>
                      db(nm).events.cold(filters: _*).flatMap(translate).map(st => nm -> st)
                    }
                    .foldLeft(Observable.empty[(String, Any)])(_ ++ _)

                monitor
                  .run(obs)
                  .asInstanceOf[X]
              })
            case ContainerHotEventMonitoring(name, monitor, filters, translate) =>
              ErrorEffect.eval(Now {
                monitor
                  .run(db(name).events.hot(filters: _*).flatMap(translate))
                  .asInstanceOf[X]
              })
            case TaggedContainerHotEventMonitoring(name, others, monitor, filters, translate) =>
              ErrorEffect.eval(Now {
                val obs =
                  (name +: others)
                    .map { nm =>
                      db(nm).events.hot(filters: _*).flatMap(translate).map(st => nm -> st)
                    }
                    .foldLeft(Observable.empty[(String, Any)])(_ ++ _)

                monitor
                  .run(obs)
                  .asInstanceOf[X]
              })
          }
        }
      })
    }
  }

}
