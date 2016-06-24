package cakesolutions.docker.testkit.network

object ImpairmentSpec {

  sealed trait Impairment {
    def command: String
  }

  // limit packets
  case class Limit(spec: String) extends Impairment {
    override def command: String = s"limit $spec"
  }

  // delay TIME [ JITTER [ CORRELATION ]]] [ distribution { uniform | normal | pareto |  paretonormal } ]
  case class Delay(spec: String = "75ms 100ms distribution normal") extends Impairment {
    override def command: String = s"delay $spec"
  }

  // loss { random PERCENT [ CORRELATION ]  | state p13 [ p31 [ p32 [ p23 [ p14]]]] | gemodel p [ r [ 1-h [ 1-k ]]] }  [ ecn ]
  case class Loss(spec: String = "random 30%") extends Impairment {
    override def command: String = s"loss $spec"
  }

  // corrupt PERCENT [ CORRELATION ]]
  case class Corrupt(spec: String) extends Impairment {
    override def command: String = s"corrupt $spec"
  }

  // duplicate PERCENT [ CORRELATION ]]
  case class Duplicate(spec: String) extends Impairment {
    override def command: String = s"duplicate $spec"
  }

  // reorder PERCENT [ CORRELATION ] [ gap DISTANCE ]
  case class Reorder(spec: String) extends Impairment {
    override def command: String = s"reorder $spec"
  }

  // rate RATE [ PACKETOVERHEAD [ CELLSIZE [ CELLOVERHEAD ]]]]
  case class Rate(spec: String) extends Impairment {
    override def command: String = s"rate $spec"
  }

}
