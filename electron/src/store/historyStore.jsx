import { create } from 'zustand'
import { api } from '../lib/ipc.jsx'

/**
 * Download history plus the two confirmations that act on it. The dialogs'
 * state lives here, not in a view, because rows on both the Download and
 * History panes can raise them.
 */
export const useHistoryStore = create((set) => ({
  items: [],
  ready: false,
  removing: null, // entry awaiting "Remove this download?"
  confirmingClear: false,

  async load() {
    try {
      set({ items: await api.get_history(), ready: true })
    } catch {
      set({ ready: true })
    }
  },

  askRemove: (entry) => set({ removing: entry }),
  cancelRemove: () => set({ removing: null }),
  async remove(entry, deleteFile) {
    const items = await api.remove_history_entry({ filepath: entry.filepath, date: entry.date, deleteFile })
    set({ items, removing: null })
  },

  askClear: () => set({ confirmingClear: true }),
  cancelClear: () => set({ confirmingClear: false }),
  async clear() {
    await api.clear_history()
    set({ items: [], confirmingClear: false })
  },
}))
