#!/usr/bin/env bash
# Demo-Modus für Erklär-Videos (nur Dev-App):
#   scripts/demo.sh on [Ordner]   Galerie zeigt nur diesen Ordner (Standard Movies/Demo), ohne Ordner-Menü und
#                                 Papierkorb; Statusleiste aufgeräumt (feste Uhrzeit, voller Akku, keine
#                                 Benachrichtigungs-Symbole); Fingertipps werden angezeigt.
#   scripts/demo.sh off           alles wieder normal.
set -euo pipefail
source "$(dirname "$0")/env.sh"
require_device
sysui() { "$ADB" shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null 2>&1 || true; }
# Xiaomi/HyperOS erlaubt das nur mit "USB-Debugging (Sicherheitseinstellungen)" in den Entwickleroptionen.
setting() {
    if ! "$ADB" shell settings put "$@" >/dev/null 2>&1; then
        echo "Hinweis: Einstellung '$2' liess sich nicht setzen. Entwickleroptionen -> \"USB-Debugging (Sicherheitseinstellungen)\" einschalten, oder von Hand: Entwickleroptionen -> \"Berührungen anzeigen\"."
        return 0
    fi
}
case "${1:-}" in
    on)
        folder="${2:-Movies/Demo}"
        "$ADB" shell mkdir -p "/sdcard/$folder"
        # App-Sprache für die Aufnahmen (Standard Englisch); die bisherige merken und bei "off" zurückstellen
        "$ADB" shell cmd locale get-app-locales "$PKG" | sed -n 's/.* are \[\(.*\)\].*/\1/p' > "$ROOT/build/demo_locale_before" || true
        "$ADB" shell cmd locale set-app-locales "$PKG" --locales "${DEMO_LANG:-en-US}"
        "$ADB" shell am start -S -W -n "$PKG/app.simplestcut.debug.DemoActivity" --es folder "$folder" >/dev/null
        setting system show_touches 1
        setting global sysui_demo_allowed 1
        sysui enter
        sysui clock -e hhmm 1000
        sysui battery -e level 100 -e plugged false
        sysui network -e wifi show -e level 4 -e mobile show -e level 4 -e datatype none
        sysui notifications -e visible false
        # Bildschirm bleibt an, solange das USB-Kabel steckt (für Aufnahmen per Skript)
        "$ADB" shell svc power stayon usb
        echo "Demo-Modus an: Galerie zeigt nur /sdcard/$folder"
        ;;
    off)
        before="$(cat "$ROOT/build/demo_locale_before" 2>/dev/null || true)"
        "$ADB" shell cmd locale set-app-locales "$PKG" --locales "$before"
        "$ADB" shell am start -S -W -n "$PKG/app.simplestcut.debug.DemoActivity" --ez off true >/dev/null
        setting system show_touches 0
        sysui exit
        "$ADB" shell svc power stayon false
        setting global sysui_demo_allowed 0
        echo "Demo-Modus aus."
        ;;
    *)
        echo "Aufruf: scripts/demo.sh on [Ordner] | off"; exit 1
        ;;
esac
