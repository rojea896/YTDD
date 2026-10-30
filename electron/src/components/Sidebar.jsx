import { Tab, TabList, Text, Caption1, CounterBadge, Tooltip, makeStyles, tokens } from '@fluentui/react-components'
import {
  ArrowDownload24Regular,
  ArrowDownload24Filled,
  History24Regular,
  History24Filled,
  Settings24Regular,
  Settings24Filled,
} from '@fluentui/react-icons'
import { useUiStore } from '../store/uiStore.jsx'
import { useDownloadStore, isActive } from '../store/downloadStore.jsx'
import { useEngineStore } from '../store/engineStore.jsx'

const VIEWS = [
  { id: 'download', label: 'Download', icon: ArrowDownload24Regular, active: ArrowDownload24Filled },
  { id: 'history', label: 'History', icon: History24Regular, active: History24Filled },
  { id: 'settings', label: 'Settings', icon: Settings24Regular, active: Settings24Filled },
]

const useStyles = makeStyles({
  nav: {
    width: '208px',
    flexShrink: 0,
    borderRight: `1px solid ${tokens.colorNeutralStroke2}`,
    backgroundColor: tokens.colorNeutralBackground1,
    padding: '16px 12px',
  },
  brandDot: { width: '10px', height: '10px', borderRadius: '50%' },
  // Same signal as WorkTrack: brand blue at rest, marigold while working.
  busy: { backgroundColor: tokens.colorPaletteMarigoldBackground3 },
  idle: { backgroundColor: tokens.colorBrandBackground },
  footer: { color: tokens.colorNeutralForeground4 },
  footerWarn: { color: tokens.colorPaletteRedForeground1 },
})

function engineLine(status) {
  if (!status?.version) return { text: 'Starting yt-dlp…' }
  const version = `yt-dlp ${status.version.split('.').slice(0, 3).join('.')}`
  if (status.checking) return { text: `${version} · checking…` }
  if (status.pendingVersion) return { text: `${version} · update ready` }
  if (status.lastResult === 'error') return { text: `${version} · update check failed`, warn: true }
  return { text: version }
}

export default function Sidebar() {
  const styles = useStyles()
  const view = useUiStore((s) => s.view)
  const setView = useUiStore((s) => s.setView)
  const activeCount = useDownloadStore((s) => s.queue.filter(isActive).length)
  const engine = useEngineStore((s) => s.status)
  const line = engineLine(engine)

  return (
    <nav className={`${styles.nav} flex flex-col`}>
      <div className="mb-6 flex items-center gap-2.5 px-2">
        <span
          className={`${styles.brandDot} ${activeCount ? styles.busy : styles.idle}`}
          title={activeCount ? 'Downloading' : 'Idle'}
        />
        <Text weight="semibold">YTDD</Text>
      </div>

      <TabList
        vertical
        appearance="subtle"
        selectedValue={view}
        onTabSelect={(_e, data) => setView(data.value)}
      >
        {VIEWS.map((v) => {
          const Icon = view === v.id ? v.active : v.icon
          return (
            <Tab key={v.id} value={v.id} icon={<Icon />}>
              <span className="flex items-center gap-2">
                {v.label}
                {v.id === 'download' && activeCount > 0 && (
                  <CounterBadge count={activeCount} size="small" appearance="filled" color="brand" />
                )}
              </span>
            </Tab>
          )
        })}
      </TabList>

      {/* mt-auto on a plain element: Griffel's margin reset beats Tailwind on
          Fluent components. */}
      <div className="mt-auto px-2">
        <Tooltip content="The download engine. YTDD keeps it up to date by itself." relationship="description">
          <Caption1 className={line.warn ? styles.footerWarn : styles.footer}>{line.text}</Caption1>
        </Tooltip>
      </div>
    </nav>
  )
}
