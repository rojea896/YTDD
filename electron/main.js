const { app, BrowserWindow, ipcMain, dialog, clipboard, shell } = require('electron');
const path = require('path');
const { spawn } = require('child_process');

let pyProc = null;
let win = null;
let rpcId = 0;
const pending = new Map();

const BACKEND_METHODS = [
  'get_settings', 'save_settings', 'get_audio_quality_options', 'get_history',
  'clear_history', 'check_playlist', 'fetch_info', 'start_download',
  'pause_download', 'resume_download', 'cancel_download', 'check_history_size',
];

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
    env: { ...process.env, FFMPEG_LOCATION: ffmpegDir },
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
        if (win) win.webContents.send('backend-event', msg);
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
    console.error('[backend]', chunk.toString('utf8'));
  });

  pyProc.on('exit', (code) => {
    console.error('[backend] exited with code', code);
    for (const [, resolver] of pending) {
      resolver.reject(new Error('Backend process exited'));
    }
    pending.clear();
  });
}

function callBackend(method, params) {
  if (!pyProc || pyProc.exitCode !== null) {
    return Promise.reject(new Error('Backend is not running'));
  }
  return new Promise((resolve, reject) => {
    const id = ++rpcId;
    pending.set(id, { resolve, reject });
    pyProc.stdin.write(JSON.stringify({ id, method, params }) + '\n');
  });
}

function createWindow() {
  win = new BrowserWindow({
    width: 560,
    height: 220,
    minWidth: 560,
    minHeight: 160,
    maxHeight: 1000,
    backgroundColor: '#111827',
    icon: path.join(__dirname, 'build', 'icon.png'),
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });
  win.loadFile(path.join(__dirname, 'webapp', 'index.html'));
}

app.setAppUserModelId('com.rojea.ytdd');

app.whenReady().then(() => {
  startBackend();
  createWindow();

  for (const method of BACKEND_METHODS) {
    ipcMain.handle(method, (_event, params) => callBackend(method, params));
  }

  ipcMain.handle('choose_destination', async () => {
    const result = await dialog.showOpenDialog(win, { properties: ['openDirectory'] });
    if (result.canceled || !result.filePaths.length) return null;
    return result.filePaths[0];
  });

  ipcMain.handle('choose_cookies_file', async () => {
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

  ipcMain.handle('open_folder', (_event, filepath) => {
    shell.showItemInFolder(filepath);
    return true;
  });

  ipcMain.handle('resize_window', (_event, params) => {
    if (!win) return false;
    const [curWidth] = win.getContentSize();
    const height = Math.max(160, Math.min(1000, Math.round(params.height)));
    win.setContentSize(curWidth, height, true);
    return true;
  });

  let clipboardTimer = null;
  let lastClipboard = '';
  const URL_MARKERS = /youtube\.com\/watch|youtu\.be\/|youtube\.com\/shorts|instagram\.com\//;
  ipcMain.handle('set_clipboard_monitor', (_event, enabled) => {
    if (clipboardTimer) {
      clearInterval(clipboardTimer);
      clipboardTimer = null;
    }
    if (enabled) {
      clipboardTimer = setInterval(() => {
        let text;
        try {
          text = clipboard.readText().trim();
        } catch {
          return;
        }
        if (text && text !== lastClipboard && text.startsWith('http') && URL_MARKERS.test(text)) {
          lastClipboard = text;
          if (win) win.webContents.send('clipboard-url', text);
        } else if (text) {
          lastClipboard = text;
        }
      }, 1500);
    }
    return true;
  });
});

app.on('window-all-closed', () => {
  if (pyProc) pyProc.kill();
  app.quit();
});
