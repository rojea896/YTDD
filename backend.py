"""Shared yt-dlp backend logic used by the GUI front end (gui_app.py).

Kept separate from downloader.py (the original tkinter app, left intact)
so both front ends can reuse the same download/history/settings logic.
"""
import json
import os
import threading
import time
from datetime import datetime

import yt_dlp
from yt_dlp.utils import DownloadCancelled

APP_DIR = os.path.join(os.environ.get("APPDATA") or os.path.expanduser("~"), "YTDLP-Downloader")
HISTORY_FILE = os.path.join(APP_DIR, "history.json")
SETTINGS_FILE = os.path.join(APP_DIR, "settings.json")
MAX_HISTORY_ENTRIES = 500
HISTORY_SIZE_WARNING_BYTES = 5 * 1024 * 1024  # 5 MB

DEFAULT_SETTINGS = {
    "darkMode": True,
    "notifyOnComplete": True,
    "clipboardMonitor": False,
    "cookiesEnabled": True,
    "cookiesMode": "auto",       # auto | browser | file
    "cookiesBrowser": "firefox",
    "cookiesFile": "",
    "codecPref": "auto",         # auto | av1 | vp9 | h264
    "containerFormat": "mp4",    # mp4 | mkv | webm
    "filesizeLimit": "",
    "preferSmaller": False,
    "rateLimit": "",
    "userAgent": "",
}

AUDIO_QUALITY_OPTIONS = [
    {"id": "mp3-320", "label": "MP3 320kbps", "codec": "mp3", "bitrate": "320"},
    {"id": "mp3-192", "label": "MP3 192kbps", "codec": "mp3", "bitrate": "192"},
    {"id": "m4a-256", "label": "M4A 256kbps", "codec": "m4a", "bitrate": "256"},
    {"id": "flac", "label": "FLAC (lossless)", "codec": "flac", "bitrate": None},
    {"id": "wav", "label": "WAV (lossless)", "codec": "wav", "bitrate": None},
]

VIDEO_HEIGHT_LADDER = [2160, 1440, 1080, 720, 480, 360, 240]

TRANSIENT_ERROR_HINTS = ("403", "forbidden", "429", "too many requests", "timed out", "timeout")
MAX_ATTEMPTS = 3


class Paused(Exception):
    pass


def _codec_filter(codec_pref):
    return {
        "av1": "[vcodec^=av01]",
        "vp9": "[vcodec^=vp9]",
        "h264": "[vcodec^=avc1]",
    }.get(codec_pref, "")


# Priority order for auto mode: firefox first since it stores cookies
# unencrypted and yt-dlp reads them directly; Chromium-based browsers can
# fail here on Chrome 127+'s App-Bound Encryption, which yt-dlp can't
# decrypt via Windows DPAPI yet (https://github.com/yt-dlp/yt-dlp/issues/10927).
_AUTO_COOKIE_BROWSERS = ["firefox", "chrome", "edge", "brave", "opera", "vivaldi"]
_auto_cookie_file_cache = None  # None = not tried yet; False = tried, nothing worked; str = cached file path


def _auto_extract_cookie_file():
    """Try each browser in priority order, caching the first one that yields
    real cookies to a Netscape cookies.txt under APP_DIR. A browser that
    isn't installed or fails to decrypt (e.g. DPAPI) is silently skipped
    rather than failing the whole fetch - this is what makes "auto" safe
    to leave on by default."""
    global _auto_cookie_file_cache
    if _auto_cookie_file_cache is not None:
        return _auto_cookie_file_cache or None

    from yt_dlp.cookies import extract_cookies_from_browser

    for browser in _AUTO_COOKIE_BROWSERS:
        try:
            jar = extract_cookies_from_browser(browser)
            if len(jar) == 0:
                continue
            cache_path = os.path.join(APP_DIR, "auto_cookies.txt")
            os.makedirs(APP_DIR, exist_ok=True)
            jar.save(cache_path, ignore_discard=True, ignore_expires=True)
            _auto_cookie_file_cache = cache_path
            return cache_path
        except Exception:
            continue

    _auto_cookie_file_cache = False
    return None


def cookie_opts(settings):
    if not settings.get("cookiesEnabled", True):
        return {}
    mode = settings.get("cookiesMode", "auto")
    if mode == "file":
        return {"cookiefile": settings["cookiesFile"]} if settings.get("cookiesFile") else {}
    if mode == "browser":
        return {"cookiesfrombrowser": (settings.get("cookiesBrowser", "firefox").lower(),)}
    # auto: prefer a manually-configured file (already proven to work if set),
    # otherwise try to silently auto-extract from whichever browser works.
    if settings.get("cookiesFile") and os.path.exists(settings["cookiesFile"]):
        return {"cookiefile": settings["cookiesFile"]}
    auto_file = _auto_extract_cookie_file()
    return {"cookiefile": auto_file} if auto_file else {}


