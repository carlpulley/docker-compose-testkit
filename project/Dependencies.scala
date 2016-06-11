// Copyright 2016 Carl Pulley

import sbt._

object Dependencies {
  val scalaVersion = "2.11.8"

  object akka {
    private val version = "2.4.7"

    object http {
      val core = "com.typesafe.akka" %% "akka-http-core" % version
      val experimental = "com.typesafe.akka" %% "akka-http-experimental" % version
      val testkit = "com.typesafe.akka" %% "akka-http-testkit" % version
    }
  }
  val json4s = "org.json4s" %% "json4s-native" % "3.3.0"
  val reactiveX = "io.reactivex" %% "rxscala" % "0.26.1"
  val scalatest = "org.scalatest" %% "scalatest" % "2.2.6"
  val yaml = "net.jcazevedo" %% "moultingyaml" % "0.2"
}
