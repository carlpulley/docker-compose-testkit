// Copyright 2016 Carl Pulley

import Dependencies._

name := "docker-compose-testkit"

val buildVersion = "0.0.3-SNAPSHOT"

lazy val root = (project in file(".")).
  settings(CommonProject.settings: _*).
  settings(ScalaDoc.settings: _*).
  settings(Publish.settings: _*).
  settings(version := buildVersion).
  settings(
    resolvers += Resolver.jcenterRepo,
    libraryDependencies ++= Seq(
      akka.actor,
      akka.cluster % "test",
      akka.http.core % "test",
      akka.http.experimental % "test",
      akka.http.testkit % "test",
      json4s.native,
      json4s.jackson,
      monix.core,
      monix.reactive,
      scalatest,
      yaml
    )
  ).
  aggregate(
    clusterNode
  )

lazy val clusterNode = (project in file("akka-cluster-node")).
  settings(CommonProject.settings: _*).
  settings(version := buildVersion)
