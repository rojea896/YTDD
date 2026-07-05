# Changelog

## v1.0.1

### Added
- Estimated file size shown for each quality option after fetching info (e.g. "1080p (245 MB)")
- Drag any entry in Recent Downloads straight into another app (e.g. Premiere) instead of going through Explorer
- Small folder icon on each history entry to reveal it in File Explorer; double-click an entry to open the file itself
- Right-click any history entry for a context menu: Open file, Show in folder, Copy file path, Remove from history
- Download History page has a time filter: Last 24 hours, Last week, Last month, Last year, or All time
- "Auto" cookie mode: tries a configured cookies.txt file first, then automatically tries installed browsers, instead of requiring manual setup
- Browser picker for cookies moved into Advanced options, with clearer guidance when Chrome's newer cookie encryption blocks extraction
- Hover glow effects on buttons, and a circular gutter around the main download button
- Recent Downloads auto-expands to show the newest entry right after a download finishes

### Fixed
- Double scrollbar that could appear in the history list at certain window sizes
- Disabled download button showing a stray line through it
- Download button visually overlapping the "Show all" button when history was expanded
- Silent failures when a file had been moved or deleted since downloading now show a clear message instead

## v1.0.0

Initial release — YTDD (YT-DLP Downloader), a Windows GUI for yt-dlp with ffmpeg bundled in.
