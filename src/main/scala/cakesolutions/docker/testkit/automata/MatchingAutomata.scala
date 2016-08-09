package cakesolutions.docker.testkit.automata

import akka.actor.SupervisorStrategy.Decider
import akka.actor._
import akka.contrib.pattern.ReceivePipeline
import akka.contrib.pattern.ReceivePipeline.{HandledCompletely, Inner}
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
import scala.util.control.NonFatal

object MatchingAutomata {
  sealed trait Notify
  case object Accept extends Notify
  final case class Fail(reasons: String*) extends Exception with Notify {
    override def toString: String = {
      s"Fail($reasons)"
    }
  }

  sealed trait Action {
    def emit: Notify
  }
  final case class Goto[State](state: State, forMax: FiniteDuration = null, emit: Notify = null) extends Action
  final case class Stay(emit: Notify = null) extends Action
  final case class Stop(emit: Notify) extends Action

  case object StateTimeout extends Exception

  type StateFunction = PartialFunction[Any, Action]

  def apply[State : ClassTag, Input : ClassTag](initial: State)(transition: PartialFunction[State, StateFunction]): MatchingAutomata[State, Input] = {
    apply[State, Input](initial, null)(transition)
  }

  def apply[State : ClassTag, Input : ClassTag](initial: State, timeout: FiniteDuration)(transition: PartialFunction[State, StateFunction]): MatchingAutomata[State, Input] = {
    new MatchingAutomata[State, Input](initial, timeout, transition)
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
    obs.map {
      case Accept =>
        Fail("negation")
      case _: Fail =>
        Accept
    }
  }

  implicit class ObservableHelper(obs: Observable[Notify]) {
    final def outcome: Observable[Accept.type] = {
      obs
        .flatMap {
          case Accept =>
            Observable(Accept)
          case exn: Fail =>
            Observable.raiseError(exn)
        }
    }

    final def &&(that: Observable[Notify]): Observable[Notify] = {
      obs.zip(that).map {
        case (Accept, Accept) =>
          Accept
        case (Fail(left @ _*), Fail(right @ _*)) =>
          Fail(left ++ right: _*)
        case (Fail(left @ _*), _) =>
          Fail(left: _*)
        case (_, Fail(right @ _*)) =>
          Fail(right: _*)
      }
    }

    final def ||(that: Observable[Notify]): Observable[Notify] = {
      obs.zip(that).map {
        case (Fail(left @  _*), Fail(right @ _*)) =>
          Fail(left ++ right: _*)
        case _ =>
          Accept
      }
    }
  }
}

final class MatchingAutomata[IOState : ClassTag, Input : ClassTag] private (
  initial: IOState,
  timeout: FiniteDuration,
  transition: PartialFunction[IOState, MatchingAutomata.StateFunction]
) {
  import MatchingAutomata._

  def run(inputs: TimedObservable[Input]*)(implicit system: ActorSystem, scheduler: Scheduler, testDuration: FiniteDuration, log: Logger): Observable[Notify] = {
    if (inputs.isEmpty) {
      Observable.empty[Notify]
    } else {
      val checker = system.actorOf(Props(new MatchingAutomataFSM(initial, timeout, transition, inputs)))

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

    private[this] var trace = Vector.empty[(State, Input)]

    def getState: State

    def getLoggedTrace: Vector[(State, Input)] = trace

    private def addEvent(state: State, event: Input): Unit = {
      if (trace.length < traceSize) {
        trace = trace :+ (state, event)
      } else {
        trace = trace.drop(1) :+ (state, event)
      }
    }

    pipelineOuter {
      case event: Input =>
        addEvent(getState, event)
        Inner(event)
    }
  }

  private class MatchingAutomataFSM(
    initial: IOState,
    timeout: FiniteDuration,
    transition: PartialFunction[IOState, StateFunction],
    inputs: Seq[TimedObservable[Input]]
  )(
    implicit testDuration: FiniteDuration,
    scheduler: Scheduler,
    log: Logger
  ) extends Actor
    with ReceivePipeline
    with SubscriberHandling
    with EventLogger {

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

    val fsmObs: Observer[Input] = new Observer[Input] {
      override def onNext(elem: Input): Future[Ack] = {
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

    val inputSubscription: Cancelable = Observable(inputs.map(_.observable): _*).merge.subscribe(fsmObs)

    private[this] var state: State = State(initial, Option(timeout), callbacks(Option(timeout)): _*)

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
              Some(forMax)
            case _ =>
              None
          }
          Option(action.emit).foreach(msg => getSubscribers.foreach(_.onNext(msg)))
          state = nextState(action, timeout)
        } catch {
          case NonFatal(exn) =>
            stop(Some(UnexpectedException(exn, StateTimeout)))
        }
      case StateTimeout =>
        stop(Some(StateTimeout))
      case event: Input if transition.isDefinedAt(state.state) =>
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
              Some(forMax)
            case _ =>
              None
          }
          Option(action.emit).foreach(msg => getSubscribers.foreach(_.onNext(msg)))
          state = nextState(action, timeout)
        } catch {
          case NonFatal(exn) =>
            stop(Some(UnexpectedException(exn, event)))
        }
      case event: Input =>
        state = nextState(Stay(), state.timeout)
    }

    private def callbacks(timeout: Option[FiniteDuration]): Seq[Cancelable] = {
      timeout.fold(Seq.empty[Cancelable]) { delay =>
        inputs.map(_.scheduler.scheduleOnce(delay) {
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
          State(next, Option(forMax), callbacks(Option(forMax)): _*)
        case Stop(exn: Fail) =>
          stop(Some(exn))
          state
        case Stop(Accept) =>
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
      sender() ! Ack.Stop
      inputSubscription.cancel()
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
          if (getLoggedTrace.length == traceSize) {
            log.error(s"${errMsg.get} - last $traceSize events (oldest first): ${pprint.tokenize(getLoggedTrace).mkString("")}", exn)
          } else {
            log.error(s"${errMsg.get} - events (oldest first): ${pprint.tokenize(getLoggedTrace).mkString("")}", exn)
          }
          getSubscribers.foreach(_.onNext(Fail(exn.toString)))
        }
      getSubscribers.foreach(_.onComplete())
      context.stop(self)
    }
  }
}
