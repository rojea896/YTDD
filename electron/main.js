const { app, BrowserWindow, ipcMain, dialog, clipboard, shell, nativeImage } = require('electron');
const path = require('path');
const fs = require('fs');
const { spawn, execFile } = require('child_process');
const { BACKEND_METHODS, NATIVE_METHODS, EVENT_CHANNELS } = require('./channels');

// Must run before 'ready' (see .claude/knowledge/electron-dev-gotchas.md #3):
// unpackaged Electron otherwise names itself "Electron" and shares userData
// with every other dev Electron app on the machine.
app.setName('YTDD');

// The single-instance lock lives in userData. Dev runs and the e2e harness get
// their own profile so they never collide with (and just focus) an installed
// YTDD that happens to be open.
if (process.env.YTDD_USER_DATA) {
  app.setPath('userData', process.env.YTDD_USER_DATA);
} else if (!app.isPackaged) {
  app.setPath('userData', path.join(app.getPath('appData'), 'YTDD Dev'));
}

const isDev = !app.isPackaged && !process.argv.includes('--prod');
const DEV_SERVER_URL = 'http://localhost:5174';
// Same place the Python backend looks (it honours APPDATA, so tests can point
// both at a sandbox).
const SETTINGS_FILE = path.join(
  process.env.APPDATA || app.getPath('appData'),
  'YTDLP-Downloader',
  'settings.json'
);

// A sandboxed preload can't require() channels.js, so the allowlist rides in
// on a launch argument instead.
const CHANNELS_ARG =
  '--ytdd-channels=' +
  encodeURIComponent(JSON.stringify({ invoke: [...BACKEND_METHODS, ...NATIVE_METHODS], events: EVENT_CHANNELS }));

let pyProc = null;
let win = null;
let rpcId = 0;
let quitting = false;
let backendRestarts = 0;
const pending = new Map();

function send(channel, payload) {
  if (win && !win.isDestroyed()) win.webContents.send(channel, payload);
}

function startBackend() {
  let cmd;
  let args;
  let ffmpegDir;
  if (app.isPackaged) {
    cmd = path.join(process.resourcesPath, 'backend', 'ytdlp-backend.exe');
    args = [];
    ffmpegDir = path.join(process.resourcesPath, 'ffmpeg');
  } else {
    cmd = 'python';
    args = [path.join(__dirname, '..', 'backend_ipc.py')];
    ffmpegDir = path.join(__dirname, '..', 'vendor', 'ffmpeg');
  }

  pyProc = spawn(cmd, args, {
    stdio: ['pipe', 'pipe', 'pipe'],
    windowsHide: true,
    env: {
      ...process.env,
      FFMPEG_LOCATION: ffmpegDir,
      // YouTube's player challenges need a JavaScript runtime, and this very
      // executable is one: with ELECTRON_RUN_AS_NODE=1 it runs as plain Node.
      // The variable only reaches the backend and what *it* spawns (yt-dlp
      // runs YTDD_JS_RUNTIME for each challenge), never this process. Saves
      // bundling a 100 MB Deno.
      YTDD_JS_RUNTIME: process.execPath,
      ELECTRON_RUN_AS_NODE: '1',
      PYTHONIOENCODING: 'utf-8',
    },
  });

  let buffer = '';
  pyProc.stdout.on('data', (chunk) => {
    buffer += chunk.toString('utf8');
    let idx;
    while ((idx = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, idx).trim();
      buffer = buffer.slice(idx + 1);
      if (!line) continue;
      let msg;
      try {
        msg = JSON.parse(line);
      } catch {
        continue;
      }
      if (msg.event) {
        send('backend-event', msg);
      } else if (msg.id != null) {
        const resolver = pending.get(msg.id);
        if (resolver) {
          pending.delete(msg.id);
          if ('error' in msg) resolver.reject(new Error(msg.error));
          else resolver.resolve(msg.result);
        }
      }
    }
  });

  pyProc.stderr.on('data', (chunk) => {
    console.error('[backend]', chunk.toString('utf8').trimEnd());
  });

  const proc = pyProc;
  proc.on('exit', (code) => {
    console.error('[backend] exited with code', code);
    for (const [, resolver] of pending) {
      resolver.reject(new Error('The download engine stopped unexpectedly.'));
    }
    pending.clear();
    // Bring it back rather than leave a window that can't do anything. The
    // renderer is told so it can fail any downloads that were in flight.
    if (!quitting && proc === pyProc && backendRestarts < 3) {
      backendRestarts += 1;
      send('backend-event', { event: 'backendRestarted', data: { code } });
      setTimeout(startBackend, 1000);
    }
  });
}

function callBackend(method, params) {
  if (!pyProc || pyProc.exitCode !== null) {
    return Promise.reject(new Error('The download engine is not running.'));
  }
  return new Promise((resolve, reject) => {
    const id = ++rpcId;
    pending.set(id, { resolve, reject });
    pyProc.stdin.write(JSON.stringify({ id, method, params }) + '\n');
  });
}

function stopBackend() {
  if (!pyProc || pyProc.exitCode !== null) return;
  // /T takes ffmpeg and any JS-runtime children with it; a plain kill would
  // leave a half-finished conversion running with no window.
  execFile('taskkill', ['/PID', String(pyProc.pid), '/T', '/F'], () => {});
}

function readDarkMode() {
  try {
    return JSON.parse(fs.readFileSync(SETTINGS_FILE, 'utf8')).darkMode !== false;
  } catch {
    return true;
  }
}

