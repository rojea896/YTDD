# Changelog

## v1.1.0

### Fixed
- **Videos import into Premiere Pro with sound.** Most downloads were coming out with Opus audio inside the MP4, which Premiere can't decode, so clips imported silent. Videos now save as H.264 + AAC by default, and anything that only exists in other codecs (4K/1440p on YouTube, AV1, VP9, some Instagram videos) is converted after downloading — on the graphics card when there is one
- **YouTube downloads failing.** YouTube now requires a JavaScript runtime to unlock most of its video formats, and YTDD didn't have one, so many downloads failed or were missing qualities. YTDD now uses its own built-in one — nothing to install
- "Prefer smaller file" now picks the smallest file *at the quality you chose*, instead of dropping to the lowest quality available

### Added
- **yt-dlp keeps itself up to date.** YTDD downloads the newest yt-dlp on its own — when it starts, every few hours, and immediately whenever a download fails (then tries that download again). Sites break things constantly and yt-dlp usually ships a fix within days, so this is what keeps downloads working between YTDD releases. Version, status and a "Check now" button are in Settings → Download engine
- New look, matching WorkTrack: sidebar with Download / History / Settings, resizable window, Fluent design throughout
- Plain-English error messages (e.g. "This video is age-restricted. Turn on Cookies…"), with the technical details one click away, and a **Retry** button on failed downloads
- Shows which stage a download is at (video, audio, converting for Premiere) with speed and time left
- History search by title
- Remembers the last folder you saved to
- Clipboard pick-up also recognises TikTok and Vimeo links
- A Windows notification when a download finishes while YTDD is in the background

### Changed
- Quality defaults to the best option that needs no conversion (usually 1080p on YouTube); 1440p/4K are still one click away and say they'll be converted
- Codec and container choices moved into Advanced options, used only with the new "Original quality" video format

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
