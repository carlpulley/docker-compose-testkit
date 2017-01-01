// Copyright 2016 Carl Pulley

package cakesolutions.docker.testkit

sealed trait Notify {
  def invert: Notify
}
final case class Accept(failures: String*) extends Notify {
  override def invert: Notify = {
    Fail(failures: _*)
  }
}
final case class Fail(reasons: String*) extends Exception with Notify {
  override def invert: Notify = {
    Accept(reasons: _*)
  }
}
