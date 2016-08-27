package cakesolutions.docker.testkit.automata

import scala.reflect.ClassTag

class FaultingAutomata[IOState : ClassTag, Input : ClassTag, Data : ClassTag] {
  // TODO: expand to allow automated randomised fault injection!!
  def fuzz(fsm: MatchingAutomata[IOState, Input]) = ???

  // TODO: expand to allow automated strategy based fault injection!!
  def search(fsm: MatchingAutomata[IOState, Input]) = ???
}
