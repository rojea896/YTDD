import { useEffect, useState } from 'react'
import {
  Accordion,
  AccordionHeader,
  AccordionItem,
  AccordionPanel,
  Badge,
  Body1,
  Button,
  Caption1,
  Card,
  Checkbox,
  Dropdown,
  Field,
  Input,
  MessageBar,
  MessageBarBody,
  Option,
  Spinner,
  Switch,
  Title3,
  makeStyles,
  tokens,
} from '@fluentui/react-components'
import { ArrowSync20Regular, DocumentDismiss20Regular, Folder20Regular } from '@fluentui/react-icons'
import { api } from '../lib/ipc.jsx'
import { basename, timeAgo } from '../lib/format.jsx'
import { useSettingsStore } from '../store/settingsStore.jsx'
import { useEngineStore } from '../store/engineStore.jsx'

const useStyles = makeStyles({
  card: { padding: '20px', rowGap: '4px' },
  muted: { color: tokens.colorNeutralForeground4 },
  dim: { color: tokens.colorNeutralForeground3 },
  numeric: { fontFamily: tokens.fontFamilyNumeric },
  field: { maxWidth: '360px' },
  advanced: { padding: '4px 8px' },
})

const OUTPUT_MODES = [
  { value: 'premiere', label: 'Premiere-ready MP4 (recommended)' },
  { value: 'original', label: 'Original quality, as the site serves it' },
]
const COOKIE_MODES = [
  { value: 'auto', label: 'Auto (recommended)' },
  { value: 'browser', label: 'A specific browser' },
  { value: 'file', label: 'A cookies.txt file' },
]
const BROWSERS = ['firefox', 'chrome', 'edge', 'brave', 'opera', 'vivaldi']
const CODECS = [
  { value: 'auto', label: 'Auto' },
  { value: 'av1', label: 'AV1' },
  { value: 'vp9', label: 'VP9' },
  { value: 'h264', label: 'H.264' },
]
const CONTAINERS = ['mp4', 'mkv', 'webm']
const CHANNELS = [
  { value: 'nightly', label: 'Nightly (recommended)' },
  { value: 'stable', label: 'Stable' },
]
const capitalise = (s) => s.charAt(0).toUpperCase() + s.slice(1)

/** A Dropdown bound to one setting. */
function SettingDropdown({ settingKey, options, disabled, className }) {
  const value = useSettingsStore((s) => s.settings[settingKey])
  const update = useSettingsStore((s) => s.update)
  return (
    <Dropdown
      className={className}
      disabled={disabled}
      value={options.find((o) => o.value === value)?.label ?? ''}
      selectedOptions={[value]}
      onOptionSelect={(_e, d) => update({ [settingKey]: d.optionValue })}
    >
      {options.map((o) => (
        <Option key={o.value} value={o.value}>
          {o.label}
        </Option>
      ))}
    </Dropdown>
  )
}

/** A text setting that saves on blur or Enter, not on every keystroke. */
function SettingInput({ settingKey, placeholder }) {
  const stored = useSettingsStore((s) => s.settings[settingKey] ?? '')
  const update = useSettingsStore((s) => s.update)
  const [draft, setDraft] = useState(stored)
  useEffect(() => setDraft(stored), [stored])
  const commit = () => {
    if (draft.trim() !== stored) update({ [settingKey]: draft.trim() })
  }
  return (
    <Input
      value={draft}
      placeholder={placeholder}
      onChange={(_e, d) => setDraft(d.value)}
      onBlur={commit}
      onKeyDown={(e) => e.key === 'Enter' && commit()}
    />
  )
}

