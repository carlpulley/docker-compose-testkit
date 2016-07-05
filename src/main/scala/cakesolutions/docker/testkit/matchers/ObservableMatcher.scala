package cakesolutions.docker.testkit.matchers

import akka.actor.FSM.Failure
import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import cakesolutions.docker.testkit.logging.Logger
import monix.execution.{Ack, Cancelable, Scheduler}
import monix.reactive.{Observable, Observer}
import org.reactivestreams.Subscription
import org.scalatest.concurrent.AsyncAssertions.Waiter
import org.scalatest.concurrent.PatienceConfiguration.{Timeout => PTimeout}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.control.NonFatal

object ObservableMatcher {
  sealed trait Action
  case object Accept extends Action
  final case class Fail(reason: String) extends Action
  final case class Goto[Event, State, Data : ClassTag](state: State, using: Data = null, forMax: FiniteDuration = null, subscribeTo: Set[Observable[Event]] = null) extends Action
  final case class Stay[Data : ClassTag](using: Data = null) extends Action

  final case class InitialState[Event, State, Data : ClassTag](state: State, data: Data, subscribeTo: Set[Observable[Event]], timeout: FiniteDuration = null)
  final case class When[Event, State, Data : ClassTag](state: State, stateTimeout: FiniteDuration = null)(val transition: PartialFunction[FSM.Event[Data], Action])

  sealed trait Observation
  final case class SubscribedObservation(sub: Subscription) extends Observation
  final case class UnsubscribedObservation[E](obs: Observable[E]) extends Observation

  case object MatchTimedOut
  final case class MatcherFailed[S, D](reason: String, state: S, data: D) extends Exception
  final case class MatchFailed[D](reason: String, event: FSM.Event[D], timeout: FiniteDuration)
  final case class UnexpectedException[D](reason: Throwable, event: FSM.Event[D], timeout: FiniteDuration) extends Exception

  // TODO: relate code to an IO automate where the output stream is of an scalatest.Outcome type?

  def shouldObserve[E, S, D : ClassTag](
    initial: InitialState[E, S, D],
    actions: When[E, S, D]*
  )(implicit testDuration: FiniteDuration,
    system: ActorSystem,
    log: Logger
  ): Unit = {
    implicit val timeout = Timeout(testDuration)

    val control = new Waiter
    val checker = system.actorOf(Props(new MatchingFSM(control, testDuration, initial, actions: _*)))

    try {
      control.await(PTimeout(testDuration + 1.second))
    } finally {
      system.stop(checker)
    }
  }

  private class MatchingFSM[E, S, D : ClassTag](
    control: Waiter,
    testDuration: FiniteDuration,
    initial: InitialState[E, S, D],
    actions: When[E, S, D]*
  )(implicit outerLog: Logger,
    timeout: Timeout,
    override val logDepth: Int = 100
  ) extends LoggingFSM[S, D] {
    implicit val scheduler: Scheduler = Scheduler(context.system.dispatcher)

    // TODO: look at using back pressure to control upstream logging sources?
    val fsmObs: Observer[E] = new Observer[E] {
      override def onNext(elem: E): Future[Ack] = {
        (self ? elem).mapTo[Ack]
      }
      override def onError(exn: Throwable): Unit = {
        stop(Failure(exn))
      }
      override def onComplete(): Unit = {
        stop()
      }
    }

    var currentSubscriptions: Set[Cancelable] = initial.subscribeTo.map(_.subscribe(fsmObs))

    startWith(initial.state, initial.data, Option(initial.timeout))

    scheduler.scheduleOnce(testDuration) {
      control {
        assert(assertion = false, "FSM matcher timed out")
      }
      control.dismiss()
    }

    actions.foreach {
      case act: When[E, S, D] =>
        when(act.state, act.stateTimeout) {
          case event: FSM.Event[D] if act.transition.isDefinedAt(event) =>
            try {
              act.transition(event) match {
                case next: Goto[E, S, D] =>
                  if (act.stateTimeout == null) {
                    outerLog.info(s"Matched in ${act.state}: $event")
                  } else {
                    outerLog.info(s"Matched in ${act.state} [${act.stateTimeout}]: $event")
                  }
                  if (next.subscribeTo != null) {
                    currentSubscriptions.foreach(_.cancel())
                    currentSubscriptions = next.subscribeTo.map(_.subscribe(fsmObs))
                  }
                  sender() ! Ack.Continue
                  (next.using, next.forMax) match {
                    case (null, null) =>
                      goto(next.state)
                    case (null, max) =>
                      goto(next.state).forMax(max)
                    case (data, null) =>
                      goto(next.state).using(data)
                    case (data, max) =>
                      goto(next.state).using(data).forMax(max)
                  }
                case Stay(null) =>
                  sender() ! Ack.Continue
                  stay()
                case Stay(data: D) =>
                  sender() ! Ack.Continue
                  stay().using(data)
                case Accept =>
                  if (act.stateTimeout == null) {
                    outerLog.info(s"Matched in ${act.state}: $event")
                  } else {
                    outerLog.info(s"Matched in ${act.state} [${act.stateTimeout}]: $event")
                  }
                  stop()
                case Fail(reason) =>
                  stop(Failure(MatchFailed(reason, event, act.stateTimeout)))
              }
            } catch {
              case NonFatal(exn) =>
                stop(Failure(UnexpectedException(exn, event, act.stateTimeout)))
            }
        }
    }

    whenUnhandled {
      case Event(StateTimeout, _) =>
        stop(Failure("State timeout"))
      case _: FSM.Event[D] =>
        sender() ! Ack.Continue
        stay()
    }

    onTermination {
      case StopEvent(FSM.Normal, _, _) =>
        sender() ! Ack.Stop
        currentSubscriptions.foreach(_.cancel())
        currentSubscriptions = Set.empty
        control.dismiss()
      case StopEvent(FSM.Shutdown, state, data) =>
        val errMsg = s"FSM matcher shutdown in state $state using data $data"
        if (getLog.length == logDepth) {
          outerLog.error(s"$errMsg - last $logDepth events (oldest first): ${pprint.tokenize(getLog).mkString("")}")
        } else {
          outerLog.error(s"$errMsg - events (oldest first): ${pprint.tokenize(getLog).mkString("")}")
        }
        sender() ! Ack.Stop
        currentSubscriptions.foreach(_.cancel())
        currentSubscriptions = Set.empty
        control {
          assert(assertion = false, errMsg)
        }
        control.dismiss()
      case StopEvent(FSM.Failure(reason), state, data) =>
        val errMsg = s"FSM matching failed in state $state using data $data - reason: $reason"
        if (getLog.length == logDepth) {
          outerLog.error(s"$errMsg - last $logDepth events (oldest first): ${pprint.tokenize(getLog).mkString("")}")
        } else {
          outerLog.error(s"$errMsg - events (oldest first): ${pprint.tokenize(getLog).mkString("")}")
        }
        sender() ! Ack.Stop
        currentSubscriptions.foreach(_.cancel())
        currentSubscriptions = Set.empty
        control {
          assert(assertion = false, errMsg)
        }
        control.dismiss()
    }

    initialize()
  }
}
