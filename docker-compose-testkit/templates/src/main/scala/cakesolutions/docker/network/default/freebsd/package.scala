// Copyright 2016 Carl Pulley

package cakesolutions.docker.network.default

import cakesolutions.docker.network.NetworkControl
import cakesolutions.docker.network.NetworkControl.Impairment
import cakesolutions.docker.testkit.DockerComposeTestKit.Driver
import cakesolutions.docker.testkit.yaml.DockerComposeProtocol.NetworkInteraction

package object freebsd {
  implicit class DummyNetControl(network: NetworkInteraction) extends NetworkControl {
    // TODO: provide an implementation based on dummynet (http://info.iet.unipi.it/~luigi/dummynet/) for OSX/FreeBSD
    def impair(impairments: Impairment*)(implicit driver: Driver): Unit = ???

    def reset()(implicit driver: Driver): Unit = ???
  }
}
