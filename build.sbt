organization := "com.example"
version := "0.1"
scalaVersion := "2.12.6"
scalacOptions := Seq(
  "-unchecked",
  "-deprecation",
  "-encoding",
  "utf8",
  "-Ypartial-unification",
  "-feature",
  "-language:higherKinds"
)

//resolvers += Resolver.sonatypeRepo("releases")

mainClass in Compile := Some("com.example.jgitexample.Main")

libraryDependencies ++= {
  val FinagleVersion = "18.5.0"
  val Http4sVersion = "0.18.10"
  val Specs2Version = "4.2.0"
  val LogbackVersion = "1.2.3"
  val rhoVersion = "0.18.0"
  Seq(
    // "io.frees"            %% "frees-core"                   % "0.6.1"
//    "org.aws4s"      %% "aws4s"               % "0.5.2",
//    "org.http4s"     %% "rho-core"            % rhoVersion,
//    "org.http4s"     %% "rho-swagger"         % rhoVersion,
//    "org.http4s"     %% "rho-hal"             % rhoVersion,
    "org.http4s"     %% "http4s-blaze-client" % Http4sVersion,
    "org.http4s"     %% "http4s-circe"        % Http4sVersion,
    "org.http4s"     %% "http4s-dsl"          % Http4sVersion,
    "io.circe"       %% "circe-generic"       % "0.9.3",
    "com.twitter"    %% "finagle-http"        % FinagleVersion,
    "org.specs2"     %% "specs2-core"         % Specs2Version % "test",
    //    "org.slf4j"      %  "slf4j-simple"        % "1.7.25"
    "ch.qos.logback" % "logback-classic"      % LogbackVersion
  )
}

//libraryDependencies += "ch.epfl.scala" %% "collection-strawman" % "0.8.0"
//libraryDependencies += "ch.epfl.scala" %% "collections-contrib" % "0.8.0" // optional

//addCompilerPlugin("org.scalameta" % "paradise" % "3.0.0-M11" cross CrossVersion.full)
scalacOptions += "-Ypartial-unification"
//scalacOptions += "-Xplugin-require:macroparadise"
scalacOptions in (Compile, console) := Seq()

assemblyMergeStrategy in assembly := {
  case PathList("META-INF", "io.netty.versions.properties") => MergeStrategy.last
  case x => MergeStrategy.defaultMergeStrategy(x)
}
