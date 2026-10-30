"""Shared yt-dlp backend logic used by the Electron front end (via backend_ipc.py).

Kept separate from downloader.py (the original tkinter app, left intact)
so both front ends can reuse the same download/history/settings logic.

yt-dlp itself is never imported at module level: ENGINE hands out whichever
copy is current - the one frozen into this exe, or a newer one it downloaded
(see ytdlp_engine.py). Always go through `with ENGINE.use() as yt_dlp:`.
"""
import importlib
import json
import os
import re
import sys
import threading
import time
from datetime import datetime

import premiere
from ytdlp_engine import Engine

APP_DIR = os.path.join(os.environ.get("APPDATA") or os.path.expanduser("~"), "YTDLP-Downloader")
HISTORY_FILE = os.path.join(APP_DIR, "history.json")
SETTINGS_FILE = os.path.join(APP_DIR, "settings.json")
MAX_HISTORY_ENTRIES = 500
HISTORY_SIZE_WARNING_BYTES = 5 * 1024 * 1024  # 5 MB

ENGINE = Engine(APP_DIR)

DEFAULT_SETTINGS = {
    "darkMode": True,
    "notifyOnComplete": True,
    "clipboardMonitor": False,
    "cookiesEnabled": True,
    "cookiesMode": "auto",       # auto | browser | file
    "cookiesBrowser": "firefox",
    "cookiesFile": "",
    "outputMode": "premiere",    # premiere | original
    "codecPref": "auto",         # auto | av1 | vp9 | h264   (original mode only)
    "containerFormat": "mp4",    # mp4 | mkv | webm          (original mode only)
    "filesizeLimit": "",
    "preferSmaller": False,
    "rateLimit": "",
    "userAgent": "",
    "autoUpdateEngine": True,
    "engineChannel": "nightly",  # nightly | stable
    "lastDestination": "",
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
# A failure triggers an engine update check, but not more often than this -
# yt-dlp doesn't publish twice in ten minutes.
FAILURE_CHECK_INTERVAL_MS = 10 * 60 * 1000


class _StderrLogger:
    """yt-dlp logger: stays off stdout (that's the JSON-RPC channel) but
    keeps warnings visible in the Electron console for debugging."""

    def debug(self, msg):
        pass

    def info(self, msg):
        pass

    def warning(self, msg):
        print(f"[yt-dlp] {msg}", file=sys.stderr, flush=True)

    def error(self, msg):
        print(f"[yt-dlp] {msg}", file=sys.stderr, flush=True)


def _js_runtime_opts():
    """YouTube's player challenges need a JS runtime. Electron's own binary,
    run with ELECTRON_RUN_AS_NODE=1 (set by main.js), is a full Node - so YTDD
    passes its own exe rather than making anyone install Deno."""
    path = os.environ.get("YTDD_JS_RUNTIME")
    if path and os.path.exists(path):
        return {"js_runtimes": {"node": {"path": path}}}
    # Backend run on its own (tests): use whatever is on PATH.
    return {"js_runtimes": {"deno": {}, "node": {}}}


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
    to leave on by default. Callers must hold ENGINE.use()."""
    global _auto_cookie_file_cache
    if _auto_cookie_file_cache is not None:
        return _auto_cookie_file_cache or None

    extract_cookies_from_browser = importlib.import_module("yt_dlp.cookies").extract_cookies_from_browser

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


def base_opts(settings):
    opts = {
        "quiet": True,
        "noprogress": True,
        "logger": _StderrLogger(),
        **_js_runtime_opts(),
        **cookie_opts(settings),
    }
    if os.environ.get("FFMPEG_LOCATION"):
        opts["ffmpeg_location"] = os.environ["FFMPEG_LOCATION"]
    if settings.get("userAgent"):
        opts["http_headers"] = {"User-Agent": settings["userAgent"]}
    return opts


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


def remove_history_entry(filepath, date, delete_file=False):
    history = load_history()
    new_history = [e for e in history if not (e.get("filepath") == filepath and e.get("date") == date)]
    save_history(new_history)
    if delete_file:
        try:
            os.remove(filepath)
        except OSError:
            pass
    return new_history


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


# ------------------------------------------------------------- errors ---

_ANSI = re.compile(r"\x1b\[[0-9;]*m")

_ERROR_HINTS = [
    (("confirm you're not a bot", "sign in to confirm you're not"),
     "YouTube wants a signed-in session for this one. Turn on Cookies in Settings and make sure "
     "you're signed in to YouTube in your browser."),
    (("sign in to confirm your age", "age-restricted", "inappropriate for some users"),
     "This video is age-restricted. Turn on Cookies in Settings and sign in to YouTube in your browser."),
    (("private video",), "This video is private."),
    (("members-only", "join this channel"),
     "This video is for channel members only — cookies from a member's account are needed."),
    (("requested format is not available",),
     "That quality isn't available for this video. Fetch it again and pick another quality."),
    (("unsupported url",), "YTDD doesn't recognise this link."),
    (("video unavailable", "video is unavailable", "video is not available", "has been removed"),
     "This video is unavailable — it may have been removed, or blocked in your region."),
    (("http error 429", "too many requests"),
     "The site is rate-limiting downloads right now. Wait a while and try again."),
    (("http error 403", "403: forbidden"), "The site refused the download (HTTP 403)."),
    (("could not copy", "cookie database", "failed to decrypt", "dpapi", "could not find firefox cookies"),
     "Couldn't read your browser's cookies. Close the browser and try again, or change the cookie "
     "source in Settings."),
    (("getaddrinfo", "name or service not known", "no address associated", "connection refused",
      "network is unreachable", "unable to download webpage", "timed out"),
     "Couldn't reach the site. Check your internet connection."),
]


def friendly_error(exc):
    """(message, detail): a plain-English line for the UI plus yt-dlp's own text."""
    raw = _ANSI.sub("", str(exc)).strip()
    raw = re.sub(r"^(ERROR:\s*)+", "", raw)
    lowered = raw.lower().replace("’", "'")
    for needles, message in _ERROR_HINTS:
        if any(n in lowered for n in needles):
            return message, raw
    return raw or "Something went wrong.", raw


# --------------------------------------------------------------- info ---

def _is_h264(f):
    return (f.get("vcodec") or "").startswith(("avc1", "h264"))


def _is_aac(f):
    return (f.get("acodec") or "").startswith(("mp4a", "aac"))


def fetch_info(url, settings):
    """Return metadata + available quality options for a single video."""
    with ENGINE.use() as yt_dlp:
        opts = {**base_opts(settings), "skip_download": True, "noplaylist": True}
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)

    formats = info.get("formats") or []
    duration = info.get("duration") or 0
    premiere_mode = settings.get("outputMode", "premiere") == "premiere"

    def _size_of(f):
        return f.get("filesize") or f.get("filesize_approx") or 0

    video_formats = [f for f in formats if f.get("vcodec") not in (None, "none") and f.get("height")]
    heights = sorted({f["height"] for f in video_formats}, reverse=True)
    if not heights:
        heights = VIDEO_HEIGHT_LADDER[2:]

    audio_only = [f for f in formats if f.get("acodec") not in (None, "none") and f.get("vcodec") in (None, "none")]
    audio_pool = audio_only or [f for f in formats if f.get("acodec") not in (None, "none")]
    if premiere_mode and any(_is_aac(f) for f in audio_pool):
        audio_pool = [f for f in audio_pool if _is_aac(f)]
    best_audio_size = max((_size_of(f) for f in audio_pool), default=0)

    video_qualities = []
    for h in heights:
        at_height = [f for f in video_formats if f.get("height") == h]
        h264 = [f for f in at_height if _is_h264(f)]
        pool = h264 if (premiere_mode and h264) else at_height
        best_video_size = max((_size_of(f) for f in pool), default=0)
        total = (best_video_size + best_audio_size) if best_video_size else 0
        video_qualities.append({
            "label": f"{h}p",
            "size": _human_size(total),
            # No H.264 at this height (YouTube stops at 1080p): the file will
            # be converted after downloading, which takes a while.
            "convert": bool(premiere_mode and video_formats and not h264),
        })

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
        **base_opts(settings),
        "outtmpl": outtmpl,
        "progress_hooks": [progress_hook],
        "noplaylist": scope != "playlist",
        "ignoreerrors": scope == "playlist",
        "retries": 10,
        "fragment_retries": 10,
        "extractor_retries": 3,
        "file_access_retries": 5,
    }

    if settings.get("rateLimit"):
        opts["ratelimit"] = _parse_size(settings["rateLimit"])
    if settings.get("filesizeLimit"):
        opts["max_filesize"] = _parse_size(settings["filesizeLimit"])
    prefer_smaller = settings.get("preferSmaller", False)

    if job["mediaType"] == "audio":
        audio_choice = next(
            (o for o in AUDIO_QUALITY_OPTIONS if o["id"] == job["quality"]),
            AUDIO_QUALITY_OPTIONS[0],
        )
        opts["format"] = "bestaudio/best"
        if prefer_smaller:
            opts["format_sort"] = ["+size"]
        pp = {"key": "FFmpegExtractAudio", "preferredcodec": audio_choice["codec"]}
        if audio_choice["bitrate"]:
            pp["preferredquality"] = audio_choice["bitrate"]
        opts["postprocessors"] = [pp]
        return opts

    height = int(str(job["quality"]).rstrip("p"))
    if settings.get("outputMode", "premiere") == "premiere":
        # Resolution first (what was picked), then the streams Premiere reads
        # natively: H.264 video, AAC audio, SDR. Anything this can't satisfy
        # is converted afterwards by premiere.make_premiere_ready().
        opts["format"] = f"bv*[height<={height}]+ba/b[height<={height}]/bv*+ba/b"
        opts["format_sort"] = [f"res:{height}", "fps", "hdr:sdr", "vcodec:h264", "acodec:aac"]
        if prefer_smaller:
            opts["format_sort"].append("+size")
        opts["merge_output_format"] = "mp4"
    else:
        codec_filter = _codec_filter(settings.get("codecPref", "auto"))
        container = settings.get("containerFormat", "mp4")
        opts["format"] = (
            f"bestvideo{codec_filter}[height<={height}]+bestaudio/"
            f"best{codec_filter}[height<={height}]/"
            f"bestvideo[height<={height}]+bestaudio/best[height<={height}]"
        )
        if prefer_smaller:
            # Smallest file at the chosen resolution - not the smallest file
            # overall, which would just be the lowest quality available.
            opts["format_sort"] = [f"res:{height}", "+size"]
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


