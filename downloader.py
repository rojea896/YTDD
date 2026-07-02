import json
import os
import sys
import threading
import time
import tkinter as tk
from datetime import datetime
from tkinter import ttk, filedialog, messagebox
from urllib.parse import urlparse, parse_qs

import yt_dlp

APP_TITLE = "YT-DLP Downloader"

AUDIO_FORMATS = ["mp3", "m4a", "opus"]
BROWSER_OPTIONS = ["None", "Chrome", "Edge", "Firefox", "Brave", "Opera", "Vivaldi"]

HISTORY_DIR = os.path.join(os.environ.get("APPDATA") or os.path.expanduser("~"), "YTDLP-Downloader")
HISTORY_FILE = os.path.join(HISTORY_DIR, "history.json")
MAX_HISTORY_ENTRIES = 500


class PlaylistChoiceDialog(tk.Toplevel):
    """Modal dialog asking whether to grab just this video or the whole playlist."""

    def __init__(self, parent):
        super().__init__(parent)
        self.title(APP_TITLE)
        self.resizable(False, False)
        self.transient(parent)
        self.grab_set()
        self.result = None

        ttk.Label(
            self,
            text="This link is part of a playlist.\nWhat would you like to download?",
            justify="center",
        ).pack(padx=24, pady=(20, 14))

        btn_frame = ttk.Frame(self)
        btn_frame.pack(pady=(0, 20))
        ttk.Button(
            btn_frame, text="This Video Only", command=lambda: self._choose("single")
        ).grid(row=0, column=0, padx=8)
        ttk.Button(
            btn_frame, text="Entire Playlist", command=lambda: self._choose("playlist")
        ).grid(row=0, column=1, padx=8)

        self.protocol("WM_DELETE_WINDOW", lambda: self._choose(None))
        self.update_idletasks()
        x = parent.winfo_rootx() + (parent.winfo_width() - self.winfo_width()) // 2
        y = parent.winfo_rooty() + (parent.winfo_height() - self.winfo_height()) // 2
        self.geometry(f"+{x}+{y}")

    def _choose(self, value):
        self.result = value
        self.destroy()


