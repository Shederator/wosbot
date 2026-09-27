#!/usr/bin/env bash
# Annotates detected close crosses or benchmarks detection on saved PNG frames.
set -euo pipefail

root=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
cd "$root"

./mvnw -pl modules/tasks -am compile -DskipTests -q

support="tools/detection-tool-support/src/main/java/dev/frostguard/tools/detection/DetectionToolSupport.java"
source="tools/close-cross-detection/src/main/java/dev/frostguard/tools/closecross/CloseCrossDetectionTool.java"
classes="tools/close-cross-detection/target/classes"
mkdir -p "$classes"
opencv=$(find "$HOME/.m2/repository/org/openpnp/opencv" -name 'opencv-*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' | sort | tail -1)
slf4j=$(find "$HOME/.m2/repository/org/slf4j/slf4j-api" -name 'slf4j-api-*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' | sort | tail -1)
classpath="modules/vision/target/classes:modules/api/target/classes:${opencv}:${slf4j}"
javac --release 21 -classpath "$classpath" -d "$classes" "$support" "$source"
exec java -classpath "$classes:$classpath" dev.frostguard.tools.closecross.CloseCrossDetectionTool "$@"
