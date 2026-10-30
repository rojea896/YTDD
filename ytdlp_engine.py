"""Keeps yt-dlp current without needing a new YTDD release.

YouTube changes something every few weeks and yt-dlp ships a fix within days,
but the copy frozen into ytdlp-backend.exe never changes after a build. So at
runtime this module downloads yt-dlp's official platform-independent release
asset (`yt-dlp`, a zipimport archive that also carries the yt-dlp-ejs YouTube
challenge-solver scripts) into APP_DIR/engine, verifies it against the
release's SHA2-256SUMS, and imports `yt_dlp` from it in preference to the
bundled copy.

Swapping modules underneath a running download would mix two versions of
yt-dlp in one process, so a newly downloaded engine is only activated when
nothing is using the current one - see Engine.use().
"""
import contextlib
import hashlib
import importlib
import importlib.abc
import json
import os
import re
import subprocess
import sys
import threading
import time
import urllib.request
import zipfile
import zipimport

CHANNEL_REPOS = {
    "nightly": "yt-dlp/yt-dlp-nightly-builds",
    "stable": "yt-dlp/yt-dlp",
}
ASSET_NAME = "yt-dlp"
CHECKSUM_ASSET = "SHA2-256SUMS"
ENGINE_PACKAGES = ("yt_dlp", "yt_dlp_ejs")
KEEP_ENGINES = 2
HTTP_TIMEOUT = 30


def _bundled_copy_marker():
    """Never called. Every real import of yt_dlp in YTDD goes through importlib,
    which PyInstaller's import scan can't follow - without these two plain
    imports the frozen exe ships with no fallback yt-dlp at all."""
    import yt_dlp  # noqa: F401
    import yt_dlp_ejs  # noqa: F401


def version_key(version):
    """'2026.09.27.232945' -> (2026, 9, 27, 232945); comparable across channels."""
    return tuple(int(part) for part in re.findall(r"\d+", version or ""))


def _unwrap_urllib3_percent_re():
    """yt-dlp's requests handler wraps urllib3's _PERCENT_RE at import time.
    Importing a second copy of yt-dlp in the same process wraps the wrapper,
    which breaks it ("'Urllib3PercentREOverride' object has no attribute
    'sub'") and silently knocks yt-dlp back to its slower urllib handler."""
    url_module = sys.modules.get("urllib3.util.url")
    if url_module is None:
        return
    for attr in ("_PERCENT_RE", "PERCENT_RE"):
        value = original = getattr(url_module, attr, None)
        while value is not None and type(value).__name__ == "Urllib3PercentREOverride":
            value = value.__dict__["re"]
        if value is not original:
            setattr(url_module, attr, value)


def _purge_engine_modules():
    _unwrap_urllib3_percent_re()
    for name in list(sys.modules):
        if name.partition(".")[0] in ENGINE_PACKAGES:
            del sys.modules[name]
    importlib.invalidate_caches()


class _ZipFirstFinder(importlib.abc.MetaPathFinder):
    """Resolves yt_dlp / yt_dlp_ejs (and every submodule) from one zip only.

    Sits at the front of sys.meta_path so it wins over PyInstaller's frozen
    importer. A submodule missing from the zip raises instead of returning
    None: falling through would quietly load that one module from the older
    bundled copy, and a half-old, half-new yt-dlp is worse than either.
    """

    def __init__(self, zip_path):
        self.zip_path = zip_path

    def find_spec(self, fullname, path=None, target=None):
        if fullname.partition(".")[0] not in ENGINE_PACKAGES:
            return None
        if "." in fullname:
            # Parent package's __path__ points inside the zip, e.g. 'x.zip\\yt_dlp'.
            base = path[0] if path else self.zip_path
        else:
            base = self.zip_path
        try:
            spec = zipimport.zipimporter(base).find_spec(fullname)
        except zipimport.ZipImportError:
            spec = None
        if spec is None:
            raise ModuleNotFoundError(f"No module named {fullname!r} in the downloaded engine", name=fullname)
        return spec


def _zip_version(zip_path):
    """Read __version__ straight out of the archive without importing it."""
    with zipfile.ZipFile(zip_path) as zf:
        source = zf.read("yt_dlp/version.py").decode("utf-8")
    match = re.search(r"^__version__\s*=\s*['\"]([^'\"]+)['\"]", source, re.M)
    if not match:
        raise ValueError("no __version__ in yt_dlp/version.py")
    return match.group(1)


