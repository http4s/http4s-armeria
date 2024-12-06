import sbt.Keys.{libraryDependencies, sourceManaged}
import sbtprotoc.ProtocPlugin.autoImport.PB

ThisBuild / tlBaseVersion := "1.0"
ThisBuild / developers := List(
  Developer(
    "ikhoon",
    "Ikhun Um",
    "ih.pert@gmail.com",
    url("https://github.com/ikhoon")
  )
)
ThisBuild / crossScalaVersions := Seq("2.13.15", "3.3.4")
ThisBuild / scalaVersion := crossScalaVersions.value.head
ThisBuild / tlCiReleaseBranches := Seq("series/1.x")
ThisBuild / tlVersionIntroduced := Map("3" -> "0.5.0")
ThisBuild / homepage := Some(url("https://github.com/http4s/http4s-armeria"))
ThisBuild / licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0"))
ThisBuild / startYear := Some(2020)
ThisBuild / resolvers += Resolver.mavenLocal
ThisBuild / Test / javaOptions += "-Dcom.linecorp.armeria.verboseResponses=true -Dcom.linecorp.armeria.verboseExceptions=always"

val versions = new {
  val armeria = "1.31.1"
  val fs2 = "3.11.0"
  val http4s = "1.0.0-M44"
  val log4cats = "2.7.0"
  val logback = "1.2.13"
  val micrometer = "1.9.2"
  val munit = "1.0.2"
  val catsEffectMunit = "2.0.0"
}

val munit = Seq(
  "org.scalameta" %% "munit" % versions.munit % Test,
  "org.typelevel" %% "munit-cats-effect" % versions.catsEffectMunit % Test
)

lazy val root = project
  .in(file("."))
  .enablePlugins(NoPublishPlugin)
  .settings(
    // Root project
    name := "http4s-armeria",
    description := " Armeria backend for http4s"
  )
  .aggregate(server, client)

lazy val server = project
  .settings(
    name := "http4s-armeria-server",
    libraryDependencies ++= List(
      "com.linecorp.armeria" % "armeria" % versions.armeria,
      "co.fs2" %% "fs2-reactive-streams" % versions.fs2,
      "org.http4s" %% "http4s-server" % versions.http4s,
      "org.typelevel" %% "log4cats-slf4j" % versions.log4cats % Test,
      "ch.qos.logback" % "logback-classic" % versions.logback % Test,
      "org.http4s" %% "http4s-dsl" % versions.http4s % Test
    ) ++ munit
  )

lazy val client = project
  .settings(
    name := "http4s-armeria-client",
    libraryDependencies ++= List(
      "com.linecorp.armeria" % "armeria" % versions.armeria,
      "co.fs2" %% "fs2-reactive-streams" % versions.fs2,
      "org.http4s" %% "http4s-client" % versions.http4s,
      "ch.qos.logback" % "logback-classic" % versions.logback % Test
    ) ++ munit
  )

lazy val exampleArmeriaHttp4s = project
  .in(file("examples/armeria-http4s"))
  .settings(
    name := "examples-armeria-http4s",
    crossScalaVersions := Seq("2.13.15"),
    scalaVersion := crossScalaVersions.value.head,
    libraryDependencies ++= List(
      "ch.qos.logback" % "logback-classic" % versions.logback % Runtime,
      "io.micrometer" % "micrometer-registry-prometheus" % versions.micrometer,
      "org.http4s" %% "http4s-dsl" % versions.http4s
    ),
    headerSources / excludeFilter := AllPassFilter
  )
  .enablePlugins(NoPublishPlugin)
  .dependsOn(server)

lazy val exampleArmeriaScalaPB = project
  .in(file("examples/armeria-scalapb"))
  .settings(
    name := "examples-armeria-scalapb",
    crossScalaVersions := Seq("2.13.15"),
    scalaVersion := crossScalaVersions.value.head,
    libraryDependencies ++= List(
      "ch.qos.logback" % "logback-classic" % versions.logback % Runtime,
      "com.linecorp.armeria" % "armeria-grpc" % versions.armeria,
      "com.linecorp.armeria" %% "armeria-scalapb" % versions.armeria,
      "org.http4s" %% "http4s-dsl" % versions.http4s,
      "com.thesamet.scalapb.common-protos" %% "proto-google-common-protos-scalapb_0.11" % "2.5.0-3" % "protobuf",
      "com.thesamet.scalapb.common-protos" %% "proto-google-common-protos-scalapb_0.11" % "2.5.0-3"
    ) ++ munit,
    Compile / PB.targets := Seq(
      scalapb.gen() -> (Compile / sourceManaged).value,
      scalapb.reactor.ReactorCodeGenerator -> (Compile / sourceManaged).value
    ),
    headerSources / excludeFilter := AllPassFilter,
    unusedCompileDependenciesTest := {}
  )
  .enablePlugins(NoPublishPlugin)
  .dependsOn(server)

lazy val exampleArmeriaFs2Grpc = project
  .in(file("examples/armeria-fs2grpc"))
  .settings(
    name := "examples-armeria-fs2grpc",
    crossScalaVersions := Seq("2.13.15"),
    scalaVersion := crossScalaVersions.value.head,
    libraryDependencies ++= List(
      "ch.qos.logback" % "logback-classic" % versions.logback % Runtime,
      "com.linecorp.armeria" % "armeria-grpc" % versions.armeria,
      "com.linecorp.armeria" %% "armeria-scalapb" % versions.armeria,
      "org.http4s" %% "http4s-dsl" % versions.http4s,
      "com.thesamet.scalapb.common-protos" %% "proto-google-common-protos-scalapb_0.11" % "2.5.0-3" % "protobuf",
      "com.thesamet.scalapb.common-protos" %% "proto-google-common-protos-scalapb_0.11" % "2.5.0-3"
    ) ++ munit,
    headerSources / excludeFilter := AllPassFilter
  )
  .enablePlugins(NoPublishPlugin, Fs2Grpc)
  .dependsOn(server)
