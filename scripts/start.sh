#!/usr/bin/env bash
# Starts the dashboard on http://localhost:8080 (builds first if needed). Data is kept in ./data.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f dist/autoscalesim-lab.jar ] || scripts/build.sh
exec java -jar dist/autoscalesim-lab.jar serve --port "${PORT:-8080}" --data-dir "${DATA_DIR:-data}"
