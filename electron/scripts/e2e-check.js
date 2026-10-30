/* eslint-disable no-console */
// End-to-end check of the real app. Plain node - no test framework.
//
//   npm run e2e                      fresh, empty data folder
//   npm run e2e -- --seed <dir>      copy settings.json/history.json from <dir> first
//   npm run e2e -- --shots <dir>     save a screenshot at each step
//   npm run e2e -- --quick           skip the 4K conversion download
//   node scripts/e2e-check.js --exe ../dist-electron/win-unpacked/YTDD.exe
//                                    drive the packaged app instead
//
// Launches the built renderer (--prod) and drives it over the Chrome DevTools
// Protocol, so every step goes UI -> preload -> main -> Python backend ->
// yt-dlp/ffmpeg exactly as it does for a user. The backend gets a throwaway
// APPDATA, so your real history, settings and downloaded engine are untouched.
const { spawn, execFileSync } = require('child_process')
const fs = require('fs')
const os = require('os')
const path = require('path')

const ROOT = path.join(__dirname, '..')
const FFPROBE = path.join(ROOT, '..', 'vendor', 'ffmpeg', 'ffprobe.exe')
const PORT = 9333
const electronPath = require('electron')

const args = process.argv.slice(2)
const argValue = (name) => {
  const i = args.indexOf(name)
  return i >= 0 ? args[i + 1] : null
}
const SEED = argValue('--seed')
const SHOTS = argValue('--shots')
const QUICK = args.includes('--quick')
const EXE = argValue('--exe')

const SHORT_VIDEO = 'https://www.youtube.com/watch?v=jNQXAC9IVRw' // 19s, H.264 + AAC exists
const UHD_VIDEO = 'https://www.youtube.com/watch?v=ppR_QhxqgEE' // 15s 4K; H.264 stops at 1080p
const MISSING_VIDEO = 'https://www.youtube.com/watch?v=xxxxxxxxxxx'

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const results = []
function check(name, passed, detail = '') {
  results.push({ name, passed })
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? `  (${detail})` : ''}`)
}

function makeSandbox() {
  const base = fs.mkdtempSync(path.join(os.tmpdir(), 'ytdd-e2e-'))
  const appData = path.join(base, 'appdata')
  const dataDir = path.join(appData, 'YTDLP-Downloader')
  const dest = path.join(base, 'downloads')
  fs.mkdirSync(dataDir, { recursive: true })
  fs.mkdirSync(dest)
  if (SEED) {
    for (const f of ['settings.json', 'history.json']) {
      if (fs.existsSync(path.join(SEED, f))) fs.copyFileSync(path.join(SEED, f), path.join(dataDir, f))
    }
  }
  return { base, appData, dest }
}

async function findPage(timeoutMs = 30000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    try {
      const targets = await (await fetch(`http://127.0.0.1:${PORT}/json`)).json()
      const page = targets.find((t) => t.type === 'page' && String(t.url).endsWith('index.html'))
      if (page?.webSocketDebuggerUrl) return page
    } catch {
      /* not up yet */
    }
    await sleep(400)
  }
  throw new Error('Timed out waiting for the renderer devtools target.')
}

async function connect(wsUrl) {
  const ws = new WebSocket(wsUrl)
  await new Promise((resolve, reject) => {
    ws.addEventListener('open', resolve, { once: true })
    ws.addEventListener('error', () => reject(new Error('CDP socket error')), { once: true })
  })
  let nextId = 0
  const pending = new Map()
  ws.addEventListener('message', (event) => {
    const msg = JSON.parse(event.data)
    const entry = pending.get(msg.id)
    if (!entry) return
    pending.delete(msg.id)
    if (msg.error) entry.reject(new Error(msg.error.message))
    else entry.resolve(msg.result)
  })
  const send = (method, params = {}) =>
    new Promise((resolve, reject) => {
      const id = ++nextId
      pending.set(id, { resolve, reject })
      ws.send(JSON.stringify({ id, method, params }))
    })
  const evaluate = async (expression) => {
    const res = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })
    if (res.exceptionDetails) throw new Error(res.exceptionDetails.exception?.description ?? 'evaluate threw')
    return res.result.value
  }
  return { send, evaluate, close: () => ws.close() }
}