def load_settings():
    try:
        with open(SETTINGS_FILE, "r", encoding="utf-8") as f:
            data = json.load(f)
        merged = dict(DEFAULT_SETTINGS)
        merged.update(data if isinstance(data, dict) else {})
        return merged
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return dict(DEFAULT_SETTINGS)


def save_settings(settings):
    os.makedirs(APP_DIR, exist_ok=True)
    with open(SETTINGS_FILE, "w", encoding="utf-8") as f:
        json.dump(settings, f, indent=2)


def load_history():
    try:
        with open(HISTORY_FILE, "r", encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, list) else []
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return []


def save_history(history):
    os.makedirs(APP_DIR, exist_ok=True)
    with open(HISTORY_FILE, "w", encoding="utf-8") as f:
        json.dump(history[:MAX_HISTORY_ENTRIES], f, indent=2)


def history_file_size():
    try:
        return os.path.getsize(HISTORY_FILE)
    except OSError:
        return 0


def _human_size(num_bytes):
    if not num_bytes:
        return None
    size = float(num_bytes)
    for unit in ("B", "KB", "MB", "GB"):
        if size < 1024 or unit == "GB":
            return f"{size:.0f} {unit}" if unit == "B" else f"{size:.1f} {unit}"
        size /= 1024


def fetch_info(url, settings):
    """Return metadata + available quality options for a single video."""
    opts = {
        "quiet": True,
        "no_warnings": True,
        "skip_download": True,
        "noplaylist": True,
        **cookie_opts(settings),
    }
    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=False)

    formats = info.get("formats", [])
    duration = info.get("duration") or 0

    video_formats = [
        f for f in formats if f.get("vcodec") not in (None, "none") and f.get("height")
    ]
    heights = sorted({f["height"] for f in video_formats}, reverse=True)
    if not heights:
        heights = VIDEO_HEIGHT_LADDER[2:]

    def _size_of(f):
        return f.get("filesize") or f.get("filesize_approx") or 0

    best_audio_size = max((_size_of(f) for f in formats if f.get("acodec") not in (None, "none")), default=0)

    video_qualities = []
    for h in heights:
        best_video_size = max((_size_of(f) for f in video_formats if f.get("height") == h), default=0)
        total = (best_video_size + best_audio_size) if best_video_size else 0
        video_qualities.append({"label": f"{h}p", "size": _human_size(total)})

    # Bitrate-based estimate (kbps * duration); lossless formats use typical
    # PCM/FLAC rates since there's no fixed target bitrate to compute from.
    audio_quality_sizes = {}
    for o in AUDIO_QUALITY_OPTIONS:
        if o["bitrate"]:
            kbps = int(o["bitrate"])
        elif o["codec"] == "wav":
            kbps = 1411
        elif o["codec"] == "flac":
            kbps = 700
        else:
            kbps = 0
        size = int(kbps * 1000 / 8 * duration) if kbps and duration else 0
        audio_quality_sizes[o["id"]] = _human_size(size)

    return {
        "title": info.get("title") or url,
        "channel": info.get("uploader") or info.get("channel") or "",
        "duration": duration,
        "thumbnail": info.get("thumbnail") or "",
        "videoQualities": video_qualities,
        "audioQualities": [o["id"] for o in AUDIO_QUALITY_OPTIONS],
        "audioQualitySizes": audio_quality_sizes,
    }


def check_playlist(url):
    from urllib.parse import urlparse, parse_qs
    return "list" in parse_qs(urlparse(url).query)


