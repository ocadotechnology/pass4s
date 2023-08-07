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
val Scala213 = "2.13.11"
ThisBuild / scalaVersion := Scala213
ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.graalvm("20"))
ThisBuild / githubWorkflowBuild ++= Seq(
  WorkflowStep.Sbt(
    commands = List("IntegrationTest/test"),
    name = Some("Integration tests")
  )
)

val Versions = new {
  val ActiveMq = "5.17.5"
  val CatsEffect = "3.4.11"
  val Circe = "0.14.5"
  val Fs2 = "3.6.1"
  val Logback = "1.4.8"
  val Log4Cats = "2.5.0"
  val Weaver = "0.8.3"
  val Laserdisc = "6.0.0"
}

lazy val IntegrationTest = config("it") extend Test

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
      "org.scalatest" %% "scalatest" % "3.2.16", // just for `shouldNot compile`
      "com.dimafeng" %% "testcontainers-scala-localstack-v2" % "0.40.17",
      "com.amazonaws" % "aws-java-sdk-core" % "1.12.521" exclude ("*", "*"), // fixme after release of https://github.com/testcontainers/testcontainers-java/pull/5827
      "com.dimafeng" %% "testcontainers-scala-mockserver" % "0.40.17",
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
    "org.typelevel" %% "cats-tagless-core" % "0.14.0",
    "org.typelevel" %% "cats-laws" % "2.9.0" % Test,
    "com.disneystreaming" %% "weaver-discipline" % Versions.Weaver % Test
  )
)

lazy val high = module("high")
  .dependsOn(core, kernel)

// connectors

val awsSnykOverrides = Seq(
  "commons-codec" % "commons-codec" % "1.15"
)

val nettyVersion = "4.1.94.Final"

//Fixes https://security.snyk.io/vuln/SNYK-JAVA-IONETTY-5725787
val nettySnykOverrides = Seq(
  "io.netty" % "netty-transport-classes-epoll" % nettyVersion,
  "io.netty" % "netty-codec-http2" % nettyVersion,
  "io.netty" % "netty-handler" % nettyVersion
)

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
    mimaPreviousArtifacts := Set(), // Remove when 0.4.2 is released
    name := "pass4s-connector-pekko-activemq",
    resolvers += "Apache Snapshots" at "https://repository.apache.org/content/repositories/snapshots/", // Resolvers to be removed when stable version is released
    resolvers ++= Resolver.sonatypeOssRepos("snapshots"),
    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-connectors-jms" % "0.0.0+140-7d704044-SNAPSHOT", // TODO to be changed to stable release once https://github.com/apache/incubator-pekko-connectors/issues/210 is ready
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
    ) ++ awsSnykOverrides
  )
  .dependsOn(core)

lazy val sns = module("sns", directory = "connectors")
  .settings(
    name := "pass4s-connector-sns",
    libraryDependencies ++= Seq(
      "io.laserdisc" %% "pure-sns-tagless" % Versions.Laserdisc
    ) ++ awsSnykOverrides ++ nettySnykOverrides
  )
  .dependsOn(core)

lazy val sqs = module("sqs", directory = "connectors")
  .settings(
    name := "pass4s-connector-sqs",
    libraryDependencies ++= Seq(
      "io.laserdisc" %% "pure-sqs-tagless" % Versions.Laserdisc,
      "org.typelevel" %% "log4cats-core" % Versions.Log4Cats
    ) ++ awsSnykOverrides ++ nettySnykOverrides
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
      "ru.tinkoff" %% "phobos-core" % "0.20.0"
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
    ) ++ awsSnykOverrides ++ nettySnykOverrides
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

lazy val commonSettings = Seq(
  organization := "com.ocadotechnology",
  compilerOptions,
  Test / fork := true,
  libraryDependencies ++= compilerPlugins,
  // mimaPreviousArtifacts := Seq(), // TODO
  libraryDependencies ++= Seq(
    "com.disneystreaming" %% "weaver-cats" % Versions.Weaver,
    "com.disneystreaming" %% "weaver-framework" % Versions.Weaver,
    "com.disneystreaming" %% "weaver-scalacheck" % Versions.Weaver
  ).map(_ % Test),
  testFrameworks += new TestFramework("weaver.framework.CatsEffect")
)

val compilerOptions =
  scalacOptions -= "-Xfatal-warnings"

val compilerPlugins = Seq(
  compilerPlugin("org.typelevel" %% "kind-projector" % "0.13.2" cross CrossVersion.full),
  compilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")
)
