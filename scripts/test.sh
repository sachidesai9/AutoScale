#!/usr/bin/env bash
# Compiles and runs the JUnit 5 tests. Usage: scripts/test.sh [unit|integration|all]   (default: all)
set -euo pipefail
cd "$(dirname "$0")/.."
WHAT=${1:-all}
[ -f dist/autoscalesim-lab.jar ] || scripts/build.sh
rm -rf build/test && mkdir -p build/test
CP="build/original:build/extension:lib/*:test-lib/*"
javac --release 17 -encoding UTF-8 -cp "$CP" -d build/test extension/test/budgetguard/*.java
TAGS=()
[ "$WHAT" = unit ] && TAGS=(--exclude-tag integration)
[ "$WHAT" = integration ] && TAGS=(--include-tag integration)
java -cp "$CP:build/test" org.junit.platform.console.ConsoleLauncher execute --scan-classpath build/test \
  "${TAGS[@]}" --details=tree --disable-banner --disable-ansi-colors --reports-dir build/test-reports
