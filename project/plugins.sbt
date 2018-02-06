scalaVersion := "2.12.4"
scalacOptions ++= Seq("-feature", "-language:postfixOps")

addSbtPlugin("org.scala-sbt" % "sbt-houserules" % "0.3.5")
addSbtPlugin("org.scala-sbt" % "sbt-contraband" % "0.3.2")
addSbtPlugin("de.heikoseeberger" % "sbt-header" % "3.0.2")
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.7.0")


libraryDependencies := {
  val deps = libraryDependencies.value
  deps.filter(_.name != "sbt-bintray")
}
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "2.1")
addSbtPlugin("com.jsuereth" % "sbt-pgp" % "1.1.0")