async function waitFor(cdp, expression, label, timeoutMs = 20000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const value = await cdp.evaluate(expression).catch(() => null)
    if (value) return value
    await sleep(300)
  }
  throw new Error(`Timed out waiting for: ${label}`)
}

let shotIndex = 0
async function shot(cdp, name) {
  if (!SHOTS) return
  fs.mkdirSync(SHOTS, { recursive: true })
  await sleep(350) // let transitions settle
  const { data } = await cdp.send('Page.captureScreenshot', { format: 'png' })
  const file = path.join(SHOTS, `${String(++shotIndex).padStart(2, '0')}-${name}.png`)
  fs.writeFileSync(file, Buffer.from(data, 'base64'))
  console.log(`      screenshot ${path.basename(file)}`)
}

function probe(file) {
  const out = execFileSync(FFPROBE, ['-v', 'error', '-show_entries', 'stream=codec_type,codec_name,height', '-of', 'json', file], { encoding: 'utf8' })
  const streams = JSON.parse(out).streams
  const v = streams.find((s) => s.codec_type === 'video')
  const a = streams.find((s) => s.codec_type === 'audio')
  return { vcodec: v?.codec_name, height: v?.height, acodec: a?.codec_name }
}

// --- page helpers (run inside the renderer) ---------------------------------
const js = {
  setUrl: (url) => `(() => {
    const input = document.querySelector('input[data-testid="url-input"]')
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(input, ${JSON.stringify(url)})
    input.dispatchEvent(new Event('input', { bubbles: true }))
    return true
  })()`,
  click: (selector) => `(() => { const el = document.querySelector(${JSON.stringify(selector)}); if (!el || el.disabled) return false; el.click(); return true })()`,
  clickText: (selector, text) => `(() => {
    const el = [...document.querySelectorAll(${JSON.stringify(selector)})].find((e) => e.textContent.trim().startsWith(${JSON.stringify(text)}))
    if (!el) return false; el.click(); return true
  })()`,
  text: (selector) => `(document.querySelector(${JSON.stringify(selector)})?.textContent ?? null)`,
  bodyHas: (text) => `document.body.innerText.includes(${JSON.stringify(text)})`,
}

async function fetchVideo(cdp, url) {
  await cdp.evaluate(js.setUrl(url))
  await waitFor(cdp, js.click('[data-testid="fetch-btn"]'), 'Fetch button to be clickable')
}

async function downloadAndWait(cdp, label, { expectConverting = false, timeoutMs = 300000 } = {}) {
  const before = await cdp.evaluate('window.api.get_history().then((h) => h.length)')
  await waitFor(cdp, js.click('[data-testid="download-btn"]'), 'Download button')
  await waitFor(cdp, `document.querySelector('[data-testid="queue"]') !== null`, `${label} to appear in the queue`)
  let sawConverting = false
  let shotDownloading = false
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const queueText = await cdp.evaluate(js.text('[data-testid="queue"]'))
    if (queueText && /Converting video to H\.264/.test(queueText) && !sawConverting) {
      sawConverting = true
      await shot(cdp, `${label}-converting`)
    } else if (queueText && /Downloading/.test(queueText) && !shotDownloading) {
      shotDownloading = true
      await shot(cdp, `${label}-downloading`)
    }
    if (queueText && /Download failed/.test(queueText)) throw new Error(`${label}: ${queueText}`)
    const after = await cdp.evaluate('window.api.get_history().then((h) => h.length)')
    if (after > before && !queueText) break
    await sleep(500)
  }
  const [entry] = await cdp.evaluate('window.api.get_history()')
  if (expectConverting) check(`${label}: queue showed the H.264 conversion step`, sawConverting)
  return entry
}

