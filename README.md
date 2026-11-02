# simplestCUT

A simple Android app for cutting and joining phone videos — frame-accurate, fast, and without losing quality.

**Try it:** simplestCUT is currently in closed testing on Google Play (Android 15+). Join the [tester group](https://groups.google.com/g/simplestcut-tester), then [opt in here](https://play.google.com/apps/testing/app.simplestcut) and install it from the Play Store. Feedback welcome: dev@zeromaster.ch

## Features

**Cut**
- Split a video into pieces, remove what you don't need, rearrange the rest.
- Frame-accurate: step through frame by frame and cut exactly where you want.
- **Save:** lossless and in seconds — the video is not re-encoded (cut list in the MP4).
- **Export:** smart cut — only the frames at the cut edges are re-encoded, everything else is copied 1:1. The result plays everywhere, including messengers.
- Rotate, crop and zoom (whole video or per piece).

**Join**
- Join several clips into one video, in any order.
- Clips with different formats (portrait/landscape, different resolutions) are fitted together automatically.

**Keeps your quality**
- HDR, 10-bit and Dolby Vision are preserved.
- H.264, H.265 (HEVC) and AV1.
- Originals are never changed; the result is saved next to the original.

**Simple gallery**
- All videos by folder, sorted by date, length or size.
- Rename, move, share, delete — with trash and undo.

**Private**
- No internet permission, no ads, no tracking, no account. Everything happens on the device.

Languages: English, German.

## Requirements

Android 15 (API 35) or newer.

## Building

You need JDK 21 and the Android SDK (compile SDK 37). Tell Gradle where the SDK is, either with the
`ANDROID_HOME` environment variable or a `local.properties` file in the project folder (`sdk.dir=/path/to/Android/Sdk`).

```sh
./gradlew assembleDebug
```

The debug build is installed as a separate app ("simplestCUT Dev", package `app.simplestcut.dev`), so it can live next to the store version.

Release builds are signed with a key that is not part of this repository. Without it, `./gradlew assembleRelease` produces an unsigned APK. To sign with your own key, create `../simplestCUT-schluessel/release.properties` next to the project folder:

```properties
storeFile=your-key.jks
storePassword=...
keyAlias=...
keyPassword=...
```

The helper scripts in `scripts/` (build + install via adb, logs, test videos) expect the SDK in `~/Android/Sdk` and the JDK in `~/Android/jdk-21`; adjust `scripts/env.sh` if yours live elsewhere.

## How it works (short)

- `mp4/` — own MP4 reader/writer: cuts by copying samples, keeps all metadata (HDR, Dolby Vision, rotation, GPS).
- `export/` — smart cut: decodes the GOPs at the cut edges with MediaCodec and re-encodes only those frames; mixed clips are joined with Media3 Transformer.
- `editor/` — Jetpack Compose UI with a frame index for frame-accurate seeking.

Source code comments are mostly in German.

## License

[GNU General Public License v3.0](LICENSE). You may use, study, change and share this code; modified versions you distribute must be released under the same license.