function EngineCard() {
  const styles = useStyles()
  const status = useEngineStore((s) => s.status)
  const checking = useEngineStore((s) => s.checking || s.status?.checking)
  const checkNow = useEngineStore((s) => s.checkNow)
  const autoUpdate = useSettingsStore((s) => s.settings.autoUpdateEngine !== false)
  const update = useSettingsStore((s) => s.update)

  let badge = null
  if (checking) badge = <Badge appearance="tint" color="informative">Checking…</Badge>
  else if (status?.pendingVersion) badge = <Badge appearance="tint" color="informative">Update ready</Badge>
  else if (status?.lastResult === 'error') badge = <Badge appearance="tint" color="danger">Couldn&apos;t check</Badge>
  else if (status?.lastResult) badge = <Badge appearance="tint" color="success">Up to date</Badge>

  const js = status?.jsRuntime

  return (
    <Card className={styles.card} appearance="filled-alternative" data-testid="engine-card">
      <Body1 weight="semibold" block>
        Download engine
      </Body1>
      <Caption1 className={styles.muted}>
        YTDD downloads with yt-dlp. Sites change constantly and yt-dlp ships fixes within days, so
        YTDD fetches the newest version by itself — when it starts, every few hours, and straight
        away whenever a download fails, before trying once more.
      </Caption1>

      <div className="mt-4 flex flex-wrap items-center gap-2">
        <Body1 className={styles.numeric} data-testid="engine-version">
          yt-dlp {status?.version ?? '…'}
        </Body1>
        {badge}
        {status?.pendingVersion && (
          <Caption1 className={styles.dim}>
            {status.pendingVersion} is downloaded and takes over once current downloads finish.
          </Caption1>
        )}
      </div>
      <Caption1 className={styles.dim}>
        {status?.source === 'bundled' ? 'Built-in copy' : 'Auto-updated copy'}
        {status?.lastChecked ? ` · checked ${timeAgo(status.lastChecked)}` : ''}
        {js ? ` · YouTube solver: Node ${js.version}, built into YTDD` : ''}
      </Caption1>

      {status && status.version && !js && (
        <div className="mt-3">
          <MessageBar intent="warning">
            <MessageBarBody>
              No JavaScript runtime was found for YouTube&apos;s player challenges, so some YouTube
              videos may fail or be missing qualities.
            </MessageBarBody>
          </MessageBar>
        </div>
      )}
      {status?.lastResult === 'error' && status.error && (
        <div className="mt-3">
          <MessageBar intent="warning">
            <MessageBarBody>Last update check failed: {status.error}</MessageBarBody>
          </MessageBar>
        </div>
      )}
      {status?.loadError && (
        <div className="mt-3">
          <MessageBar intent="warning">
            <MessageBarBody>
              A downloaded update couldn&apos;t be loaded, so YTDD is using the newest copy that
              works. ({status.loadError})
            </MessageBarBody>
          </MessageBar>
        </div>
      )}

      <div className="mt-4 flex flex-wrap items-center gap-4">
        <Switch
          checked={autoUpdate}
          onChange={(_e, d) => update({ autoUpdateEngine: d.checked })}
          label="Keep yt-dlp up to date automatically"
        />
        <div className="ml-auto">
          <Button
            appearance="secondary"
            icon={checking ? <Spinner size="tiny" /> : <ArrowSync20Regular />}
            disabled={checking}
            onClick={() => checkNow().catch(() => {})}
            data-testid="engine-check"
          >
            Check now
          </Button>
        </div>
      </div>
    </Card>
  )
}

