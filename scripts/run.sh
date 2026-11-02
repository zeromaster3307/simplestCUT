#!/usr/bin/env bash
# Baut die Debug-APK, installiert sie aufs Handy und startet die App.
set -euo pipefail
source "$(dirname "$0")/env.sh"
require_device
cd "$ROOT"
if ! OUT="$(./gradlew assembleDebug --console=plain -q 2>&1)"; then
    echo "$OUT"
    echo "BAUEN FEHLGESCHLAGEN"
    exit 1
fi
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" shell am force-stop "$PKG"
"$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
echo "App gestartet."
