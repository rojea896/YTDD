import json
import os
import sys
import threading
import uuid

import pyperclip
import webview

import webview_patch  # noqa: F401 — must be imported before any window is created
import backend

_BASE_DIR = sys._MEIPASS if getattr(sys, "frozen", False) else os.path.dirname(os.path.abspath(__file__))
HTML_PATH = os.path.join(_BASE_DIR, "webapp", "index.html")


class Api:
    def __init__(self):
        self.window = None
        self.jobs = {}  # job_id -> backend.DownloadJob
        self._clipboard_thread = None
        self._clipboard_stop = threading.Event()
        self._last_clipboard = ""

    def set_window(self, window):
        self.window = window

    def _push(self, fn_name, *args):
        if not self.window:
            return
        payload = ", ".join(json.dumps(a) for a in args)
        try:
            self.window.evaluate_js(f"{fn_name}({payload})")
        except Exception:
            pass

    # ------------------------------------------------------------ Settings ---
    def get_settings(self):
        settings = backend.load_settings()
        if settings.get("clipboardMonitor"):
            self._start_clipboard_watch()
        return settings

    def save_settings(self, settings):
        backend.save_settings(settings)
        if settings.get("clipboardMonitor"):
            self._start_clipboard_watch()
        else:
            self._stop_clipboard_watch()
        return True

    def get_audio_quality_options(self):
        return backend.AUDIO_QUALITY_OPTIONS

    # ------------------------------------------------------------- History ---
    def get_history(self):
        return backend.load_history()

    def clear_history(self):
        backend.save_history([])
        return True

    def open_folder(self, filepath):
        folder = os.path.dirname(filepath)
        if os.path.isdir(folder):
            os.startfile(folder)
            return True
        return False

    # --------------------------------------------------------------- Fetch ---
    def check_playlist(self, url):
        return backend.check_playlist(url)

    def fetch_info(self, url):
        settings = backend.load_settings()
        try:
            return {"ok": True, "data": backend.fetch_info(url, settings)}
        except Exception as exc:
            return {"ok": False, "error": str(exc)}

    # ------------------------------------------------------------ Download ---
    def choose_destination(self):
        result = self.window.create_file_dialog(webview.FOLDER_DIALOG)
        if not result:
            return None
        return result[0]

    def choose_cookies_file(self):
        result = self.window.create_file_dialog(
            webview.OPEN_DIALOG, file_types=("Cookie files (*.txt)", "All files (*.*)")
        )
        if not result:
            return None
        return result[0]

    def start_download(self, url, job, dest_dir, scope):
        settings = backend.load_settings()
        job_id = str(uuid.uuid4())
        full_job = dict(job)
        full_job["scope"] = scope

        def on_progress(jid, pct, speed, eta, status):
            self._push("onQueueProgress", jid, pct, speed, eta, status)

        def on_done(jid, success, message, entries):
            self.jobs.pop(jid, None)
            if success and entries:
                history = backend.load_history()
                history = entries[::-1] + history
                backend.save_history(history)
            self._push("onQueueDone", jid, success, message, entries or [])

        dl = backend.DownloadJob(job_id, url, full_job, dest_dir, settings, on_progress, on_done)
        self.jobs[job_id] = dl
        dl.start()
        return job_id

    def pause_download(self, job_id):
        job = self.jobs.get(job_id)
        if job:
            job.pause()
        return bool(job)

    def resume_download(self, job_id):
        job = self.jobs.get(job_id)
        if job:
            job.resume()
        return bool(job)

    def cancel_download(self, job_id):
        job = self.jobs.get(job_id)
        if job:
            job.cancel()
        return bool(job)

    # --------------------------------------------------------- Clipboard ---
    def _start_clipboard_watch(self):
        if self._clipboard_thread and self._clipboard_thread.is_alive():
            return
        self._clipboard_stop.clear()
        self._clipboard_thread = threading.Thread(target=self._watch_clipboard, daemon=True)
        self._clipboard_thread.start()

    def _stop_clipboard_watch(self):
        self._clipboard_stop.set()

    def _watch_clipboard(self):
        import time
        while not self._clipboard_stop.is_set():
            try:
                text = pyperclip.paste().strip()
            except Exception:
                text = ""
            if text and text != self._last_clipboard and self._looks_like_video_url(text):
                self._last_clipboard = text
                self._push("onClipboardUrl", text)
            elif text:
                self._last_clipboard = text
            time.sleep(1.5)

    @staticmethod
    def _looks_like_video_url(text):
        markers = ("youtube.com/watch", "youtu.be/", "youtube.com/shorts", "instagram.com/")
        return text.startswith("http") and any(m in text for m in markers)

def main():
    api = Api()
    window = webview.create_window(
        "YT-DLP Downloader",
        HTML_PATH,
        js_api=api,
        width=560,
        height=760,
        min_size=(560, 640),
        background_color="#111827",
    )
    api.set_window(window)
    webview.start()


if __name__ == "__main__":
    main()
