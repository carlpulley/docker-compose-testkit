// Copyright 2016 Carl Pulley

import sbt.Keys._
import sbt._

object Dependencies {
  object akka {
    private val version = "2.4.16"

    val actor = "com.typesafe.akka" %% "akka-actor" % version
    val cluster = "com.typesafe.akka" %% "akka-cluster" % version
    val contrib = "com.typesafe.akka" %% "akka-contrib" % version

    object http {
      private val httpVersion = "10.0.1"

      val core = "com.typesafe.akka" %% "akka-http-core" % httpVersion
      val experimental = "com.typesafe.akka" %% "akka-http" % httpVersion
      val testkit = "com.typesafe.akka" %% "akka-http-testkit" % httpVersion
    }

    val slf4j = "com.typesafe.akka" %% "akka-slf4j" % version
  }

  val config = "com.typesafe" % "config" % "1.3.1"
  object eff {
    private val version = "2.2.0"

    val core = "org.atnos" %% "eff" % version
    val monix = "org.atnos" %% "eff-monix" % version
  }
  val java8Compat = "org.scala-lang.modules" %% "scala-java8-compat" % "0.8.0"

  object json4s {
    private val version = "3.5.0"

    val jackson = "org.json4s" %% "json4s-jackson" % version
    val native = "org.json4s" %% "json4s-native" % version
  }

  val kindProjection = "org.spire-math" %% "kind-projector" % "0.9.3"
  val logback = "ch.qos.logback" % "logback-classic" % "1.1.8"

  object monix {
    private val version = "2.1.2"

    val core = "io.monix" %% "monix" % version
    val reactive = "io.monix" %% "monix-reactive" % version
  }

  val pprint = "com.lihaoyi" %% "pprint" % "0.4.4"
  // TODO: to avoid issues with incompatible classes, ensure compatability with scalatest
  val scalacheck = "org.scalacheck" %% "scalacheck" % "1.12.1"
  val scalacompiler = Def.setting("org.scala-lang" % "scala-compiler" % scalaVersion.value)
  // TODO: wait until scalatest version > 3.0.1 is released (see https://github.com/scalatest/scalatest/pull/980)
  val scalatest = "org.scalatest" %% "scalatest" % "3.0.0-M15"
  val shapeless = "com.chuusai" %% "shapeless" % "2.3.2"
  val si2712 = "com.milessabin" % "si2712fix-plugin_2.11.8" % "1.2.0"
  val yaml = "net.jcazevedo" %% "moultingyaml" % "0.3.1"
}
