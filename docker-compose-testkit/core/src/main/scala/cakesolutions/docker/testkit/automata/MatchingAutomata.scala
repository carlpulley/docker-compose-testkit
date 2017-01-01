// Copyright 2016 Carl Pulley

package cakesolutions.docker.testkit.automata

import akka.actor.SupervisorStrategy.Decider
import akka.actor._
import akka.contrib.pattern.ReceivePipeline
import akka.contrib.pattern.ReceivePipeline.{HandledCompletely, Inner}
import akka.event.Logging
import akka.pattern.ask
import akka.util.Timeout
import cakesolutions.docker.testkit.TimedObservable
import cakesolutions.docker.testkit.logging.Logger
import monix.execution.{Ack, Cancelable, Scheduler}
import monix.reactive.observers.Subscriber
import monix.reactive.{Observable, Observer, OverflowStrategy}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.higherKinds
import scala.reflect.ClassTag
import scala.util.Try
import scala.util.control.NonFatal

object MatchingAutomata {
  sealed trait Notify {
    def invert: Notify
  }
  final case class Accept(failures: String*) extends Notify {
    override def invert: Notify = {
      Fail(failures: _*)
    }
  }
  final case class Fail(reasons: String*) extends Exception with Notify {
    override def invert: Notify = {
      Accept(reasons: _*)
    }
  }

  sealed trait Action {
    def emit: Option[Notify]
  }
  final case class Goto[State : ClassTag](state: State, forMax: Option[FiniteDuration] = None, emit: Option[Notify] = None) extends Action
  object Goto {
    def apply[State : ClassTag](state: State, forMax: FiniteDuration): Goto[State] = {
      Goto(state, Some(forMax), None)
    }

    def apply[State : ClassTag](state: State, emit: Notify): Goto[State] = {
      Goto(state, None, Some(emit))
    }

    def apply[State : ClassTag](state: State, forMax: FiniteDuration, emit: Notify): Goto[State] = {
      Goto(state, Some(forMax), Some(emit))
    }
  }
  final case class Stay(emit: Option[Notify] = None) extends Action
  object Stay {
    def apply(emit: Notify): Stay = {
      Stay(Some(emit))
    }
  }
  final case class Stop(toEmit: Notify) extends Action {
    val emit = Some(toEmit)
  }

  case object StateTimeout extends Exception

  type StateFunction = PartialFunction[Any, Action]

  def apply[State : ClassTag, Input : ClassTag](initial: State)(transition: PartialFunction[State, StateFunction]): MatchingAutomata[State, Input] = {
    new MatchingAutomata[State, Input](initial, None, transition)
  }

  def apply[State : ClassTag, Input : ClassTag](initial: State, timeout: FiniteDuration)(transition: PartialFunction[State, StateFunction]): MatchingAutomata[State, Input] = {
    new MatchingAutomata[State, Input](initial, Some(timeout), transition)
  }

  final def not[In](obs: In => Observable[Notify]): In => Observable[Notify] = { in =>
    not(obs(in))
  }

  implicit class LiftedObservableHelper[InL](left: InL => Observable[Notify]) {
    final def &&[InR](right: InR => Observable[Notify]): (InL, InR) => Observable[Notify] = {
      case (inL, inR) =>
        left(inL) && right(inR)
    }

    final def ||[InR](right: InR => Observable[Notify]): (InL, InR) => Observable[Notify] = {
      case (inL, inR) =>
        left(inL) || right(inR)
    }
  }

  final def not(obs: Observable[Notify]): Observable[Notify] = {
    obs.map(_.invert)
  }

  implicit class ObservableHelper(obs: Observable[Notify]) {
    final def outcome: Observable[Accept] = {
      obs
        .flatMap {
          case accept: Accept =>
            Observable(accept)
          case exn: Fail =>
            Observable.raiseError(exn)
        }
    }

    final def &&(that: Observable[Notify]): Observable[Notify] = {
      obs.zip(that).map {
        case (Accept(left @ _*), Accept(right @ _*)) =>
          Accept(left ++ right: _*)
        case (Fail(left @ _*), Fail(right @ _*)) =>
          Fail(left ++ right: _*)
        case (Fail(left @ _*), Accept(right @ _*)) =>
          Fail(left ++ right: _*)
        case (Accept(left @ _*), Fail(right @ _*)) =>
          Fail(left ++ right: _*)
      }
    }

    final def ||(that: Observable[Notify]): Observable[Notify] = {
      obs.zip(that).map {
        case (Accept(left @ _*), Accept(right @ _*)) =>
          Accept(left ++ right: _*)
        case (Fail(left @ _*), Fail(right @ _*)) =>
          Fail(left ++ right: _*)
        case (Fail(left @ _*), Accept(right @ _*)) =>
          Accept(left ++ right: _*)
        case (Accept(left @ _*), Fail(right @ _*)) =>
          Accept(left ++ right: _*)
      }
    }

    final def lift[X]: Observer[X] => Observable[Notify] = { _ =>
      obs
    }
  }

//  final def not[X](obs: Observer[X] => Observable[Notify]): Observer[X] => Observable[Notify] = { probe =>
//    not(obs(probe))
//  }