export default function Settings() {
  const styles = useStyles()
  const settings = useSettingsStore((s) => s.settings)
  const error = useSettingsStore((s) => s.error)
  const update = useSettingsStore((s) => s.update)
  const premiere = settings.outputMode !== 'original'

  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-4">
      <Title3>Settings</Title3>

      {error && (
        <MessageBar intent="error">
          <MessageBarBody>{error}</MessageBarBody>
        </MessageBar>
      )}

      <Card className={styles.card} appearance="filled-alternative">
        <Body1 weight="semibold" block>
          Video format
        </Body1>
        <Caption1 className={styles.muted}>
          {premiere
            ? 'Every video is saved as an MP4 with H.264 video and AAC audio, which Premiere Pro, After Effects and every other editor read natively. Most YouTube videos come that way up to 1080p; anything else (4K, AV1, VP9, Opus audio) is converted after downloading.'
            : "Videos keep whatever the site serves — often AV1 or VP9 video with Opus audio. Smaller and untouched, but Premiere Pro can't read Opus audio (clips import silent) or AV1/VP9 video."}
        </Caption1>
        <div className="mt-4">
          <SettingDropdown settingKey="outputMode" options={OUTPUT_MODES} className={styles.field} />
        </div>
      </Card>

      <Card className={styles.card} appearance="filled-alternative">
        <Body1 weight="semibold" block>
          General
        </Body1>
        <div className="mt-2 flex flex-col gap-1">
          <Switch
            checked={Boolean(settings.darkMode)}
            onChange={(_e, d) => update({ darkMode: d.checked })}
            label="Dark mode"
          />
          <Switch
            checked={Boolean(settings.notifyOnComplete)}
            onChange={(_e, d) => update({ notifyOnComplete: d.checked })}
            label="Notify me when a download finishes"
          />
          <Switch
            checked={Boolean(settings.clipboardMonitor)}
            onChange={(_e, d) => update({ clipboardMonitor: d.checked })}
            label="Pick up video links I copy"
          />
        </div>
        <Caption1 className={styles.muted}>
          With link pick-up on, copying a YouTube, Instagram, TikTok or Vimeo link drops it into the
          link box (only when the box is empty).
        </Caption1>
      </Card>

      <Card className={styles.card} appearance="filled-alternative">
        <Body1 weight="semibold" block>
          Cookies
        </Body1>
        <Caption1 className={styles.muted}>
          Lets YTDD download as you — needed for private, age-restricted or members-only videos, and
          when YouTube asks to &ldquo;confirm you&apos;re not a bot&rdquo;.
        </Caption1>
        <div className="mt-3">
          <Switch
            checked={settings.cookiesEnabled !== false}
            onChange={(_e, d) => update({ cookiesEnabled: d.checked })}
            label="Use cookies from my browser"
          />
        </div>
        {settings.cookiesEnabled !== false && (
          <div className="mt-2 flex flex-wrap items-end gap-3">
            <Field label="Where from" className={styles.field}>
              <SettingDropdown settingKey="cookiesMode" options={COOKIE_MODES} />
            </Field>
            {settings.cookiesMode === 'browser' && (
              <Field label="Browser">
                <SettingDropdown
                  settingKey="cookiesBrowser"
                  options={BROWSERS.map((b) => ({ value: b, label: capitalise(b) }))}
                />
              </Field>
            )}
            {settings.cookiesMode === 'file' && (
              <div className="flex items-center gap-2">
                <Button
                  icon={<Folder20Regular />}
                  onClick={async () => {
                    const path = await api.choose_cookies_file()
                    if (path) update({ cookiesFile: path })
                  }}
                >
                  {settings.cookiesFile ? basename(settings.cookiesFile) : 'Choose cookies.txt…'}
                </Button>
                {settings.cookiesFile && (
                  <Button
                    appearance="subtle"
                    icon={<DocumentDismiss20Regular />}
                    onClick={() => update({ cookiesFile: '' })}
                  >
                    Clear
                  </Button>
                )}
              </div>
            )}
          </div>
        )}
        {settings.cookiesEnabled !== false && settings.cookiesMode === 'auto' && (
          <Caption1 className={styles.muted}>
            Auto uses a cookies.txt file if you&apos;ve set one, otherwise tries each installed
            browser until one works. Firefox is the most reliable — Chrome and Edge lock their
            cookies while they&apos;re open.
          </Caption1>
        )}
      </Card>

      <EngineCard />

      <Card className={styles.advanced} appearance="filled-alternative">
        <Accordion collapsible>
          <AccordionItem value="advanced">
            <AccordionHeader>Advanced options</AccordionHeader>
            <AccordionPanel>
              <div className="flex flex-col gap-4 pb-4">
                <div className="flex flex-wrap gap-3">
                  <Field
                    label="Video codec"
                    hint={premiere ? 'Only used with “Original quality”.' : 'Preferred when a site offers several.'}
                  >
                    <SettingDropdown settingKey="codecPref" options={CODECS} disabled={premiere} />
                  </Field>
                  <Field label="Container" hint={premiere ? 'Premiere-ready is always MP4.' : ' '}>
                    <SettingDropdown
                      settingKey="containerFormat"
                      options={CONTAINERS.map((c) => ({ value: c, label: c.toUpperCase() }))}
                      disabled={premiere}
                    />
                  </Field>
                </div>
                <div className="flex flex-wrap gap-3">
                  <Field label="File size limit" hint="Skip files bigger than this, e.g. 500M or 2G.">
                    <SettingInput settingKey="filesizeLimit" placeholder="No limit" />
                  </Field>
                  <Field label="Speed limit" hint="Throttle downloads, e.g. 2M (per second).">
                    <SettingInput settingKey="rateLimit" placeholder="Unlimited" />
                  </Field>
                </div>
                <Checkbox
                  checked={Boolean(settings.preferSmaller)}
                  onChange={(_e, d) => update({ preferSmaller: Boolean(d.checked) })}
                  label="Prefer the smallest file at the chosen quality"
                />
                <Field label="User-agent override" hint="Only if a site blocks yt-dlp's default.">
                  <SettingInput settingKey="userAgent" placeholder="yt-dlp default" />
                </Field>
                <Field
                  label="yt-dlp update channel"
                  hint="Nightly gets site fixes days sooner, and is what yt-dlp itself recommends."
                  className={styles.field}
                >
                  <SettingDropdown settingKey="engineChannel" options={CHANNELS} />
                </Field>
              </div>
            </AccordionPanel>
          </AccordionItem>
        </Accordion>
      </Card>
    </div>
  )
}
