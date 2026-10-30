import { create } from 'zustand'

/** Which pane is showing. */
export const useUiStore = create((set) => ({
  view: 'download', // download | history | settings
  setView: (view) => set({ view }),
}))
