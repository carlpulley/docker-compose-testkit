// Copyright 2016 Carl Pulley

import sbt.Keys._

lazy val root = (project in file("."))
  .settings(
    publishArtifact := false,
    sonatypeProfileName := "net.cakesolutions"
  )
  .aggregate(
    dockerCompose,
    dockerComposeTemplates,
    dockerComposeTests
  )
  .enablePlugins(VersionEyePlugin)

lazy val dockerCompose = project in file("docker-compose-testkit/core")

lazy val dockerComposeTemplates = (project in file("docker-compose-testkit/templates"))
  .dependsOn(dockerCompose)

lazy val dockerComposeTests = (project in file("docker-compose-testkit/tests"))
  .settings(
    libraryDependencies ++= Seq(
      "net.cakesolutions" %% "docker-compose-testkit-templates" % (version in dockerComposeTemplates).value % Test,
      "net.cakesolutions" %% "docker-compose-testkit" % (version in dockerCompose).value % Test
    )
  )
