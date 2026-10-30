import { useState } from 'react'
import {
  Body1,
  Button,
  Caption1,
  Card,
  Divider,
  Dropdown,
  Input,
  Link,
  MessageBar,
  MessageBarBody,
  MessageBarTitle,
  Option,
  Skeleton,
  SkeletonItem,
  Spinner,
  Subtitle2,
  Tab,
  TabList,
  makeStyles,
  tokens,
} from '@fluentui/react-components'
import {
  ArrowDownload24Filled,
  Link20Regular,
  MusicNote220Regular,
  Search20Regular,
  Video20Regular,
} from '@fluentui/react-icons'
import QueueItem from '../components/QueueItem.jsx'
import HistoryRow from '../components/HistoryRow.jsx'
import { useDownloadStore } from '../store/downloadStore.jsx'
import { useHistoryStore } from '../store/historyStore.jsx'
import { useSettingsStore } from '../store/settingsStore.jsx'
import { useUiStore } from '../store/uiStore.jsx'
import { formatClock } from '../lib/format.jsx'

const RECENT_COUNT = 4

const useStyles = makeStyles({
  // The hero, styled like WorkTrack's timer card.
  hero: {
    borderRadius: tokens.borderRadiusXLarge,
    border: `1px solid ${tokens.colorNeutralStroke2}`,
    backgroundColor: tokens.colorNeutralBackground2,
    padding: '24px',
  },
  urlInput: { flexGrow: 1, minWidth: 0 },
  thumb: {
    width: '192px',
    height: '108px',
    flexShrink: 0,
    borderRadius: tokens.borderRadiusLarge,
    objectFit: 'cover',
    backgroundColor: tokens.colorNeutralBackground4,
  },
  title: {
    display: '-webkit-box',
    WebkitLineClamp: 2,
    WebkitBoxOrient: 'vertical',
    overflow: 'hidden',
  },
  muted: { color: tokens.colorNeutralForeground3 },
  faint: { color: tokens.colorNeutralForeground4 },
  quality: { minWidth: '240px' },
  detail: {
    fontFamily: tokens.fontFamilyMonospace,
    fontSize: tokens.fontSizeBase100,
    whiteSpace: 'pre-wrap',
    wordBreak: 'break-word',
  },
  panel: { padding: '16px' },
})

function qualityText(q) {
  return q.size ? `${q.label} · ${q.size}` : q.label
}

function outputHint({ mediaType, premiere, selected, audioLabel, scope }) {
  const prefix = scope === 'playlist' ? 'Every video in the playlist · ' : ''
  if (mediaType === 'audio') return `${prefix}Saves as ${audioLabel}.`
  if (!premiere) {
    return `${prefix}Keeps the site's original codecs (Settings → Video format). Premiere may not be able to read them.`
  }
  if (selected?.convert) {
    return `${prefix}This quality only comes as AV1/VP9, so it's converted to H.264 after downloading — takes a little longer.`
  }
  return `${prefix}Saves as an MP4 with H.264 video and AAC audio — drops straight into Premiere.`
}