/**
 * Window background behind the page (seen while resizing), kept in step with
 * the app theme. The native title bar is deliberately left to Windows: tested
 * on Electron 43, nativeTheme.themeSource can't hold a light title bar while
 * Windows is in dark mode - it flips back on its own - and a title bar that
 * changes colour at random is worse than one that just follows Windows.
 */
function applyWindowTheme(dark) {
  if (win && !win.isDestroyed()) win.setBackgroundColor(dark ? '#1f1f1f' : '#fafafa');
}

function createWindow() {
  const dark = readDarkMode();
  win = new BrowserWindow({
    width: 980,
    height: 700,
    minWidth: 780,
    minHeight: 540,
    title: 'YTDD',
    // Matches Fluent's neutral background for the saved theme, so there's no
    // white flash before React paints.
    backgroundColor: dark ? '#1f1f1f' : '#fafafa',
    icon: path.join(__dirname, 'build', 'icon.png'),
    autoHideMenuBar: true,
    show: false,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      // The saved theme, so the very first paint is right rather than
      // flashing dark until the backend has answered get_settings.
      additionalArguments: [CHANNELS_ARG, `--ytdd-theme=${dark ? 'dark' : 'light'}`],
    },
  });

  win.once('ready-to-show', () => win.show());

  if (isDev) win.loadURL(DEV_SERVER_URL);
  else win.loadFile(path.join(__dirname, 'dist', 'index.html'));

  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:/.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });
  win.webContents.on('will-navigate', (event, url) => {
    if (!(isDev && url.startsWith(DEV_SERVER_URL))) event.preventDefault();
  });
}

/** Every handler answers { ok, data } or { ok: false, error } so no raw
 *  "Error invoking remote method" text reaches the UI; preload unwraps it. */
function handle(channel, fn) {
  ipcMain.handle(channel, async (event, params) => {
    try {
      return { ok: true, data: await fn(params, event) };
    } catch (err) {
      return { ok: false, error: err?.message || String(err) };
    }
  });
}

function registerIpc() {
  for (const method of BACKEND_METHODS) {
    handle(method, (params) => callBackend(method, params));
  }

  handle('choose_destination', async (params) => {
    // scripts/e2e-check.js can't click a native folder dialog.
    if (process.env.YTDD_TEST_DEST) return process.env.YTDD_TEST_DEST;
    const defaultPath = params?.defaultPath && fs.existsSync(params.defaultPath) ? params.defaultPath : undefined;
    const result = await dialog.showOpenDialog(win, {
      title: 'Save downloads to…',
      defaultPath,
      properties: ['openDirectory', 'createDirectory'],
    });
    if (result.canceled || !result.filePaths.length) return null;
    return result.filePaths[0];
  });

  handle('choose_cookies_file', async () => {
    const result = await dialog.showOpenDialog(win, {
      properties: ['openFile'],
      filters: [
        { name: 'Cookie files', extensions: ['txt'] },
        { name: 'All files', extensions: ['*'] },
      ],
    });
    if (result.canceled || !result.filePaths.length) return null;
    return result.filePaths[0];
  });

  handle('open_folder', (filepath) => {
    if (!filepath || !fs.existsSync(filepath)) return false;
    shell.showItemInFolder(filepath);
    return true;
  });

  handle('open_file', async (filepath) => {
    if (!filepath || !fs.existsSync(filepath)) return false;
    const err = await shell.openPath(filepath);
    return !err;
  });

  handle('set_theme', (dark) => {
    applyWindowTheme(Boolean(dark));
    return true;
  });

  handle('copy_text', (text) => {
    clipboard.writeText(String(text ?? ''));
    return true;
  });

  const dragIcon = nativeImage
    .createFromPath(path.join(__dirname, 'build', 'icon.png'))
    .resize({ width: 32, height: 32 });
  handle('start_drag', (filePath, event) => {
    if (!filePath || !fs.existsSync(filePath)) return false;
    event.sender.startDrag({ file: filePath, icon: dragIcon });
    return true;
  });

  let clipboardTimer = null;
  let lastClipboard = '';
  const URL_MARKERS = /youtube\.com\/watch|youtu\.be\/|youtube\.com\/shorts|instagram\.com\/|tiktok\.com\/|vimeo\.com\//;
  handle('set_clipboard_monitor', (enabled) => {
    if (clipboardTimer) {
      clearInterval(clipboardTimer);
      clipboardTimer = null;
    }
    if (enabled) {
      // Seed with what's there now so turning this on doesn't immediately
      // grab a link copied an hour ago.
      try {
        lastClipboard = clipboard.readText().trim();
      } catch {
        lastClipboard = '';
      }
      clipboardTimer = setInterval(() => {
        let text;
        try {
          text = clipboard.readText().trim();
        } catch {
          return;
        }
        if (text && text !== lastClipboard && text.startsWith('http') && URL_MARKERS.test(text)) {
          lastClipboard = text;
          send('clipboard-url', text);
        } else if (text) {
          lastClipboard = text;
        }
      }, 1500);
    }
    return true;
  });
}

app.setAppUserModelId('com.rojea.ytdd');

const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (!win) return;
    if (win.isMinimized()) win.restore();
    win.show();
    win.focus();
  });

  app.whenReady().then(() => {
    startBackend();
    registerIpc();
    createWindow();
  });

  app.on('before-quit', () => {
    quitting = true;
    stopBackend();
  });

  app.on('window-all-closed', () => {
    quitting = true;
    stopBackend();
    app.quit();
  });
}
