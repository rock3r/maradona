#!/usr/bin/env bash
# Regenerates the oracle web report and serves it locally.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
"$ROOT/oracle/tools/.venv/bin/python" "$ROOT/oracle/tools/web_report.py"
PORT=${1:-8931}
URL="http://localhost:$PORT/out/web/"
( sleep 1; open "$URL" ) &
cd "$ROOT/oracle"
echo "serving $URL (Ctrl+C to stop)"
python3 -m http.server "$PORT"
