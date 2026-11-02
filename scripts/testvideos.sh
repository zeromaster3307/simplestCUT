#!/usr/bin/env bash
# Erzeugt künstliche Testvideos mit großen Keyframe-Abständen (ffmpeg auf dem PC) und legt sie nach testclips/.
# Jedes Bild zeigt Buchstabe, Bildnummer und Zeit; Keyframes tragen gelb "KEYFRAME"; Ton piept einmal pro Sekunde.
#   A, B: H.264, Keyframe alle 10 s, gleich kodiert (zusammenfügbar)
#   C:    HEVC, Keyframe alle 5 s (passt nicht zu A/B)
#   D:    H.264 im Querformat 1920×1080, Keyframe alle 2 s (für Zusammenfügen hochkant + quer)
#   E:    AV1 (SVT-AV1), Keyframe alle 5 s (Smart Cut mit Software-Encoder)
# Aufruf: scripts/testvideos.sh [--push]   (--push kopiert sie aufs Handy nach Movies/Test)
set -euo pipefail
source "$(dirname "$0")/env.sh"
OUT="$ROOT/testclips"
mkdir -p "$OUT"
F="$(fc-match -f '%{file}' 'DejaVu Sans:bold')"

mk() { # name label dauer(s) keyframe-abstand(bilder) farbe tonhöhe codec-argumente...  (SIZE=BxH optional)
    local name=$1 label=$2 dur=$3 gop=$4 color=$5 freq=$6 size=${SIZE:-720x1280}; shift 6
    local vf="drawbox=x=0:y=0:w=iw:h=ih:color=${color}@0.35:t=fill"
    vf+=",drawtext=fontfile=${F}:text='${label}':fontsize=220:fontcolor=white:borderw=8:x=(w-tw)/2:y=110*h/1280"
    vf+=",drawtext=fontfile=${F}:text='Bild %{frame_num}':fontsize=90:fontcolor=white:borderw=6:x=(w-tw)/2:y=500*h/1280"
    vf+=",drawtext=fontfile=${F}:text='%{pts\\:hms}':fontsize=70:fontcolor=white:borderw=6:x=(w-tw)/2:y=640*h/1280"
    vf+=",drawtext=fontfile=${F}:text='Keyframe alle ${gop} Bilder':fontsize=40:fontcolor=white:borderw=4:x=(w-tw)/2:y=1150*h/1280"
    vf+=",drawtext=fontfile=${F}:text='KEYFRAME':fontsize=110:fontcolor=yellow:borderw=8:x=(w-tw)/2:y=820*h/1280:enable='eq(mod(n,${gop}),0)'"
    ffmpeg -v error -y \
        -f lavfi -i "testsrc2=size=${size}:rate=30:duration=${dur}" \
        -f lavfi -i "sine=frequency=${freq}:beep_factor=4:sample_rate=44100:duration=${dur}" \
        -vf "$vf" -pix_fmt yuv420p "$@" -c:a aac -b:a 128k -ac 2 -shortest "$OUT/${name}.mp4"
    echo "$OUT/${name}.mp4"
}

X264=(-c:v libx264 -profile:v high -preset medium -crf 23 -g 300 -keyint_min 300 -sc_threshold 0 -bf 2)
mk Test_A_h264_10s A 30 300 blue 880 "${X264[@]}"
mk Test_B_h264_10s B 20 300 green 660 "${X264[@]}"
mk Test_C_hevc_5s C 20 150 red 440 -c:v libx265 -tag:v hvc1 -preset medium -crf 26 \
    -x265-params "keyint=150:min-keyint=150:scenecut=0:bframes=2:log-level=error"
SIZE=1920x1080 mk Test_D_quer D 20 60 purple 550 -c:v libx264 -profile:v high -preset medium -crf 23 -g 60 -keyint_min 60 -sc_threshold 0 -bf 2
mk Test_E_av1_5s E 20 150 orange 770 -c:v libsvtav1 -preset 8 -crf 35 -g 150 -svtav1-params "scd=0:irefresh-type=2"

if [[ "${1:-}" == "--push" ]]; then
    require_device
    for f in "$OUT"/Test_*.mp4; do
        "$ADB" push "$f" /sdcard/Movies/Test/ >/dev/null
        "$ADB" shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
            -d "file:///sdcard/Movies/Test/$(basename "$f")" >/dev/null
    done
    echo "Auf dem Handy unter Movies/Test."
fi
