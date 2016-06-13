// Copyright 2016 Carl Pulley

import sbt._

object Dependencies {
  val scalaVersion = "2.11.8"

  object akka {
    private val version = "2.4.7"

    val actor = "com.typesafe.akka" %% "akka-actor" % version
    val cluster = "com.typesafe.akka" %% "akka-cluster" % version

    object http {
      val core = "com.typesafe.akka" %% "akka-http-core" % version
      val experimental = "com.typesafe.akka" %% "akka-http-experimental" % version
      val testkit = "com.typesafe.akka" %% "akka-http-testkit" % version
    }

    val slf4j = "com.typesafe.akka" %% "akka-slf4j" % version
  }

  object json4s {
    private val version = "3.3.0"

    val jackson = "org.json4s" %% "json4s-jackson" % version
    val native = "org.json4s" %% "json4s-native" % version
  }

  val logback = "ch.qos.logback" % "logback-classic" % "1.1.7"
  val reactiveX = "io.reactivex" %% "rxscala" % "0.26.1"
  val scalatest = "org.scalatest" %% "scalatest" % "2.2.6"
  val yaml = "net.jcazevedo" %% "moultingyaml" % "0.2"
}