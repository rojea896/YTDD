/**
 * Thin wrapper over the preload bridge.
 *
 * Vite also serves this app at http://localhost:5174 in a plain browser,
 * where there is no preload and therefore no window.api. Detect that once and
 * let the UI explain, rather than every store throwing "cannot read
 * properties of undefined".
 */
const bridge = typeof window !== 'undefined' ? window.api : undefined

export const hasBridge = Boolean(bridge && bridge.get_settings)

function missing() {
  return Promise.reject(
    new Error('The Electron bridge is unavailable. Open YTDD through `npm run dev`, not in a browser.')
  )
}

/** Flat API: api.fetch_info({ url }), api.open_folder(path), … */
export const api = hasBridge ? bridge : new Proxy({}, { get: () => missing })

/** Subscribe to a main-process push channel. Returns an unsubscribe function. */
export function onEvent(channel, callback) {
  if (!hasBridge) return () => {}
  return bridge.on(channel, callback)
}
