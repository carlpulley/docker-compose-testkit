// Copyright 2016 Carl Pulley

package cakesolutions.docker.testkit.automata

import cakesolutions.docker.testkit.matchers.ObservableMatcher
import monix.execution.Scheduler
import monix.reactive.Observable
import org.scalacheck.Gen
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{FreeSpec, Matchers}

import scala.concurrent.duration._

// TODO: speedup or distinguish long running tests
class MatchingAutomataTest extends FreeSpec with Matchers with GeneratorDrivenPropertyChecks {

  import MatchingAutomata.{not => negation, _}
  import ObservableMatcher._

  implicit val testDuration: FiniteDuration = 10.milliseconds
  implicit val scheduler = Scheduler.io("MatchingAutomataTest")

  // TODO: how do logical operators behave when subscriptions are cancelled?

  val delayGen = Gen.oneOf(1.millisecond, 2.milliseconds, 3.milliseconds)
  val left = Observable(Accept(), Fail("left-1"), Accept(), Fail("left-2"), Accept(), Fail("left-3"), Accept(), Fail("left-4"))
  val middle = Observable(Accept(), Accept(), Fail("middle-1"), Fail("middle-2"), Accept(), Accept(), Fail("middle-3"), Fail("middle-4"))
  val right = Observable(Accept(), Accept(), Accept(), Accept(), Fail("right-1"), Fail("right-2"), Fail("right-3"), Fail("right-4"))

  "conjunction" - {
    "propositional properties" in {
      val expected = Seq(Accept(), Fail("left-1"), Accept(), Fail("left-2"), Fail("right-1"), Fail("left-3", "right-2"), Fail("right-3"), Fail("left-4", "right-4"))

      Observable(Accept()) && Observable(Accept()) should observe(Accept())
      Observable(Accept()) && Observable(Fail("right-example")) should observe(Fail("right-example"))
      Observable(Fail("left-example")) && Observable(Accept()) should observe(Fail("left-example"))
      Observable(Fail("left-example")) && Observable(Fail("right-example")) should observe(Fail("left-example", "right-example"))

      left && right should observe(expected: _*)
    }

    "timed propositional properties" in {
      val expected = Seq(Accept(), Fail("left-1"), Accept(), Fail("left-2"), Fail("right-1"), Fail("left-3", "right-2"), Fail("right-3"), Fail("left-4", "right-4"))

      forAll(delayGen, delayGen) {
        case (leftDelay, rightDelay) =>
          Observable.evalDelayed(leftDelay, Accept()) && Observable.evalDelayed(rightDelay, Accept()) should observe(Accept())
          Observable.evalDelayed(leftDelay, Accept()) && Observable.evalDelayed(rightDelay, Fail("right-example")) should observe(Fail("right-example"))
          Observable.evalDelayed(leftDelay, Fail("left-example")) && Observable.evalDelayed(rightDelay, Accept()) should observe(Fail("left-example"))
          Observable.evalDelayed(leftDelay, Fail("left-example")) && Observable.evalDelayed(rightDelay, Fail("right-example")) should observe(Fail("left-example", "right-example"))

          left.delayOnNext(leftDelay) && right.delayOnNext(rightDelay) should observe(expected: _*)(implicitly, expected.length * 3.seconds)
      }
    }

    "associativity" in {
      val expected = Seq(Accept(), Fail("left-1"), Fail("middle-1"), Fail("left-2", "middle-2"), Fail("right-1"), Fail("left-3", "right-2"), Fail("middle-3", "right-3"), Fail("left-4", "middle-4", "right-4"))

      left && middle && right should observe(expected: _*)
    }

    "timed associativity" in {
      val expected = Seq(Accept(), Fail("left-1"), Fail("middle-1"), Fail("left-2", "middle-2"), Fail("right-1"), Fail("left-3", "right-2"), Fail("middle-3", "right-3"), Fail("left-4", "middle-4", "right-4"))

      forAll(delayGen, delayGen, delayGen) {
        case (leftDelay, middleDelay, rightDelay) =>
          left.delayOnNext(leftDelay) && middle.delayOnNext(middleDelay) && right.delayOnNext(rightDelay) should observe(expected: _*)(implicitly, expected.length * 3.seconds)
      }
    }
  }

