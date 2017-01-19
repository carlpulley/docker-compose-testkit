// Copyright 2016 Carl Pulley

package cakesolutions.docker.network

import cakesolutions.docker.testkit.DockerComposeTestKit.Driver

object NetworkControl {
  // NOTE: constraints are *only* applied to egress points!
  sealed trait Constraint
  final case class Port(value: Int) extends Constraint {
    require(value >= 0)
  }

  sealed trait Protocol extends Constraint
  case object Tcp extends Protocol
  case object Udp extends Protocol
  case object Icmp extends Protocol

  sealed trait Impairment
  // limit packets
  final case class Limit(spec: String) extends Impairment
  // delay TIME [ JITTER [ CORRELATION ]]] [ distribution { uniform | normal | pareto |  paretonormal } ]
  final case class Delay(spec: String = "75ms 100ms distribution normal") extends Impairment
  // loss { random PERCENT [ CORRELATION ]  | state p13 [ p31 [ p32 [ p23 [ p14]]]] | gemodel p [ r [ 1-h [ 1-k ]]] }  [ ecn ]
  final case class Loss(spec: String, constraints: Constraint*) extends Impairment
  // corrupt PERCENT [ CORRELATION ]]
  final case class Corrupt(spec: String) extends Impairment
  // duplicate PERCENT [ CORRELATION ]]
  final case class Duplicate(spec: String) extends Impairment
  // reorder PERCENT [ CORRELATION ] [ gap DISTANCE ]
  final case class Reorder(spec: String) extends Impairment
  // rate RATE [ PACKETOVERHEAD [ CELLSIZE [ CELLOVERHEAD ]]]]
  final case class Rate(spec: String) extends Impairment
}

trait NetworkControl {
  import NetworkControl._

  def impair(impairments: Impairment*)(implicit driver: Driver): Unit

  def reset()(implicit driver: Driver): Unit
}
