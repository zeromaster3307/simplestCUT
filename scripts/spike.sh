#!/usr/bin/env bash
# Startet einen Schnitt-Test auf dem Handy und wartet auf das Ergebnis.
# Aufruf: scripts/spike.sh <quelle auf handy> <ziel auf handy> <start ms> <ende ms> <smart|editlist|plain|app> [weitere am-start-Argumente, z. B. --es segs 1000-2000,3000-4000]
set -euo pipefail
source "$(dirname "$0")/env.sh"
require_device
"$ADB" logcat -c
"$ADB" shell am start -W -n "$PKG/app.simplestcut.debug.SpikeActivity" --es src "$1" --es out "$2" --el start "$3" --el end "$4" --es mode "$5" "${@:6}" >/dev/null </dev/null
for _ in $(seq 1 600); do
    line="$("$ADB" logcat -d -s Spike:I | grep -E ' (OK|FEHLER) ' || true)"
    if [[ -n "$line" ]]; then echo "${line#*Spike   : }"; exit 0; fi
    sleep 0.5
done
echo "Zeitüberschreitung"; exit 1
