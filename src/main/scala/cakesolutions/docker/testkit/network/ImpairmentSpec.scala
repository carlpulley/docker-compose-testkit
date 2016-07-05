package cakesolutions.docker.testkit.network

object ImpairmentSpec {

  sealed trait Impairment {
    def command: Seq[String]
  }

  // limit packets
  final case class Limit(spec: String) extends Impairment {
    override def command: Seq[String] = "limit" +: spec.split(" ")
  }

  // delay TIME [ JITTER [ CORRELATION ]]] [ distribution { uniform | normal | pareto |  paretonormal } ]
  final case class Delay(spec: String = "75ms 100ms distribution normal") extends Impairment {
    override def command: Seq[String] = "delay" +: spec.split(" ")
  }

  // loss { random PERCENT [ CORRELATION ]  | state p13 [ p31 [ p32 [ p23 [ p14]]]] | gemodel p [ r [ 1-h [ 1-k ]]] }  [ ecn ]
  final case class Loss(spec: String = "random 30%") extends Impairment {
    override def command: Seq[String] = "loss" +: spec.split(" ")
  }

  // corrupt PERCENT [ CORRELATION ]]
  final case class Corrupt(spec: String) extends Impairment {
    override def command: Seq[String] = "corrupt" +: spec.split(" ")
  }

  // duplicate PERCENT [ CORRELATION ]]
  final case class Duplicate(spec: String) extends Impairment {
    override def command: Seq[String] = "duplicate" +: spec.split(" ")
  }

  // reorder PERCENT [ CORRELATION ] [ gap DISTANCE ]
  final case class Reorder(spec: String) extends Impairment {
    override def command: Seq[String] = "reorder" +: spec.split(" ")
  }

  // rate RATE [ PACKETOVERHEAD [ CELLSIZE [ CELLOVERHEAD ]]]]
  final case class Rate(spec: String) extends Impairment {
    override def command: Seq[String] = "rate" +: spec.split(" ")
  }

}
