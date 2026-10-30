# YTDD
### YT-DLP Downloader

A Windows app for downloading video and audio from YouTube, Instagram, TikTok, Vimeo and over a thousand other sites, built on [yt-dlp](https://github.com/yt-dlp/yt-dlp). Paste a link, pick a quality, done.

Videos save as MP4s that drop straight into Premiere Pro (or any other editor) with picture *and* sound.

## Download

Grab the latest version from the [Releases page](https://github.com/rojea896/YTDD/releases):

- **YTDD Setup.exe** — installer (recommended)
- **YTDD.exe** — portable, no install needed, just run it

Windows will show an "Unrecognized app" warning the first time you open it since it isn't code-signed — click **More info → Run anyway**. Everything it needs (including ffmpeg) is bundled in, nothing else to install.

YTDD needs an internet connection anyway, and uses it to keep its download engine (yt-dlp) up to date by itself — so when YouTube or another site changes something, downloads usually start working again within days, without you installing anything new.

## Features

- Paste a link, pick a quality — video or audio-only (MP3, M4A, FLAC, WAV), with an estimated file size for each option
- **Premiere-ready by default:** H.264 video and AAC audio in an MP4. If a quality only exists in other formats (like 4K on YouTube), YTDD converts it after downloading, using your graphics card when it can
- Whole playlists, saved into their own numbered folder
- Recent downloads and a searchable history: drag a file straight onto a Premiere timeline, double-click to open it, or right-click to show it in Explorer, copy its path or remove it
- Clear error messages, a Retry button, and pause/cancel for anything in progress
- Uses your browser's cookies for private, age-restricted or members-only videos
- Dark and light mode, and optional pick-up of video links you copy

## Changelog

See [CHANGELOG.md](CHANGELOG.md).

## License

GPL-3.0 (see `LICENSE`).
