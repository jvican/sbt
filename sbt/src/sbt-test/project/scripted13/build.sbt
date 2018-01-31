lazy val root = (project in file("."))
  .settings(
    sbtPlugin := true,
    resolvers += Resolver.typesafeIvyRepo("releases")
  )
