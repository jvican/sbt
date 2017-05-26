package sbt.internal

import java.io.File

import sbt.{ ProjectRef, Reference }
import sbt.internal.librarymanagement._
import sbt.internal.util.{ ConsoleAppender, ConsoleLogger, HNil }
import sbt.internal.util.Types.:+:
import sbt.io.Hash
import sbt.librarymanagement._
import sbt.librarymanagement.syntax._
import sbt.util.CacheImplicits._
import sbt.util._
import sjsonnew.support.scalajson.unsafe.{ CompactPrinter, Converter, Parser }

import scala.util.control.NonFatal
import scala.json.ast.unsafe.{ JField, JValue }

object LibraryManagement {
  private[sbt] type UpdateInputs =
    IvyConfiguration :+: ModuleSettings :+: UpdateConfiguration :+: HNil

  // Define a fallback logger useful for serialization issues.
  private final lazy val fallbackLogger = ConsoleLogger()

  private[sbt] object UpdateInputs {
    def apply(updateConfiguration: UpdateConfiguration, module: IvySbt#Module): UpdateInputs =
      module.owner.configuration :+: module.moduleSettings :+: updateConfiguration :+: HNil
  }

  private def hashModules(modules: Vector[ModuleID]): String = {
    import sbt.io.Hash
    import AltLibraryManagementCodec._
    // We are using the serialized contents to hash rather than object identity
    val tryHash = Converter.toJson(modules).map(ast => Hash(jsonPrettyPrinter.to(ast)))
    tryHash.fold(
      error => sys.error(s"Failed to hash the resolution inputs: ${error.getMessage}."),
      jsonContent => Hash.toHex(Hash(jsonContent)).take(40) // sha-1 format
    )
  }

  import scala.io.AnsiColor
  private def applyColor(text: String, color: String): String =
    if (ConsoleAppender.formatEnabled) s"$color$text${AnsiColor.RESET}" else text
  private final val Step: String = applyColor("=>", AnsiColor.CYAN)
  private final val DepArrow: String = applyColor("->", AnsiColor.BLUE)
  private final val DepSep = s"\t$DepArrow "
  private final val WarnDepSep = s"\t${applyColor("->", AnsiColor.YELLOW)} " // Same as WARN

  private def list[T](items: Seq[T], sep: String): String = items.mkString(sep, s"\n$sep", "")

  /** Clean the configurations to print modules to users, they are too verbose. */
  private def keepEssentialData(modules: Seq[ModuleID]): Seq[ModuleID] =
    modules.map(m => ModuleID(m.organization, m.name, m.revision).withChecksum(m.checksum))

  private final val UnsupportedModuleSettings: String =
    "The lock file is unsupported for ivy file and pom file configuration."
  private final val ScalaDepsTip: String =
    "The dependency lock file has not been updated for all your Scala version. Run `lockDependencies`."
  private def MissingDepsFor(scalaVersion: String): Nothing = sys.error(
    s"Missing dependencies for scala version $scalaVersion in the dependency lock file.\n$ScalaDepsTip"
  )

  private type IvyModule = IvySbt#Module

  /** Modifies the ivy module injecting all the explicit dependencies from the lock file. */
  private[sbt] def useLockFile(
      lockFile: LockFileContent,
      module: IvySbt#Module,
      updateConfiguration: UpdateConfiguration,
      logger: Logger
  ): Option[(IvyModule, Vector[ModuleID])] = {
    module.moduleSettings match {
      case i: InlineConfiguration =>
        val seedDependencies = hashModules(i.dependencies)
        val previousSeed = lockFile.seed
        if (seedDependencies == previousSeed) {
          val explicitModules = lockFile.explicitDependencies
          logger.debug("The following locked dependencies have been injected to the Ivy module:")
          logger.debug(list(keepEssentialData(explicitModules), DepSep))
          val newModuleSettings = i.withDependencies(explicitModules)
          val sbtIvy = module.owner
          Some(new sbtIvy.Module(newModuleSettings) -> explicitModules)
        } else {
          logger.debug("The lock file contents are not injected to the Ivy module.")
          None
        }
      case _ =>
        // We cannot inject the explicit dependencies in xml files -- use inline instead
        logger.warn(UnsupportedModuleSettings)
        None
    }
  }

  private[sbt] def update(
      dependencyLockFile: File,
      projectRef: ProjectRef,
      updateInputs: UpdateInputs,
      module0: IvyModule,
      transform: UpdateReport => UpdateReport,
      uwConfig: UnresolvedWarningConfiguration,
      logicalClock: LogicalClock,
      depDir: Option[File],
      evictionWarningConf: EvictionWarningOptions,
      mavenStyle: Boolean,
      compatWarning: CompatibilityWarningOptions,
      log: Logger
  ): UpdateReport = {
    import sbt.util.ShowLines._
    val updateConfig0 = updateInputs.tail.tail.head

    // Check if it exists before creating the file store
    val existsLockFile = dependencyLockFile.exists()
    val lockStore = createLockFileStore(dependencyLockFile)
    val maybeLockFile = readFromLockFile(lockStore)
    val usingLockFile = maybeLockFile.flatMap(useLockFile(_, module0, updateConfig0, log))
    val module = usingLockFile.map(_._1).getOrElse(module0)
    val existsUsableLockFile = maybeLockFile.isDefined
    val ivyConfiguration = updateInputs.head

    val label = Reference.display(projectRef)
    log.info(s"Updating $label...")

    val maybeFrozenUpdateConfig = usingLockFile.map {
      case (ivyModule: IvyModule, explicitModules: Vector[ModuleID]) =>
        val baseDirectory = new java.io.File(projectRef.build)
        val absolutePath = dependencyLockFile.getAbsolutePath
        val relativePath = new sbt.io.RichFile(dependencyLockFile).relativeTo(baseDirectory)
        val lockFileLocation = relativePath.map(r => s"{.}/$r").getOrElse(absolutePath)
        log.info(s"$Step Using lock file at $lockFileLocation.")

        val transitiveModules = explicitModules.filter(_.isTransitive)
        log.warn("Your dependency lock file is not deterministic because of transitive module(s):")
        log.warn(list(keepEssentialData(transitiveModules), WarnDepSep))

        // Frozen mode == no transitive dependencies + no deps changed checks
        if (transitiveModules.nonEmpty) updateConfig0
        else updateConfig0.withFrozen(true)
    }

    val updateConfig = maybeFrozenUpdateConfig.getOrElse(updateConfig0)
    val reportOrUnresolved: Either[UnresolvedWarning, UpdateReport] =
      IvyActions.updateEither(module, updateConfig, uwConfig, logicalClock, depDir, log)

    val report = reportOrUnresolved match {
      case Right(report0) => report0
      case Left(unresolvedWarning) =>
        lockStore.close() // Don't leak resources
        unresolvedWarning.lines.foreach(log.warn(_))
        throw unresolvedWarning.resolveException
    }

    val finalReport = transform(report)
    if (!existsUsableLockFile) {
      val seedDependencies = module.moduleSettings match {
        case i: InlineConfiguration => i.dependencies
        // TODO(jvican): Don't use sys.error for this.
        case _ => sys.error(UnsupportedModuleSettings)
      }

      val resolvedModules = finalReport.allModules
      if (resolvedModules.nonEmpty) {
        val contents = LockFileContent(seedDependencies, resolvedModules)
        writeToLockFile(contents, lockStore, existsLockFile, log)
      }
    }

    val stats = finalReport.stats
    val resolveTime = s"${stats.resolveTime}ms"
    val downloadTime = s"${stats.downloadTime}ms"
    log.info(s"$Step Updated dependencies (resolution: $resolveTime, download: $downloadTime).")

    // Warn of any eviction and compatibility warnings
    val evictionProcessor = EvictionWarning(module, evictionWarningConf, finalReport, log)
    evictionProcessor.lines.foreach(log.warn(_))
    evictionProcessor.infoAllTheThings.foreach(log.info(_))
    CompatibilityWarning.run(compatWarning, module, mavenStyle, log)

    lockStore.close()
    finalReport
  }

  private[sbt] def cachedUpdate(
      cacheStoreFactory: CacheStoreFactory,
      dependencyLockFile: File,
      projectRef: ProjectRef,
      updateInputs: UpdateInputs,
      mod0: IvySbt#Module,
      transform: UpdateReport => UpdateReport,
      skip: Boolean,
      force: Boolean,
      depsUpdated: Boolean,
      uwConfig: UnresolvedWarningConfiguration,
      logicalClock: LogicalClock,
      depDir: Option[File],
      ewo: EvictionWarningOptions,
      mavenStyle: Boolean,
      compatWarning: CompatibilityWarningOptions,
      log: Logger
  ): UpdateReport = {

    /* Resolve the module settings from the inputs. */
    def resolve(inputs: UpdateInputs): UpdateReport = {
      // format: off
      update(dependencyLockFile, projectRef, updateInputs, mod0, transform, uwConfig,
        logicalClock, depDir, ewo, mavenStyle, compatWarning, log)
      // format: on
    }

    /* Check if a update report is still up to date or we must resolve again. */
    def upToDate(inChanged: Boolean, out: UpdateReport): Boolean = {
      !force &&
      !depsUpdated &&
      !inChanged &&
      out.allFiles.forall(f => fileUptodate(f, out.stamps)) &&
      fileUptodate(out.cachedDescriptor, out.stamps)
    }

    /* Skip resolve if last output exists, otherwise error. */
    def skipResolve(cache: CacheStore): UpdateInputs => UpdateReport = {
      import sbt.librarymanagement.LibraryManagementCodec._
      Tracked.lastOutput[UpdateInputs, UpdateReport](cache) {
        case (_, Some(out)) => out
        case _ =>
          sys.error("Skipping update requested, but update has not previously run successfully.")
      }
    }

    def doResolve(cache: CacheStore): UpdateInputs => UpdateReport = {
      val doCachedResolve = { (inChanged: Boolean, updateInputs: UpdateInputs) =>
        import sbt.librarymanagement.LibraryManagementCodec._
        val cachedResolve = Tracked.lastOutput[UpdateInputs, UpdateReport](cache) {
          case (_, Some(out)) if upToDate(inChanged, out) => out
          case _                                          => resolve(updateInputs)
        }
        import scala.util.control.Exception.catching
        catching(classOf[NullPointerException], classOf[OutOfMemoryError])
          .withApply { t =>
            val resolvedAgain = resolve(updateInputs)
            val culprit = t.getClass.getSimpleName
            log.warn(s"Update task caching failed due to $culprit.")
            log.warn("Report the following output to sbt:")
            resolvedAgain.toString.lines.foreach(log.warn(_))
            log.trace(t)
            resolvedAgain
          }
          .apply(cachedResolve(updateInputs))
      }
      import AltLibraryManagementCodec._
      Tracked.inputChanged(cacheStoreFactory.make("inputs"))(doCachedResolve)
    }

    // Get the handler to use and feed it in the inputs
    val outStore = cacheStoreFactory.make("output")
    val handler = if (skip && !force) skipResolve(outStore) else doResolve(outStore)
    handler(updateInputs)
  }

  private[sbt] object LockPrettyPrinter extends CompactPrinter {
    import java.lang.StringBuilder
    private final val spaces = "  "
    private var indentationLevel: Int = 0
    override protected def printJObject(members: Array[JField], sb: StringBuilder): Unit = {
      sb.append("{\n")
      printArray(members, sb.append(s",\n")) { m =>
        indentationLevel += 1
        sb.append(spaces * indentationLevel)
        printString(m.field, sb)
        sb.append(": ")
        print(m.value, sb)
        indentationLevel -= 1
      }
      sb.append('\n').append(spaces * indentationLevel).append('}')
    }

    override protected def printJArray(elements: Array[JValue], sb: StringBuilder): Unit = {
      sb.append('[')
      printArray(elements, sb.append(", "))(print(_, sb))
      sb.append(']')
    }
  }

  private final val jsonPrettyPrinter: sjsonnew.IsoString[JValue] =
    sjsonnew.IsoString.iso(LockPrettyPrinter.apply, Parser.parseUnsafe)

  /** Represents the format used to lock all the dependencies.
   *
   * @param version The version of the used dependency lock file.
   * @param seed The hash of the library dependencies that generated the lock file.
   * @param dependencies The resolved modules product of the resolution of [[UpdateInputs]].
   */
  private[sbt] final case class LockFileContent(version: String,
                                                seed: String,
                                                dependencies: Vector[ModuleID]) {

    /** The resolved intransitive dependencies for deterministic results. */
    lazy val explicitDependencies: Vector[ModuleID] = {
      // Cross versions are already disabled since they have already been resolved
      dependencies.map { module =>
        // If you want this not to be intransitive, set `isChanging` in your dependency
        if (module.isChanging || module.revision.endsWith("-SNAPSHOT")) module
        else module.withIsTransitive(false).withIsForce(true)
      }
    }
  }

  private[sbt] object LockFileContent {
    private val DefaultVersion = "0.1"
    def apply(seedDependencies: Vector[ModuleID],
              allExplicitDependencies: Vector[ModuleID]): LockFileContent =
      LockFileContent(DefaultVersion, hashModules(seedDependencies), allExplicitDependencies)

    import sjsonnew.{ LNil, LList }
    import sjsonnew.LList.:*:
    import sjsonnew.IsoLList.Aux
    import AltLibraryManagementCodec._

    implicit val lockFileFormatIso = LList.iso(
      (l: LockFileContent) =>
        ("version", l.version) :*: ("seed", l.seed) :*: ("dependencies", l.dependencies) :*: LNil,
      (in: String :*: String :*: Vector[ModuleID] :*: LNil) =>
        LockFileContent(in.head, in.tail.head, in.tail.tail.head)
    )
  }

  private[sbt] def createLockFileStore(lockFile: File): CacheStore =
    new FileBasedStore(lockFile, Converter)(jsonPrettyPrinter)

  private[sbt] def readFromLockFile(lockStore: CacheStore): Option[LockFileContent] = {
    import LockFileContent.lockFileFormatIso
    try Some(lockStore.read[LockFileContent])
    catch { case NonFatal(_) => None }
  }

  private[sbt] def writeToLockFile(lockContent: LockFileContent,
                                   lockStore: CacheStore,
                                   alreadyExists: Boolean,
                                   logger: Logger): Unit = {
    import LockFileContent.lockFileFormatIso
    lockStore.write[LockFileContent](lockContent)
    val performedActionMsg: String =
      if (alreadyExists) s"$Step The dependency lock file has been updated."
      else s"$Step The dependency lock file has been generated."
    logger.success(performedActionMsg)
  }

  private[this] def fileUptodate(file: File, stamps: Map[File, Long]): Boolean =
    stamps.get(file).forall(_ == file.lastModified)
}
