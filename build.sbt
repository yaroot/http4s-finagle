organization := "org.http4s"
version := "0.1"
scalaVersion := "2.12.8"
scalacOptions := Seq(
  "-unchecked",
  "-deprecation",
  "-encoding",
  "utf8",
  "-Ypartial-unification",
  "-feature",
  "-language:higherKinds"
)

libraryDependencies ++= {
  val FinagleVersion = "19.3.0"
  val Http4sVersion = "0.20.0-RC1"
  val Specs2Version = "4.5.1"
  val LogbackVersion = "1.2.3"
  Seq(
    "org.http4s"     %% "http4s-blaze-client" % Http4sVersion,
    "org.http4s"     %% "http4s-circe"        % Http4sVersion,
    "org.http4s"     %% "http4s-dsl"          % Http4sVersion,
    "com.twitter"    %% "finagle-http"        % FinagleVersion,
    "org.specs2"     %% "specs2-core"         % Specs2Version % "test",
    //    "org.slf4j"      %  "slf4j-simple"        % "1.7.25"
    "ch.qos.logback" % "logback-classic"      % LogbackVersion
  )
}

scalacOptions += "-Ypartial-unification"
