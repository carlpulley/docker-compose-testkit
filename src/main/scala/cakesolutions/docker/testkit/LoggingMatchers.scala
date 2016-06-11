package cakesolutions.docker.testkit

import cakesolutions.docker.testkit.DockerComposeTestKit.LogEvent
import org.scalatest.matchers.{MatchResult, Matcher}
import rx.lang.scala.Notification.{OnCompleted, OnError, OnNext}
import rx.lang.scala.Observable

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.{Await, Promise}

object LoggingMatchers {
  implicit class ObservableMatchFilter(obs: Observable[LogEvent]) {
    def matchFirst(eventMatch: LogEvent => Boolean): Observable[(Int, LogEvent)] = {
      obs
        .map { entry =>
          if (eventMatch(entry)) {
            Some(entry)
          } else {
            None
          }
        }
        .collect { case Some(entry) => (0, entry) }
        .first
    }

    def matchFirstOrdered(eventMatches: (LogEvent => Boolean)*): Observable[(Int, LogEvent)] = {
      obs
        .scan[(Option[(Int, LogEvent)], Int)]((None, 0)) {
        case ((_, matchedEventsCount), entry) if matchedEventsCount < eventMatches.length && eventMatches(matchedEventsCount)(entry) =>
          (Some((matchedEventsCount, entry)), matchedEventsCount + 1)
        case ((_, matchedEventsCount), _) =>
          (None, matchedEventsCount)
      }
        .collect { case (Some(indexedEntry), _) => indexedEntry }
    }

    def matchFirstUnordered(eventMatches: (LogEvent => Boolean)*): Observable[(Int, LogEvent)] = {
      obs
        .scan[(Option[(Int, LogEvent)], Set[Int])]((None, Set.empty)) {
        case ((_, matchedEventIndexes), entry) =>
          val index = eventMatches.indexWhere(_(entry))
          if (index >= 0 && ! matchedEventIndexes.contains(index)) {
            (Some((index, entry)), matchedEventIndexes + index)
          } else {
            (None, matchedEventIndexes)
          }
      }
        .collect { case (Some(indexedEntry), _) => indexedEntry }
    }

    def matchUnordered(eventMatches: (LogEvent => Boolean)*): Observable[(Int, LogEvent)] = {
      obs
        .scan[(Option[(Int, LogEvent)], Set[Int])]((None, Set.empty)) {
        case ((_, matchedEventIndexes), entry) =>
          val index = eventMatches.indexWhere(_(entry))
          if (index >= 0) {
            (Some((index, entry)), matchedEventIndexes + index)
          } else {
            (None, matchedEventIndexes)
          }
      }
        .collect { case (Some(indexedEntry), _) => indexedEntry }
    }
  }

  def observe(expected: Int)(implicit timeout: FiniteDuration, log: TestLogger) = Matcher { (obs: Observable[(Int, LogEvent)]) =>
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

    MatchResult(Await.result(result.future, Duration.Inf), s"failed to observe $expected events", "observed unexpected events")
  }
}
