Redesign YTDD, an Android video downloader, from the attached screenshots. Make it more minimal and calm. Build it mostly on Material 3, with One UI-style icons. It should still look like a Material 3 app, not a Samsung app: no big One UI headers. Keep it simple and no-nonsense: every element has to earn its place.

WHAT THE APP DOES
Paste or share a link from YouTube, TikTok, Instagram, X and 1,000+ other sites. It saves a video that plays everywhere to the gallery, then lets you send it straight to WhatsApp (Status or a chat), share it anywhere, or play it. Most people will use it from other apps: in TikTok they tap Share → YTDD and a bottom sheet appears over TikTok. The notification and the home-screen widget matter as much as the app itself.

STYLE
- Material 3 throughout: normal small top app bar, M3 type scale, M3 components (filled and outlined buttons, segmented button, linear progress, modal bottom sheet, menus, switches, radio buttons) and M3 shapes.
- Icons in the One UI style (as in the OneUIProject icon set): rounded, soft, slightly heavier line icons. Use them consistently everywhere: app, notification actions and widget.
- Flat surfaces in tonal layers, no shadows. Use a thin 1dp outline on important buttons and controls (the primary action, Share, the Video/Audio switch) instead of heavy fills or elevation.
- Generous spacing, few colours, quiet secondary text. No coloured dots, badges or decoration.
- No Material You wallpaper colours. Use fixed colours.

COLOUR
- Dark by default. Also design the Light theme.
- One accent colour: coral #FF5A5A, from the app icon (a white download arrow dropping into a tray, on a coral rounded square). Use it only for the main action and progress.
- WhatsApp green #1DAA61 appears only on the WhatsApp button.

APP SCREENS
- One screen, no tabs. Settings is the only other page.
- On the home screen, only:
  1. a small app bar (YTDD + settings);
  2. one big "Paste & download" button, which reads the clipboard;
  3. a Video / Audio (MP3) switch;
  4. one line: "YouTube, TikTok, Instagram, X and 1,000+ more";
  5. downloads.
  The link text field can shrink to a secondary "Type a link" option.
- Each download shows a thumbnail, its title, and one line of details (site · length · size), plus WhatsApp and Share. Play, Copy link, Remove and Delete go in the ⋮ menu.
- Share sheet (M3 modal bottom sheet over another app), in these states: working (progress, Hide, Cancel, "Just the audio"), done (WhatsApp / Share / Play), already downloaded, error, and no link found.
- Settings: video quality, appearance, shortcuts and Advanced (engine version, update now, stable vs nightly, clear list). One-line descriptions, no paragraphs.
- Drop the tip banner, or show it once on first run.

NOTIFICATIONS
Design these fully, in dark and light. They must be buildable with Android's standard notification templates: monochrome small icon, app name, title, text, optional progress bar, the video thumbnail as the large icon, and up to 3 action buttons. Show each one collapsed and expanded, as it appears in the notification shade:
1. Downloading: title, "Downloading · 45% · 0:12 left", progress bar, Cancel.
2. Converting: "Making it play everywhere · 60%".
3. Several at once: "Downloading 3 videos", combined progress.
4. Done: thumbnail, title, "Video saved — ready to send", with WhatsApp, Share and Play actions.
5. Done (audio): "MP3 saved".
6. Failed: one plain-language reason and "Try again".

WIDGET
Design it fully as a resizable home-screen widget, in dark and light, shown on a realistic home screen with wallpaper. It must be buildable with Jetpack Glance: solid colours, rounded corners, simple rows and columns, no blur or custom drawing.
1. Small (2×1): one button, "Paste link".
2. Medium (4×1): "Paste link & download" plus an MP3 button.
3. Large (4×2 / 4×3): the buttons, then the last 3 downloads as rows (thumbnail, title, WhatsApp and Share icon buttons).
4. States: empty (no downloads yet, with one helpful line), downloading (progress on the newest row), normal.
Also show the launcher long-press shortcuts ("Download copied link", "Get MP3") and the Quick Settings tile ("Download link") so all entry points look like one family.

DELIVER
Home (empty, downloading, with downloads), the share sheet in each state, Settings, every notification state, and every widget size and state. Dark and light, at 1080×2400, plus a short spec of colours, type sizes, corner radii, outline usage and icon style.
