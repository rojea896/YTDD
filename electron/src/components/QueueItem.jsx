import { useState } from 'react'
import {
  Body1,
  Button,
  Caption1,
  Card,
  Link,
  MessageBar,
  MessageBarBody,
  MessageBarTitle,
  ProgressBar,
  Tooltip,
  makeStyles,
  tokens,
} from '@fluentui/react-components'
import {
  ArrowClockwise20Regular,
  Dismiss20Regular,
  Pause20Filled,
  Play20Filled,
} from '@fluentui/react-icons'
import { useDownloadStore } from '../store/downloadStore.jsx'
import { formatClock, formatSpeed } from '../lib/format.jsx'

const useStyles = makeStyles({
  card: { padding: '14px 16px', rowGap: '10px' },
  thumb: {
    width: '64px',
    height: '36px',
    flexShrink: 0,
    borderRadius: tokens.borderRadiusMedium,
    objectFit: 'cover',
    backgroundColor: tokens.colorNeutralBackground4,
  },
  muted: { color: tokens.colorNeutralForeground3 },
  // Tabular figures so the percentage and speed don't jitter as they tick.
  numbers: { fontFamily: tokens.fontFamilyNumeric, color: tokens.colorNeutralForeground3 },
  detail: {
    fontFamily: tokens.fontFamilyMonospace,
    fontSize: tokens.fontSizeBase100,
    whiteSpace: 'pre-wrap',
    wordBreak: 'break-word',
    color: tokens.colorNeutralForeground3,
  },
})

function statusLine(item) {
  const pct = `${Math.floor(item.progress || 0)}%`
  if (item.status === 'paused') return `Paused · ${pct}`
  if (item.status === 'starting') return 'Starting…'
  if (item.status === 'processing') return `${item.stage || 'Finishing up'}…`
  const parts = [item.stage, pct]
  if (item.status === 'downloading') {
    const speed = formatSpeed(item.speed)
    if (speed) parts.push(speed)
  }
  const eta = formatClock(item.eta)
  if (eta && item.progress > 0) parts.push(`${eta} left`)
  return parts.filter(Boolean).join(' · ')
}

export default function QueueItem({ item }) {
  const styles = useStyles()
  const { pause, resume, cancel, dismiss, retry } = useDownloadStore.getState()
  const [showDetail, setShowDetail] = useState(false)
  const failed = item.status === 'error'
  // Merging and the update check have no meaningful percentage.
  const indeterminate = item.status === 'processing' || item.status === 'starting'
  const canPause = item.status === 'downloading' || item.status === 'paused'

  return (
    <Card className={styles.card} appearance="filled-alternative">
      <div className="flex items-center gap-3">
        {item.thumbnail ? (
          <img className={styles.thumb} src={item.thumbnail} alt="" />
        ) : (
          <div className={styles.thumb} />
        )}
        <div className="min-w-0 flex-1">
          <Body1 truncate block wrap={false}>
            {item.title}
          </Body1>
          <Caption1 className={styles.muted}>{item.format}</Caption1>
        </div>
        {failed ? (
          <>
            <Button appearance="subtle" icon={<ArrowClockwise20Regular />} onClick={() => retry(item.id)}>
              Retry
            </Button>
            <Tooltip content="Dismiss" relationship="label">
              <Button appearance="subtle" icon={<Dismiss20Regular />} onClick={() => dismiss(item.id)} />
            </Tooltip>
          </>
        ) : (
          <>
            {canPause && (
              <Tooltip content={item.status === 'paused' ? 'Resume' : 'Pause'} relationship="label">
                <Button
                  appearance="subtle"
                  icon={item.status === 'paused' ? <Play20Filled /> : <Pause20Filled />}
                  onClick={() => (item.status === 'paused' ? resume(item.id) : pause(item.id))}
                />
              </Tooltip>
            )}
            <Tooltip content="Cancel" relationship="label">
              <Button appearance="subtle" icon={<Dismiss20Regular />} onClick={() => cancel(item.id)} />
            </Tooltip>
          </>
        )}
      </div>

      {failed ? (
        <MessageBar intent="error" layout="multiline">
          <MessageBarBody>
            <MessageBarTitle>Download failed</MessageBarTitle>
            {item.error?.message}{' '}
            {item.error?.detail && item.error.detail !== item.error.message && (
              <Link as="button" inline onClick={() => setShowDetail((v) => !v)}>
                {showDetail ? 'Hide details' : 'Details'}
              </Link>
            )}
            {showDetail && <div className={`${styles.detail} mt-2`}>{item.error.detail}</div>}
          </MessageBarBody>
        </MessageBar>
      ) : (
        <>
          <ProgressBar
            thickness="large"
            value={indeterminate ? undefined : Math.min(1, (item.progress || 0) / 100)}
            color={item.status === 'paused' ? 'warning' : 'brand'}
          />
          <Caption1 className={styles.numbers}>{statusLine(item)}</Caption1>
        </>
      )}
    </Card>
  )
}
