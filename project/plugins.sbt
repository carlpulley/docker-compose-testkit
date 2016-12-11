// Copyright 2016 Carl Pulley

import sbt.Keys._
import sbt._

resolvers += "Typesafe Repository" at "https://repo.typesafe.com/typesafe/releases/"
resolvers += "jgit-repo" at "http://download.eclipse.org/jgit/maven"

addSbtPlugin("com.codacy" % "sbt-codacy-coverage" % "1.3.4")
addSbtPlugin("com.dwijnand" % "sbt-travisci" % "1.0.0-M4")
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.6.1")
addSbtPlugin("com.jsuereth" % "sbt-pgp" % "1.0.1")
addSbtPlugin("com.typesafe.sbt" % "sbt-ghpages" % "0.5.4")
addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.1.4")
addSbtPlugin("com.typesafe.sbt" % "sbt-site" % "0.8.1")
addSbtPlugin("com.typesafe.sbt" % "sbt-twirl" % "1.1.1")
addSbtPlugin("com.updateimpact" % "updateimpact-sbt-plugin" % "2.1.1")
addSbtPlugin("com.versioneye" % "sbt-versioneye-plugin" % "0.2.0")
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "1.3.5")
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "1.1")
