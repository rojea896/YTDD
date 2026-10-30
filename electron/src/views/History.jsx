import { useMemo, useState } from 'react'
import {
  Button,
  Caption1,
  Card,
  Dropdown,
  Option,
  SearchBox,
  Title3,
  makeStyles,
  tokens,
} from '@fluentui/react-components'
import { Delete20Regular } from '@fluentui/react-icons'
import HistoryRow from '../components/HistoryRow.jsx'
import { useHistoryStore } from '../store/historyStore.jsx'
import { parseHistoryDate } from '../lib/format.jsx'

const PAGE = 50
const DAY = 86400000
const RANGES = [
  { value: 'all', label: 'All time', ms: null },
  { value: '24h', label: 'Last 24 hours', ms: DAY },
  { value: 'week', label: 'Last week', ms: 7 * DAY },
  { value: 'month', label: 'Last month', ms: 30 * DAY },
  { value: 'year', label: 'Last year', ms: 365 * DAY },
]

const useStyles = makeStyles({
  // SearchBox ships with a 468px max-width; let it fill the row instead.
  search: { flexGrow: 1, maxWidth: 'none' },
  range: { minWidth: '160px' },
  list: { padding: '8px', rowGap: '0' },
  muted: { color: tokens.colorNeutralForeground3 },
  empty: { padding: '24px', textAlign: 'center', color: tokens.colorNeutralForeground3 },
})

export default function History() {
  const styles = useStyles()
  const items = useHistoryStore((s) => s.items)
  const askClear = useHistoryStore((s) => s.askClear)
  const [query, setQuery] = useState('')
  const [range, setRange] = useState('all')
  const [shown, setShown] = useState(PAGE)

  const filtered = useMemo(() => {
    const span = RANGES.find((r) => r.value === range)?.ms
    const cutoff = span ? Date.now() - span : null
    const q = query.trim().toLowerCase()
    return items.filter((e) => {
      if (q && !String(e.title || '').toLowerCase().includes(q)) return false
      if (cutoff == null) return true
      const t = parseHistoryDate(e.date)
      return t != null && t >= cutoff
    })
  }, [items, query, range])

  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-4">
      <div className="flex items-baseline gap-3">
        <Title3>History</Title3>
        <Caption1 className={styles.muted}>
          {items.length} download{items.length === 1 ? '' : 's'}
        </Caption1>
        <div className="ml-auto">
          <Button appearance="subtle" icon={<Delete20Regular />} disabled={!items.length} onClick={askClear}>
            Clear history
          </Button>
        </div>
      </div>

      <div className="flex gap-3">
        <SearchBox
          className={styles.search}
          placeholder="Search by title"
          value={query}
          onChange={(_e, d) => {
            setQuery(d.value)
            setShown(PAGE)
          }}
        />
        <Dropdown
          className={styles.range}
          aria-label="Time range"
          value={RANGES.find((r) => r.value === range)?.label}
          selectedOptions={[range]}
          onOptionSelect={(_e, d) => {
            setRange(d.optionValue)
            setShown(PAGE)
          }}
        >
          {RANGES.map((r) => (
            <Option key={r.value} value={r.value}>
              {r.label}
            </Option>
          ))}
        </Dropdown>
      </div>

      <Card className={styles.list} appearance="filled-alternative">
        {filtered.length ? (
          filtered.slice(0, shown).map((entry) => (
            <HistoryRow key={`${entry.filepath}|${entry.date}`} entry={entry} />
          ))
        ) : (
          <Caption1 block className={styles.empty}>
            {items.length ? 'Nothing matches that search or time range.' : 'No downloads yet.'}
          </Caption1>
        )}
      </Card>

      {filtered.length > shown && (
        <div className="flex justify-center">
          <Button appearance="secondary" onClick={() => setShown((n) => n + PAGE)}>
            Show more ({filtered.length - shown} left)
          </Button>
        </div>
      )}
    </div>
  )
}
