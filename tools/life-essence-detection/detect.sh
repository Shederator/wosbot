#!/usr/bin/env bash
# Renders Life Essence marker detection onto PNG copies of the given frames.
set -euo pipefail

root=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
cd "$root"

./mvnw -pl modules/tasks -am compile -DskipTests -q

source="tools/life-essence-detection/src/main/java/dev/frostguard/tools/lifeessence/LifeEssenceDetectionTool.java"
classes="tools/life-essence-detection/target/classes"
mkdir -p "$classes"
classpath="modules/tasks/target/classes:modules/vision/target/classes:modules/api/target/classes"
javac --release 21 -classpath "$classpath" -d "$classes" "$source"
exec java -classpath "$classes:$classpath" dev.frostguard.tools.lifeessence.LifeEssenceDetectionTool "$@"
