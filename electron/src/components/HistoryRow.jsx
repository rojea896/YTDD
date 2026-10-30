import {
  Body1,
  Button,
  Caption1,
  Menu,
  MenuDivider,
  MenuItem,
  MenuList,
  MenuPopover,
  MenuTrigger,
  Tooltip,
  makeStyles,
  mergeClasses,
  tokens,
} from '@fluentui/react-components'
import {
  Copy20Regular,
  Delete20Regular,
  FolderOpen20Regular,
  MoreHorizontal20Regular,
  MusicNote220Regular,
  Open20Regular,
  Video20Regular,
} from '@fluentui/react-icons'
import { api } from '../lib/ipc.jsx'
import { formatHistoryDate } from '../lib/format.jsx'
import { useHistoryStore } from '../store/historyStore.jsx'
import { useNotify } from '../lib/notify.jsx'

const useStyles = makeStyles({
  row: {
    display: 'flex',
    alignItems: 'center',
    columnGap: '12px',
    padding: '8px 8px 8px 10px',
    borderRadius: tokens.borderRadiusMedium,
    cursor: 'default',
    userSelect: 'none',
    ':hover': { backgroundColor: tokens.colorSubtleBackgroundHover },
  },
  tile: {
    width: '32px',
    height: '32px',
    flexShrink: 0,
    borderRadius: tokens.borderRadiusMedium,
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
  },
  video: {
    backgroundColor: tokens.colorBrandBackground2,
    color: tokens.colorBrandForeground2,
  },
  audio: {
    backgroundColor: tokens.colorPaletteTealBackground2,
    color: tokens.colorPaletteTealForeground2,
  },
  muted: { color: tokens.colorNeutralForeground3 },
})

const MISSING = "File not found — it's been moved or deleted since downloading."

function useEntryActions(entry) {
  const notify = useNotify()
  const run = async (fn) => {
    const ok = await fn().catch(() => false)
    if (!ok) notify({ intent: 'warning', title: MISSING })
  }
  return {
    open: () => run(() => api.open_file(entry.filepath)),
    showInFolder: () => run(() => api.open_folder(entry.filepath)),
    copyPath: async () => {
      await api.copy_text(entry.filepath)
      notify({ intent: 'success', title: 'File path copied' })
    },
    remove: () => useHistoryStore.getState().askRemove(entry),
    drag: () => run(() => api.start_drag(entry.filepath)),
  }
}

function EntryMenuList({ actions }) {
  return (
    <MenuList>
      <MenuItem icon={<Open20Regular />} onClick={actions.open}>
        Open file
      </MenuItem>
      <MenuItem icon={<FolderOpen20Regular />} onClick={actions.showInFolder}>
        Show in folder
      </MenuItem>
      <MenuItem icon={<Copy20Regular />} onClick={actions.copyPath}>
        Copy file path
      </MenuItem>
      <MenuDivider />
      <MenuItem icon={<Delete20Regular />} onClick={actions.remove}>
        Remove from history…
      </MenuItem>
    </MenuList>
  )
}

/**
 * One downloaded file. Double-click opens it, dragging hands the real file to
 * another app (straight onto a Premiere timeline), right-click or "…" gives
 * the rest.
 */
export default function HistoryRow({ entry }) {
  const styles = useStyles()
  const actions = useEntryActions(entry)
  const isAudio = entry.type === 'audio'
  const Icon = isAudio ? MusicNote220Regular : Video20Regular

  return (
    <Menu openOnContext>
      <MenuTrigger disableButtonEnhancement>
        <div
          className={styles.row}
          draggable
          onDragStart={(e) => {
            e.preventDefault()
            actions.drag()
          }}
          onDoubleClick={actions.open}
          title="Double-click to open · drag into Premiere or any other app"
          data-testid="history-row"
        >
          <div className={mergeClasses(styles.tile, isAudio ? styles.audio : styles.video)}>
            <Icon />
          </div>
          <div className="min-w-0 flex-1">
            <Body1 truncate block wrap={false}>
              {entry.title}
            </Body1>
            <Caption1 className={styles.muted}>
              {isAudio ? 'Audio' : 'Video'} · {formatHistoryDate(entry.date)}
            </Caption1>
          </div>
          <Tooltip content="Show in folder" relationship="label">
            <Button
              appearance="subtle"
              icon={<FolderOpen20Regular />}
              onClick={actions.showInFolder}
              onDoubleClick={(e) => e.stopPropagation()}
            />
          </Tooltip>
          <Menu>
            <MenuTrigger disableButtonEnhancement>
              <Button
                appearance="subtle"
                icon={<MoreHorizontal20Regular />}
                aria-label="More actions"
                onDoubleClick={(e) => e.stopPropagation()}
              />
            </MenuTrigger>
            <MenuPopover>
              <EntryMenuList actions={actions} />
            </MenuPopover>
          </Menu>
        </div>
      </MenuTrigger>
      <MenuPopover>
        <EntryMenuList actions={actions} />
      </MenuPopover>
    </Menu>
  )
}
