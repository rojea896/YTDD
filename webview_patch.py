"""Local, project-scoped patch for pywebview's EdgeChromium backend.

Some environments run an automated accessibility/UI-Automation scanner that
walks every new window's accessibility tree. WebView2's renderer exposes a
much larger tree than a native control, and walking it can trigger runaway
recursion that pins the app's message pump (Windows reports "Not Responding"
even though nothing in our own code is blocking). This app has no need for
screen-reader support, so we disable the renderer's accessibility engine via
a Chromium launch flag.

pywebview hardcodes its AdditionalBrowserArguments string inside
webview.platforms.edgechromium.EdgeChrome.__init__, and CreationProperties
can no longer be modified once EnsureCoreWebView2Async has been called at the
end of that same method — so a post-hoc monkeypatch that runs after __init__
returns is too late. The only way to inject an extra browser flag is to
replace __init__ itself. This is a full copy of pywebview 5.x's
EdgeChrome.__init__ with one extra line added; it must be imported before
any pywebview window is created.
"""
import os

import webview.platforms.edgechromium as _ec

EXTRA_BROWSER_ARGS = "--disable-renderer-accessibility --force-renderer-accessibility=off"


def _patched_init(self, form, window, cache_dir):
    self.pywebview_window = window
    self.webview = _ec.WebView2()
    props = _ec.CoreWebView2CreationProperties()

    runtime_path = _ec.webview_settings["WEBVIEW2_RUNTIME_PATH"]
    if runtime_path:
        if not os.path.isabs(runtime_path):
            runtime_path = os.path.join(_ec.get_app_root(), runtime_path)
        if os.path.exists(runtime_path):
            props.BrowserExecutableFolder = runtime_path
            _ec.logger.debug(f"Using custom WebView2 runtime: {runtime_path}")
        else:
            _ec.logger.warning(
                f"Custom WebView2 runtime path does not exist: {runtime_path}. Using system WebView2."
            )

    props.UserDataFolder = cache_dir
    self.user_data_folder = props.UserDataFolder
    props.set_IsInPrivateModeEnabled(_ec._state["private_mode"])
    props.AdditionalBrowserArguments = "--disable-features=ElasticOverscroll " + EXTRA_BROWSER_ARGS

    if _ec.webview_settings["ALLOW_FILE_URLS"]:
        props.AdditionalBrowserArguments += " --allow-file-access-from-files"

    if _ec.webview_settings["REMOTE_DEBUGGING_PORT"] is not None:
        props.AdditionalBrowserArguments += (
            f' --remote-debugging-port={_ec.webview_settings["REMOTE_DEBUGGING_PORT"]}'
        )

    self.webview.CreationProperties = props

    self.form = form
    form.Controls.Add(self.webview)

    self.js_results = {}
    self.js_result_semaphore = _ec.Semaphore(0)
    self.webview.Dock = _ec.WinForms.DockStyle.Fill
    self.webview.BringToFront()
    self.webview.CoreWebView2InitializationCompleted += self.on_webview_ready
    self.webview.NavigationStarting += self.on_navigation_start
    self.webview.NavigationCompleted += self.on_navigation_completed
    self.webview.WebMessageReceived += self.on_script_notify
    self.syncContextTaskScheduler = _ec.TaskScheduler.FromCurrentSynchronizationContext()
    self.webview.DefaultBackgroundColor = _ec.Color.FromArgb(
        255,
        int(window.background_color.lstrip("#")[0:2], 16),
        int(window.background_color.lstrip("#")[2:4], 16),
        int(window.background_color.lstrip("#")[4:6], 16),
    )

    if window.transparent:
        self.webview.DefaultBackgroundColor = _ec.Color.Transparent

    self.url = None
    self.ishtml = False
    self.html = _ec.DEFAULT_HTML

    self.webview.EnsureCoreWebView2Async(None)


_ec.EdgeChrome.__init__ = _patched_init
