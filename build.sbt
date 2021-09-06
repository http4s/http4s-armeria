import sbt.Keys.{libraryDependencies, sourceManaged}
import sbtprotoc.ProtocPlugin.autoImport.PB
import sbtrelease.ReleasePlugin.autoImport._

inThisBuild(
  Seq(
    organization := "org.http4s",
    crossScalaVersions := Seq("2.13.6", "2.12.14"),
    scalaVersion := crossScalaVersions.value.head,
    homepage := Some(url("https://github.com/http4s/http4s-armeria")),
    licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    startYear := Some(2020),
    resolvers += Resolver.mavenLocal,
    Test / javaOptions += "-Dcom.linecorp.armeria.verboseResponses=true -Dcom.linecorp.armeria.verboseExceptions=always"
  )
)

val versions = new {
  val armeria = "1.11.0"
  val fs2 = "3.1.1"
  val http4s = "0.23.1"
  val logback = "1.2.5"
  val micrometer = "1.7.3"
  val munit = "0.7.29"
  val catsEffectMunit = "1.0.5"
}

val munit = Seq(
  "org.scalameta" %% "munit" % versions.munit % Test,
  "org.typelevel" %% "munit-cats-effect-3" % versions.catsEffectMunit % Test
)

lazy val root = project
  .in(file("."))
  .enablePlugins(PrivateProjectPlugin)
  .settings(
    // Root project
    name := "http4s-armeria",
    description := " Armeria backend for http4s"
  )
  .aggregate(server, client, exampleArmeriaHttp4s, exampleArmeriaScalaPB)

lazy val server = project
  .settings(publishSettings: _*)
  .settings(
    name := "http4s-armeria-server",
    libraryDependencies ++= List(
      "com.linecorp.armeria" % "armeria" % versions.armeria,
      "co.fs2" %% "fs2-reactive-streams" % versions.fs2,
      "org.http4s" %% "http4s-server" % versions.http4s,
      "ch.qos.logback" % "logback-classic" % versions.logback % Test,
      "org.http4s" %% "http4s-dsl" % versions.http4s % Test,
    ) ++ munit
  )

lazy val client = project
  .settings(publishSettings: _*)
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
    libraryDependencies ++= List(
      "ch.qos.logback" % "logback-classic" % versions.logback % Runtime,
      "io.micrometer" % "micrometer-registry-prometheus" % versions.micrometer,
      "org.http4s" %% "http4s-dsl" % versions.http4s
    )
  )
  .enablePlugins(PrivateProjectPlugin)
  .dependsOn(server)

lazy val exampleArmeriaScalaPB = project
  .in(file("examples/armeria-scalapb"))
  .settings(
    name := "examples-armeria-scalapb",
    libraryDependencies ++= List(
      "ch.qos.logback" % "logback-classic" % versions.logback % Runtime,
      "com.linecorp.armeria" % "armeria-grpc" % versions.armeria,
      "com.linecorp.armeria" %% "armeria-scalapb" % versions.armeria,
      "org.http4s" %% "http4s-dsl" % versions.http4s,
      "com.thesamet.scalapb.common-protos" %% "proto-google-common-protos-scalapb_0.11" % "2.5.0-2" % "protobuf",
      "com.thesamet.scalapb.common-protos" %% "proto-google-common-protos-scalapb_0.11" % "2.5.0-2"
    ) ++ munit,
    Compile / PB.targets := Seq(
      scalapb.gen() -> (Compile / sourceManaged).value,
      scalapb.reactor.ReactorCodeGenerator -> (Compile / sourceManaged).value
    )
  )
  .enablePlugins(PrivateProjectPlugin)
  .dependsOn(server)

lazy val exampleArmeriaFs2Grpc = project
  .in(file("examples/armeria-fs2grpc"))
  .settings(
    name := "examples-armeria-fs2grpc",
    libraryDependencies ++= List(
      "ch.qos.logback" % "logback-classic" % versions.logback % Runtime,
      "com.linecorp.armeria" % "armeria-grpc" % versions.armeria,
      "com.linecorp.armeria" %% "armeria-scalapb" % versions.armeria,
      "org.http4s" %% "http4s-dsl" % versions.http4s
    ) ++ munit
  )
  .enablePlugins(PrivateProjectPlugin, Fs2Grpc)
  .dependsOn(server)

lazy val publishSettings = List(
  scmInfo := Some(
    ScmInfo(
      url("https://github.com/http4s/http4s-armeria"),
      "git@github.com:http4s/http4s-armeria.git")),
  developers := List(
    Developer(
      "ikhoon",
      "Ikhun Um",
      "ih.pert@gmail.com",
      url("https://github.com/ikhoon")
    )
  ),
  publishTo := {
    if (isSnapshot.value)
      Some(Opts.resolver.sonatypeSnapshots)
    else
      Some(Opts.resolver.sonatypeStaging)
  },
  releasePublishArtifactsAction := PgpKeys.publishSigned.value,
  publishMavenStyle := true,
  pomIncludeRepository := { _ => false },
  Test / publishArtifact := false,
  credentials ++= (for {
    username <- sys.env.get("SONATYPE_USERNAME")
    password <- sys.env.get("SONATYPE_PASSWORD")
  } yield Credentials(
    "Sonatype Nexus Repository Manager",
    "oss.sonatype.org",
    username,
    password
  ))
)
