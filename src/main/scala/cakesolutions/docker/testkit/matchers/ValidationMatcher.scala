package cakesolutions.docker.testkit.matchers

import cakesolutions.docker.testkit.logging.TestLogger
import org.scalatest.matchers.{MatchResult, Matcher}
import rx.lang.scala.Observable

import scala.concurrent.duration.FiniteDuration

object ValidationMatcher {
  def validate[T : Manifest](query: String)(implicit timeout: FiniteDuration, log: TestLogger) = Matcher { (obs: Observable[T]) =>
    // TODO:

    MatchResult(???, s"failed to validate $query", "observed unexpected events")
  }
}
