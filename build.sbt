// Copyright 2016 Carl Pulley

import Dependencies._

name := "docker-compose-testkit"

version := "0.0.2-SNAPSHOT"

CommonProject.settings

ScalaDoc.settings

Publish.settings

resolvers += Resolver.jcenterRepo

libraryDependencies ++= Seq(
  akka.http.core % "test",
  akka.http.experimental % "test",
  akka.http.testkit % "test",
  json4s,
  reactiveX,
  scalatest
)
