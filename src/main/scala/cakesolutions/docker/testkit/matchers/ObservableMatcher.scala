package cakesolutions.docker.testkit.matchers

import java.util.concurrent.TimeoutException

import cakesolutions.docker.testkit.automata.MatchingAutomata.Notify
import monix.execution.Scheduler
import monix.reactive.Notification.{OnComplete, OnError, OnNext}
import monix.reactive.Observable
import org.scalatest.matchers.{MatchResult, Matcher}

import scala.concurrent._
import scala.concurrent.duration._

object ObservableMatcher {

  private case class TestFailed(n: Int, action: Notify) extends Exception
  private case class ObservableError(n: Int, cause: Throwable) extends Exception
  private case class ObservableClosed(n: Int) extends Exception

  def observe(actions: Notify*)(implicit scheduler: Scheduler, timeout: FiniteDuration) = new Matcher[Observable[Notify]] {
    def apply(obs: Observable[Notify]) = {
      val result = Promise[Unit]

      if (actions.isEmpty) {
        result.success(())
      } else {
        obs
          .timeoutOnSlowUpstream(timeout + 1.second)
          .materialize
          .scan[Option[Int]](Some(0)) {
            case data =>
              if (result.isCompleted) {
                None
              } else {
                data match {
                  case (Some(n), OnNext(act)) if n < 0 || n >= actions.length =>
                    result.failure(ObservableError(n, new IndexOutOfBoundsException))
                    None
                  case (Some(n), OnNext(act)) if actions(n) == act =>
                    Some(n + 1)
                  case (Some(n), OnNext(act)) =>
                    result.failure(TestFailed(n, act))
                    None
                  case (Some(n), OnError(exn)) =>
                    result.failure(ObservableError(n, exn))
                    None
                  case (Some(n), OnComplete) if actions.length == n =>
                    result.success(())
                    None
                  case (Some(n), OnComplete) =>
                    result.failure(ObservableClosed(n))
                    None
                  case _ =>
                    None
                }
              }
          }
          .subscribe()
      }

      try {
        Await.result(result.future, timeout + 1.second)
        MatchResult(matches = true, "", "")
      } catch {
        case _: TimeoutException =>
          val errMsg = s"After $timeout test timed out"
          MatchResult(matches = false, errMsg, errMsg)
        case ObservableError(n, cause: IndexOutOfBoundsException) if n < 0 =>
          val errMsg = s"Observable emitted an unexpected exception $cause with negative index $n"
          MatchResult(matches = false, errMsg, errMsg)
        case ObservableError(n, cause: IndexOutOfBoundsException) if actions.length >= n =>
          val errMsg = s"Observable emitted an unexpected exception $cause at list index $n (list contained ${actions.length} members!)"
          MatchResult(matches = false, errMsg, errMsg)
        case ObservableError(0, cause) =>
          val errMsg = s"Observable emitted an unexpected exception $cause - expected ${actions(0)}"
          MatchResult(matches = false, errMsg, errMsg)
        case ObservableError(position, cause) =>
          val errMsg = s"Observable emitted an unexpected exception $cause - matched ${actions.take(position)} and expected ${actions(position)}"
          MatchResult(matches = false, errMsg, errMsg)
        case ObservableClosed(0) =>
          val errMsg = s"Observable closed prematurely - expected ${actions(0)}"
          MatchResult(matches = false, errMsg, errMsg)
        case ObservableClosed(position) =>
          val errMsg = s"Observable closed prematurely - matched ${actions.take(position)} and expected ${actions(position)}"
          MatchResult(matches = false, errMsg, errMsg)
        case TestFailed(0, action) =>
          val errMsg = s"Test failed to match first event - received $action expected ${actions(0)}"
          MatchResult(matches = false, errMsg, errMsg)
        case TestFailed(position, action) =>
          val errMsg = s"Test matched ${actions.take(position)} and then matching failed - received $action expected ${actions(position)}"
          MatchResult(matches = false, errMsg, errMsg)
      }
    }
  }

}
