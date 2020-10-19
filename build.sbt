name := "http4s-finagle"
organization := "com.github.yaroot"
scalaVersion := "2.13.3"
crossScalaVersions := Seq("2.12.12", "2.13.3")

scalacOptions in (Compile, console) --= Seq("-Ywarn-unused:imports", "-Xfatal-warnings")

libraryDependencies ++= {
  val FinagleVersion = "20.9.0"
  val Http4sVersion  = "0.21.8"
  Seq(
    "org.http4s"    %% "http4s-client"       % Http4sVersion,
    "org.http4s"    %% "http4s-server"       % Http4sVersion,
    "com.twitter"   %% "finagle-http"        % FinagleVersion,
    "org.http4s"    %% "http4s-blaze-client" % Http4sVersion % Test,
    "org.http4s"    %% "http4s-circe"        % Http4sVersion % Test,
    "org.http4s"    %% "http4s-dsl"          % Http4sVersion % Test,
    "org.http4s"    %% "http4s-testing"      % Http4sVersion % Test,
    "org.slf4j"      % "slf4j-simple"        % "1.7.30"      % Test,
    "org.typelevel" %% "munit-cats-effect"   % "0.3.0"       % Test
  )
}

addCompilerPlugin("org.typelevel"    % "kind-projector"    % "0.11.0" cross CrossVersion.full)
addCompilerPlugin("com.github.cb372" % "scala-typed-holes" % "0.1.5" cross CrossVersion.full)

scalafmtOnCompile := true
cancelable in Global := true

testFrameworks += new TestFramework("minitest.runner.Framework")

version ~= (_.replace('+', '-'))
dynver ~= (_.replace('+', '-'))
