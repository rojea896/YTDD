#!/usr/bin/env bash
# Capture every screen/state of the installed app into design/screenshots-redesign/.
# Wipes the app's data first (history and settings; downloaded files stay in the gallery) and uses
# real downloads, so the emulator must be online.   Run: bash scripts/screenshots.sh
set -uo pipefail
cd "$(dirname "$0")/.."
export MSYS_NO_PATHCONV=1
ADB="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}/platform-tools/adb"
OUT=design/screenshots-redesign
rm -rf "$OUT"; mkdir -p "$OUT"
PKG=com.rojea.ytdd
TIKTOK="https://www.tiktok.com/@scout2015/video/6718335390845095173"

shot() { "$ADB" exec-out screencap -p > "$OUT/$1.png"; echo "  $1"; }
share() { "$ADB" shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "'$1'" -n $PKG/.QuickActivity >/dev/null 2>&1; }
dump() { "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; "$ADB" shell cat /sdcard/ui.xml; }
# Centre of the first on-screen element matching a literal attribute, e.g. 'text="Hide"'.
find() {
  local b; b=$(dump | tr '>' '\n' | grep -F "$1" | head -1 | grep -oE 'bounds="[^"]+"')
  [ -z "$b" ] && return 1
  read -r x1 y1 x2 y2 <<<"$(echo "$b" | grep -oE '[0-9]+' | tr '\n' ' ')"
  echo "$(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))"
}
tap() { local p; p=$(find "$1") || { echo "  (couldn't find $1)"; return 1; }; "$ADB" shell input tap $p; sleep 1; }
longpress() { local p; p=$(find "$1") || { echo "  (couldn't find $1)"; return 1; }; read -r x y <<<"$p"; "$ADB" shell input swipe $x $y $x $y 1200; }
waitfor() { local t=0; until dump | grep -qE "$1"; do sleep 1; t=$((t + 1)); [ $t -ge "$2" ] && { echo "  (timed out waiting for $1)"; return 1; }; done; }
open_app() { "$ADB" shell am force-stop $PKG; "$ADB" shell am start -n $PKG/.MainActivity >/dev/null; sleep 3; }
settings_appearance() { tap 'content-desc="Settings"'; sleep 2; tap "text=\"$1\""; sleep 1; "$ADB" shell input keyevent BACK; sleep 1; }

echo "Capturing to $OUT"
"$ADB" shell pm clear $PKG >/dev/null
"$ADB" shell pm grant $PKG android.permission.POST_NOTIFICATIONS
"$ADB" shell cmd statusbar collapse
open_app; shot 01-home-empty

# Share sheet while working, the home card and notification during it, then done.
share "$TIKTOK"
waitfor 'Downloading|Making it play' 90; sleep 2; shot 02-sheet-working
tap 'text="Hide"'; sleep 1; shot 03-home-downloading
"$ADB" shell cmd statusbar expand-notifications; sleep 1; shot 04-notification-downloading
"$ADB" shell cmd statusbar collapse
waitfor 'text="Downloads"' 120; sleep 2; sleep 2
"$ADB" shell cmd statusbar expand-notifications; sleep 2; shot 05-notification-saved
"$ADB" shell cmd statusbar collapse; sleep 1

share "$TIKTOK"; waitfor 'Already in your gallery' 20; sleep 2; shot 06-sheet-already-downloaded
tap 'text="Download again"'; waitfor 'Saved to your gallery' 150; sleep 2; shot 07-sheet-done
tap 'text="Done"'; sleep 1

# A second item (the MP3) so the list has more than one row.
"$ADB" shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "'$TIKTOK'" -n $PKG/.QuickActivity >/dev/null 2>&1
waitfor 'Already in your gallery' 20; sleep 2; tap 'text="Done"'; sleep 1
open_app; tap 'text="Audio (MP3)"'; sleep 1; tap 'text="Type a link"'; sleep 1; shot 08-home-type-a-link
"$ADB" shell input text "$TIKTOK"; "$ADB" shell input keyevent 66; sleep 1
waitfor 'text="Downloads"' 120; sleep 2; tap 'text="Video"'; sleep 1

# Errors and empty input.
share "https://www.reddit.com/r/videos/comments/6rrwyj/that_small_heart_attack/"
waitfor 'text="Close"' 150; sleep 2; shot 09-sheet-error
tap 'text="Details"'; sleep 1; shot 10-sheet-error-details
tap 'text="Close"'; sleep 1
share "just some text without a link"; sleep 2; shot 11-sheet-no-link
tap 'text="OK"'; sleep 1
"$ADB" shell am start -a com.rojea.ytdd.action.PASTE_VIDEO -n $PKG/.QuickActivity >/dev/null 2>&1; sleep 3
shot 12-sheet-nothing-copied; tap 'text="OK"'; sleep 1

open_app; shot 13-home-collapsed
tap 'text="Downloads"'; sleep 2; shot 14-home-expanded
tap 'content-desc="More"'; sleep 1; shot 15-home-menu; "$ADB" shell input keyevent BACK; sleep 1

tap 'content-desc="Settings"'; sleep 2; shot 16-settings
tap 'text="Advanced"'; sleep 1; "$ADB" shell input swipe 540 1900 540 600 300; sleep 1; shot 17-settings-advanced
"$ADB" shell input keyevent BACK; sleep 1

settings_appearance "Light"; open_app; shot 18-home-light
tap 'text="Downloads"'; sleep 2; shot 19-home-expanded-light
share "$TIKTOK"; waitfor 'Already in your gallery' 20; sleep 2; shot 20-sheet-light; tap 'text="Done"'; sleep 1
"$ADB" shell input keyevent HOME; sleep 3; "$ADB" shell input swipe 900 1200 150 1200 300; sleep 3; shot 21-widget-light
open_app; settings_appearance "Dark"

# Outside the app: widget, launcher shortcuts, Quick Settings tile.
"$ADB" shell input keyevent HOME; sleep 3; "$ADB" shell input swipe 900 1200 150 1200 300; sleep 3; shot 22-widget-dark
"$ADB" shell input swipe 540 2000 540 600 300; sleep 2
longpress 'text="YTDD"' && { sleep 2; shot 23-launcher-shortcuts; "$ADB" shell input keyevent BACK; }
"$ADB" shell input keyevent HOME
"$ADB" shell cmd statusbar add-tile $PKG/.shortcuts.PasteTileService >/dev/null 2>&1
"$ADB" shell cmd statusbar expand-settings; sleep 2; shot 24-quick-settings-tile
"$ADB" shell cmd statusbar collapse
echo "Done."
