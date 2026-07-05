# YTDD
### YT-DLP Downloader

A lightweight Windows GUI for downloading video/audio from YouTube and other sites, built on [yt-dlp](https://github.com/yt-dlp/yt-dlp). Paste a link, pick a quality, done.

## Download

Grab the latest version from the [Releases page](https://github.com/rojea896/YTDD/releases):

- **YTDD Setup.exe** — installer (recommended)
- **YTDD.exe** — portable, no install needed, just run it

Windows will show an "Unrecognized app" warning the first time you open it since it isn't code-signed — click **More info → Run anyway**. Everything it needs (including ffmpeg) is bundled in, nothing else to install.

## Features

- Paste a URL, pick a quality — video or audio, with an estimated file size shown for each option
- Recent downloads list: drag an entry straight into another app, double-click to open it, click the folder icon to reveal it in Explorer, or right-click for more options (open, show in folder, copy path, remove)
- Full download history page with a time filter (last 24 hours / week / month / year / all time)
- Dark/light mode, clipboard monitoring, automatic cookie handling for private/age-restricted videos
- Advanced options: cookie source, codec preference, container format, filesize/rate limits, custom user-agent

## Changelog

See [CHANGELOG.md](CHANGELOG.md).

## License

GPL-3.0 (see `LICENSE`).
