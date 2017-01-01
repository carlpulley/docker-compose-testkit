// Copyright 2016 Carl Pulley

package cakesolutions.docker.testkit

import scala.concurrent.duration.FiniteDuration

sealed trait Action[State] {
  def emit: Option[Notify]
}
final case class Goto[State](state: State, forMax: Option[FiniteDuration] = None, emit: Option[Notify] = None) extends Action[State]
object Goto {
  def apply[State](state: State, forMax: FiniteDuration): Goto[State] = {
    Goto(state, Some(forMax), None)
  }

  def apply[State](state: State, emit: Notify): Goto[State] = {
    Goto(state, None, Some(emit))
  }

  def apply[State](state: State, forMax: FiniteDuration, emit: Notify): Goto[State] = {
    Goto(state, Some(forMax), Some(emit))
  }
}
final case class Stay[State](emit: Option[Notify] = None) extends Action[State]
object Stay {
  def apply[State](emit: Notify): Stay[State] = {
    Stay(Some(emit))
  }
}
final case class Stop[State](toEmit: Notify) extends Action[State] {
  override val emit = Some(toEmit)
}
