#!/usr/bin/env bash
# Builds the unmodified AutoScaleSim sources (Java 8 target, as in the authors' nbproject) and the extension
# (Java 17), then packages dist/autoscalesim-lab.jar. Run from anywhere; needs JDK 17 or newer.
set -euo pipefail
cd "$(dirname "$0")/.."
rm -rf build/original build/extension dist
mkdir -p build/original build/extension dist/lib
find src -name '*.java' > build/sources-original.txt
javac --release 8 -nowarn -encoding UTF-8 -cp "lib/*" -d build/original @build/sources-original.txt
javac --release 17 -Xlint:all -Xlint:-options -Xlint:-serial -encoding UTF-8 -cp "build/original:lib/*" -d build/extension extension/src/budgetguard/*.java
mkdir -p build/extension/web && cp -r extension/web/. build/extension/web/
CLASSPATH_ATTR=$(cd lib && ls *.jar | sed 's#^#lib/#' | tr '\n' ' ')
printf 'Main-Class: budgetguard.BudgetGuard\nClass-Path: %s\n' "$CLASSPATH_ATTR" > build/manifest.txt
jar --create --file dist/autoscalesim-lab.jar --manifest build/manifest.txt -C build/original . -C build/extension .
cp lib/*.jar dist/lib/
echo "Built dist/autoscalesim-lab.jar"
