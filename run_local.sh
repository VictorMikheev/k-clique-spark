#!/bin/bash

#  путь где установлен Apache spark
SPARK_HOME="/Users/victormikheev/opt/spark-3.5.1"

#  Толстый  JAR с приложением  находится в папке target/scala-2.13 проекта
JARFILE="/Users/victormikheev/projects/k-clique-spark/target/scala-2.13/k-clique-spark-assembly-0.1.0-SNAPSHOT.jar"

"${SPARK_HOME}/bin/spark-submit" \
  --class "KCliqueSpark" \
  --master "local[4]" \
  --driver-memory 16g \
  --conf "spark.executor.memory=4g" \
  "$JARFILE" \
  "$@"
