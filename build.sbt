name := "http4s-finagle"
organization := "com.github.yaroot"
scalaVersion := "2.13.3"
crossScalaVersions := Seq("2.12.11", "2.13.3")

scalacOptions in (Compile, console) --= Seq("-Ywarn-unused:imports", "-Xfatal-warnings")

resolvers += "gh-maven" at "https://yaroot.github.io/packages/maven2"

libraryDependencies ++= {
  val FinagleVersion           = "20.9.0"
  val Http4sVersion            = "0.21.8"
  val Slf4jVersion             = "1.7.30"
  val CatsEffectTwitterVersion = "0.1.8"

  Seq(
    "com.github.yaroot" %% "cats-effect-interop-twitter"  % CatsEffectTwitterVersion,
    "org.http4s"        %% "http4s-client"                % Http4sVersion,
    "org.http4s"        %% "http4s-server"                % Http4sVersion,
    "com.twitter"       %% "finagle-http"                 % FinagleVersion,
    "org.http4s"        %% "http4s-blaze-client"          % Http4sVersion % Test,
    "org.http4s"        %% "http4s-circe"                 % Http4sVersion % Test,
    "org.http4s"        %% "http4s-dsl"                   % Http4sVersion % Test,
    "org.http4s"        %% "http4s-testing"               % Http4sVersion % Test,
    "org.slf4j"          % "slf4j-simple"                 % Slf4jVersion  % Test,
    "io.monix"          %% "minitest"                     % "2.8.2",
    "com.codecommit"    %% "cats-effect-testing-minitest" % "0.4.1"
  )
}

addCompilerPlugin("org.typelevel"    % "kind-projector"     % "0.11.0" cross CrossVersion.full)
addCompilerPlugin("com.olegpy"      %% "better-monadic-for" % "0.3.1")
addCompilerPlugin("com.github.cb372" % "scala-typed-holes"  % "0.1.5" cross CrossVersion.full)

scalafmtOnCompile := true
cancelable in Global := true

testFrameworks += new TestFramework("minitest.runner.Framework")

version ~= (_.replace('+', '-'))
dynver ~= (_.replace('+', '-'))
