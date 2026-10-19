#!/usr/bin/env bash
# Renders mermaid goldens for every oracle chart into oracle/goldens/.
# Uses mermaid-cli with the system Chrome (see puppeteer-config.json).
# First run installs @mermaid-js/mermaid-cli into oracle/tools/node_modules.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
TOOLS=$ROOT/oracle/tools
cd "$TOOLS"
if [ ! -d node_modules/@mermaid-js ]; then
  npm install --no-fund --no-audit @mermaid-js/mermaid-cli
fi
for chart in "$ROOT"/oracle/charts/*.mmd; do
  id=$(basename "$chart" .mmd)
  npx --no-install mmdc -p "$TOOLS/puppeteer-config.json" -c "$TOOLS/mermaid-config.json" --cssFile "$TOOLS/golden.css" \
    -i "$chart" -o "$ROOT/oracle/goldens/$id.png" -b "#FFFCF0" -s 2 --quiet
  # The SVG is the structural oracle for the layout comparison.
  npx --no-install mmdc -p "$TOOLS/puppeteer-config.json" -c "$TOOLS/mermaid-config.json" --cssFile "$TOOLS/golden.css" \
    -i "$chart" -o "$ROOT/oracle/goldens/$id.svg" -b "#FFFCF0" --quiet
  echo "golden: $id (png + svg)"
done

# Extract the geometry (node ids/shapes/boxes, edges, labels, clusters) that the
# layout oracle compares Maradona's IL against.
"$ROOT/oracle/tools/.venv/bin/python" "$TOOLS/golden_geometry.py"
