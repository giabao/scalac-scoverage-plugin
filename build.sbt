import com.typesafe.sbt.pgp.PgpKeys
import sbtrelease.ReleasePlugin

val appSettings = Seq(
  organization := "org.scoverage",
  scalaVersion := "2.11.8",
  crossScalaVersions := Seq("2.10.6", "2.11.8"),
  fork in Test := false,
  parallelExecution in Test := false,
  scalacOptions := Seq("-unchecked", "-deprecation", "-feature", "-encoding", "utf8"),
  resolvers += "releases" at "https://oss.sonatype.org/service/local/staging/deploy/maven2",
  concurrentRestrictions in Global += Tags.limit(Tags.Test, 1),
  libraryDependencies ++= Seq(
    "org.mockito" % "mockito-all" % "1.10.19" % Test,
    "org.scalatest" %% "scalatest" % "2.2.6" % Test
  )
) ++ ReleasePlugin.releaseSettings ++ Seq(
  ReleaseKeys.crossBuild := true,
  ReleaseKeys.publishArtifactsAction := PgpKeys.publishSigned.value
)

lazy val runtime = (project in file("scalac-scoverage-runtime"))
  .settings(name := "scalac-scoverage-runtime")
  .settings(appSettings: _*)

lazy val plugin = (project in file("scalac-scoverage-plugin"))
  .dependsOn(runtime % Test)
  .settings(name := "scalac-scoverage-plugin")
  .settings(appSettings: _*)
  .settings(libraryDependencies ++= Seq(
    "org.scala-lang" % "scala-reflect" % scalaVersion.value % Provided,
    "org.scala-lang" % "scala-compiler" % scalaVersion.value % Provided,
    "org.joda" % "joda-convert" % "1.8.1" % Test,
    "joda-time" % "joda-time" % "2.9.3" % Test,
    "com.typesafe.scala-logging" %% "scala-logging-slf4j" % "2.1.2" % Test
  ) ++ (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, 11)) =>
      EnvSupport.setEnv("CrossBuildScalaVersion", "2.11.8")
      Seq("org.scala-lang.modules" %% "scala-xml" % "1.0.5")
    case _ =>
      EnvSupport.setEnv("CrossBuildScalaVersion", "2.10.6")
      Nil
    })
  )

lazy val root = Project("scalac-scoverage", file("."))
  .settings(name := "scalac-scoverage")
  .settings(appSettings: _*)
  .settings(publishArtifact := false)
  .aggregate(plugin, runtime)