function FetchCard() {
  const styles = useStyles()
  const s = useDownloadStore()
  const premiere = useSettingsStore((st) => st.settings?.outputMode !== 'original')
  const [showDetail, setShowDetail] = useState(false)

  const info = s.info
  const qualities = info?.videoQualities ?? []
  const selectedVideo = qualities.find((q) => q.label === s.videoQuality)
  const audioSizes = info?.audioQualitySizes ?? {}
  const selectedAudio = s.audioOptions.find((o) => o.id === s.audioQuality)
  const canFetch = s.url.trim().length > 0 && !s.fetching

  const dropdownValue =
    s.mediaType === 'video'
      ? selectedVideo
        ? qualityText(selectedVideo)
        : ''
      : selectedAudio
        ? `${selectedAudio.label}${audioSizes[selectedAudio.id] ? ` · ${audioSizes[selectedAudio.id]}` : ''}`
        : ''

  return (
    <section className={styles.hero}>
      <form
        className="flex items-center gap-3"
        onSubmit={(e) => {
          e.preventDefault()
          if (canFetch) s.requestFetch()
        }}
      >
        <Input
          className={styles.urlInput}
          size="large"
          value={s.url}
          onChange={(_e, d) => s.setUrl(d.value)}
          contentBefore={<Link20Regular />}
          placeholder="Paste a YouTube, Instagram or TikTok link"
          aria-label="Video link"
          data-testid="url-input"
        />
        <Button
          type="submit"
          appearance={info ? 'secondary' : 'primary'}
          size="large"
          disabled={!canFetch}
          icon={s.fetching ? <Spinner size="tiny" /> : <Search20Regular />}
          data-testid="fetch-btn"
        >
          {s.fetching ? 'Fetching…' : 'Fetch'}
        </Button>
      </form>

      {!info && !s.fetching && !s.fetchError && (
        <div className="mt-3">
          <Caption1 className={styles.muted}>
            {premiere
              ? 'Works with YouTube, Instagram, TikTok, Vimeo and over a thousand other sites. Videos save as MP4s that drop straight into Premiere.'
              : 'Works with YouTube, Instagram, TikTok, Vimeo and over a thousand other sites.'}
          </Caption1>
        </div>
      )}

      {s.fetchError && (
        <div className="mt-4">
          <MessageBar intent="error" layout="multiline">
            <MessageBarBody>
              <MessageBarTitle>Couldn&apos;t fetch this link</MessageBarTitle>
              {s.fetchError.message}{' '}
              {s.fetchError.detail && s.fetchError.detail !== s.fetchError.message && (
                <Link as="button" inline onClick={() => setShowDetail((v) => !v)}>
                  {showDetail ? 'Hide details' : 'Details'}
                </Link>
              )}
              {showDetail && <div className={`${styles.detail} mt-2`}>{s.fetchError.detail}</div>}
            </MessageBarBody>
          </MessageBar>
        </div>
      )}

      {s.fetching && (
        <>
          <div className="my-5">
            <Divider />
          </div>
          <Skeleton aria-label="Fetching video details">
            <div className="flex gap-4">
              <SkeletonItem shape="rectangle" style={{ width: 192, height: 108 }} />
              <div className="flex flex-1 flex-col gap-2 pt-1">
                <SkeletonItem size={16} style={{ width: '70%' }} />
                <SkeletonItem size={12} style={{ width: '40%' }} />
              </div>
            </div>
          </Skeleton>
        </>
      )}

      {info && (
        <>
          <div className="my-5">
            <Divider />
          </div>
          <div className="flex gap-4">
            {info.thumbnail ? (
              <img className={styles.thumb} src={info.thumbnail} alt="" />
            ) : (
              <div className={styles.thumb} />
            )}
            <div className="flex min-w-0 flex-1 flex-col gap-1">
              <Subtitle2 className={styles.title} data-testid="video-title">
                {info.title}
              </Subtitle2>
              <Caption1 className={styles.muted}>
                {[info.channel, formatClock(info.duration)].filter(Boolean).join(' · ')}
              </Caption1>
              <div className="mt-auto">
                <TabList
                  size="small"
                  selectedValue={s.mediaType}
                  onTabSelect={(_e, d) => s.setMediaType(d.value)}
                >
                  <Tab value="video" icon={<Video20Regular />}>
                    Video
                  </Tab>
                  <Tab value="audio" icon={<MusicNote220Regular />}>
                    Audio only
                  </Tab>
                </TabList>
              </div>
            </div>
          </div>

          <div className="mt-5 flex flex-wrap items-center gap-3">
            <Dropdown
              className={styles.quality}
              aria-label="Quality"
              value={dropdownValue}
              selectedOptions={[s.mediaType === 'video' ? (s.videoQuality ?? '') : s.audioQuality]}
              onOptionSelect={(_e, d) =>
                s.mediaType === 'video' ? s.setVideoQuality(d.optionValue) : s.setAudioQuality(d.optionValue)
              }
              data-testid="quality-dropdown"
            >
              {s.mediaType === 'video'
                ? qualities.map((q) => (
                    <Option key={q.label} value={q.label} text={qualityText(q)}>
                      <span>
                        {qualityText(q)}
                        {q.convert && premiere && <span className={styles.faint}> · converts to H.264</span>}
                      </span>
                    </Option>
                  ))
                : s.audioOptions.map((o) => (
                    <Option
                      key={o.id}
                      value={o.id}
                      text={audioSizes[o.id] ? `${o.label} · ${audioSizes[o.id]}` : o.label}
                    >
                      {audioSizes[o.id] ? `${o.label} · ${audioSizes[o.id]}` : o.label}
                    </Option>
                  ))}
            </Dropdown>

            <div className="ml-auto">
              <Button
                appearance="primary"
                size="large"
                icon={<ArrowDownload24Filled />}
                onClick={() => s.startDownload()}
                disabled={s.mediaType === 'video' && !s.videoQuality}
                data-testid="download-btn"
              >
                {s.scope === 'playlist' ? 'Download playlist' : 'Download'}
              </Button>
            </div>
          </div>
          <div className="mt-3">
            <Caption1 className={styles.muted} data-testid="output-hint">
              {outputHint({
                mediaType: s.mediaType,
                premiere,
                selected: selectedVideo,
                audioLabel: selectedAudio?.label ?? 'audio',
                scope: s.scope,
              })}
            </Caption1>
          </div>
        </>
      )}
    </section>
  )
}

function RecentDownloads() {
  const styles = useStyles()
  const items = useHistoryStore((s) => s.items)
  const setView = useUiStore((s) => s.setView)
  const recent = items.slice(0, RECENT_COUNT)

  return (
    <Card className={styles.panel} appearance="filled-alternative">
      <div className="flex items-center">
        <Body1 weight="semibold">Recent downloads</Body1>
        {items.length > RECENT_COUNT && (
          <div className="ml-auto">
            <Button appearance="subtle" size="small" onClick={() => setView('history')}>
              View all {items.length}
            </Button>
          </div>
        )}
      </div>
      {recent.length ? (
        <div className="flex flex-col">
          {recent.map((entry) => (
            <HistoryRow key={`${entry.filepath}|${entry.date}`} entry={entry} />
          ))}
        </div>
      ) : (
        <Caption1 className={styles.muted}>
          Finished downloads show up here. Double-click one to open it, or drag it straight onto a
          Premiere timeline.
        </Caption1>
      )}
    </Card>
  )
}

export default function Download() {
  const queue = useDownloadStore((s) => s.queue)
  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-6">
      <FetchCard />
      {queue.length > 0 && (
        <section className="flex flex-col gap-3" data-testid="queue">
          <Body1 weight="semibold">Downloads</Body1>
          {queue.map((item) => (
            <QueueItem key={item.id} item={item} />
          ))}
        </section>
      )}
      <RecentDownloads />
    </div>
  )
}
