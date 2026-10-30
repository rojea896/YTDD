"""Turn the One UI-style SVG icons from the Claude Design file into Android VectorDrawables.

    python scripts/make_icons.py design/source/icons.json app/src/main/res/drawable

icons.json maps icon id -> <symbol> markup (24x24 grid). Every icon becomes ic_ui_<id>.xml, drawn in
white so Compose / Glance / notifications can tint it. Also writes the launcher and shortcut icons.
"""
import json
import os
import re
import sys

src, out = sys.argv[1], sys.argv[2]
symbols = json.load(open(src, encoding="utf-8"))

# Icons the app uses. The rest (wifi, torch...) only decorate the design's mock home screens.
WANTED = ["paste", "tray", "music", "share", "wa", "play", "link", "trash", "kbd", "alert", "more",
          "gear", "back", "check", "checkc", "down", "up", "x", "redo", "grid", "tile", "info"]


def attrs(tag):
    return dict(re.findall(r'([\w-]+)="([^"]*)"', tag))


def circle(a):
    cx, cy, r = float(a["cx"]), float(a["cy"]), float(a["r"])
    return f"M{cx - r:g},{cy:g}a{r:g},{r:g} 0 1,0 {2 * r:g},0a{r:g},{r:g} 0 1,0 {-2 * r:g},0z"


def rect(a):
    x, y, w, h = (float(a[k]) for k in ("x", "y", "width", "height"))
    r = float(a.get("rx", 0))
    if not r:
        return f"M{x:g},{y:g}h{w:g}v{h:g}h{-w:g}z"
    return (f"M{x + r:g},{y:g}h{w - 2 * r:g}a{r:g},{r:g} 0 0 1 {r:g},{r:g}v{h - 2 * r:g}"
            f"a{r:g},{r:g} 0 0 1 {-r:g},{r:g}h{-(w - 2 * r):g}a{r:g},{r:g} 0 0 1 {-r:g},{-r:g}"
            f"v{-(h - 2 * r):g}a{r:g},{r:g} 0 0 1 {r:g},{-r:g}z")


def paths(markup, color="#FFFFFF", stroke_scale=1.0):
    """VectorDrawable <path> elements for one <symbol>."""
    root = attrs(re.match(r"<symbol[^>]*>", markup).group(0))
    out_paths = []
    for tag, kind in re.findall(r"(<(path|circle|rect)\b[^>]*>)", markup):
        a = {**root, **attrs(tag)}
        d = a["d"] if kind == "path" else circle(a) if kind == "circle" else rect(a)
        fill = a.get("fill", "none")
        stroke = a.get("stroke", "none")
        p = f'<path android:pathData="{d}"'
        if fill != "none":
            p += f' android:fillColor="{color}"'
        if stroke != "none":
            sw = float(a.get("stroke-width", "1.9")) * stroke_scale
            p += (f' android:strokeColor="{color}" android:strokeWidth="{sw:g}"'
                  ' android:strokeLineCap="round" android:strokeLineJoin="round"')
        out_paths.append(p + " />")
    return out_paths


def vector(body, size=24, viewport=24):
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    android:width="{size}dp" android:height="{size}dp"\n'
            f'    android:viewportWidth="{viewport}" android:viewportHeight="{viewport}">\n    '
            + "\n    ".join(body) + "\n</vector>\n")


def write(name, text):
    with open(os.path.join(out, name), "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


for k in WANTED:
    write(f"ic_ui_{k}.xml", vector(paths(symbols[k])))

# Notification small icon: the tray at the design's 16dp weight (2.1 instead of 1.9).
write("ic_stat_download.xml", vector(paths(symbols["tray"], stroke_scale=2.1 / 1.9)))


def on_circle(k, glyph_color="#FFFFFF"):
    """48dp coral circle with a white glyph (pinned and static shortcuts)."""
    body = ['<path android:fillColor="#FF5A5A" android:pathData="M24,0a24,24 0 1,1 0,48a24,24 0 1,1 0,-48z" />',
            '<group android:translateX="12" android:translateY="12">',
            *("    " + p for p in paths(symbols[k], glyph_color)),
            "</group>"]
    return vector(body, 48, 48)


write("ic_shortcut_video.xml", on_circle("paste"))
write("ic_shortcut_audio.xml", on_circle("music"))

# Launcher foreground: the tray glyph, white, centred in the 66dp safe zone of the 108dp canvas.
fg = ['<group android:translateX="27" android:translateY="27" android:scaleX="2.25" android:scaleY="2.25">',
      *("    " + p for p in paths(symbols["tray"])), "</group>"]
write("ic_launcher_foreground.xml", vector(fg, 108, 108))
print("icons written:", len(WANTED) + 4)
