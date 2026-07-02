"""Stdio JSON-RPC server wrapping backend.py, spawned by the Electron main process.

Protocol (newline-delimited JSON, UTF-8):
  Request  (stdin):  {"id": <int>, "method": "<name>", "params": {...}}
  Response (stdout): {"id": <int>, "result": ...} or {"id": <int>, "error": "..."}
  Push event (stdout, no id): {"event": "<name>", "data": {...}}

Folder/file pickers and clipboard access are intentionally NOT handled here —
those are native OS dialogs/APIs better owned by Electron's main process.
"""
import json
import sys
import threading

import backend

_stdout_lock = threading.Lock()


def send(obj):
    with _stdout_lock:
        sys.stdout.write(json.dumps(obj) + "\n")
        sys.stdout.flush()


def send_event(name, data):
    send({"event": name, "data": data})


class RpcServer:
    def __init__(self):
        self.jobs = {}  # job_id -> backend.DownloadJob

    # -------------------------------------------------------------- RPC ---
    def get_settings(self, params):
        return backend.load_settings()

    def save_settings(self, params):
        backend.save_settings(params["settings"])
        return True

    def get_audio_quality_options(self, params):
        return backend.AUDIO_QUALITY_OPTIONS

    def get_history(self, params):
        return backend.load_history()

    def clear_history(self, params):
        backend.save_history([])
        return True

    def check_playlist(self, params):
        return backend.check_playlist(params["url"])

    def fetch_info(self, params):
        settings = backend.load_settings()
        try:
            return {"ok": True, "data": backend.fetch_info(params["url"], settings)}
        except Exception as exc:
            return {"ok": False, "error": str(exc)}

    def start_download(self, params):
        settings = backend.load_settings()
        job_id = params["jobId"]
        job = dict(params["job"])
        job["scope"] = params["scope"]
        dest_dir = params["destDir"]
        url = params["url"]

        def on_progress(jid, pct, speed, eta, status):
            send_event("queueProgress", {
                "jobId": jid, "progress": pct, "speed": speed, "eta": eta, "status": status,
            })

        def on_done(jid, success, message, entries):
            self.jobs.pop(jid, None)
            if success and entries:
                history = backend.load_history()
                history = entries[::-1] + history
                backend.save_history(history)
            send_event("queueDone", {
                "jobId": jid, "success": success, "message": message, "entries": entries or [],
            })

        dl = backend.DownloadJob(job_id, url, job, dest_dir, settings, on_progress, on_done)
        self.jobs[job_id] = dl
        dl.start()
        return job_id

    def pause_download(self, params):
        job = self.jobs.get(params["jobId"])
        if job:
            job.pause()
        return bool(job)

    def resume_download(self, params):
        job = self.jobs.get(params["jobId"])
        if job:
            job.resume()
        return bool(job)

    def cancel_download(self, params):
        job = self.jobs.get(params["jobId"])
        if job:
            job.cancel()
        return bool(job)

    # ------------------------------------------------------------- Loop ---
    def run(self):
        for line in sys.stdin:
            line = line.strip()
            if not line:
                continue
            try:
                req = json.loads(line)
            except json.JSONDecodeError:
                continue
            threading.Thread(target=self._handle, args=(req,), daemon=True).start()

    def _handle(self, req):
        req_id = req.get("id")
        method_name = req.get("method")
        params = req.get("params") or {}
        method = getattr(self, method_name, None)
        if method is None:
            send({"id": req_id, "error": f"Unknown method: {method_name}"})
            return
        try:
            result = method(params)
            send({"id": req_id, "result": result})
        except Exception as exc:
            send({"id": req_id, "error": str(exc)})


if __name__ == "__main__":
    RpcServer().run()
