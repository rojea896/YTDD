# YTDD for Android — developer notes

Maintainer detail that doesn't belong in the user-facing README. This is the `android/` folder of the
YTDD repo; everything else in the repo is the Windows app. The two are built and released separately.

## Layout

| Path | What it is |
|---|---|
| `core/Engine.kt` | youtubedl-android wrapper: init, self-update (nightly by default), update gate so a running download is never swapped out |
| `download/Downloads.kt` | Job queue (2 at a time), the yt-dlp call, update-and-retry on failure |
| `download/Compat.kt` | Probe with MediaExtractor; convert anything that isn't H.264/AAC MP4 with Media3 Transformer |
| `download/Gallery.kt` | MediaStore publish (Movies/YTDD, Music/YTDD), thumbnail cache |
| `download/Errors.kt` | yt-dlp stderr → one headline + one sentence |
| `download/Notify.kt`, `DownloadService.kt` | Notifications and the dataSync foreground service |
| `QuickActivity.kt` | The bottom sheet for every quick entry point (share, process-text, shortcuts, tile, widget, notification actions) |
| `ui/` | Compose UI. `Theme.kt` holds the design tokens (`T.accent`, …) |
| `shortcuts/` | Glance widget, Quick Settings tile, pin helpers |
| `design/` | Claude Design redesign (`YTDD redesign.html`), the prompt, and `source/icons.json` |

## Engine

`io.github.junkfood02.youtubedl-android` 0.18.1 bundles Python 3.12, ffmpeg 7.1.1 and **QuickJS**, which
it passes to yt-dlp as `--js-runtimes` (YouTube's JS challenges). yt-dlp updates itself from the nightly
channel on every launch, hourly while open, and after any failed download (then that download is retried once).

Format selection: `-S res:<quality>,fps,hdr:sdr,vcodec:h264,acodec:aac,ext:mp4:m4a`, merged to MP4. TikTok
often serves HEVC and Shorts VP9; those get converted on-device.

## Building

```
cd android
bash scripts/run.sh            # debug build, install and launch on the running emulator/phone
./gradlew assembleRelease      # signed release APKs in app/build/outputs/apk/release/
bash scripts/screenshots.sh    # every screen into design/screenshots-redesign/ (wipes app data, ~10 min)
python scripts/make_icons.py design/source/icons.json app/src/main/res/drawable
```

JDK: `%LOCALAPPDATA%\Android\jdk-21.0.13+9` (`run.sh` sets it). AGP 8.13, Gradle 8.14.3, compileSdk 36, minSdk 29.

## Releasing

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`, add a `CHANGELOG.md` entry.
2. `./gradlew assembleRelease`.
3. **Smoke-test the release build on the emulator** (`adb install app/build/outputs/apk/release/app-x86_64-release.apk`):
   R8 minification has broken engine init before (Commons Compress uses reflection — see `proguard-rules.pro`).
4. Tag the commit **`android-v<version>`** (never plain `v…`: that tag pattern triggers the Windows
   build in `.github/workflows/build.yml`).
5. Create a GitHub release for that tag titled "YTDD for Android <version>", with **"Set as latest
   release" turned off** so the repo's Latest badge stays on the Windows app. Upload
   `app-arm64-v8a-release.apk` as `YTDD-Android-<version>.apk` and `app-universal-release.apk` as
   `YTDD-Android-<version>-universal.apk`.

Release signing reads `keystore.properties` → `keystore/ytdd-release.jks`. Both are gitignored and must be
backed up: an update signed with a different key won't install over the existing app.

## Gotchas

- The share sheet is a `ModalBottomSheet`, i.e. a separate window: clipboard reads key off the sheet's
  `LocalWindowInfo` focus, not the activity's `onWindowFocusChanged`.
- Glance sessions outlive `updateAll()` — the widget collects state *inside* `provideContent`.
- yt-dlp prints "Writing video metadata" before the `.info.json` exists; it's read on the first `[download]` line.
- Reddit and Vimeo need login or browser impersonation (no curl_cffi on Android), so they fail with a plain error.
- `Support.URL` in `core/Support.kt` is the "Buy me a coffee" link.
