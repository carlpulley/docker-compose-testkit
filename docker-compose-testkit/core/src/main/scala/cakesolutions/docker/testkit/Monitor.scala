// Copyright 2016 Carl Pulley

package cakesolutions.docker.testkit

import akka.actor.SupervisorStrategy.Decider
import akka.actor._
import akka.contrib.pattern.ReceivePipeline
import akka.contrib.pattern.ReceivePipeline.{HandledCompletely, Inner}
import akka.event.Logging
import akka.pattern.ask
import akka.util.Timeout
import cakesolutions.docker.testkit.logging.Logger
import monix.execution.{Ack, Cancelable, Scheduler}
import monix.reactive.observers.Subscriber
import monix.reactive.{Observable, Observer, OverflowStrategy}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.Try
import scala.util.control.NonFatal

sealed abstract case class Monitor[IOState: ClassTag, Event] private(initial: IOState, timeout: Option[FiniteDuration], behaviour: Behaviour[IOState, Event]) {
  def run(
    sensor: Observable[Event]
  )(implicit system: ActorSystem,
    scheduler: Scheduler,
    log: Logger
  ): Observable[Notify] = {
    val checker = system.actorOf(Props(new IOAutomata(initial, timeout, behaviour, sensor)))

    Observable.create(OverflowStrategy.Unbounded) { (sub: Subscriber[Notify]) =>
      checker ! Subscribe(sub)

      new Cancelable {
        def cancel(): Unit = {
          checker ! Unsubscribe(sub)
        }
      }
    }
  }

  private final case class Subscribe(sub: Subscriber[Notify])
  private final case class Unsubscribe(sub: Subscriber[Notify])

  private case object Shutdown extends Exception
  private final case class UnexpectedException(reason: Throwable, event: ObservedEvent[Event]) extends Exception
  private final case class State(state: IOState, timeout: Option[FiniteDuration], callback: Option[Cancelable])

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
    protected val traceSize: Int = 100

    private[this] var trace = Vector.empty[(State, ObservedEvent[Event])]

    protected def getState: State

    protected def getLoggedTrace: Vector[(State, ObservedEvent[Event])] = trace

    private def addEvent(state: State, event: ObservedEvent[Event]): Unit = {
      if (trace.length < traceSize) {
        trace = trace :+(state, event)
      } else {
        trace = trace.drop(1) :+(state, event)
      }
    }

    pipelineOuter {
      case event: ObservedEvent[Event] =>
        addEvent(getState, event)
        Inner(event)
    }
  }

  private class IOAutomata(
    initial: IOState,
    timeout: Option[FiniteDuration],
    transition: Behaviour[IOState, Event],
    sensor: Observable[Event]
  )(implicit scheduler: Scheduler,
    log: Logger
  ) extends Actor
    with ReceivePipeline
    with SubscriberHandling
    with EventLogger {

    override val supervisorStrategy: SupervisorStrategy = {
      def stoppingDecider: Decider = {
        case NonFatal(exn) =>
          stop(Some(exn))
          SupervisorStrategy.Stop
      }
      OneForOneStrategy()(stoppingDecider)
    }

    private val fsmObs: Observer[Event] = new Observer[Event] {
      override def onNext(elem: Event): Future[Ack] = {
        log.debug(s"FSM Input Observer: onNext($elem)")
        // FIXME: set via configuration
        self.ask(Observe(elem))(Timeout(10.seconds)).mapTo[Ack]
      }

      override def onError(exn: Throwable): Unit = {
        log.debug(s"FSM Input Observer: onError($exn)")
        stop(Some(exn))
      }

      override def onComplete(): Unit = {
        log.debug("FSM Input Observer: onComplete()")
        if (state.timeout.isDefined) {
          stop(Some(StateTimeout))
        } else {
          stop(Some(Shutdown))
        }
      }
    }
    private val outputSubscription: Cancelable = sensor.subscribe(fsmObs)

    private val behaviour: IOState => ObservedEvent[Event] => Action[IOState] = { state => event =>
      if (transition.isDefinedAt(state) && transition(state).isDefinedAt(event)) {
        log.info(s"@ $state matched: $event")
        transition(state)(event)
      } else {
        event match {
          case StateTimeout =>
            Stop(Fail(s"FSM matching failed in state $state"))
          case _: ObservedEvent[Event] =>
            log.debug(s"ignoring $event in state $state")
            Stay()
        }
      }
    }

    private[this] var state: State = State(initial, timeout, callback(timeout))

    override protected def getState = state

    def receive: Receive = {
      case event: ObservedEvent[Event] =>
        try {
          val action = behaviour(state.state)(event)
          val timeout = action match {
            case Goto(_, forMax, _) =>
              forMax
            case _ =>
              None
          }
          action.emit
            .foreach(msg => getSubscribers.foreach(_.onNext(msg)))
          state = nextState(action, timeout)
          action match {
            case Stop(exn: Fail) =>
              stop(Some(exn))
            case Stop(_: Accept) =>
              log.debug(s"FSM stopping in state $state")
              stop()
            case _ =>
            // No work to do
          }
        } catch {
          case NonFatal(exn) =>
            sender() ! Ack.Stop
            stop(Some(UnexpectedException(exn, event)))
        }
    }

    private def callback(timeout: Option[FiniteDuration]): Option[Cancelable] = {
      timeout.map { delay =>
        scheduler.scheduleOnce(delay) {
          self ! StateTimeout
          state.callback.foreach(_.cancel())
        }
      }
    }

    private def nextState(action: Action[IOState], timeout: Option[FiniteDuration]): State = {
      action match {
        case Goto(next: IOState, forMax, _) =>
          // FIXME: take into account Goto that is equal to a Stay???
          sender() ! Ack.Continue
          state.callback.foreach(_.cancel())
          State(next, forMax, callback(forMax))
        case Stop(_) =>
          sender() ! Ack.Stop
          state
        case Stay(_) =>
          sender() ! Ack.Continue
          state
      }
    }

    private def stop(reason: Option[Throwable] = None): Unit = {
      state.callback.foreach(_.cancel())
      outputSubscription.cancel()
      val errMsg = reason.map {
        case StateTimeout =>
          s"FSM timed out in state $state"
        case Shutdown =>
          s"FSM upstreams closed in state $state"
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
          log.debug(exnMsg)
        }
      // FIXME: surely we base ending on the reason???
      getSubscribers.foreach(_.onComplete())
      Try(context.stop(self))
    }
  }

}
object Monitor {
  def apply[State: ClassTag, Event](initial: State)(behaviour: Behaviour[State, Event]): Monitor[State, Event] = {
    new Monitor(initial, None, behaviour) {}
  }

  def apply[State: ClassTag, Event](initial: State, timeout: FiniteDuration)(behaviour: Behaviour[State, Event]): Monitor[State, Event] = {
    new Monitor(initial, Some(timeout), behaviour) {}
  }
}
