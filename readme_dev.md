# YTDD — developer notes

Maintainer detail that doesn't belong in the user-facing README.

The Android app lives in [`android/`](android/) and has its own notes: [`android/readme_dev.md`](android/readme_dev.md).
It's released separately under `android-v*` tags, which the Windows build workflow ignores.

## Layout

| Path | What it is |
|---|---|
| `backend.py` | Download/history/settings logic. Never imports yt-dlp at module level — always `with ENGINE.use() as yt_dlp:` |
| `ytdlp_engine.py` | Self-updating yt-dlp: downloads, verifies and hot-swaps the engine |
| `premiere.py` | ffprobe + ffmpeg pass that makes a finished video Premiere-ready |
| `backend_ipc.py` | Newline-delimited JSON-RPC over stdio, spawned by Electron |
| `electron/main.js` | Spawns the backend, relays RPC, owns dialogs/shell/clipboard/drag-out |
| `electron/channels.js` | The whole IPC allowlist (main registers it; the sandboxed preload gets it via a launch argument) |
| `electron/src/` | React 19 + Fluent UI v9 + Zustand renderer, built by Vite |
| `electron/scripts/e2e-check.js` | End-to-end check over the DevTools protocol |
| `downloader.py`, `gui_app.py`, `webview_patch.py`, root `webapp/` | Legacy tkinter/pywebview front ends — not shipped |

Styling follows WorkTrack: **Fluent owns colour, type and controls** (`makeStyles` + `tokens`);
**Tailwind is layout only**, and only on plain DOM elements — Griffel's sheet loads after Tailwind's,
so a Tailwind class on a Fluent component silently loses.

## Running it

```
cd electron
npm install
npm run dev        # Vite on :5174 + Electron; backend runs from source with system Python
npm start          # build the renderer, run it over file:// like the packaged app
npm run e2e        # full end-to-end check (see below)
```

Dev mode needs `python` on PATH with `pip install -r requirements.txt`, and ffmpeg/ffprobe in
`vendor/ffmpeg/` (CI downloads them; locally copy the same BtbN **8.1** build).

Always launch through `scripts/launch-electron.js` (the npm scripts do) — IDE terminals leak
`ELECTRON_RUN_AS_NODE=1`, which turns `electron .` into plain Node.

## How downloads stay working

**Self-updating yt-dlp (`ytdlp_engine.py`).** The frozen `ytdlp-backend.exe` contains a yt-dlp that
never changes after a build. At runtime the backend fetches yt-dlp's official `yt-dlp` release
asset — a zipimport archive that also carries `yt_dlp_ejs` — into
`%APPDATA%\YTDLP-Downloader\engine\yt-dlp-<version>.zip`, checks it against the release's
`SHA2-256SUMS`, and imports `yt_dlp` from it through a meta-path finder placed ahead of PyInstaller's
importer. Checks happen at startup, every 6 hours, and after any failed download or fetch (then the
job is retried once). Default channel is nightly, as yt-dlp recommends; stable is in Advanced options.

- A new engine only activates when no download is using the current one (`Engine.use()`), because
  swapping modules mid-download would mix two yt-dlp versions in one process.
- If a downloaded engine fails to import, the backend falls back to the next newest, then the
  bundled copy, and reports `loadError` in Settings.
- yt-dlp wraps `urllib3`'s `_PERCENT_RE` at import time; a second import wraps the wrapper and breaks
  the requests handler. `_purge_engine_modules()` unwraps it before every re-import.
- `_bundled_copy_marker()` exists only so PyInstaller's import scan sees `yt_dlp` — without it the
  frozen exe ships with **no** fallback copy at all.

**YouTube's JS challenges.** yt-dlp needs a JS runtime (Node 22+ or Deno) for most YouTube formats.
Electron's own exe is one: `main.js` passes `YTDD_JS_RUNTIME=process.execPath` and
`ELECTRON_RUN_AS_NODE=1` to the backend only, and the backend hands yt-dlp
`js_runtimes={'node': {'path': ...}}`. If you ever enable Electron fuses, keep `RunAsNode` on, or
YouTube breaks.

**Premiere-ready output (`premiere.py`).** Format selection sorts `res:<chosen>, fps, hdr:sdr,
vcodec:h264, acodec:aac`, then every finished video is probed: non-AAC/MP3 audio is re-encoded to AAC,
non-H.264/HEVC video is re-encoded to H.264 (NVENC → QSV → AMF → libx264, first that passes a test
encode), non-MP4 is remuxed. Quality levels (NVENC cq 23 / x264 crf 20) were picked by SSIM >0.99
against an AV1 source.

## Tests

- `npm run e2e` launches the built app with a throwaway `APPDATA` (your real history/settings are
  untouched) and drives it: engine update, JS runtime, a real download checked with ffprobe, a 4K
  download through the conversion step, the error path, history, settings, theme toggle, context menu.
- `--seed "%APPDATA%\YTDLP-Downloader"` copies real settings/history in; `--shots <dir>` saves
  screenshots; `--quick` skips the 4K download; `--exe ../dist-electron/win-unpacked/YTDD.exe`
  drives the packaged build.
- `YTDD_TEST_DEST` (set by the harness) makes the folder picker return that folder instead of opening
  a dialog. Never set it otherwise.

## Releasing

**After changing any Python file, refreeze the backend before packaging** — electron-builder bundles
whatever is in `dist/` and does not rebuild it:

```
pyinstaller --onefile --name ytdlp-backend --distpath dist backend_ipc.py
cd electron && npm run pack
```

Then: bump `electron/package.json` version, add a `CHANGELOG.md` entry, commit, push `main`,
`git tag vX.Y.Z`, `git push origin vX.Y.Z`. GitHub Actions (`.github/workflows/build.yml`) builds both
exes and attaches them to a Release (~5 min).

CI pins ffmpeg to BtbN's **8.1** release branch on purpose: master and 9.0 builds need NVIDIA driver
610+ for NVENC and fall back to (much slower) CPU encoding on anything older.
