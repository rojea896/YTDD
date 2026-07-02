const { contextBridge, ipcRenderer } = require('electron');

const BACKEND_METHODS = [
  'get_settings', 'save_settings', 'get_audio_quality_options', 'get_history',
  'clear_history', 'check_playlist', 'fetch_info', 'start_download',
  'pause_download', 'resume_download', 'cancel_download',
  'choose_destination', 'choose_cookies_file', 'open_folder', 'set_clipboard_monitor',
  'resize_window',
];

const api = {};
for (const method of BACKEND_METHODS) {
  api[method] = (params) => ipcRenderer.invoke(method, params);
}
api.onBackendEvent = (callback) => {
  ipcRenderer.on('backend-event', (_event, msg) => callback(msg));
};
api.onClipboardUrl = (callback) => {
  ipcRenderer.on('clipboard-url', (_event, url) => callback(url));
};

contextBridge.exposeInMainWorld('api', api);
