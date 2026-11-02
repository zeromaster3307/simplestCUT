#!/usr/bin/env bash
# Baut eine signierte Release-APK zum Weitergeben (releases/simplestCUT-<version>.apk) und das App-Bundle
# für den Play Store (releases/simplestCUT-<version>.aab, signiert mit demselben Schlüssel als Upload-Schlüssel).
# Aufruf: scripts/release.sh <version>   z. B. scripts/release.sh 1.0
# Setzt versionName auf <version> und zählt versionCode um 1 hoch (Android braucht das für Updates).
# Schlüssel: ../simplestCUT-schluessel neben dem Repo (außerhalb des Repos, siehe LIESMICH.txt dort).
set -euo pipefail
source "$(dirname "$0")/env.sh"
VERSION="${1:?Aufruf: scripts/release.sh <version>, z. B. 1.0}"
cd "$ROOT"
if [[ ! -f ../simplestCUT-schluessel/release.properties ]]; then
    echo "Release-Schlüssel fehlt: ../simplestCUT-schluessel/release.properties"
    exit 1
fi
GRADLE_FILE=app/build.gradle.kts
CODE=$(grep -oP 'versionCode = \K[0-9]+' "$GRADLE_FILE")
BACKUP="$(mktemp)"
cp "$GRADLE_FILE" "$BACKUP"
sed -i "s/versionCode = $CODE/versionCode = $((CODE + 1))/; s/versionName = \".*\"/versionName = \"$VERSION\"/" "$GRADLE_FILE"
if ! OUT="$(./gradlew assembleRelease bundleRelease --console=plain -q 2>&1)"; then
    echo "$OUT"
    cp "$BACKUP" "$GRADLE_FILE" # Versionsnummer nicht hochzählen, wenn nichts entstanden ist
    echo "BAUEN FEHLGESCHLAGEN"
    exit 1
fi
mkdir -p releases
cp app/build/outputs/apk/release/app-release.apk "releases/simplestCUT-$VERSION.apk"
cp app/build/outputs/bundle/release/app-release.aab "releases/simplestCUT-$VERSION.aab"
rm -f "$BACKUP"
CERTS="$("$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)/apksigner" verify --print-certs "releases/simplestCUT-$VERSION.apk" 2>/dev/null)"
echo "$CERTS" | grep "certificate DN" | head -1
echo "Fertig: releases/simplestCUT-$VERSION.apk und .aab (versionCode $((CODE + 1)))"
