import { create } from 'zustand'
import { api } from '../lib/ipc.jsx'

/**
 * The yt-dlp engine's state, as reported by ytdlp_engine.py. The backend
 * pushes an `engineStatus` event whenever it changes (startup load, update
 * checks, a hot swap), so after load() this mostly just receives.
 */
export const useEngineStore = create((set) => ({
  status: null,
  checking: false,

  async load() {
    try {
      set({ status: await api.get_engine_status() })
    } catch {
      /* the event stream will fill it in */
    }
  },

  apply: (status) => set({ status }),

  async checkNow() {
    set({ checking: true })
    try {
      set({ status: await api.check_engine_update() })
    } finally {
      set({ checking: false })
    }
  },
}))
