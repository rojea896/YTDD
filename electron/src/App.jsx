import { useEffect, useRef, useState } from 'react'
import { Card, Spinner, Text, Toaster, makeStyles, tokens } from '@fluentui/react-components'
import Sidebar from './components/Sidebar.jsx'
import { ClearHistoryDialog, HistorySizeDialog, PlaylistDialog, RemoveEntryDialog } from './components/Dialogs.jsx'
import Download from './views/Download.jsx'
import History from './views/History.jsx'
import Settings from './views/Settings.jsx'
import { api, hasBridge, onEvent } from './lib/ipc.jsx'
import { TOASTER_ID, useNotify } from './lib/notify.jsx'
import { useUiStore } from './store/uiStore.jsx'
import { useSettingsStore } from './store/settingsStore.jsx'
import { useEngineStore } from './store/engineStore.jsx'
import { useHistoryStore } from './store/historyStore.jsx'
import { useDownloadStore } from './store/downloadStore.jsx'

const useStyles = makeStyles({
  shell: { height: '100%', backgroundColor: tokens.colorNeutralBackground2 },
  main: { flex: 1, overflowY: 'auto', padding: '32px' },
  notice: { maxWidth: '440px', padding: '24px', textAlign: 'center' },
})

function NoBridge() {
  const styles = useStyles()
  return (
    <div className="flex h-full items-center justify-center p-8">
      <Card className={styles.notice}>
        <Text weight="semibold" block>
          Open YTDD from Electron
        </Text>
        <Text size={200} block>
          This page is being served by Vite in a plain browser, where there is no backend. Run{' '}
          <code>npm run dev</code> and use the app window instead.
        </Text>
      </Card>
    </div>
  )
}

/** Wires backend push events into the stores and turns the interesting ones into toasts. */
function useBackendEvents() {
  const notify = useNotify()
  const notifyRef = useRef(notify)
  notifyRef.current = notify

  useEffect(() => {
    if (!hasBridge) return undefined

    const offBackend = onEvent('backend-event', (msg) => {
      const data = msg.data || {}
      if (msg.event === 'queueProgress') {
        useDownloadStore.getState().onProgress(data)
      } else if (msg.event === 'queueDone') {
        const item = useDownloadStore.getState().onDone(data)
        if (data.entries?.length) useHistoryStore.getState().load()
        if (!item) return
        if (item.failed) {
          // The queue shows the error inline; only shout if it isn't on screen.
          if (useUiStore.getState().view !== 'download') {
            notifyRef.current({ intent: 'error', title: 'Download failed', body: item.title })
          }
        } else if (useSettingsStore.getState().settings?.notifyOnComplete) {
          notifyRef.current({ intent: 'success', title: 'Download complete', body: item.title })
          if (!document.hasFocus()) {
            try {
              new Notification('Download complete', { body: item.title, silent: false })
            } catch {
              /* notifications blocked: the toast is enough */
            }
          }
        }
      } else if (msg.event === 'engineStatus') {
        useEngineStore.getState().apply(data)
      } else if (msg.event === 'backendRestarted') {
        useDownloadStore.getState().failActive('The download engine restarted. Try again.')
        useEngineStore.getState().load()
      }
    })

    const offClipboard = onEvent('clipboard-url', (url) => {
      if (useDownloadStore.getState().offerClipboardUrl(url)) {
        notifyRef.current({ intent: 'info', title: 'Link picked up from the clipboard', timeout: 2500 })
      }
    })

    return () => {
      offBackend()
      offClipboard()
    }
  }, [])
}

export default function App() {
  const styles = useStyles()
  const view = useUiStore((s) => s.view)
  const settingsReady = useSettingsStore((s) => Boolean(s.settings))
  const [historyWarningBytes, setHistoryWarningBytes] = useState(0)
  const mainRef = useRef(null)

  // One scroll container serves every pane, so without this a pane opens at
  // wherever the previous one was scrolled to.
  useEffect(() => {
    mainRef.current?.scrollTo(0, 0)
  }, [view])

  useBackendEvents()

  useEffect(() => {
    if (!hasBridge) return
    useSettingsStore.getState().load()
    useEngineStore.getState().load()
    useHistoryStore.getState().load()
    useDownloadStore.getState().init()
    api
      .check_history_size()
      .then((info) => info?.tooBig && setHistoryWarningBytes(info.sizeBytes))
      .catch(() => {})
  }, [])

  if (!hasBridge) return <NoBridge />

  return (
    <div className={`${styles.shell} flex`}>
      <Sidebar />
      <main className={styles.main} ref={mainRef}>
        {!settingsReady ? (
          <div className="flex h-full items-center justify-center">
            <Spinner size="small" label="Starting…" />
          </div>
        ) : view === 'download' ? (
          <Download />
        ) : view === 'history' ? (
          <History />
        ) : (
          <Settings />
        )}
      </main>
      <PlaylistDialog />
      <RemoveEntryDialog />
      <ClearHistoryDialog />
      <HistorySizeDialog sizeBytes={historyWarningBytes} onClose={() => setHistoryWarningBytes(0)} />
      <Toaster toasterId={TOASTER_ID} position="bottom-end" />
    </div>
  )
}
