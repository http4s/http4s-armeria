// http4s organization
addSbtPlugin("org.http4s" % "sbt-http4s-org" % "0.2.1")
// Scalac options
addSbtPlugin("io.github.davidgregory084" % "sbt-tpolecat" % "0.1.20")
// Release
addSbtPlugin("com.github.sbt" % "sbt-release" % "1.0.15")
addSbtPlugin("com.jsuereth" % "sbt-pgp" % "2.1.1")
// ScalaDoc API mapping
addSbtPlugin("com.thoughtworks.sbt-api-mappings" % "sbt-api-mappings" % "3.0.0")

// ScalaPB Reactor
addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.4")
libraryDependencies += "kr.ikhoon.scalapb-reactor" %% "scalapb-reactor-codegen" % "0.2.0"

// fs2-grpc
addSbtPlugin("org.lyranthe.fs2-grpc" % "sbt-java-gen" % "0.8.0")
