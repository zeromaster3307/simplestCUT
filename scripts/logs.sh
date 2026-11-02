#!/usr/bin/env bash
# Zeigt die Logs der App (live). Mit "-c" vorher das Log leeren, mit "-d" nur bisheriges ausgeben.
set -euo pipefail
source "$(dirname "$0")/env.sh"
require_device
if [[ "${1:-}" == "-c" ]]; then "$ADB" logcat -c; shift; fi
PID="$("$ADB" shell pidof -s "$PKG" || true)"
if [[ -n "$PID" ]]; then
    "$ADB" logcat --pid="$PID" "$@"
else
    echo "App läuft nicht, zeige Abstürze und AndroidRuntime-Fehler:"
    "$ADB" logcat -b crash -b main -s AndroidRuntime:E "$@"
fi
