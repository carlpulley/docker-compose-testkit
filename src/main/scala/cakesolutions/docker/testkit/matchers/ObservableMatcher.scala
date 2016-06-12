package cakesolutions.docker.testkit.matchers

import cakesolutions.docker.testkit.logging.TestLogger
import org.scalatest.matchers.{MatchResult, Matcher}
import rx.lang.scala.Notification.{OnCompleted, OnError, OnNext}
import rx.lang.scala.Observable

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, Promise}

object ObservableMatcher {
  def observe[T : Manifest](expected: Int)(implicit timeout: FiniteDuration, log: TestLogger) = Matcher { (obs: Observable[(Int, T)]) =>
    require(expected >= 0)

    val result = Promise[Boolean]

    obs
      .take(timeout)
      .toVector
      .materialize
      .foreach {
        case OnCompleted =>
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
        case OnError(_: NoSuchElementException) =>
          log.info("Matched no elements")
          result.success(expected == 0)
        case OnError(exn) =>
          log.error("Matching stream failed", exn)
          result.failure(exn)
      }

    MatchResult(Await.result(result.future, timeout), s"failed to observe $expected events", "observed unexpected events")
  }
}
