ThisBuild / tlBaseVersion := "0.4" // current series x.y

ThisBuild / organization := "com.ocadotechnology"
ThisBuild / organizationName := "Ocado Technology"
ThisBuild / licenses := Seq(License.Apache2)
ThisBuild / developers := List(
  tlGitHubDev("majk-p", "Michał Pawlik"),
  tlGitHubDev("matwojcik", "Mateusz Wójcik")
)
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / homepage := Some(url("https://github.com/ocadotechnology/sttp-oauth2"))
val Scala213 = "2.13.12"
ThisBuild / scalaVersion := Scala213
ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.graalvm("21"))
ThisBuild / githubWorkflowBuild ++= Seq(
  WorkflowStep.Sbt(
    commands = List("IntegrationTest/test"),
    name = Some("Integration tests")
  )
)

val Versions = new {
  val ActiveMq = "5.18.3"
  val CatsEffect = "3.5.3"
  val Circe = "0.14.6"
  val Fs2 = "3.9.3"
  val Logback = "1.4.13"
  val Log4Cats = "2.6.0"
  val Weaver = "0.8.3"
  val Laserdisc = "6.0.5"
  val PekkoConnectors = "1.0.1"
}

lazy val IntegrationTest = config("it") extend Test

lazy val securityDependencyOverrides = Seq(
  "io.netty" % "netty-handler" % "4.1.100.Final", // SNYK-JAVA-IONETTY-5725787 introduced through software.amazon.awssdk:s3
  "io.netty" % "netty-codec-http2" % "4.1.100.Final" // SNYK-JAVA-IONETTY-5953332 introduced through software.amazon.awssdk:s3
)

lazy val root = (project in file("."))
  .configs(IntegrationTest)
  .enablePlugins(NoPublishPlugin)
  .settings(
    commonSettings,
    name := "pass4s",
    libraryDependencies ++= Seq(
      "com.amazonaws" % "amazon-sqs-java-extended-client-lib" % "2.0.3",
      "com.disneystreaming" %% "weaver-cats" % Versions.Weaver,
      "com.disneystreaming" %% "weaver-framework" % Versions.Weaver,
      "com.disneystreaming" %% "weaver-scalacheck" % Versions.Weaver,
      "org.scalatest" %% "scalatest" % "3.2.17", // just for `shouldNot compile`
      "com.dimafeng" %% "testcontainers-scala-localstack-v2" % "0.41.0",
      "com.dimafeng" %% "testcontainers-scala-mockserver" % "0.41.0",
      "org.mock-server" % "mockserver-client-java" % "5.15.0",
      "org.apache.activemq" % "activemq-broker" % Versions.ActiveMq,
      "org.typelevel" %% "log4cats-core" % Versions.Log4Cats,
      "org.typelevel" %% "log4cats-slf4j" % Versions.Log4Cats,
      "ch.qos.logback" % "logback-classic" % Versions.Logback
    ).map(_ % IntegrationTest),
    Defaults.itSettings,
    inConfig(IntegrationTest) {
      Defaults.testSettings
    },
    IntegrationTest / classDirectory := (Test / classDirectory).value,
    IntegrationTest / parallelExecution := true
  )
  .aggregate(core, kernel, high, activemqAkka, activemqPekko, kinesis, sns, sqs, circe, phobos, plaintext, extra, logging, demo, s3Proxy)
  .dependsOn(high, activemqAkka, activemqPekko, kinesis, sns, sqs, circe, logging, extra, s3Proxy)

def module(name: String, directory: String = ".") = Project(s"pass4s-$name", file(directory) / name).settings(commonSettings)

lazy val core = module("core")
  .settings(
    libraryDependencies ++= Seq(
      "org.scala-lang" % "scala-reflect" % scalaVersion.value,
      "co.fs2" %% "fs2-core" % Versions.Fs2,
      "org.typelevel" %% "cats-effect" % Versions.CatsEffect
    )
  )

lazy val kernel = module("kernel").settings(
  libraryDependencies ++= Seq(
    "co.fs2" %% "fs2-core" % Versions.Fs2,
    "org.typelevel" %% "cats-effect" % Versions.CatsEffect,
    "org.typelevel" %% "cats-tagless-core" % "0.15.0",
    "org.typelevel" %% "cats-laws" % "2.9.0" % Test,
    "com.disneystreaming" %% "weaver-discipline" % Versions.Weaver % Test
  )
)

lazy val high = module("high")
  .dependsOn(core, kernel)

// connectors

lazy val activemqAkka = module("activemq", directory = "connectors")
  .settings(
    name := "pass4s-connector-activemq",
    libraryDependencies ++= Seq(
      "com.lightbend.akka" %% "akka-stream-alpakka-jms" % "4.0.0", // 5.x.x contains akka-streams +2.7.x which is licensed under BUSL 1.1
      "org.apache.activemq" % "activemq-pool" % Versions.ActiveMq,
      "org.typelevel" %% "log4cats-core" % Versions.Log4Cats
    ),
    headerSources / excludeFilter := HiddenFileFilter || "taps.scala"
  )
  .dependsOn(core)