def _recently_checked():
    status = ENGINE.status()
    last = status.get("lastChecked") or 0
    return status.get("lastResult") == "up-to-date" and (time.time() * 1000 - last) < FAILURE_CHECK_INTERVAL_MS


def try_self_heal(settings):
    """After a failure: fetch a newer yt-dlp if there is one. True if the
    engine in force changed, i.e. a retry is worth it."""
    if not settings.get("autoUpdateEngine", True) or _recently_checked():
        return False
    before = ENGINE.status().get("version")
    ENGINE.check_for_update(settings.get("engineChannel", "nightly"))
    return ENGINE.status().get("version") != before


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
        # Replaced per attempt by the DownloadCancelled class of the engine in
        # use - it must be that exact class or yt-dlp won't recognise it.
        self._cancel_exc = Exception
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

    def _stage(self, d):
        info = d.get("info_dict") or {}
        if self.job["mediaType"] != "video":
            stage = "Downloading audio"
        elif info.get("acodec") == "none":
            stage = "Downloading video"
        elif info.get("vcodec") == "none":
            stage = "Downloading audio"
        else:
            stage = "Downloading"
        index, count = info.get("playlist_index"), info.get("n_entries")
        if index and count:
            stage = f"Item {index} of {count} · {stage}"
        return stage

    def _hook(self, d):
        if self._cancelled.is_set():
            raise self._cancel_exc("cancelled by user")
        while self._paused.is_set():
            if self._cancelled.is_set():
                raise self._cancel_exc("cancelled by user")
            time.sleep(0.3)

        if d["status"] == "downloading":
            total = d.get("total_bytes") or d.get("total_bytes_estimate")
            downloaded = d.get("downloaded_bytes", 0)
            pct = (downloaded / total * 100) if total else 0.0
            speed = d.get("speed") or 0
            eta = d.get("eta")
            self.on_progress(self.job_id, pct, speed, eta, "downloading", self._stage(d))
        elif d["status"] == "finished":
            self.on_progress(self.job_id, 100.0, 0, 0, "processing", "Finishing up")

    def _run(self):
        last_exc = None
        healed = False
        attempt = 0
        while attempt < MAX_ATTEMPTS:
            attempt += 1
            if self._cancelled.is_set():
                return self.on_done(self.job_id, False, "Cancelled", None)
            try:
                with ENGINE.use() as yt_dlp:
                    self._cancel_exc = yt_dlp.utils.DownloadCancelled
                    opts = build_ydl_opts(self.job, self.dest_dir, self.settings, self._hook)
                    with yt_dlp.YoutubeDL(opts) as ydl:
                        info = ydl.extract_info(self.url, download=True)
            except Exception as exc:
                if self._cancelled.is_set() or isinstance(exc, self._cancel_exc):
                    return self.on_done(self.job_id, False, "Cancelled", None)
                last_exc = exc
                is_transient = any(h in str(exc).lower() for h in TRANSIENT_ERROR_HINTS)
                if is_transient and attempt < MAX_ATTEMPTS:
                    time.sleep(3 * attempt)
                    continue
                # Sites break yt-dlp all the time and fixes ship within days:
                # grab a newer engine if there is one and give it one more go.
                if not healed:
                    healed = True
                    self.on_progress(self.job_id, 0.0, 0, None, "processing", "Checking for a yt-dlp update")
                    if try_self_heal(self.settings):
                        attempt = min(attempt, MAX_ATTEMPTS - 1)
                        continue
                break
            else:
                entries = history_entries_from_info(info, self.job["mediaType"])
                if not entries:
                    last_exc = RuntimeError("Nothing was downloaded.")
                    break
                return self._finish(entries)

        message, detail = friendly_error(last_exc)
        self.on_done(self.job_id, False, message, None, detail)

    def _finish(self, entries):
        """Premiere-proof each downloaded video, then report."""
        if self.job["mediaType"] == "video" and self.settings.get("outputMode", "premiere") == "premiere":
            for entry in entries:
                def report(pct, eta, label):
                    self.on_progress(self.job_id, pct, 0, eta, "converting", label)
                try:
                    entry["filepath"] = premiere.make_premiere_ready(
                        entry["filepath"], on_progress=report, cancelled=self._cancelled.is_set,
                    )
                except premiere.ConversionCancelled:
                    # The download itself finished - keep it in history.
                    return self.on_done(self.job_id, False, "Cancelled", entries)
                except Exception as exc:
                    message = "Downloaded, but converting it for Premiere failed."
                    return self.on_done(self.job_id, False, message, entries, str(exc))
        self.on_done(self.job_id, True, "Download complete.", entries)


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
