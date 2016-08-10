package cakesolutions.docker.testkit.automata

import cakesolutions.docker.testkit.matchers.ObservableMatcher
import monix.execution.Scheduler
import monix.reactive.Observable
import org.scalatest.{Matchers, FreeSpec}

import scala.concurrent.duration._

class MatchingAutomataTest extends FreeSpec with Matchers {

  import MatchingAutomata.{not => negation, _}
  import ObservableMatcher._

  implicit val testDuration: FiniteDuration = 10.seconds
  implicit val scheduler = Scheduler.io("MatchingAutomataTest")

  // TODO: how do logical operators behave when subscriptions are cancelled?

  val left = Observable(Accept, Fail("left-1"), Accept, Fail("left-2"), Accept, Fail("left-3"), Accept, Fail("left-4"))
  val middle = Observable(Accept, Accept, Fail("middle-1"), Fail("middle-2"), Accept, Accept, Fail("middle-3"), Fail("middle-4"))
  val right = Observable(Accept, Accept, Accept, Accept, Fail("right-1"), Fail("right-2"), Fail("right-3"), Fail("right-4"))

  "conjunction" - {
    "propositional properties" in {
      val expected = Seq(Accept, Fail("left-1"), Accept, Fail("left-2"), Fail("right-1"), Fail("left-3", "right-2"), Fail("right-3"), Fail("left-4", "right-4"))

      Observable(Accept) && Observable(Accept) should observe(Accept)
      Observable(Accept) && Observable(Fail("right-example")) should observe(Fail("right-example"))
      Observable(Fail("left-example")) && Observable(Accept) should observe(Fail("left-example"))
      Observable(Fail("left-example")) && Observable(Fail("right-example")) should observe(Fail("left-example", "right-example"))

      left && right should observe(expected: _*)
    }

    "associativity" in {
      val expected = Seq(Accept, Fail("left-1"), Fail("middle-1"), Fail("left-2", "middle-2"), Fail("right-1"), Fail("left-3", "right-2"), Fail("middle-3", "right-3"), Fail("left-4", "middle-4", "right-4"))

      left && middle && right should observe(expected: _*)
    }

    // FIXME:
    "subscription" ignore {
      left && right should observe(Accept)
    }
  }

  "disjunction" - {
    "propositional properties" in {
      val expected = Seq(Accept, Accept, Accept, Accept, Accept, Fail("left-3", "right-2"), Accept, Fail("left-4", "right-4"))

      Observable(Accept) || Observable(Accept) should observe(Accept)
      Observable(Accept) || Observable(Fail("right-example")) should observe(Accept)
      Observable(Fail("left-example")) || Observable(Accept) should observe(Accept)
      Observable(Fail("left-example")) || Observable(Fail("right-example")) should observe(Fail("left-example", "right-example"))

      left ||right should observe(expected: _*)
    }

    "associativity" in {
      val expected = Seq(Accept, Accept, Accept, Accept, Accept, Accept, Accept, Fail("left-4", "middle-4", "right-4"))

      left || middle || right should observe(expected: _*)
    }
  }

  "negation" - {
    "propositional properties" in {
      val expected = Seq(Fail("negation"), Accept, Fail("negation"), Accept, Fail("negation"), Accept, Fail("negation"), Accept)

      negation(Observable(Accept)) should observe(Fail("negation"))
      negation(Observable(Fail("example"))) should observe(Accept)

      negation(left) should observe(expected: _*)
    }

    "de morgan" in {
      val expectedAnd = Seq(Fail("negation"), Accept, Fail("negation"), Accept, Accept, Accept, Accept, Accept)
      val expectedOr = Seq(Fail("negation"), Fail("negation"), Fail("negation"), Fail("negation"), Fail("negation"), Accept, Fail("negation"), Accept)

      negation(left && right) should observe(expectedAnd: _*)
      negation(left || right) should observe(expectedOr: _*)
    }
  }

}
