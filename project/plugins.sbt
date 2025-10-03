// http4s organization
addSbtPlugin("org.http4s" % "sbt-http4s-org" % "2.0.1")

// ScalaDoc API mapping
addSbtPlugin("com.thoughtworks.sbt-api-mappings" % "sbt-api-mappings" % "3.0.2")

// ScalaPB Reactor
addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.8")
libraryDependencies += "kr.ikhoon.scalapb-reactor" %% "scalapb-reactor-codegen" % "0.3.0"

// fs2-grpc
addSbtPlugin("org.lyranthe.fs2-grpc" % "sbt-java-gen" % "1.0.1")