  implicit class BehaviourHelper[X](left: Observer[X] => Observable[Notify]) {
    final def outcome: Observer[X] => Observable[Notify] = { probe =>
      left(probe).outcome
    }

    final def &&(right: Observer[X] => Observable[Notify]): Observer[X] => Observable[Notify] = { probe =>
      left(probe) && right(probe)
    }

    final def ||(right: Observer[X] => Observable[Notify]): Observer[X] => Observable[Notify] = { probe =>
      left(probe) || right(probe)
    }
  }
}

final class MatchingAutomata[IOState : ClassTag, Output : ClassTag] private (
  initial: IOState,
  timeout: Option[FiniteDuration],
  transition: PartialFunction[IOState, MatchingAutomata.StateFunction]
) {
  import MatchingAutomata._

  def run(
    outputs: TimedObservable[Output]*
  )(
    implicit system: ActorSystem,
    scheduler: Scheduler,
    testDuration: FiniteDuration,
    log: Logger
  ): Observable[Notify] = {
    val fsm = runUsing[Unit]()(outputs: _*)

    fsm(Seq.empty[Observable[Unit]])
  }

  def runUsing[Input : ClassTag](
    inputs: Observer[Input]*
  )(
    outputs: TimedObservable[Output]*
  )(
    implicit system: ActorSystem,
    scheduler: Scheduler,
    testDuration: FiniteDuration,
    log: Logger
  ): Seq[Observable[Input]] => Observable[Notify] = { traces =>
    require(traces.length == inputs.length)

    if (outputs.isEmpty) {
      Observable.empty[Notify]
    } else {
      val checker = system.actorOf(Props(new IOAutomata[Input](initial, timeout, transition, outputs, inputs, traces)))

      Observable.create(OverflowStrategy.Unbounded) { (sub: Subscriber[Notify]) =>
        checker ! Subscribe(sub)

        new Cancelable {
          def cancel(): Unit = {
            checker ! Unsubscribe(sub)
          }
        }
      }
    }
  }

  private final case class Subscribe(sub: Subscriber[Notify])
  private final case class Unsubscribe(sub: Subscriber[Notify])

  private case object TestTimeout extends Exception
  private case object Shutdown extends Exception
  private final case class UnexpectedException(reason: Throwable, event: Any) extends Exception

  private final case class State(state: IOState, timeout: Option[FiniteDuration], callbacks: Cancelable*)

  private trait SubscriberHandling {
    this: ReceivePipeline =>

    private[this] var subscribers: Set[Subscriber[Notify]] = Set.empty

    def getSubscribers: Set[Subscriber[Notify]] = subscribers

    pipelineOuter {
      case Subscribe(sub: Subscriber[Notify]) =>
        subscribers = subscribers + sub
        HandledCompletely
      case Unsubscribe(sub: Subscriber[Notify]) =>
        subscribers = subscribers - sub
        HandledCompletely
    }
  }

  private trait EventLogger {
    this: ReceivePipeline =>

    // FIXME: set via configuration!
    val traceSize: Int = 100

    private[this] var trace = Vector.empty[(State, Output)]

    def getState: State

    def getLoggedTrace: Vector[(State, Output)] = trace

    private def addEvent(state: State, event: Output): Unit = {
      if (trace.length < traceSize) {
        trace = trace :+ (state, event)
      } else {
        trace = trace.drop(1) :+ (state, event)
      }
    }

    pipelineOuter {
      case event: Output =>
        addEvent(getState, event)
        Inner(event)
    }
  }

  private class IOAutomata[Input](
    initial: IOState,
    timeout: Option[FiniteDuration],
    transition: PartialFunction[IOState, StateFunction],
    outputs: Seq[TimedObservable[Output]],
    inputs: Seq[Observer[Input]],
    traces: Seq[Observable[Input]]
  )(
    implicit testDuration: FiniteDuration,
    scheduler: Scheduler,
    log: Logger
  ) extends Actor
    with ReceivePipeline
    with SubscriberHandling
    with EventLogger {

    require(inputs.length == traces.length)

    override val supervisorStrategy: SupervisorStrategy = {
      def stoppingDecider: Decider = {
        case exn: Exception =>
          stop(Some(exn))
          SupervisorStrategy.Stop
      }
      OneForOneStrategy()(stoppingDecider)
    }

    scheduler.scheduleOnce(testDuration) {
      stop(Some(TestTimeout))
    }

    val fsmObs: Observer[Output] = new Observer[Output] {
      override def onNext(elem: Output): Future[Ack] = {
        log.debug(s"FSM Input Observer: onNext($elem)")
        self.ask(elem)(Timeout(testDuration)).mapTo[Ack]
      }

      override def onError(exn: Throwable): Unit = {
        log.debug(s"FSM Input Observer: onError($exn)")
        stop(Some(exn))
      }

      override def onComplete(): Unit = {
        log.debug("FSM Input Observer: onComplete()")
        if (state.timeout.isDefined) {
          self ! StateTimeout
        } else {
          stop(Some(Shutdown))
        }
      }
    }

    val inputSubscriptions: Seq[Cancelable] = inputs.zipWithIndex.map { case (point, index) => traces(index).subscribe(point) }
    val outputSubscription: Cancelable = Observable(outputs.map(_.observable): _*).merge.subscribe(fsmObs)

    private[this] var state: State = State(initial, timeout, callbacks(timeout): _*)

    def getState = state

    def receive: Receive = {
      case StateTimeout if transition.isDefinedAt(state.state) && transition(state.state).isDefinedAt(StateTimeout) =>
        if (state.timeout.isEmpty) {
          log.info(s"@ ${state.state} matched: StateTimeout")
        } else {
          log.info(s"@ ${state.state} matched [${state.timeout}]: StateTimeout")
        }
        try {
          val action = transition(state.state)(StateTimeout)
          val timeout = action match {
            case Goto(_, forMax, _) =>
              forMax
            case _ =>
              None
          }
          action.emit.foreach(msg => getSubscribers.foreach(_.onNext(msg)))
          state = nextState(action, timeout)
        } catch {
          case NonFatal(exn) =>
            stop(Some(UnexpectedException(exn, StateTimeout)))
        }
      case StateTimeout =>
        stop(Some(StateTimeout))
      case event: Output if transition.isDefinedAt(state.state) =>
        if (transition(state.state).isDefinedAt(event)) {
          if (state.timeout.isEmpty) {
            log.info(s"@ ${state.state} matched: $event")
          } else {
            log.info(s"@ ${state.state} matched [${state.timeout}]: $event")
          }
        }
        try {
          val action = transition(state.state).lift(event).getOrElse(Stay())
          val timeout = action match {
            case Goto(_, forMax, _) =>
              forMax
            case _ =>
              None
          }
          action match {
            case Stop(_: Fail) =>
              // Sending to subscribers will be handled in stop via nextState
            case _ =>
              action.emit
                .foreach(msg => getSubscribers.foreach(_.onNext(msg)))
          }
          state = nextState(action, timeout)
        } catch {
          case NonFatal(exn) =>
            stop(Some(UnexpectedException(exn, event)))
        }
      case event: Output =>
        state = nextState(Stay(), state.timeout)
    }

    private def callbacks(timeout: Option[FiniteDuration]): Seq[Cancelable] = {
      timeout.fold(Seq.empty[Cancelable]) { delay =>
        outputs.map(_.scheduler.scheduleOnce(delay) {
            self ! StateTimeout
            Option(state).foreach(_.callbacks.foreach(_.cancel()))
        })
      }
    }

    private def nextState(action: Action, timeout: Option[FiniteDuration]): State = {
      action match {
        case Goto(next: IOState, forMax, _) =>
          sender() ! Ack.Continue
          state.callbacks.foreach(_.cancel())
          State(next, forMax, callbacks(forMax): _*)
        case Stop(exn: Fail) =>
          stop(Some(exn))
          state
        case Stop(_: Accept) =>
          stop()
          state.callbacks.foreach(_.cancel())
          state
        case Stay(_) =>
          sender() ! Ack.Continue
          state
      }
    }

    private def stop(reason: Option[Throwable] = None): Unit = {
      Option(state).foreach(_.callbacks.foreach(_.cancel()))
      Try(sender()).foreach(_ ! Ack.Stop)
      inputSubscriptions.foreach(_.cancel())
      outputSubscription.cancel()
      val errMsg = reason.map {
        case Shutdown =>
          s"FSM upstreams closed in state $state"
        case TestTimeout =>
          s"FSM matching timed out in state $state"
        case UnexpectedException(_, event) =>
          s"FSM matching failed in state $state whilst handling $event"
        case Fail(causes) =>
          s"FSM matching failed in state $state due to $causes"
        case _ =>
          s"FSM matching failed in state $state"
      }
      reason
        .map {
          case UnexpectedException(exn, _) =>
            exn
          case exn =>
            exn
        }
        .foreach { exn =>
          val exnMsg = if (getLoggedTrace.length == traceSize) {
            s"${errMsg.get} - last $traceSize events (oldest first): ${pprint.tokenize(getLoggedTrace).mkString("")}\n${Logging.stackTraceFor(exn)}"
          } else {
            s"${errMsg.get} - events (oldest first): ${pprint.tokenize(getLoggedTrace).mkString("")}\n${Logging.stackTraceFor(exn)}"
          }
          getSubscribers.foreach(_.onNext(Fail(exnMsg)))
        }
      getSubscribers.foreach(_.onComplete())
      Try(context.stop(self))
    }
  }
}
