# Gemeinsame Pfade für die Skripte (wird per "source" eingebunden)
export ANDROID_HOME="$HOME/Android/Sdk"
export JAVA_HOME="$HOME/Android/jdk-21"
ADB="$ANDROID_HOME/platform-tools/adb"
# Debug-Builds haben das Paket app.simplestcut.dev (Play-Store-Version: app.simplestcut)
PKG="app.simplestcut.dev"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

require_device() {
    if ! "$ADB" get-state >/dev/null 2>&1; then
        echo "Kein Handy verbunden. Ausgabe von 'adb devices':"
        "$ADB" devices
        exit 1
    fi
}
