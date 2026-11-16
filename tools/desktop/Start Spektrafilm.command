#!/bin/bash
# Spektrafilm desktop editor launcher. GPLv3.
# Locates the project from this file's location and runs the local Python server.
# It changes nothing outside the project and does not open any network port other
# than the 127.0.0.1 loopback socket chosen by run.py.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DESK="$ROOT/tools/desktop"

if ! command -v python3 >/dev/null 2>&1; then
  echo "需要 Python 3（未找到 python3）。" >&2
  exit 1
fi
if [ ! -f "$DESK/run.py" ]; then
  echo "缺少 $DESK/run.py" >&2
  exit 1
fi

cd "$DESK"
exec python3 "$DESK/run.py" "$@"
