package cakesolutions.docker.testkit.matchers

import akka.actor.FSM.Failure
import akka.actor.{ActorSystem, FSM, Props}
import cakesolutions.docker.testkit.logging.Logger
import monix.execution.Scheduler
import monix.reactive.Observable
import org.scalatest.matchers.{MatchResult, Matcher}

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, Promise}
import scala.util.Try

object ObservableMatcher {
  sealed trait Action
  case class Fail(reason: String) extends Action
  case class Goto[State, Data](state: State, using: Data = null, forMax: FiniteDuration = null) extends Action
  case object Accept extends Action
  case object Stay extends Action

  case class InitialState[State, Data](state: State, data: Data, timeout: FiniteDuration = null)

  final case class When[State, Data](state: State, stateTimeout: FiniteDuration = null)(val transition: PartialFunction[FSM.Event[Data], Action])

  def observe[E : Manifest, S, D](initial: InitialState[S, D], actions: When[S, D]*)(implicit timeout: FiniteDuration, system: ActorSystem, outerLog: Logger) = Matcher { (obs: Observable[E]) =>
    val result = Promise[Boolean]

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
            case (event, Stay) =>
              stay()
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
          result.success(true)
        case StopEvent(FSM.Shutdown, _, _) =>
          result.success(false)
        case StopEvent(FSM.Failure(reason), state, data) =>
          outerLog.error(s"FSM matching failed in state $state using data $data - reason: $reason")
          result.success(false)
      }

      initialize()
    }

    val checker = system.actorOf(Props(new MatchingFSM))
    obs.foreach { event =>
      checker ! event
    }(Scheduler(system.dispatcher))

    result.future.onComplete {
      case _: Try[_] =>
        system.stop(checker)
    }(system.dispatcher)

    MatchResult(Await.result(result.future, timeout), "+ve ???" , "-ve ???")
  }
}