class DownloaderApp(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title(APP_TITLE)
        self.geometry("620x700")
        self.minsize(560, 560)

        self.media_type = tk.StringVar(value="video")
        self.audio_format = tk.StringVar(value=AUDIO_FORMATS[0])
        self.audio_quality = tk.StringVar()
        self.video_quality = tk.StringVar()
        self.browser_cookies = tk.StringVar(value=BROWSER_OPTIONS[0])
        self.status_text = tk.StringVar(value="Enter a link, then click Fetch Info.")
        self.progress_value = tk.DoubleVar(value=0.0)

        self.download_scope = "single"  # "single" or "playlist"
        self.analyzed_url = None
        self.video_heights = []   # e.g. [1080, 720, 480, 360]
        self.audio_bitrates = []  # e.g. [160, 128, 70]

        self.history = self._load_history()

        self._build_ui()
        self._populate_history_tree()

    # ---------------------------------------------------------------- UI ---
    def _build_ui(self):
        pad = {"padx": 12, "pady": 6}

        url_frame = ttk.Frame(self)
        url_frame.pack(fill="x", **pad)
        ttk.Label(url_frame, text="Video link:").pack(anchor="w")
        entry_row = ttk.Frame(url_frame)
        entry_row.pack(fill="x", pady=(4, 0))
        self.url_entry = ttk.Entry(entry_row, width=52)
        self.url_entry.pack(side="left", fill="x", expand=True)
        self.fetch_btn = ttk.Button(entry_row, text="Fetch Info", command=self._on_fetch_clicked)
        self.fetch_btn.pack(side="left", padx=(8, 0))

        cookie_row = ttk.Frame(url_frame)
        cookie_row.pack(fill="x", pady=(6, 0))
        ttk.Label(cookie_row, text="Login required (Instagram, etc.) — use cookies from:").pack(side="left")
        ttk.Combobox(
            cookie_row, textvariable=self.browser_cookies, values=BROWSER_OPTIONS,
            state="readonly", width=10,
        ).pack(side="left", padx=(8, 0))

        type_frame = ttk.LabelFrame(self, text="Download type")
        type_frame.pack(fill="x", **pad)
        ttk.Radiobutton(
            type_frame, text="Audio", variable=self.media_type, value="audio",
            command=self._refresh_options,
        ).grid(row=0, column=0, padx=10, pady=8, sticky="w")
        ttk.Radiobutton(
            type_frame, text="Video (mp4)", variable=self.media_type, value="video",
            command=self._refresh_options,
        ).grid(row=0, column=1, padx=10, pady=8, sticky="w")

        self.options_frame = ttk.LabelFrame(self, text="Options")
        self.options_frame.pack(fill="x", **pad)
        self._build_audio_options()
        self._build_video_options()
        self._refresh_options()

        self.download_btn = ttk.Button(
            self, text="Download", command=self._on_download_clicked, state="disabled"
        )
        self.download_btn.pack(pady=(4, 8))

        progress_frame = ttk.Frame(self)
        progress_frame.pack(fill="x", **pad)
        self.progress_bar = ttk.Progressbar(
            progress_frame, variable=self.progress_value, maximum=100.0
        )
        self.progress_bar.pack(fill="x")

        status_label = ttk.Label(self, textvariable=self.status_text, wraplength=580)
        status_label.pack(fill="x", padx=12, pady=(0, 10))

        self._build_history_section(pad)

    def _build_history_section(self, pad):
        history_frame = ttk.LabelFrame(self, text="Download History")
        history_frame.pack(fill="both", expand=True, **pad)

        columns = ("date", "title", "type", "location")
        self.history_tree = ttk.Treeview(
            history_frame, columns=columns, show="headings", height=8
        )
        self.history_tree.heading("date", text="Date")
        self.history_tree.heading("title", text="Title")
        self.history_tree.heading("type", text="Type")
        self.history_tree.heading("location", text="Location")
        self.history_tree.column("date", width=110, anchor="w")
        self.history_tree.column("title", width=220, anchor="w")
        self.history_tree.column("type", width=90, anchor="w")
        self.history_tree.column("location", width=140, anchor="w")

        scrollbar = ttk.Scrollbar(history_frame, orient="vertical", command=self.history_tree.yview)
        self.history_tree.configure(yscrollcommand=scrollbar.set)
        self.history_tree.pack(side="left", fill="both", expand=True, padx=(6, 0), pady=6)
        scrollbar.pack(side="left", fill="y", pady=6)

        self.history_tree.bind("<Double-1>", self._on_history_double_click)

        btn_col = ttk.Frame(history_frame)
        btn_col.pack(side="left", fill="y", padx=8, pady=6)
        ttk.Button(btn_col, text="Open Folder", command=self._open_selected_history_folder).pack(
            fill="x", pady=(0, 6)
        )
        ttk.Button(btn_col, text="Clear History", command=self._clear_history).pack(fill="x")

    def _build_audio_options(self):
        self.audio_frame = ttk.Frame(self.options_frame)
        ttk.Label(self.audio_frame, text="Format:").grid(row=0, column=0, sticky="w", padx=10, pady=8)
        self.audio_format_combo = ttk.Combobox(
            self.audio_frame, textvariable=self.audio_format, values=AUDIO_FORMATS,
            state="readonly", width=10,
        )
        self.audio_format_combo.grid(row=0, column=1, padx=10, pady=8, sticky="w")

        ttk.Label(self.audio_frame, text="Quality:").grid(row=0, column=2, sticky="w", padx=10, pady=8)
        self.audio_quality_combo = ttk.Combobox(
            self.audio_frame, textvariable=self.audio_quality,
            values=["Fetch Info first"], state="readonly", width=16,
        )
        self.audio_quality_combo.current(0)
        self.audio_quality_combo.grid(row=0, column=3, padx=10, pady=8, sticky="w")

    def _build_video_options(self):
        self.video_frame = ttk.Frame(self.options_frame)
        ttk.Label(self.video_frame, text="Quality:").grid(row=0, column=0, sticky="w", padx=10, pady=8)
        self.video_quality_combo = ttk.Combobox(
            self.video_frame, textvariable=self.video_quality,
            values=["Fetch Info first"], state="readonly", width=16,
        )
        self.video_quality_combo.current(0)
        self.video_quality_combo.grid(row=0, column=1, padx=10, pady=8, sticky="w")

    def _cookie_opts(self):
        browser = self.browser_cookies.get()
        if browser == "None":
            return {}
        return {"cookiesfrombrowser": (browser.lower(),)}

    def _refresh_options(self):
        self.audio_frame.pack_forget()
        self.video_frame.pack_forget()
        if self.media_type.get() == "audio":
            self.audio_frame.pack(fill="x")
        else:
            self.video_frame.pack(fill="x")

    # --------------------------------------------------------- Fetch info ---
    def _on_fetch_clicked(self):
        url = self.url_entry.get().strip()
        if not url:
            messagebox.showwarning(APP_TITLE, "Please enter a video link first.")
            return

        scope = "single"
        query = parse_qs(urlparse(url).query)
        if "list" in query:
            dialog = PlaylistChoiceDialog(self)
            self.wait_window(dialog)
            if dialog.result is None:
                return
            scope = dialog.result

        self.download_scope = scope
        self.fetch_btn.config(state="disabled")
        self.download_btn.config(state="disabled")
        self.status_text.set("Fetching available qualities...")

        thread = threading.Thread(target=self._run_fetch, args=(url,), daemon=True)
        thread.start()

    def _run_fetch(self, url):
        try:
            opts = {
                "quiet": True,
                "no_warnings": True,
                "skip_download": True,
                "noplaylist": True,  # always inspect the single target video's formats
                **self._cookie_opts(),
            }
            with yt_dlp.YoutubeDL(opts) as ydl:
                info = ydl.extract_info(url, download=False)

            formats = info.get("formats", [])
            heights = sorted({
                f["height"] for f in formats
                if f.get("vcodec") != "none" and f.get("height")
            }, reverse=True)
            bitrates = sorted({
                round(f["abr"]) for f in formats
                if f.get("acodec") != "none" and f.get("abr")
            }, reverse=True)

            if not heights:
                heights = [720, 480, 360]
            if not bitrates:
                bitrates = [128]

            self.after(0, self._on_fetch_done, True, url, heights, bitrates, None)
        except Exception as exc:
            self.after(0, self._on_fetch_done, False, url, [], [], str(exc))

    def _on_fetch_done(self, success, url, heights, bitrates, error):
        self.fetch_btn.config(state="normal")
        if not success:
            self.status_text.set(f"Could not fetch info: {error}")
            messagebox.showerror(APP_TITLE, f"Could not fetch video info:\n{error}")
            return

        self.analyzed_url = url
        self.video_heights = heights
        self.audio_bitrates = bitrates

        video_values = [f"{h}p" for h in heights]
        self.video_quality_combo.config(values=video_values)
        self.video_quality_combo.current(0)

        audio_values = [f"{b} kbps" for b in bitrates]
        self.audio_quality_combo.config(values=audio_values)
        self.audio_quality_combo.current(0)

        scope_note = "entire playlist" if self.download_scope == "playlist" else "this video"
        self.status_text.set(
            f"Found {len(heights)} video quality option(s) and {len(bitrates)} audio quality "
            f"option(s) for {scope_note}. Choose options and click Download."
        )
        self.download_btn.config(state="normal")

    # ------------------------------------------------------------ Actions ---
    def _on_download_clicked(self):
        url = self.url_entry.get().strip()
        if not url:
            messagebox.showwarning(APP_TITLE, "Please enter a video link first.")
            return
        if url != self.analyzed_url:
            messagebox.showwarning(
                APP_TITLE, "The link changed since Fetch Info ran. Click Fetch Info again."
            )
            return

        dest_dir = filedialog.askdirectory(title="Choose a download location")
        if not dest_dir:
            return

        self.download_btn.config(state="disabled")
        self.fetch_btn.config(state="disabled")
        self.progress_value.set(0.0)
        self.status_text.set("Starting download...")

        thread = threading.Thread(
            target=self._run_download, args=(url, dest_dir), daemon=True
        )
        thread.start()

    TRANSIENT_ERROR_HINTS = ("403", "forbidden", "429", "too many requests", "timed out", "timeout")
    MAX_ATTEMPTS = 3

    def _run_download(self, url, dest_dir):
        last_exc = None
        for attempt in range(1, self.MAX_ATTEMPTS + 1):
            try:
                ydl_opts = self._build_ydl_opts(dest_dir)
                with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                    info = ydl.extract_info(url, download=True)
                entries = self._history_entries_from_info(info)
                self.after(0, self._record_history_entries, entries)
                self.after(0, self._on_done, True, "Download complete.")
                return
            except Exception as exc:
                last_exc = exc
                is_transient = any(h in str(exc).lower() for h in self.TRANSIENT_ERROR_HINTS)
                if is_transient and attempt < self.MAX_ATTEMPTS:
                    self.after(
                        0, self._update_progress, 0.0,
                        f"Temporary error (attempt {attempt}/{self.MAX_ATTEMPTS}), retrying...",
                    )
                    time.sleep(3 * attempt)
                    continue
                break
        self.after(0, self._on_done, False, f"Error: {last_exc}")

    def _history_entries_from_info(self, info):
        if info is None:
            return []
        candidates = info.get("entries") if info.get("_type") == "playlist" else [info]
        media_type = self.media_type.get()
        entries = []
        now = datetime.now().strftime("%Y-%m-%d %H:%M")
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

    def _build_ydl_opts(self, dest_dir):
        if self.download_scope == "playlist":
            outtmpl = os.path.join(
                dest_dir, "%(playlist_title)s", "%(playlist_index)s - %(title)s.%(ext)s"
            )
        else:
            outtmpl = os.path.join(dest_dir, "%(title)s.%(ext)s")

        opts = {
            "outtmpl": outtmpl,
            "progress_hooks": [self._progress_hook],
            "noprogress": True,
            "quiet": True,
            "no_warnings": True,
            "noplaylist": self.download_scope != "playlist",
            "ignoreerrors": self.download_scope == "playlist",
            "retries": 10,
            "fragment_retries": 10,
            "extractor_retries": 3,
            "file_access_retries": 5,
            **self._cookie_opts(),
        }

        if self.media_type.get() == "audio":
            fmt = self.audio_format.get()
            bitrate = self._selected_audio_bitrate()
            opts["format"] = "bestaudio/best"
            opts["postprocessors"] = [{
                "key": "FFmpegExtractAudio",
                "preferredcodec": fmt,
                "preferredquality": str(bitrate),
            }]
        else:
            height = self._selected_video_height()
            opts["format"] = (
                f"bestvideo[ext=mp4][height<={height}]+bestaudio[ext=m4a]/"
                f"best[ext=mp4][height<={height}]/"
                f"bestvideo[height<={height}]+bestaudio/best[height<={height}]"
            )
            opts["merge_output_format"] = "mp4"

        return opts

    def _selected_video_height(self):
        text = self.video_quality.get().rstrip("p")
        return int(text) if text.isdigit() else (self.video_heights[0] if self.video_heights else 1080)

    def _selected_audio_bitrate(self):
        text = self.audio_quality.get().split()[0]
        return int(text) if text.isdigit() else (self.audio_bitrates[0] if self.audio_bitrates else 128)

    def _progress_hook(self, d):
        if d["status"] == "downloading":
            total = d.get("total_bytes") or d.get("total_bytes_estimate")
            downloaded = d.get("downloaded_bytes", 0)
            pct = (downloaded / total * 100) if total else 0
            speed = d.get("_speed_str", "").strip()
            filename = os.path.basename(d.get("filename", ""))
            self.after(0, self._update_progress, pct, f"Downloading {filename}... {pct:.1f}% {speed}")
        elif d["status"] == "finished":
            self.after(0, self._update_progress, 100.0, "Processing (converting/merging)...")

    def _update_progress(self, pct, message):
        self.progress_value.set(pct)
        self.status_text.set(message)

    def _on_done(self, success, message):
        self.download_btn.config(state="normal")
        self.fetch_btn.config(state="normal")
        self.status_text.set(message)
        if success:
            self.progress_value.set(100.0)
            messagebox.showinfo(APP_TITLE, message)
        else:
            messagebox.showerror(APP_TITLE, message)

    # ------------------------------------------------------------ History ---
    @staticmethod
    def _load_history():
        try:
            with open(HISTORY_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
            return data if isinstance(data, list) else []
        except (FileNotFoundError, json.JSONDecodeError, OSError):
            return []

    def _save_history(self):
        os.makedirs(HISTORY_DIR, exist_ok=True)
        with open(HISTORY_FILE, "w", encoding="utf-8") as f:
            json.dump(self.history[:MAX_HISTORY_ENTRIES], f, indent=2)

    def _record_history_entries(self, entries):
        if not entries:
            return
        self.history = entries[::-1] + self.history
        self._save_history()
        self._populate_history_tree()

    def _populate_history_tree(self):
        self.history_tree.delete(*self.history_tree.get_children())
        for entry in self.history:
            self.history_tree.insert(
                "", "end",
                values=(
                    entry.get("date", ""),
                    entry.get("title", ""),
                    entry.get("type", ""),
                    entry.get("filepath", ""),
                ),
            )

    def _on_history_double_click(self, _event):
        self._open_selected_history_folder()

    def _open_selected_history_folder(self):
        selection = self.history_tree.selection()
        if not selection:
            return
        filepath = self.history_tree.item(selection[0], "values")[3]
        folder = os.path.dirname(filepath)
        if os.path.isdir(folder):
            os.startfile(folder)
        else:
            messagebox.showwarning(APP_TITLE, "That folder no longer exists.")

    def _clear_history(self):
        if not self.history:
            return
        if messagebox.askyesno(APP_TITLE, "Clear all download history? This cannot be undone."):
            self.history = []
            self._save_history()
            self._populate_history_tree()


if __name__ == "__main__":
    app = DownloaderApp()
    app.mainloop()
