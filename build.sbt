ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.14"

lazy val root = (project in file("."))
  .settings(
    name := "k-clique-spark"
  )

libraryDependencies ++= Seq(
  "org.apache.spark"            %% "spark-core"                     % "3.5.2"         % Provided,
  "org.jgrapht" % "jgrapht-core" % "1.5.2",
  "org.scalatest"               %% "scalatest"                      % "3.2.19"       % Test,

)


assembly / assemblyMergeStrategy := {
  case PathList("META-INF", "services", _*) => MergeStrategy.concat
  case PathList("META-INF", xs @ _*) => MergeStrategy.discard
  case x => MergeStrategy.first
}