async function main() {
  const sandbox = makeSandbox()
  console.log(`sandbox: ${sandbox.base}`)
  const env = {
    ...process.env,
    APPDATA: sandbox.appData,
    YTDD_TEST_DEST: sandbox.dest,
    // Own Electron profile, so an open YTDD can't swallow this launch via the
    // single-instance lock.
    YTDD_USER_DATA: path.join(sandbox.base, 'electron-profile'),
  }
  delete env.ELECTRON_RUN_AS_NODE
  const [cmd, cmdArgs] = EXE
    ? [path.resolve(EXE), [`--remote-debugging-port=${PORT}`]]
    : [electronPath, ['.', '--prod', `--remote-debugging-port=${PORT}`]]
  const proc = spawn(cmd, cmdArgs, { cwd: ROOT, env, stdio: ['ignore', 'ignore', 'pipe'] })
  proc.stderr.on('data', (d) => {
    const text = d.toString().trim()
    if (text.includes('[backend]')) console.log(`      ${text.split('\n')[0].slice(0, 200)}`)
  })

  let cdp
  try {
    cdp = await connect((await findPage()).webSocketDebuggerUrl)
    await cdp.send('Runtime.enable')
    await cdp.send('Page.enable')

    await waitFor(cdp, `typeof window.api?.get_settings === 'function'`, 'preload bridge')
    check('preload bridge mounted (sandboxed preload got its channel list)', true)
    await waitFor(cdp, js.bodyHas('Recent downloads'), 'Download view to render')
    check('Fluent UI rendered the Download view', true)

    const engine = await waitFor(
      cdp,
      `window.api.get_engine_status().then((s) => s.lastResult && !s.checking ? s : null)`,
      'yt-dlp update check to finish',
      180000
    )
    check('yt-dlp update check ran', engine.lastResult !== 'error', `${engine.lastResult}; yt-dlp ${engine.version} (${engine.source})`)
    check('YouTube JS runtime is Electron-as-Node', Boolean(engine.jsRuntime), engine.jsRuntime ? `node ${engine.jsRuntime.version}` : 'none')
    await shot(cdp, 'download-empty')

    // --- 1. A plain YouTube video: should come out H.264 + AAC, no conversion.
    await fetchVideo(cdp, SHORT_VIDEO)
    await waitFor(cdp, js.text('[data-testid="video-title"]'), 'video title', 90000)
    check('Fetch shows the video', true, await cdp.evaluate(js.text('[data-testid="video-title"]')))
    const hint = await cdp.evaluate(js.text('[data-testid="output-hint"]'))
    check('Output hint promises Premiere-ready MP4', /H\.264 video and AAC audio/.test(hint || ''), hint)
    await shot(cdp, 'fetched')
    const small = await downloadAndWait(cdp, 'short-video')
    const smallProbe = probe(small.filepath)
    check('Short video saved as H.264 + AAC (Premiere-ready)', smallProbe.vcodec === 'h264' && smallProbe.acodec === 'aac', JSON.stringify(smallProbe))
    await shot(cdp, 'after-download')

    // --- 2. A 4K video: H.264 only goes to 1080p, so 2160p must be converted.
    if (!QUICK) {
      await fetchVideo(cdp, UHD_VIDEO)
      await waitFor(cdp, js.text('[data-testid="video-title"]'), 'video title', 90000)
      const defaultQuality = await cdp.evaluate(js.text('[data-testid="quality-dropdown"]'))
      check('Smart default picks the best quality that needs no conversion', /^1080p/.test(defaultQuality || ''), defaultQuality)
      await cdp.evaluate(js.click('[data-testid="quality-dropdown"]'))
      await waitFor(cdp, js.clickText('[role="option"]', '2160p'), '2160p option')
      const uhdHint = await waitFor(cdp, `(${js.text('[data-testid="output-hint"]')} || '').includes('converted') && ${js.text('[data-testid="output-hint"]')}`, 'conversion hint')
      check('Choosing 2160p explains the conversion', true, uhdHint)
      await shot(cdp, 'fetched-4k')
      const uhd = await downloadAndWait(cdp, '4k-video', { expectConverting: true, timeoutMs: 600000 })
      const uhdProbe = probe(uhd.filepath)
      check('4K video converted to H.264 + AAC MP4', uhdProbe.vcodec === 'h264' && uhdProbe.acodec === 'aac' && uhd.filepath.endsWith('.mp4'), JSON.stringify(uhdProbe))
      check('4K resolution kept through conversion', uhdProbe.height === 2160, `${uhdProbe.height}p`)
    }

    // --- 3. A failing link shows a plain-English error.
    await fetchVideo(cdp, MISSING_VIDEO)
    await waitFor(cdp, js.bodyHas("Couldn't fetch this link"), 'fetch error message', 120000)
    check('Unavailable video shows a friendly error', await cdp.evaluate(js.bodyHas('may have been removed')))
    await shot(cdp, 'fetch-error')

    // --- 4. The other panes.
    await cdp.evaluate(js.clickText('[role="tab"]', 'History'))
    await waitFor(cdp, `document.querySelectorAll('[data-testid="history-row"]').length > 0`, 'history rows')
    const firstRow = await cdp.evaluate(`document.querySelector('[data-testid="history-row"]').textContent`)
    check('History lists the newest download first', firstRow.includes(QUICK ? 'Me at the zoo' : 'Forest'), firstRow.slice(0, 60))
    await shot(cdp, 'history')

    await cdp.evaluate(js.clickText('[role="tab"]', 'Settings'))
    await waitFor(cdp, `document.querySelector('[data-testid="engine-version"]') !== null`, 'engine card')
    check('Settings shows the engine version', true, await cdp.evaluate(js.text('[data-testid="engine-version"]')))
    await shot(cdp, 'settings')

    // Light theme, then back.
    const dark = await cdp.evaluate('window.api.get_settings().then((s) => s.darkMode)')
    await cdp.evaluate(js.clickText('label', 'Dark mode'))
    await waitFor(cdp, `window.api.get_settings().then((s) => s.darkMode === ${!dark})`, 'theme setting saved')
    await shot(cdp, dark ? 'settings-light' : 'settings-dark')
    await cdp.evaluate(js.clickText('label', 'Dark mode'))
    await waitFor(cdp, `window.api.get_settings().then((s) => s.darkMode === ${dark})`, 'theme restored')
    check('Dark mode toggles and persists', true)

    // The bottom of Settings: engine card and the advanced options.
    await cdp.evaluate(js.clickText('button', 'Advanced options'))
    await waitFor(cdp, js.bodyHas('yt-dlp update channel'), 'advanced options to expand')
    await cdp.evaluate(`document.querySelector('main').scrollTop = 1e6`)
    await shot(cdp, 'settings-advanced')

    // Right-click a history row: a real right-button press, so Chromium raises
    // the contextmenu event Fluent's openOnContext listens for.
    await cdp.evaluate(js.clickText('[role="tab"]', 'History'))
    await waitFor(cdp, `document.querySelectorAll('[data-testid="history-row"]').length > 0`, 'history rows')
    const box = await cdp.evaluate(`(() => { const r = document.querySelector('[data-testid="history-row"]').getBoundingClientRect(); return { x: r.left + 120, y: r.top + r.height / 2 } })()`)
    for (const type of ['mousePressed', 'mouseReleased']) {
      await cdp.send('Input.dispatchMouseEvent', { type, x: box.x, y: box.y, button: 'right', clickCount: 1 })
    }
    const menu = await waitFor(cdp, `[...document.querySelectorAll('[role="menuitem"]')].map((m) => m.textContent).join(' | ') || null`, 'context menu')
    check('Right-click opens the file menu', /Open file.*Show in folder.*Copy file path.*Remove from history/.test(menu), menu)
    await shot(cdp, 'history-context-menu')
  } catch (err) {
    check('run completed', false, err.message)
    if (cdp) await shot(cdp, 'failure').catch(() => {})
  } finally {
    if (cdp) cdp.close()
    try {
      execFileSync('taskkill', ['/PID', String(proc.pid), '/T', '/F'], { stdio: 'ignore' })
    } catch {
      /* already gone */
    }
  }

  const failed = results.filter((r) => !r.passed)
  console.log(`\n${results.length - failed.length}/${results.length} checks passed`)
  process.exit(failed.length ? 1 : 0)
}

main()