def build_ydl_opts(job, dest_dir, settings, progress_hook):
    scope = job.get("scope", "single")
    if scope == "playlist":
        outtmpl = os.path.join(
            dest_dir, "%(playlist_title)s", "%(playlist_index)s - %(title)s.%(ext)s"
        )
    else:
        outtmpl = os.path.join(dest_dir, "%(title)s.%(ext)s")

    opts = {
        "outtmpl": outtmpl,
        "progress_hooks": [progress_hook],
        "noprogress": True,
        "quiet": True,
        "no_warnings": True,
        "noplaylist": scope != "playlist",
        "ignoreerrors": scope == "playlist",
        "retries": 10,
        "fragment_retries": 10,
        "extractor_retries": 3,
        "file_access_retries": 5,
        **cookie_opts(settings),
    }

    if os.environ.get("FFMPEG_LOCATION"):
        opts["ffmpeg_location"] = os.environ["FFMPEG_LOCATION"]

    if settings.get("rateLimit"):
        opts["ratelimit"] = _parse_size(settings["rateLimit"])
    if settings.get("filesizeLimit"):
        opts["max_filesize"] = _parse_size(settings["filesizeLimit"])
    if settings.get("userAgent"):
        opts["http_headers"] = {"User-Agent": settings["userAgent"]}

    codec_filter = _codec_filter(settings.get("codecPref", "auto"))
    prefer_smaller = settings.get("preferSmaller", False)
    if prefer_smaller:
        opts["format_sort"] = ["+size"]

    if job["mediaType"] == "audio":
        audio_choice = next(
            (o for o in AUDIO_QUALITY_OPTIONS if o["id"] == job["quality"]),
            AUDIO_QUALITY_OPTIONS[0],
        )
        opts["format"] = f"bestaudio{codec_filter}/bestaudio/best"
        pp = {"key": "FFmpegExtractAudio", "preferredcodec": audio_choice["codec"]}
        if audio_choice["bitrate"]:
            pp["preferredquality"] = audio_choice["bitrate"]
        opts["postprocessors"] = [pp]
    else:
        height = int(str(job["quality"]).rstrip("p"))
        container = settings.get("containerFormat", "mp4")
        opts["format"] = (
            f"bestvideo{codec_filter}[height<={height}]+bestaudio/"
            f"best{codec_filter}[height<={height}]/"
            f"bestvideo[height<={height}]+bestaudio/best[height<={height}]"
        )
        opts["merge_output_format"] = container

    return opts


def _parse_size(text):
    """Turn '2M', '500K', '10' into yt-dlp's expected numeric bytes-per-second / bytes."""
    text = text.strip().upper()
    multipliers = {"K": 1024, "M": 1024 ** 2, "G": 1024 ** 3}
    if text and text[-1] in multipliers:
        try:
            return float(text[:-1]) * multipliers[text[-1]]
        except ValueError:
            return None
    try:
        return float(text)
    except ValueError:
        return None


class DownloadJob:
    """Runs one download in a background thread with pause/cancel/progress support."""

    def __init__(self, job_id, url, job, dest_dir, settings, on_progress, on_done):
        self.job_id = job_id
        self.url = url
        self.job = job
        self.dest_dir = dest_dir
        self.settings = settings
        self.on_progress = on_progress
        self.on_done = on_done
        self._paused = threading.Event()
        self._cancelled = threading.Event()
        self._thread = threading.Thread(target=self._run, daemon=True)

    def start(self):
        self._thread.start()

    def pause(self):
        self._paused.set()

    def resume(self):
        self._paused.clear()

    def cancel(self):
        self._cancelled.set()
        self._paused.clear()

    def _hook(self, d):
        if self._cancelled.is_set():
            raise DownloadCancelled("cancelled by user")
        while self._paused.is_set():
            if self._cancelled.is_set():
                raise DownloadCancelled("cancelled by user")
            time.sleep(0.3)

        if d["status"] == "downloading":
            total = d.get("total_bytes") or d.get("total_bytes_estimate")
            downloaded = d.get("downloaded_bytes", 0)
            pct = (downloaded / total * 100) if total else 0.0
            speed = d.get("speed") or 0
            eta = d.get("eta")
            self.on_progress(self.job_id, pct, speed, eta, "downloading")
        elif d["status"] == "finished":
            self.on_progress(self.job_id, 100.0, 0, 0, "processing")

    def _run(self):
        last_exc = None
        for attempt in range(1, MAX_ATTEMPTS + 1):
            if self._cancelled.is_set():
                self.on_done(self.job_id, False, "Cancelled", None)
                return
            try:
                opts = build_ydl_opts(self.job, self.dest_dir, self.settings, self._hook)
                with yt_dlp.YoutubeDL(opts) as ydl:
                    info = ydl.extract_info(self.url, download=True)
                entries = history_entries_from_info(info, self.job["mediaType"])
                self.on_done(self.job_id, True, "Download complete.", entries)
                return
            except DownloadCancelled:
                self.on_done(self.job_id, False, "Cancelled", None)
                return
            except Exception as exc:
                last_exc = exc
                is_transient = any(h in str(exc).lower() for h in TRANSIENT_ERROR_HINTS)
                if is_transient and attempt < MAX_ATTEMPTS:
                    time.sleep(3 * attempt)
                    continue
                break
        self.on_done(self.job_id, False, f"Error: {last_exc}", None)


def history_entries_from_info(info, media_type):
    if info is None:
        return []
    candidates = info.get("entries") if info.get("_type") == "playlist" else [info]
    now = datetime.now().strftime("%Y-%m-%d %H:%M")
    entries = []
    for entry in candidates or []:
        if not entry:
            continue
        requested = entry.get("requested_downloads") or []
        filepath = requested[0].get("filepath") if requested else None
        if not filepath:
            continue
        entries.append({
            "date": now,
            "title": entry.get("title") or os.path.basename(filepath),
            "type": media_type,
            "filepath": filepath,
        })
    return entries
