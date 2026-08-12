scalaVersion := "3.8.4"

val zioVersion = "2.1.26"
val tapirVersion = "1.13.31"

// sbt 2 disk cache restores non-instrumented classes for coverage builds
// (coverage flags are added via Def.uncached and excluded from the cache key),
// so the compiler never runs and scoverage-data is never created.
if (sys.env.contains("COVERAGE"))
  Def.settings(Global / cacheStores := Seq.empty)
else
  Def.settings()

lazy val root = rootProject
  .settings(
    name := "calorie-ledger",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zioVersion,
      "dev.zio" %% "zio-http" % "3.11.3",
      "com.softwaremill.sttp.tapir" %% "tapir-zio-http-server" % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-zio" % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-json-zio" % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-bundle" % tapirVersion,
      "io.getquill" %% "quill-jdbc-zio" % "4.8.6",
      "org.postgresql" % "postgresql" % "42.7.13",
      "org.flywaydb" % "flyway-core" % "13.2.0",
      "org.flywaydb" % "flyway-database-postgresql" % "13.2.0",
      "com.zaxxer" % "HikariCP" % "7.1.0",
      "dev.zio" %% "zio-config" % "4.0.8",
      "dev.zio" %% "zio-config-typesafe" % "4.0.8",
      "dev.zio" %% "zio-config-magnolia" % "4.0.8",
      "dev.zio" %% "zio-logging" % "2.5.3",
      "dev.zio" %% "zio-logging-slf4j2-bridge" % "2.5.3",
      "dev.zio" %% "zio-test" % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
      "com.dimafeng" %% "testcontainers-scala-postgresql" % "0.44.1" % Test
    ),
    coverageFailOnMinimum := true,
    coverageMinimumStmtTotal := 0,
    coverageEnabled := sys.env.contains("COVERAGE"),
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Wunused:all",
      "-Wvalue-discard",
      "-Werror"
    ),
    Compile / resourceGenerators += Def.task {
      val to = (Compile / resourceManaged).value / "static"
      IO.delete(to)
      val from = baseDirectory.value / "frontend"
      if (from.exists) IO.copyDirectory(from, to) else to.mkdirs()
      Seq(to)
    }.taskValue,
    assembly / mainClass := Some("pro.drsdgdbye.Main"),
    assembly / assemblyOutputPath := Def.uncached(target.value / "calorie-ledger.jar"),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", _ @ _*) => MergeStrategy.filterDistinctLines
      case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
      case PathList("META-INF", xs @ _*)
          if xs.lastOption.exists(n => n.endsWith(".SF") || n.endsWith(".DSA") || n.endsWith(".RSA")) =>
        MergeStrategy.discard
      case "reference.conf" | "application.conf" => MergeStrategy.concat
      case "module-info.class" => MergeStrategy.discard
      case _ => MergeStrategy.first
    },
    libraryDependencySchemes += "dev.zio" %% "zio-json" % VersionScheme.Always,
    Test / testFrameworks := Seq(new TestFramework("zio.test.sbt.ZTestFramework"))
  )