def _http_get(url, accept=None):
    req = urllib.request.Request(url, headers={"User-Agent": "YTDD", **({"Accept": accept} if accept else {})})
    with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT) as resp:
        return resp.read(), resp.geturl()


class Engine:
    def __init__(self, app_dir, on_status=None):
        self.engine_dir = os.path.normpath(os.path.join(app_dir, "engine"))
        self.on_status = on_status
        self._lock = threading.RLock()
        self._check_lock = threading.Lock()
        self._module = None
        self._finder = None
        self._active = 0
        self._pending_zip = None
        self._status = {
            "version": None,
            "bundledVersion": None,
            "source": None,          # bundled | updated
            "channel": "nightly",
            "checking": False,
            "lastChecked": None,     # epoch ms
            "lastResult": None,      # up-to-date | updated | error
            "error": None,
            "pendingVersion": None,
            "loadError": None,
            "jsRuntime": None,
        }

    # ----------------------------------------------------------- status ---
    def status(self):
        with self._lock:
            return dict(self._status)

    def _set_status(self, **changes):
        with self._lock:
            self._status.update(changes)
            snapshot = dict(self._status)
        if self.on_status:
            try:
                self.on_status(snapshot)
            except Exception:
                pass

    # ---------------------------------------------------------- loading ---
    def get(self):
        """The yt_dlp module currently in force (loaded on first call)."""
        with self._lock:
            if self._module is None:
                self._initial_load()
            return self._module

    @contextlib.contextmanager
    def use(self):
        """Hold the current engine for the duration of an operation.

        While any operation holds it, a freshly downloaded engine waits; the
        last one out activates it.
        """
        with self._lock:
            module = self.get()
            self._active += 1
        try:
            yield module
        finally:
            with self._lock:
                self._active -= 1
                if self._active == 0 and self._pending_zip:
                    self._activate_pending()

    def is_idle(self):
        with self._lock:
            return self._active == 0

    def _import_bundled(self):
        if self._finder in sys.meta_path:
            sys.meta_path.remove(self._finder)
        self._finder = None
        _purge_engine_modules()
        return importlib.import_module("yt_dlp")

    def _import_zip(self, zip_path):
        if self._finder in sys.meta_path:
            sys.meta_path.remove(self._finder)
        _purge_engine_modules()
        self._finder = _ZipFirstFinder(zip_path)
        sys.meta_path.insert(0, self._finder)
        module = importlib.import_module("yt_dlp")
        # Touch the parts YTDD actually drives so a broken archive fails here,
        # at load time, rather than halfway through someone's download.
        importlib.import_module("yt_dlp.cookies")
        importlib.import_module("yt_dlp.utils")
        loaded_from = os.path.normcase(os.path.normpath(module.__file__ or ""))
        if not loaded_from.startswith(os.path.normcase(os.path.normpath(zip_path))):
            raise ImportError(f"yt_dlp resolved to {module.__file__}, not the downloaded engine")
        return module

    def _initial_load(self):
        bundled = self._import_bundled()
        bundled_version = bundled.version.__version__
        self._module = bundled
        self._status.update(version=bundled_version, bundledVersion=bundled_version, source="bundled")

        for zip_path in self._installed_zips():
            try:
                if version_key(_zip_version(zip_path)) <= version_key(bundled_version):
                    break  # sorted newest first; nothing newer than bundled
                self._module = self._import_zip(zip_path)
                self._status.update(version=self._module.version.__version__, source="updated", loadError=None)
                return
            except Exception as exc:
                self._status["loadError"] = f"{os.path.basename(zip_path)}: {exc}"
                self._module = self._import_bundled()
                continue

    def _activate_pending(self):
        """Swap to the downloaded engine. Caller holds self._lock and no
        operation is using the current engine."""
        zip_path, self._pending_zip = self._pending_zip, None
        fallback_zip = self._finder.zip_path if self._finder else None
        try:
            self._module = self._import_zip(zip_path)
            self._set_status(version=self._module.version.__version__, source="updated",
                             pendingVersion=None, loadError=None)
        except Exception as exc:
            # Put back whatever was working before.
            try:
                self._module = self._import_zip(fallback_zip) if fallback_zip else self._import_bundled()
            except Exception:
                self._module = self._import_bundled()
            self._set_status(version=self._module.version.__version__, pendingVersion=None,
                             source="updated" if self._finder else "bundled",
                             loadError=f"{os.path.basename(zip_path)}: {exc}")

    def _installed_zips(self):
        try:
            names = [n for n in os.listdir(self.engine_dir) if n.startswith("yt-dlp-") and n.endswith(".zip")]
        except OSError:
            return []
        paths = [os.path.join(self.engine_dir, n) for n in names]
        return sorted(paths, key=lambda p: version_key(os.path.basename(p)), reverse=True)

    def _prune(self):
        current = self._finder.zip_path if self._finder else None
        for path in self._installed_zips()[KEEP_ENGINES:]:
            if path != current:
                try:
                    os.remove(path)
                except OSError:
                    pass

    # --------------------------------------------------------- updating ---
    def _latest_tag(self, repo):
        try:
            body, _ = _http_get(f"https://api.github.com/repos/{repo}/releases/latest", "application/vnd.github+json")
            return json.loads(body)["tag_name"]
        except Exception:
            # The API allows 60 unauthenticated calls an hour per IP. If that's
            # spent, the web redirect still names the latest tag.
            _, final_url = _http_get(f"https://github.com/{repo}/releases/latest")
            match = re.search(r"/releases/tag/([^/?#]+)", final_url)
            if not match:
                raise RuntimeError("couldn't determine the latest yt-dlp release")
            return match.group(1)

    def check_for_update(self, channel="nightly"):
        """Download a newer engine if one exists. Returns True if one was installed.

        Never raises: failures are recorded in status() so the UI can show them.
        """
        repo = CHANNEL_REPOS.get(channel, CHANNEL_REPOS["nightly"])
        if not self._check_lock.acquire(blocking=False):
            return False  # a check is already running
        try:
            self._set_status(checking=True, channel=channel)
            self.get()
            tag = self._latest_tag(repo)
            with self._lock:
                newest_known = max(
                    [self._status["version"], self._status["pendingVersion"] or ""],
                    key=version_key,
                )
            if version_key(tag) <= version_key(newest_known):
                self._set_status(checking=False, lastChecked=int(time.time() * 1000),
                                 lastResult="up-to-date", error=None)
                return False

            zip_path = self._download(repo, tag)
            with self._lock:
                self._pending_zip = zip_path
                self._status["pendingVersion"] = _zip_version(zip_path)
                if self._active == 0:
                    self._activate_pending()
            self._prune()
            self._set_status(checking=False, lastChecked=int(time.time() * 1000),
                             lastResult="updated", error=None)
            return True
        except Exception as exc:
            self._set_status(checking=False, lastChecked=int(time.time() * 1000),
                             lastResult="error", error=str(exc))
            return False
        finally:
            self._check_lock.release()

    def _download(self, repo, tag):
        base = f"https://github.com/{repo}/releases/download/{tag}"
        sums, _ = _http_get(f"{base}/{CHECKSUM_ASSET}")
        expected = None
        for line in sums.decode("utf-8", "replace").splitlines():
            parts = line.split()
            if len(parts) == 2 and parts[1].lstrip("*") == ASSET_NAME:
                expected = parts[0].lower()
        if not expected:
            raise RuntimeError(f"{CHECKSUM_ASSET} for {tag} has no entry for {ASSET_NAME}")

        payload, _ = _http_get(f"{base}/{ASSET_NAME}")
        actual = hashlib.sha256(payload).hexdigest()
        if actual != expected:
            raise RuntimeError(f"checksum mismatch for yt-dlp {tag}; download discarded")

        os.makedirs(self.engine_dir, exist_ok=True)
        final_path = os.path.join(self.engine_dir, f"yt-dlp-{tag}.zip")
        part_path = final_path + ".part"
        with open(part_path, "wb") as f:
            f.write(payload)
        version = _zip_version(part_path)  # also proves it's a readable zip
        if version_key(version) != version_key(tag):
            os.remove(part_path)
            raise RuntimeError(f"release {tag} contains yt-dlp {version}")
        os.replace(part_path, final_path)
        return final_path

    # -------------------------------------------------------- JS runtime ---
    def detect_js_runtime(self, path):
        """Report the JS runtime yt-dlp will use for YouTube's challenges."""
        info = None
        if path:
            try:
                out = subprocess.run(
                    [path, "--version"], capture_output=True, text=True, timeout=20,
                    creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
                ).stdout.strip()
                match = re.match(r"v?(\d+\.\d+\.\d+)", out)
                if match:
                    info = {"name": "node", "version": match.group(1), "builtIn": True}
            except Exception:
                info = None
        self._set_status(jsRuntime=info)
        return info