lazy val activemqPekko = module("activemq-pekko", directory = "connectors")
  .settings(
    name := "pass4s-connector-pekko-activemq",
    mimaPreviousArtifacts := { // this setting can be removed in 0.5.x
      val artifacts = mimaPreviousArtifacts.value
      artifacts.filter(_.revision >= "0.4.5") // this module has been prod ready in 0.4.5
    },
    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-connectors-jms" % Versions.PekkoConnectors,
      "org.apache.activemq" % "activemq-pool" % Versions.ActiveMq,
      "org.typelevel" %% "log4cats-core" % Versions.Log4Cats
    ),
    headerSources / excludeFilter := HiddenFileFilter || "taps.scala"
  )
  .dependsOn(core)

lazy val kinesis = module("kinesis", directory = "connectors")
  .settings(
    name := "pass4s-connector-kinesis",
    libraryDependencies ++= Seq(
      "io.laserdisc" %% "pure-kinesis-tagless" % Versions.Laserdisc
    ) ++ securityDependencyOverrides
  )
  .dependsOn(core)

lazy val sns = module("sns", directory = "connectors")
  .settings(
    name := "pass4s-connector-sns",
    libraryDependencies ++= Seq(
      "io.laserdisc" %% "pure-sns-tagless" % Versions.Laserdisc
    ) ++ securityDependencyOverrides
  )
  .dependsOn(core)

lazy val sqs = module("sqs", directory = "connectors")
  .settings(
    name := "pass4s-connector-sqs",
    libraryDependencies ++= Seq(
      "io.laserdisc" %% "pure-sqs-tagless" % Versions.Laserdisc,
      "org.typelevel" %% "log4cats-core" % Versions.Log4Cats
    ) ++ securityDependencyOverrides
  )
  .dependsOn(core)

// addons

lazy val circe = module("circe", directory = "addons")
  .settings(
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-parser" % Versions.Circe
    )
  )
  .dependsOn(core, kernel)

lazy val phobos = module("phobos", directory = "addons")
  .settings(
    libraryDependencies ++= Seq(
      "ru.tinkoff" %% "phobos-core" % "0.21.0"
    )
  )
  .dependsOn(core, kernel)

lazy val plaintext = module("plaintext", directory = "addons")
  .dependsOn(core, kernel)

lazy val extra = module("extra", directory = "addons")
  .dependsOn(high, circe)

lazy val s3Proxy = module("s3proxy", directory = "addons")
  .settings(
    libraryDependencies ++= Seq(
      "io.laserdisc" %% "pure-s3-tagless" % Versions.Laserdisc,
      "io.circe" %% "circe-literal" % Versions.Circe % Test
    ) ++ securityDependencyOverrides
  )
  .dependsOn(high, circe)

lazy val logging = module("logging", directory = "addons")
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "log4cats-core" % Versions.Log4Cats
    )
  )
  .dependsOn(high)

def latestStableVersion = {
  import scala.sys.process._
  "git -c versionsort.suffix=- tag --list --sort=-version:refname"
    .!!
    .split("\n")
    .toList
    .map(_.trim)
    .filter(_.startsWith("v"))
    .head
}

// online documentation

lazy val docs = project // new documentation project
  .in(file("mdoc")) // important: it must not be docs/
  .settings(
    mdocVariables := Map(
      "VERSION" -> { if (isSnapshot.value) latestStableVersion else version.value }
    ),
    githubWorkflowBuild := Seq(
      WorkflowStep.Sbt(List("docs/mdoc"))
    )
  )
  .dependsOn(high, activemqAkka, activemqPekko, kinesis, sns, sqs, circe, logging, extra, s3Proxy)
  .enablePlugins(MdocPlugin, DocusaurusPlugin)

// misc

lazy val demo = module("demo")
  .enablePlugins(NoPublishPlugin)
  .settings(
    publishArtifact := false,
    // mimaPreviousArtifacts := Set(), // TODO
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-generic" % Versions.Circe,
      "org.typelevel" %% "log4cats-core" % Versions.Log4Cats,
      "org.typelevel" %% "log4cats-slf4j" % Versions.Log4Cats,
      "ch.qos.logback" % "logback-classic" % Versions.Logback
    )
  )
  .dependsOn(activemqPekko, sns, sqs, extra, logging)

// Those versions failed to release
val versionsExcludedFromMima = List("0.4.3")

lazy val commonSettings = Seq(
  organization := "com.ocadotechnology",
  compilerOptions,
  Test / fork := true,
  libraryDependencies ++= compilerPlugins,
  libraryDependencies ++= Seq(
    "com.disneystreaming" %% "weaver-cats" % Versions.Weaver,
    "com.disneystreaming" %% "weaver-framework" % Versions.Weaver,
    "com.disneystreaming" %% "weaver-scalacheck" % Versions.Weaver
  ).map(_ % Test),
  mimaPreviousArtifacts := {
    val artifacts = mimaPreviousArtifacts.value
    artifacts.filterNot(artifact => versionsExcludedFromMima.contains(artifact.revision))
  },
  testFrameworks += new TestFramework("weaver.framework.CatsEffect")
)

val compilerOptions =
  scalacOptions -= "-Xfatal-warnings"

val compilerPlugins = Seq(
  compilerPlugin("org.typelevel" %% "kind-projector" % "0.13.2" cross CrossVersion.full),
  compilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")
)
