package cakesolutions.docker.testkit.matchers

import akka.actor.FSM.Failure
import akka.actor.{ActorSystem, FSM, Props}
import cakesolutions.docker.testkit.logging.Logger
import monix.execution.Scheduler
import monix.reactive.Observable
import org.scalatest.concurrent.AsyncAssertions.Waiter
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.matchers.{MatchResult, Matcher}

import scala.concurrent.duration.FiniteDuration

object ObservableMatcher {
  sealed trait Action
  case object Accept extends Action
  final case class Fail(reason: String) extends Action
  final case class Goto[State, Data](state: State, using: Data = null, forMax: FiniteDuration = null) extends Action
  final case class Stay[Data](using: Data = null) extends Action

  final case class InitialState[State, Data](state: State, data: Data, timeout: FiniteDuration = null)
  final case class When[State, Data](state: State, stateTimeout: FiniteDuration = null)(val transition: PartialFunction[FSM.Event[Data], Action])

  def observe[E : Manifest, S, D](initial: InitialState[S, D], actions: When[S, D]*)(implicit testDuration: FiniteDuration, system: ActorSystem, outerLog: Logger) = Matcher { (obs: Observable[E]) =>
    val control = new Waiter

    var result: Option[Boolean] = None

    class MatchingFSM extends FSM[S, D] {
      startWith(initial.state, initial.data, Option(initial.timeout))

      actions.foreach {
        case act @ When(state: S, stateTimeout) =>
          when(state, stateTimeout)(({
            case event: FSM.Event[D] if act.transition.isDefinedAt(event) =>
              (event, act.transition(event))
          }: PartialFunction[FSM.Event[D], (FSM.Event[D], Action)]) andThen {
            case (event, Goto(state: S, null, null)) =>
              outerLog.info(s"Matched: $event")
              goto(state)
            case (event, Goto(state: S, null, max)) =>
              outerLog.info(s"Matched: $event")
              goto(state).forMax(max)
            case (event, Goto(state: S, data: D, null)) =>
              outerLog.info(s"Matched: $event")
              goto(state).using(data)
            case (event, Goto(state: S, data: D, max)) =>
              outerLog.info(s"Matched: $event")
              goto(state).using(data).forMax(max)
            case (event, Stay(null)) =>
              stay()
            case (event, Stay(data: D)) =>
              stay().using(data)
            case (event, Accept) =>
              outerLog.info(s"Matched: $event")
              stop()
            case (event, Fail(reason)) =>
              outerLog.error(s"Failed with $event - reason: $reason")
              stop(Failure(reason))
          }
        )
      }

      whenUnhandled {
        case Event(StateTimeout, _) =>
          stop(Failure("State timeout"))
        case _ =>
          stay()
      }

      onTermination {
        case StopEvent(FSM.Normal, _, _) =>
          control.dismiss()
          result = Some(true)
        case StopEvent(FSM.Shutdown, _, _) =>
          control.dismiss()
          result = Some(false)
        case StopEvent(FSM.Failure(reason), state, data) =>
          outerLog.error(s"FSM matching failed in state $state using data $data - reason: $reason")
          control.dismiss()
          result = Some(false)
      }

      initialize()
    }

    val checker = system.actorOf(Props(new MatchingFSM))
    obs.foreach { event =>
      checker ! event
    }(Scheduler(system.dispatcher))

    control.await(Timeout(testDuration))

    system.stop(checker)

    MatchResult(result.contains(true), "+ve ???" , "-ve ???")
  }
}
