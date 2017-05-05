ivyPaths := IvyPaths(
  baseDirectory.value,
  Some(target.value / "ivy-cache")
)

def checkDependencyLockFile(expects: Boolean): Def.Initialize[Task[Unit]] = Def.task {
  val hasBeenGenerated = generateDependencyLock.value
  if (hasBeenGenerated != expects) {
    if (expects) sys.error("Dependency lock file has not been generated/overridden.")
    else sys.error("Dependency lock file has been generated while expecting otherwise.")
  } else ()
}

val checkDependencyLockFileIsUpdated = taskKey[Unit]("Check lock file is updated.")
val checkDependencyLockFileIsTheSame = taskKey[Unit]("Check lock file is the same.")

val commonSettings: Seq[Setting[_]] = List(
  checkDependencyLockFileIsUpdated := checkDependencyLockFile(true).value,
  checkDependencyLockFileIsTheSame := checkDependencyLockFile(false).value
)

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
