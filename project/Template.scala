// Copyright 2016 Carl Pulley

import play.twirl.sbt.Import.TwirlKeys
import play.twirl.sbt.Import.TwirlKeys._
import sbt.Keys._
import sbt._

object Template {

  val settings = Seq(
    sourceDirectories in (Compile, TwirlKeys.compileTemplates) := (unmanagedResourceDirectories in Compile).value,
    templateFormats := Map("template" -> "play.twirl.api.TxtFormat")
  )

}
