// Copyright 2016 Carl Pulley

import Dependencies._

name := "docker-compose-testkit"

CommonProject.settings

Publish.settings

ScalaDoc.settings

resolvers += Resolver.jcenterRepo

libraryDependencies ++= Seq(
  akka.actor,
  akka.cluster,
  akka.contrib,
  akka.http.core,
  akka.http.experimental,
  akka.slf4j,
  java8Compat,
  json4s.native,
  json4s.jackson,
  monix.core,
  monix.reactive,
  pprint,
  scalacheck,
  scalacompiler,
  scalatest,
  yaml
)

dependencyOverrides ++= Set(
  java8Compat
)
