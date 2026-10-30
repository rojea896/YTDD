// The whole IPC surface between the renderer and the main process.
//
// main.js registers a handler for every name here; preload.js receives this
// list through webPreferences.additionalArguments, because a sandboxed preload
// can only require('electron'), not local files. One list, no drift.

// Relayed straight through to the Python backend (backend_ipc.py).
const BACKEND_METHODS = [
  'get_settings', 'save_settings', 'get_audio_quality_options', 'get_history',
  'clear_history', 'check_playlist', 'fetch_info', 'start_download',
  'pause_download', 'resume_download', 'cancel_download', 'check_history_size',
  'remove_history_entry', 'get_engine_status', 'check_engine_update',
];

// Handled in main: native dialogs, the shell, the clipboard, drag-out.
const NATIVE_METHODS = [
  'choose_destination', 'choose_cookies_file', 'open_folder', 'open_file',
  'set_clipboard_monitor', 'start_drag', 'copy_text', 'set_theme',
];

// Pushed from main to the renderer.
const EVENT_CHANNELS = ['backend-event', 'clipboard-url'];

module.exports = { BACKEND_METHODS, NATIVE_METHODS, EVENT_CHANNELS };
