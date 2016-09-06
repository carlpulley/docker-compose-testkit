package cakesolutions.docker.testkit

import monix.execution.Scheduler
import monix.reactive.Observable
import monix.reactive.observables.ConnectableObservable

object TimedObservable {

  final case class cold[Data](observable: Observable[Data])(implicit val scheduler: Scheduler) extends TimedObservable[Data]

  final case class hot[Data](observable: ConnectableObservable[Data])(implicit val scheduler: Scheduler) extends TimedObservable[Data]

}

sealed trait TimedObservable[Data] {
  def observable: Observable[Data]

  def scheduler: Scheduler
}
