// Copyright 2016 Carl Pulley

package cakesolutions

import cakesolutions.docker.testkit.{Accept, Fail, Notify}
import monix.reactive.Observable

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

  // TODO: add in container logging and event effects
}
