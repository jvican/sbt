# The dependency lock file

The dependency lock file contains all the dependencies (both direct and transitive)
of your build. Its goal is to bring predictable dependency management to your projects.

If reproducible builds is a new concept to you, we encourage you to read [Reproducible builds](https://reproducible-builds.org/).
 
## An overview

The purpose of the lock file is to enforce that your locally resolved artifacts match
the upstream dependencies for development. These checks help you avoid mistakes that are
otherwise difficult to spot.

The dependency lock file builds on the concept that a clean `compile` and `test` should
produce the same results across different computers. It is not uncommon that artifacts
with the same coordinates but from different resolvers make a project not compile or modify
a program's runtime behaviour.

## Lock your dependencies

There are two ways of locking your dependencies:

* Set `useLockFile in update := true` and run `update`; or
* Run `lockDependencies`.

If you run one of those tasks, the dependency lock file is generated. Its location
depends on the value of the the `dependencyLockFile` setting. By default, it's named
`dependency-lock.json` and located at the root of a project.

### Lock file format

A dependency lock file is a json file that contains the following data:

* The version of the dependency lock file.
* A signature of the library dependencies in your project.
* For every Scala version, a list of its explicit dependencies with:
  * Its module id information (organization, name, revision, configuration).
  * A list of downloaded artifacts (jars, sources, docs) with:
    * The full artifact information.
    * The SHA1 of its contents.

Note that the lock file contains the resolution of your dependencies for all the Scala
versions your project cross compiles to. When you add a new version to `crossScalaVersions`,
the dependency lock file is recomputed.

The dependency lock file is ignored and regenerated every time the following happens:

* You manually change the `libraryDependencies`.
* You run `lockDependencies` again.

Otherwise, their contents will be always used for resolving artifacts.

### Enable the lock file in all your sbt projects

Add ... TBD.

## Use the dependency lock

If `useLockFile in update` is enabled, you don't need to do anything to use the lock file.
The `update` task reads the build graph from the lock file and replaces the library dependencies
defined in your project by all the explicit dependencies. Note that these library dependencies
will be different depending on the used Scala version.

When the resolution is done, `update` checks that the resolved artifacts coordinates and its
contents are the same than those in the lock file. If they are not, the default behaviour is
to warn the user which artifacts do not match. If you want to turn this warning into an error,
set `forcePredictable in update := true`.
