package cakesolutions.docker.testkit.matchers

import cakesolutions.docker.testkit.logging.Logger
import monix.execution.Scheduler
import org.scalatest.matchers.{MatchResult, Matcher}
import monix.reactive.Notification.{OnComplete, OnError, OnNext}
import monix.reactive.Observable

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, Promise}

object ObservableMatcher {
  def legacyObserve[T : Manifest](expected: Int)(implicit timeout: FiniteDuration, scheduler: Scheduler, log: Logger) = Matcher { (obs: Observable[(Int, T)]) =>
    require(expected >= 0)

    val result = Promise[Boolean]

    obs
      .takeByTimespan(timeout)
      .foldLeftF(Vector.empty[(Int, T)]) { case (matches, value) => matches :+ value }
      .firstOrElseF(Vector.empty)
      .materialize
      .foreach {
        case OnComplete =>
        // No work to do
        case OnNext(matches) if matches.isEmpty =>
          log.info("Matched no elements")
          result.success(expected == 0)
        case OnNext(matches) =>
          matches.foreach {
            case (index, entry) =>
              log.info(s"Matched at $index: $entry")
          }
          result.success(matches.length == expected)
        case OnError(exn) =>
          log.error("Matching stream failed", exn)
          result.failure(exn)
      }

    MatchResult(Await.result(result.future, timeout * 1.25), s"failed to observe $expected events", "observed unexpected events")
  }
}
