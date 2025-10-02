import xerial.sbt.Sonatype.*

lazy val scala213 = "2.13.16"
lazy val scala3 = "3.3.6"
lazy val supportedScalaVersions = List(scala213, scala3)

ThisBuild / scalaVersion     := scala213
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


lazy val playJsonVersion = "3.0.5"
lazy val playWsVersion = "3.0.6"
lazy val pekkoVersion = "1.1.3"
lazy val pekkoHttpVersion = "1.1.0"
lazy val metricsVersion = "4.2.12"
lazy val excludesJackson = Seq(
  ExclusionRule(organization = "com.fasterxml.jackson.core"),
  ExclusionRule(organization = "com.fasterxml.jackson.datatype"),
  ExclusionRule(organization = "com.fasterxml.jackson.dataformat")
)

scalacOptions ++= {
  CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, 13)) => Seq(
      "-feature",
      "-language:higherKinds",
      "-language:implicitConversions",
      "-language:existentials",
      "-language:postfixOps",
      "-Xsource:3"
    )
    case Some((3, _)) => Seq(
      "-feature",
      "-language:higherKinds",
      "-language:implicitConversions",
      "-language:existentials",
      "-language:postfixOps",
      "-source:3.3-migration"
    )
    case _ => Seq(
      "-feature",
      "-language:higherKinds",
      "-language:implicitConversions",
      "-language:existentials",
      "-language:postfixOps"
    )
  }
}

lazy val root = (project in file("."))
  .settings(
    name := "wasm4s",
    crossScalaVersions := supportedScalaVersions,
    //githubOwner := "MAIF",
    //githubRepository := "wasm4s",
    //githubTokenSource := TokenSource.Environment("GITHUB_TOKEN"),
    libraryDependencies ++= Seq(
      "org.playframework"     %% "play-ws"        % playWsVersion % "provided",
      "org.playframework"     %% "play-json"      % playJsonVersion % "provided",
      "org.apache.pekko"      %% "pekko-stream"   % pekkoVersion % "provided",
      "org.apache.pekko"      %% "pekko-http"     % pekkoHttpVersion % "provided",
      "org.apache.pekko"      %% "pekko-actor"    % pekkoVersion % "provided",
      "org.apache.pekko"      %% "pekko-actor-typed" % pekkoVersion % "provided",
      "org.apache.pekko"      %% "pekko-serialization-jackson" % pekkoVersion % "provided",
      "org.apache.pekko"      %% "pekko-slf4j"    % pekkoVersion % "provided",
      "org.playframework"     %% "play-json-joda" % playJsonVersion % "provided",
      "org.lz4"               % "lz4-java"        % "1.8.0" % "provided",
      "com.auth0"             % "java-jwt"        % "4.2.0" % "provided" excludeAll (excludesJackson: _*),
      "commons-codec"         % "commons-codec"   % "1.16.0" % "provided",
      "net.java.dev.jna"      % "jna"             % "5.13.0" % "provided",
      "com.google.code.gson"  % "gson"            % "2.10" % "provided",
      "io.dropwizard.metrics" % "metrics-json"    % metricsVersion % "provided" excludeAll (excludesJackson: _*), // Apache 2.0
      "org.scalameta"        %% "munit"           % "0.7.29" % Test,
    ),
  )

usePgpKeyHex("4EFDC6FC2DEC936B13B7478C2F8C0F4E1D397E7F")
sonatypeProjectHosting := Some(GitHubHosting("MAIF", "wasm4s", "mathieu.ancelin@serli.com"))
sonatypeRepository := "https://ossrh-staging-api.central.sonatype.com/service/local/"
sonatypeCredentialHost := sonatypeCentralHost
publishTo := sonatypePublishToBundle.value

assembly / artifact := {
  val art = (assembly / artifact).value
  art.withClassifier(Some("bundle"))
}

addArtifact(assembly / artifact, assembly)
assembly / test := {}
assembly / assemblyJarName := s"wasm4s-bundle_${scalaVersion.value.split("\\.").init.mkString(".")}-${version.value}.jar"
