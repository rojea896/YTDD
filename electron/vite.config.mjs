import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// Electron loads the renderer from http://localhost:5174 in dev (5173 is
// WorkTrack's) and from dist/ when packaged, so `base` must be relative for
// the file:// load to resolve assets.
export default defineConfig({
  base: './',
  plugins: [react(), tailwindcss()],
  server: {
    port: 5174,
    strictPort: true,
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    // Read from local disk by Electron, not fetched over a network: Fluent's
    // size costs parse time only, so don't chase Vite's web-tuned warning.
    chunkSizeWarningLimit: 1500,
  },
})
