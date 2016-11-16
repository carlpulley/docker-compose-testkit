// Copyright 2016 Carl Pulley

import Dependencies._
import NativePackagerHelper._

name := "docker-compose-testkit-tests"

CommonProject.settings

enablePlugins(JavaAppPackaging)

enablePlugins(DockerPlugin)

enablePlugins(BuildInfoPlugin)

buildInfoKeys := Seq[BuildInfoKey](name, version, Keys.scalaVersion, sbtVersion)
buildInfoPackage := "cakesolutions"

libraryDependencies ++= Seq(
  akka.actor,
  akka.cluster,
  akka.http.testkit % Test,
  akka.slf4j,
  logback
)

mainClass in Compile := Some("cakesolutions.akka.cluster.Node")

// Dockerfile setup

dockerBaseImage := "openjdk:8-jre"
