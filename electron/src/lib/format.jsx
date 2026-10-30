const pad = (n) => String(n).padStart(2, '0')

/** 75 -> "1:15", 3725 -> "1:02:05". */
export function formatClock(totalSeconds) {
  if (totalSeconds == null || !Number.isFinite(totalSeconds)) return null
  const sec = Math.max(0, Math.round(totalSeconds))
  const h = Math.floor(sec / 3600)
  const m = Math.floor((sec % 3600) / 60)
  const s = sec % 60
  return h ? `${h}:${pad(m)}:${pad(s)}` : `${m}:${pad(s)}`
}

export function formatSpeed(bytesPerSec) {
  if (!bytesPerSec) return null
  if (bytesPerSec >= 1024 * 1024) return `${(bytesPerSec / 1024 / 1024).toFixed(1)} MB/s`
  return `${Math.round(bytesPerSec / 1024)} KB/s`
}

/** History dates are stored as local "YYYY-MM-DD HH:MM" strings. */
export function parseHistoryDate(text) {
  const t = new Date(String(text || '').replace(' ', 'T')).getTime()
  return Number.isNaN(t) ? null : t
}

export function formatHistoryDate(text) {
  const t = parseHistoryDate(text)
  if (t == null) return text || ''
  const date = new Date(t)
  const time = date.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
  const startOfToday = new Date()
  startOfToday.setHours(0, 0, 0, 0)
  const dayMs = 86400000
  if (t >= startOfToday.getTime()) return `Today, ${time}`
  if (t >= startOfToday.getTime() - dayMs) return `Yesterday, ${time}`
  const sameYear = date.getFullYear() === startOfToday.getFullYear()
  return date.toLocaleDateString(undefined, {
    day: 'numeric',
    month: 'short',
    ...(sameYear ? {} : { year: 'numeric' }),
  })
}

export function timeAgo(epochMs) {
  if (!epochMs) return null
  const sec = Math.round((Date.now() - epochMs) / 1000)
  if (sec < 60) return 'just now'
  const min = Math.round(sec / 60)
  if (min < 60) return `${min} minute${min === 1 ? '' : 's'} ago`
  const hr = Math.round(min / 60)
  if (hr < 24) return `${hr} hour${hr === 1 ? '' : 's'} ago`
  const days = Math.round(hr / 24)
  return `${days} day${days === 1 ? '' : 's'} ago`
}

export function basename(filepath) {
  return String(filepath || '').split(/[\\/]/).pop()
}
