// Copyright 2016 Carl Pulley

import Dependencies._

name := "docker-compose-testkit-templates"

CommonProject.settings

Publish.settings

ScalaDoc.settings

Template.settings

enablePlugins(SbtTwirl)

libraryDependencies ++= Seq(
  akka.actor,
  akka.cluster,
  akka.contrib,
  akka.slf4j,
  eff.core,
  eff.monix,
  monix.core,
  monix.reactive
)
