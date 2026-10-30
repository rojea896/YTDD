"""Make a downloaded video import cleanly into Premiere Pro.

Premiere can't decode Opus audio (the clip imports silent), VP9 or AV1 video,
or MKV/WebM containers. YouTube's best streams are very often exactly those,
so after yt-dlp finishes, the file is probed and anything Premiere can't read
is converted: audio to AAC (seconds), video to H.264 (slower - uses the GPU
encoder when there is one). Streams that are already fine are copied as-is.
"""
import json
import os
import shutil
import subprocess
import threading
import time

PREMIERE_VIDEO = {"h264", "hevc", "prores", "dnxhd", "mpeg2video"}
PREMIERE_AUDIO = {"aac", "mp3"}
AUDIO_BITRATE = "320k"

_NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)

# Tried in order; the first that can actually encode a test frame on this
# machine wins (the probe also catches a driver too old for this ffmpeg's
# NVENC API). Quality levels were picked by SSIM against an AV1 source: all
# score >0.99, i.e. indistinguishable, without tripling the file size.
_ENCODERS = [
    ("h264_nvenc", ["-c:v", "h264_nvenc", "-preset", "p5", "-tune", "hq", "-rc", "vbr", "-cq", "23", "-b:v", "0"]),
    ("h264_qsv", ["-c:v", "h264_qsv", "-global_quality", "23"]),
    ("h264_amf", ["-c:v", "h264_amf", "-quality", "quality", "-rc", "cqp", "-qp_i", "20", "-qp_p", "22"]),
]
_SOFTWARE_ENCODER = ("libx264", ["-c:v", "libx264", "-preset", "fast", "-crf", "20"])

_encoder_lock = threading.Lock()
_hw_encoder_cache = {}


class ConversionCancelled(Exception):
    pass


def tool_path(name):
    """ffmpeg/ffprobe from the bundled folder (FFMPEG_LOCATION), else PATH."""
    folder = os.environ.get("FFMPEG_LOCATION")
    if folder:
        candidate = os.path.join(folder, name + ".exe")
        if os.path.exists(candidate):
            return candidate
    return shutil.which(name) or name


def probe(path):
    out = subprocess.run(
        [tool_path("ffprobe"), "-v", "error", "-show_entries",
         "stream=index,codec_type,codec_name,pix_fmt:format=duration,format_name",
         "-of", "json", path],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
        creationflags=_NO_WINDOW, timeout=60,
    )
    if out.returncode != 0:
        raise RuntimeError(f"ffprobe couldn't read the file: {out.stderr.strip()[:300]}")
    data = json.loads(out.stdout or "{}")
    streams = data.get("streams", [])
    video = next((s for s in streams if s.get("codec_type") == "video" and s.get("codec_name") not in ("mjpeg", "png")), None)
    audio = next((s for s in streams if s.get("codec_type") == "audio"), None)
    try:
        duration = float(data.get("format", {}).get("duration") or 0)
    except ValueError:
        duration = 0.0
    return {
        "vcodec": video.get("codec_name") if video else None,
        "acodec": audio.get("codec_name") if audio else None,
        "duration": duration,
    }


def needs_conversion(info, path):
    """(convert_video, convert_audio, remux) for a probed file."""
    convert_video = bool(info["vcodec"]) and info["vcodec"] not in PREMIERE_VIDEO
    convert_audio = bool(info["acodec"]) and info["acodec"] not in PREMIERE_AUDIO
    remux = os.path.splitext(path)[1].lower() != ".mp4"
    return convert_video, convert_audio, remux


def _hw_encoder():
    with _encoder_lock:
        if "choice" in _hw_encoder_cache:
            return _hw_encoder_cache["choice"]
        choice = None
        for name, args in _ENCODERS:
            test = subprocess.run(
                [tool_path("ffmpeg"), "-hide_banner", "-v", "error", "-f", "lavfi",
                 "-i", "color=c=black:s=256x256:d=0.2", *args, "-pix_fmt", "yuv420p", "-f", "null", "-"],
                capture_output=True, creationflags=_NO_WINDOW, timeout=30,
            )
            if test.returncode == 0:
                choice = (name, args)
                break
        _hw_encoder_cache["choice"] = choice
        return choice


def _run_ffmpeg(args, duration, on_progress, cancelled):
    proc = subprocess.Popen(
        [tool_path("ffmpeg"), "-hide_banner", "-v", "error", "-y", *args, "-progress", "pipe:1", "-nostats"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding="utf-8", errors="replace",
        creationflags=_NO_WINDOW,
    )
    stderr_chunks = []
    threading.Thread(target=lambda: stderr_chunks.append(proc.stderr.read()), daemon=True).start()
    started = time.time()
    for line in proc.stdout:
        if cancelled():
            proc.kill()
            proc.wait()
            raise ConversionCancelled()
        key, _, value = line.strip().partition("=")
        if key == "out_time_us" and duration and value.isdigit():
            done = int(value) / 1_000_000
            pct = max(0.0, min(99.0, done / duration * 100))
            elapsed = time.time() - started
            eta = (elapsed / done * (duration - done)) if done > 0 else None
            on_progress(pct, eta)
    proc.wait()
    if cancelled():
        raise ConversionCancelled()
    if proc.returncode != 0:
        raise RuntimeError("".join(stderr_chunks).strip()[-400:] or f"ffmpeg exited with {proc.returncode}")


def make_premiere_ready(path, on_progress=lambda pct, eta, label: None, cancelled=lambda: False):
    """Convert `path` in place (to .mp4) if Premiere can't read it. Returns the final path."""
    info = probe(path)
    convert_video, convert_audio, remux = needs_conversion(info, path)
    if not (convert_video or convert_audio or remux):
        return path

    if convert_video:
        label = "Converting video to H.264 for Premiere"
    elif convert_audio:
        label = "Converting audio to AAC for Premiere"
    else:
        label = "Repackaging as MP4 for Premiere"

    base, _ = os.path.splitext(path)
    final_path = base + ".mp4"
    temp_path = base + ".ytdd-converting.mp4"

    audio_args = ["-c:a", "aac", "-b:a", AUDIO_BITRATE] if convert_audio else ["-c:a", "copy"]
    common_in = ["-i", path, "-map", "0:v:0?", "-map", "0:a:0?", "-map_metadata", "0"]
    common_out = [*audio_args, "-movflags", "+faststart", temp_path]

    def report(pct, eta):
        on_progress(pct, eta, label)

    report(0.0, None)
    try:
        if convert_video:
            # yuv420p: Premiere and every GPU H.264 encoder want 8-bit 4:2:0,
            # and VP9/AV1 sources are sometimes 10-bit.
            encoders = [e for e in (_hw_encoder(), _SOFTWARE_ENCODER) if e]
            last_error = None
            for _name, enc_args in encoders:
                try:
                    _run_ffmpeg([*common_in, *enc_args, "-pix_fmt", "yuv420p", *common_out],
                                info["duration"], report, cancelled)
                    last_error = None
                    break
                except ConversionCancelled:
                    raise
                except Exception as exc:
                    # e.g. the GPU encoder rejects a resolution it can't do; libx264 can.
                    last_error = exc
            if last_error:
                raise last_error
        else:
            _run_ffmpeg([*common_in, "-c:v", "copy", *common_out], info["duration"], report, cancelled)
    except BaseException:
        try:
            os.remove(temp_path)
        except OSError:
            pass
        raise

    os.replace(temp_path, final_path)
    if os.path.normcase(final_path) != os.path.normcase(path):
        try:
            os.remove(path)
        except OSError:
            pass
    return final_path
