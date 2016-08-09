import Dependencies._
import NativePackagerHelper._

name := "akka-cluster-node"

CommonProject.settings

enablePlugins(JavaAppPackaging)

enablePlugins(DockerPlugin)

libraryDependencies ++= Seq(
  akka.actor,
  akka.cluster,
  akka.slf4j,
  logback
)

mainClass in Compile := Some("cakesolutions.akka.cluster.Node")

// Dockerfile setup

dockerBaseImage := "java:openjdk-8-jre"

mappings in Universal ++= directory(s"${name.value}/src/main/resources")

bashScriptExtraDefines += """addJava "-Dconfig.file=${app_home}/../resources/application.conf""""
