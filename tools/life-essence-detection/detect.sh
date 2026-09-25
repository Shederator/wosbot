#!/usr/bin/env bash
# Renders Life Essence marker detection onto PNG copies of the given frames.
set -euo pipefail

root=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
cd "$root"

./mvnw -pl modules/tasks -am compile -DskipTests -q

source="tools/life-essence-detection/src/main/java/dev/frostguard/tools/lifeessence/LifeEssenceDetectionTool.java"
classes="tools/life-essence-detection/target/classes"
mkdir -p "$classes"
opencv=$(find "$HOME/.m2/repository/org/openpnp/opencv" -name 'opencv-*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' | sort | tail -1)
slf4j=$(find "$HOME/.m2/repository/org/slf4j/slf4j-api" -name 'slf4j-api-*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' | sort | tail -1)
classpath="modules/tasks/target/classes:modules/vision/target/classes:modules/api/target/classes:${opencv}:${slf4j}"
javac --release 21 -classpath "$classpath" -d "$classes" "$source"
exec java -classpath "$classes:$classpath" dev.frostguard.tools.lifeessence.LifeEssenceDetectionTool "$@"
