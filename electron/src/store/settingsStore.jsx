import { create } from 'zustand'
import { api } from '../lib/ipc.jsx'

/**
 * Settings live in the Python backend's settings.json; this is the renderer's
 * copy. Writes are optimistic: the UI updates at once and the whole object is
 * saved behind it (the backend stores it wholesale).
 */
export const useSettingsStore = create((set, get) => ({
  settings: null,
  error: null,

  async load() {
    try {
      const settings = await api.get_settings()
      set({ settings, error: null })
      api.set_clipboard_monitor(Boolean(settings.clipboardMonitor)).catch(() => {})
    } catch (err) {
      set({ error: err.message })
    }
  },

  async update(patch) {
    const next = { ...get().settings, ...patch }
    set({ settings: next })
    try {
      await api.save_settings({ settings: next })
      if ('clipboardMonitor' in patch) await api.set_clipboard_monitor(Boolean(next.clipboardMonitor))
      set({ error: null })
    } catch (err) {
      set({ error: `Couldn't save settings: ${err.message}` })
    }
  },
}))
