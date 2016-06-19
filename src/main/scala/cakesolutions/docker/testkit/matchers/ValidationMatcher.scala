package cakesolutions.docker.testkit.matchers

import cakesolutions.docker.testkit.logging.Logger
import org.scalatest.matchers.{MatchResult, Matcher}
import monix.reactive.Observable

import scala.concurrent.duration.FiniteDuration

object ValidationMatcher {
  def validate[T : Manifest](query: String)(implicit timeout: FiniteDuration, log: Logger) = Matcher { (obs: Observable[T]) =>
    // TODO:

    MatchResult(???, s"failed to validate $query", "observed unexpected events")
  }
}
