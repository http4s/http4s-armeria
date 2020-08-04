import sbt.Keys.{libraryDependencies, scalacOptions}

inThisBuild(
  Seq(
    organization := "org.http4s",
    name := "http4s-armeria",
    crossScalaVersions := Seq("2.13.2", "2.12.11"),
    scalaVersion := crossScalaVersions.value.head
  )
)

val versions = new {
  val armeria = "0.99.9"
  val circe = "0.13.0"
  val fs2 = "2.4.2"
  val http4s = "0.21.6"
  val logback = "1.2.3"
  val micrometer = "1.5.3"
  val scalaTest = "3.2.0"
  val silencer = "1.7.1"
}

lazy val server = project
  .settings(
    name := "http4s-armeria-server",
    libraryDependencies ++= List(
      "com.linecorp.armeria" % "armeria" % versions.armeria,
      "co.fs2" %% "fs2-reactive-streams" % versions.fs2,
      "org.http4s" %% "http4s-server" % versions.http4s,
      "ch.qos.logback" % "logback-classic" % versions.logback % Test,
      "org.http4s" %% "http4s-dsl" % versions.http4s % Test,
      "org.scalatest" %% "scalatest" % versions.scalaTest % Test
    )
  )

lazy val exampleArmeriaHttp4s = project
  .in(file("examples/armeria-http4s"))
  .settings(
    name := "examples-armeria-http4s",
    scalacOptions += "-P:silencer:pathFilters=.*.html",
    libraryDependencies ++= List(
      "ch.qos.logback" % "logback-classic" % versions.logback % Runtime,
      "io.circe" %% "circe-core" % versions.circe,
      "io.micrometer" % "micrometer-registry-prometheus" % versions.micrometer,
      "org.http4s" %% "http4s-circe" % versions.http4s,
      "org.http4s" %% "http4s-dsl" % versions.http4s,
      "org.http4s" %% "http4s-twirl" % versions.http4s,
      "org.http4s" %% "http4s-scala-xml" % versions.http4s,
      ("com.github.ghik" % "silencer-lib" % versions.silencer % Provided).cross(CrossVersion.full),
      compilerPlugin(
        ("com.github.ghik" % "silencer-plugin" % versions.silencer).cross(CrossVersion.full))
    )
  )
  .enablePlugins(SbtTwirl)
  .dependsOn(server)

lazy val exampleArmeriaScalaPB = project
  .in(file("examples/armeria-scalapb"))
  .settings(
    name := "examples-armeria-scalapb"
  )
