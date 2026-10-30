import { create } from 'zustand'
import { api } from '../lib/ipc.jsx'
import { useSettingsStore } from './settingsStore.jsx'

const ACTIVE = new Set(['starting', 'downloading', 'processing', 'converting', 'paused'])
export const isActive = (item) => ACTIVE.has(item.status)

function newJobId() {
  return `job-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

/**
 * The link being worked on (URL → fetched info → chosen format) and the queue
 * of downloads it has started. Queue items keep the exact request they were
 * started with, so a failed one can be retried without re-fetching.
 */
export const useDownloadStore = create((set, get) => ({
  url: '',
  fetching: false,
  fetchError: null, // { message, detail }
  info: null,
  fetchedUrl: '',
  scope: 'single', // single | playlist
  playlistPrompt: false,
  mediaType: 'video', // video | audio
  videoQuality: null,
  audioQuality: 'mp3-320',
  audioOptions: [],
  queue: [],

  async init() {
    try {
      set({ audioOptions: await api.get_audio_quality_options() })
    } catch {
      /* the audio tab just shows nothing until the backend is up */
    }
  },

  setUrl(url) {
    set((s) => ({
      url,
      // Editing the link invalidates whatever was fetched for the old one.
      ...(url.trim() !== s.fetchedUrl ? { info: null, fetchError: null } : {}),
    }))
  },

  /** A link from the clipboard monitor only lands in an empty field. */
  offerClipboardUrl(url) {
    const s = get()
    if (s.url.trim() || s.fetching) return false
    set({ url, info: null, fetchError: null })
    return true
  },

  async requestFetch() {
    const url = get().url.trim()
    if (!url || get().fetching) return
    let isPlaylist = false
    try {
      isPlaylist = await api.check_playlist({ url })
    } catch {
      /* treat as a single video */
    }
    if (isPlaylist) set({ playlistPrompt: true })
    else await get().fetch('single')
  },

  answerPlaylist(scope) {
    set({ playlistPrompt: false })
    if (scope) get().fetch(scope)
  },

  async fetch(scope) {
    const url = get().url.trim()
    set({ fetching: true, fetchError: null, info: null, scope })
    let res
    try {
      res = await api.fetch_info({ url })
    } catch (err) {
      res = { ok: false, error: err.message }
    }
    // The link was edited while this was in flight: drop the stale answer.
    if (get().url.trim() !== url) {
      set({ fetching: false })
      return
    }
    if (!res.ok) {
      set({ fetching: false, fetchError: { message: res.error, detail: res.detail } })
      return
    }
    const qualities = res.data.videoQualities || []
    const premiere = useSettingsStore.getState().settings?.outputMode !== 'original'
    // Default to the best quality that's Premiere-ready straight off the site
    // (usually 1080p on YouTube); 1440p/4K still a click away, but they need
    // a conversion pass, so they're never picked silently.
    const preferred = (premiere && qualities.find((q) => !q.convert)) || qualities[0]
    set({ fetching: false, info: res.data, fetchedUrl: url, videoQuality: preferred?.label ?? null })
  },

  setMediaType: (mediaType) => set({ mediaType }),
  setVideoQuality: (videoQuality) => set({ videoQuality }),
  setAudioQuality: (audioQuality) => set({ audioQuality }),

  async startDownload() {
    const { info, mediaType, videoQuality, audioQuality, scope, fetchedUrl, audioOptions } = get()
    if (!info) return
    const settingsStore = useSettingsStore.getState()
    const lastDestination = settingsStore.settings?.lastDestination || ''
    const destDir = await api.choose_destination({ defaultPath: lastDestination })
    if (!destDir) return
    if (destDir !== lastDestination) settingsStore.update({ lastDestination: destDir })

    const quality = mediaType === 'video' ? videoQuality : audioQuality
    const format =
      mediaType === 'video'
        ? `${videoQuality} video`
        : (audioOptions.find((o) => o.id === audioQuality)?.label ?? audioQuality)
    await get().launch({
      title: scope === 'playlist' ? `${info.title} (playlist)` : info.title,
      thumbnail: info.thumbnail,
      format,
      request: { url: fetchedUrl, job: { mediaType, quality }, destDir, scope },
    })
  },

  async launch({ title, thumbnail, format, request }) {
    const id = newJobId()
    const item = {
      id, title, thumbnail, format, request,
      status: 'starting', stage: 'Starting', progress: 0, speed: 0, eta: null, error: null,
    }
    set((s) => ({ queue: [item, ...s.queue] }))
    try {
      await api.start_download({ jobId: id, ...request })
    } catch (err) {
      get().patch(id, { status: 'error', error: { message: err.message } })
    }
  },

  patch(id, changes) {
    set((s) => ({ queue: s.queue.map((q) => (q.id === id ? { ...q, ...changes } : q)) }))
  },

  onProgress(d) {
    const item = get().queue.find((q) => q.id === d.jobId)
    if (!item || item.status === 'error') return
    get().patch(d.jobId, {
      progress: d.progress,
      speed: d.speed,
      eta: d.eta,
      stage: d.stage || item.stage,
      // A paused job still reports its last tick; don't flip it back.
      status: item.status === 'paused' ? 'paused' : d.status,
    })
  },

  /** Returns the finished item (for the toast) or null. */
  onDone(d) {
    const item = get().queue.find((q) => q.id === d.jobId)
    if (!item) return null
    if (d.success || (d.message === 'Cancelled' && !d.detail)) {
      set((s) => ({ queue: s.queue.filter((q) => q.id !== d.jobId) }))
      return d.success ? item : null
    }
    get().patch(d.jobId, { status: 'error', error: { message: d.message, detail: d.detail } })
    return { ...item, failed: true, error: { message: d.message } }
  },

  /** The backend process died and was restarted: nothing it was doing survives. */
  failActive(message) {
    set((s) => ({
      queue: s.queue.map((q) => (isActive(q) ? { ...q, status: 'error', error: { message } } : q)),
    }))
  },

  pause(id) {
    api.pause_download({ jobId: id }).catch(() => {})
    get().patch(id, { status: 'paused' })
  },
  resume(id) {
    api.resume_download({ jobId: id }).catch(() => {})
    get().patch(id, { status: 'downloading' })
  },
  cancel(id) {
    api.cancel_download({ jobId: id }).catch(() => {})
    set((s) => ({ queue: s.queue.filter((q) => q.id !== id) }))
  },
  dismiss(id) {
    set((s) => ({ queue: s.queue.filter((q) => q.id !== id) }))
  },
  retry(id) {
    const item = get().queue.find((q) => q.id === id)
    if (!item) return
    get().dismiss(id)
    get().launch(item)
  },
}))