  "disjunction" - {
    "propositional properties" in {
      val expected = Seq(Accept(), Accept("left-1"), Accept(), Accept("left-2"), Accept("right-1"), Fail("left-3", "right-2"), Accept("right-3"), Fail("left-4", "right-4"))

      Observable(Accept()) || Observable(Accept()) should observe(Accept())
      Observable(Accept()) || Observable(Fail("right-example")) should observe(Accept("right-example"))
      Observable(Fail("left-example")) || Observable(Accept()) should observe(Accept("left-example"))
      Observable(Fail("left-example")) || Observable(Fail("right-example")) should observe(Fail("left-example", "right-example"))

      left || right should observe(expected: _*)
    }

    "timed propositional properties" in {
      val expected = Seq(Accept(), Accept("left-1"), Accept(), Accept("left-2"), Accept("right-1"), Fail("left-3", "right-2"), Accept("right-3"), Fail("left-4", "right-4"))

      forAll(delayGen, delayGen) {
        case (leftDelay, rightDelay) =>
          Observable.evalDelayed(leftDelay, Accept()) || Observable.evalDelayed(rightDelay, Accept()) should observe(Accept())
          Observable.evalDelayed(leftDelay, Accept()) || Observable.evalDelayed(rightDelay, Fail("right-example")) should observe(Accept("right-example"))
          Observable.evalDelayed(leftDelay, Fail("left-example")) || Observable.evalDelayed(rightDelay, Accept()) should observe(Accept("left-example"))
          Observable.evalDelayed(leftDelay, Fail("left-example")) || Observable.evalDelayed(rightDelay, Fail("right-example")) should observe(Fail("left-example", "right-example"))

          left.delayOnNext(leftDelay) || right.delayOnNext(rightDelay) should observe(expected: _*)(implicitly, expected.length * 3.seconds)
      }
    }

    "associativity" in {
      val expected = Seq(Accept(), Accept("left-1"), Accept("middle-1"), Accept("left-2", "middle-2"), Accept("right-1"), Accept("left-3", "right-2"), Accept("middle-3", "right-3"), Fail("left-4", "middle-4", "right-4"))

      left || middle || right should observe(expected: _*)
    }

    "timed associativity" in {
      val expected = Seq(Accept(), Accept("left-1"), Accept("middle-1"), Accept("left-2", "middle-2"), Accept("right-1"), Accept("left-3", "right-2"), Accept("middle-3", "right-3"), Fail("left-4", "middle-4", "right-4"))

      forAll(delayGen, delayGen, delayGen) {
        case (leftDelay, middleDelay, rightDelay) =>
          left.delayOnNext(leftDelay) || middle.delayOnNext(middleDelay) || right.delayOnNext(rightDelay) should observe(expected: _*)(implicitly, expected.length * 3.seconds)
      }
    }
  }

  "negation" - {
    "propositional properties" in {
      val expected = Seq(Fail(), Accept("left-1"), Fail(), Accept("left-2"), Fail(), Accept("left-3"), Fail(), Accept("left-4"))

      negation(Observable(Accept())) should observe(Fail())
      negation(Observable(Fail("example"))) should observe(Accept("example"))

      negation(left) should observe(expected: _*)
    }

    "de morgan" in {
      val expectedAnd = Seq(Fail(), Accept("left-1"), Fail(), Accept("left-2"), Accept("right-1"), Accept("left-3", "right-2"), Accept("right-3"), Accept("left-4", "right-4"))
      val expectedOr = Seq(Fail(), Fail("left-1"), Fail(), Fail("left-2"), Fail("right-1"), Accept("left-3", "right-2"), Fail("right-3"), Accept("left-4", "right-4"))

      negation(left && right) should observe(expectedAnd: _*)
      negation(left || right) should observe(expectedOr: _*)
    }

    "timed de morgan" in {
      val expectedAnd = Seq(Fail(), Accept("left-1"), Fail(), Accept("left-2"), Accept("right-1"), Accept("left-3", "right-2"), Accept("right-3"), Accept("left-4", "right-4"))
      val expectedOr = Seq(Fail(), Fail("left-1"), Fail(), Fail("left-2"), Fail("right-1"), Accept("left-3", "right-2"), Fail("right-3"), Accept("left-4", "right-4"))

      forAll(delayGen, delayGen) {
        case (leftDelay, rightDelay) =>
          negation(left.delayOnNext(leftDelay) && right.delayOnNext(rightDelay)) should observe(expectedAnd: _*)(implicitly, expectedAnd.length * 3.seconds)
          negation(left.delayOnNext(leftDelay) || right.delayOnNext(rightDelay)) should observe(expectedOr: _*)(implicitly, expectedOr.length * 3.seconds)
      }
    }
  }

}
