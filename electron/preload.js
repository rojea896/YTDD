const { contextBridge, ipcRenderer } = require('electron');

/**
 * The renderer never sees ipcRenderer - only the allowlisted surface built here.
 *
 * With `sandbox: true` this file can only require('electron'), so main hands
 * over the channel list (from channels.js) as a launch argument.
 *
 * Main answers every invoke with { ok, data | error }; this layer unwraps it,
 * so React code just `await window.api.fetch_info(...)` and catches a plain
 * Error with a readable message.
 */
const PREFIX = '--ytdd-channels=';

function readChannels() {
  const arg = process.argv.find((a) => a.startsWith(PREFIX));
  if (!arg) return { invoke: [], events: [] };
  try {
    return JSON.parse(decodeURIComponent(arg.slice(PREFIX.length)));
  } catch {
    return { invoke: [], events: [] };
  }
}

const { invoke: INVOKE_CHANNELS, events: EVENT_CHANNELS } = readChannels();
const EVENT_SET = new Set(EVENT_CHANNELS);

const api = {};
for (const channel of INVOKE_CHANNELS) {
  api[channel] = async (params) => {
    const res = await ipcRenderer.invoke(channel, params);
    if (res && res.ok === false) throw new Error(res.error || 'Request failed');
    return res ? res.data : undefined;
  };
}

/** The theme saved at launch ('dark' | 'light'), for the first paint. */
api.initialTheme = (process.argv.find((a) => a.startsWith('--ytdd-theme=')) || '').split('=')[1] || 'dark';

/** Subscribe to a push channel. Returns an unsubscribe function. */
api.on = (channel, callback) => {
  if (!EVENT_SET.has(channel)) throw new Error(`Unknown event channel: ${channel}`);
  const listener = (_event, payload) => callback(payload);
  ipcRenderer.on(channel, listener);
  return () => ipcRenderer.removeListener(channel, listener);
};

contextBridge.exposeInMainWorld('api', api);
