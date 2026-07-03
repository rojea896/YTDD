# YTDD (YT-DLP Downloader)

A lightweight Windows GUI for yt-dlp. Paste a link, pick a quality, done — ffmpeg included, nothing to install.

Electron front end, Python backend, ffmpeg bundled in — recipients don't need Python or ffmpeg installed.

## Project layout

- `backend.py` — shared yt-dlp logic (settings, history, fetch_info, download jobs with pause/resume/cancel). Used by both front ends below.
- `downloader.py` — the original standalone tkinter GUI. Untouched, still works independently of everything below.
- `backend_ipc.py` — thin stdio JSON-RPC server wrapping `backend.py`, spawned by Electron's main process. Folder pickers/clipboard are handled on the Electron side instead.
- `electron/` — the Electron app (the one actively developed/shipped):
  - `main.js` — spawns the backend (dev: `python backend_ipc.py`; packaged: `resources/backend/ytdlp-backend.exe`), relays JSON-RPC over stdio, owns native dialogs/clipboard/window resizing.
  - `preload.js` — exposes `window.api.*` to the renderer via `contextBridge` (no `nodeIntegration`).
  - `webapp/index.html` — the entire UI (vanilla JS, no framework). Collapsed empty state that expands as you type a URL, paginated Recent Downloads (5 shown, "Show more"/"Show all"), settings/advanced options, history page.
  - `build/icon.ico` / `icon.png` — app icon.
  - `afterPack.js` — post-package hook that runs `rcedit` to embed the icon + ProductName into the exe (electron-builder's own icon-signing path crashes in this environment without real code-signing certs, so this bypasses it).
- `vendor/ffmpeg/` — **gitignored**, holds `ffmpeg.exe`/`ffprobe.exe` for local dev builds. Not committed (too large); CI downloads a fresh static build from BtbN/FFmpeg-Builds on every run.
- `.github/workflows/build.yml` — GitHub Actions: on any `vX.Y.Z` tag push, freezes the backend, downloads ffmpeg, builds the installer + portable exe, uploads both to a GitHub Release.

## Requirements bundled vs. not

- **Bundled**: Python + yt-dlp (frozen into `ytdlp-backend.exe` via PyInstaller), ffmpeg/ffprobe (static build, no external DLLs).
- **Not bundled**: nothing — the packaged exe is self-contained. (The *unpackaged dev mode* needs a system Python with `yt-dlp` installed — see below.)

## Building locally

From the project root:

```powershell
# 1. Refreeze the Python backend (only needed after editing backend.py / backend_ipc.py)
pip install -r requirements.txt
& "$env:LOCALAPPDATA\Programs\Python\Python314\Scripts\pyinstaller.exe" --onefile --name ytdlp-backend --distpath dist backend_ipc.py

# 2. Make sure vendor/ffmpeg/ffmpeg.exe and ffprobe.exe exist (see "Getting ffmpeg" below)

# 3. Build the Electron installer + portable exe
cd electron
npm install
npx electron-builder --win
```

Output lands in `dist-electron\`:
- `YTDD Setup 1.0.0.exe` — NSIS installer (recommended for most people)
- `YTDD 1.0.0.exe` — portable exe, no install needed, just run it

### Getting ffmpeg for a local build

`vendor/ffmpeg/` is gitignored and won't exist on a fresh clone. Download a static Windows build and drop `ffmpeg.exe` + `ffprobe.exe` into `vendor/ffmpeg/`:

```powershell
curl -L -o ffmpeg.zip "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip"
Expand-Archive ffmpeg.zip -DestinationPath ffmpeg-extract
mkdir vendor\ffmpeg
copy ffmpeg-extract\ffmpeg-master-latest-win64-gpl\bin\ffmpeg.exe vendor\ffmpeg\
copy ffmpeg-extract\ffmpeg-master-latest-win64-gpl\bin\ffprobe.exe vendor\ffmpeg\
```

### Running in dev mode (no build)

Needs a system Python with `pip install -r requirements.txt` run once.

```powershell
cd electron
npm install
npx electron .
```

## Releasing via GitHub Actions (no local build needed)

The repo already has this wired up:

```powershell
git tag v1.0.1
git push origin v1.0.1
```

A few minutes later, both installers appear under the repo's **Releases** page as downloadable files — that's the easiest way to share a new version.

## Sharing the installer with friends

Two options:

1. **GitHub Releases** (best for repeat sharing) — push a tag as above, then send friends the release page URL. They just click the `.exe` they want.
2. **Direct file share** (fastest one-off) — grab `YTDD Setup 1.0.0.exe` (or the portable exe) from `dist-electron\` and upload it wherever you'd share any file with someone: Google Drive, Dropbox, a Discord/Slack DM, WeTransfer, etc. Anyone with the link can download and run it.

Either way, since it's unsigned (no Apple/Microsoft code-signing certificate), Windows SmartScreen will show an "Unrecognized app" warning the first time someone runs it — that's expected, not a sign of a broken build. They click **More info → Run anyway**.

## Known gaps / things to revisit

- Not yet built for macOS — needs an actual Mac (or a `macos-latest` GitHub Actions job) since electron-builder can't cross-compile a `.dmg` from Windows, and the Python backend needs a Mac-native PyInstaller freeze too.
- No code signing — fine for sharing with friends, but scaling beyond that would mean an Apple Developer account ($99/yr) and/or a Windows code-signing certificate to remove the SmartScreen/Gatekeeper warnings.
- `history.json` gets a one-time-per-session warning modal if it crosses 5 MB (~10,000 entries); "Clear History" wired to the same button as the manual clear.

## License

GPL-3.0 (see `LICENSE`) — this repo bundles a GPL-licensed static ffmpeg build, so the project as a whole is distributed under GPL terms.
