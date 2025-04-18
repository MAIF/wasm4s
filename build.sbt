import Dependencies.munit
import xerial.sbt.Sonatype._

lazy val scala212 = "2.12.20"
lazy val scala213 = "2.13.11"
lazy val supportedScalaVersions = List(scala212, scala213)

ThisBuild / scalaVersion     := scala212
ThisBuild / organization     := "fr.maif"

inThisBuild(
  List(
    description := "Library to run wasm vm in a scala app",
    startYear := Some(2023),
    organization := "fr.maif",
    sonatypeProfileName := "fr.maif",
    publishMavenStyle := true,
    homepage := Some(url("https://github.com/MAIF/wasm4s")),
    licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/MAIF/wasm4s"),
        "scm:git@github.com:MAIF/wasm4s.git"
      )
    ),
    developers := List(
      Developer(
        "mathieuancelin",
        "Mathieu Ancelin",
        "mathieu.ancelin@serli.com",
        url("https://github.com/mathieuancelin")
      )
    )
  )
)


lazy val playJsonVersion = "2.9.3"
lazy val playWsVersion = "2.8.19"
lazy val akkaVersion = "2.6.20"
lazy val akkaHttpVersion = "10.2.10"
lazy val metricsVersion = "4.2.12"
lazy val excludesJackson = Seq(
  ExclusionRule(organization = "com.fasterxml.jackson.core"),
  ExclusionRule(organization = "com.fasterxml.jackson.datatype"),
  ExclusionRule(organization = "com.fasterxml.jackson.dataformat")
)

scalacOptions ++= Seq(
  "-feature",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-language:existentials",
  "-language:postfixOps"
)

lazy val root = (project in file("."))
  .settings(
    name := "wasm4s",
    crossScalaVersions := supportedScalaVersions,
    //githubOwner := "MAIF",
    //githubRepository := "wasm4s",
    //githubTokenSource := TokenSource.Environment("GITHUB_TOKEN"),
    libraryDependencies ++= Seq(
      munit % Test,
      "com.typesafe.play"     %% "play-ws"        % playWsVersion % "provided",
      "com.typesafe.play"     %% "play-json"      % playJsonVersion % "provided",
      "com.typesafe.akka"     %% "akka-stream"    % akkaVersion % "provided",
      "com.typesafe.akka"     %% "akka-http"      % akkaHttpVersion % "provided",
      "com.typesafe.play"     %% "play-json-joda" % playJsonVersion % "provided",
      "org.lz4"               %%"lz4-java"        % "1.8.0" % "provided",
      "com.auth0"             % "java-jwt"        % "4.2.0" % "provided" excludeAll (excludesJackson: _*),
      "commons-codec"         % "commons-codec"   % "1.16.0" % "provided",
      "net.java.dev.jna"      % "jna"             % "5.13.0" % "provided",
      "com.google.code.gson"  % "gson"            % "2.10" % "provided",
      "io.dropwizard.metrics" % "metrics-json"    % metricsVersion % "provided" excludeAll (excludesJackson: _*), // Apache 2.0
    ),
  )

usePgpKeyHex("4EFDC6FC2DEC936B13B7478C2F8C0F4E1D397E7F")
sonatypeProjectHosting := Some(GitHubHosting("MAIF", "wasm4s", "mathieu.ancelin@serli.com"))
sonatypeRepository := "https://s01.oss.sonatype.org/service/local"
publishTo := sonatypePublishToBundle.value
sonatypeCredentialHost := "s01.oss.sonatype.org"

assembly / artifact := {
  val art = (assembly / artifact).value
  art.withClassifier(Some("bundle"))
}

addArtifact(assembly / artifact, assembly)
assembly / test := {}
assembly / assemblyJarName := s"wasm4s-bundle_${scalaVersion.value.split("\\.").init.mkString(".")}-${version.value}.jar"
