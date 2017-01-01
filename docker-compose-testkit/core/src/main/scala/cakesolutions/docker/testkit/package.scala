// Copyright 2016 Carl Pulley

package cakesolutions.docker

import monix.reactive.Observable
import org.atnos.eff.Eff

package object testkit {
  sealed trait ObservedEvent[+Event]
  case object StateTimeout extends Throwable with ObservedEvent[Nothing]
  final case class Observe[Event](event: Event) extends ObservedEvent[Event]

  type Behaviour[State, Event] = PartialFunction[State, PartialFunction[ObservedEvent[Event], Action[State]]]

  implicit class LogicalOperators[Model](left: Eff[Model, Observable[Notify]]) {
    def &&(right: Eff[Model, Observable[Notify]]): Eff[Model, Observable[Notify]] = {
      for {
        l <- left
        r <- right
      } yield l.zip(r).map {
        case (Accept(leftFailures@_*), Accept(rightFailures@_*)) =>
          Accept(leftFailures ++ rightFailures: _*)
        case (Fail(leftFailures@_*), Accept(rightFailures@_*)) =>
          Fail(leftFailures ++ rightFailures: _*)
        case (Accept(leftFailures@_*), Fail(rightFailures@_*)) =>
          Fail(leftFailures ++ rightFailures: _*)
        case (Fail(leftFailures@_*), Fail(rightFailures@_*)) =>
          Fail(leftFailures ++ rightFailures: _*)
      }
    }

    def ||(right: Eff[Model, Observable[Notify]]): Eff[Model, Observable[Notify]] = {
      for {
        l <- left
        r <- right
      } yield l.zip(r).map {
        case (Accept(leftFailures@_*), Accept(rightFailures@_*)) =>
          Accept(leftFailures ++ rightFailures: _*)
        case (Fail(leftFailures@_*), Accept(rightFailures@_*)) =>
          Accept(leftFailures ++ rightFailures: _*)
        case (Accept(leftFailures@_*), Fail(rightFailures@_*)) =>
          Accept(leftFailures ++ rightFailures: _*)
        case (Fail(leftFailures@_*), Fail(rightFailures@_*)) =>
          Fail(leftFailures ++ rightFailures: _*)
      }
    }

    def not(): Eff[Model, Observable[Notify]] = {
      left.map(
        _.map {
          case Accept(failures@_*) =>
            Fail(failures: _*)
          case Fail(reasons@_*) =>
            Accept(reasons: _*)
        }
      )
    }
  }
}
