// http4s organization
addSbtPlugin("org.http4s" % "sbt-http4s-org" % "0.14.3")

// ScalaDoc API mapping
addSbtPlugin("com.thoughtworks.sbt-api-mappings" % "sbt-api-mappings" % "3.0.2")

// ScalaPB Reactor
addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.6")
libraryDependencies += "kr.ikhoon.scalapb-reactor" %% "scalapb-reactor-codegen" % "0.3.0"

// fs2-grpc
addSbtPlugin("org.lyranthe.fs2-grpc" % "sbt-java-gen" % "0.11.2")
