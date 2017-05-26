ivyPaths := IvyPaths(
  baseDirectory.value,
  Some(target.value / "ivy-cache")
)

val commonSettings: Seq[Setting[_]] = List(scalaVersion := "2.12.2")

val p1 = project
  .in(file("p1"))
  .settings(name := "p1-lock-file", commonSettings, version := "1.0.0-SNAPSHOT")

val root = project
  .in(file("."))
  .dependsOn(p1)
  .settings(
    name := "root-lock-file",
    commonSettings,
    libraryDependencies ++= Seq(
      ModuleID("org.apache.avro", "avro", "1.7.7"),
      ModuleID("com.linkedin.pegasus", "data-avro", "1.9.40"),
      ModuleID("org.jboss.netty", "netty", "3.2.0.Final")
    )
  )
