#!/usr/bin/env bash
# Screenshot vom Handy nach screenshots/<zeit>.png (oder angegebener Pfad).
set -euo pipefail
source "$(dirname "$0")/env.sh"
require_device
OUT="${1:-$ROOT/screenshots/$(date +%Y%m%d-%H%M%S).png}"
mkdir -p "$(dirname "$OUT")"
"$ADB" exec-out screencap -p > "$OUT"
echo "$OUT"
