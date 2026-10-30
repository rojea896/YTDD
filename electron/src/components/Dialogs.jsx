import { useEffect, useState } from 'react'
import {
  Body1,
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogBody,
  DialogContent,
  DialogSurface,
  DialogTitle,
  makeStyles,
  tokens,
} from '@fluentui/react-components'
import { useHistoryStore } from '../store/historyStore.jsx'
import { useDownloadStore } from '../store/downloadStore.jsx'

const useStyles = makeStyles({
  danger: {
    backgroundColor: tokens.colorPaletteRedBackground3,
    color: tokens.colorNeutralForegroundOnBrand,
    ':hover': { backgroundColor: tokens.colorPaletteRedForeground1, color: tokens.colorNeutralForegroundOnBrand },
  },
  muted: { color: tokens.colorNeutralForeground3 },
})

function ConfirmDialog({ open, title, children, confirmLabel, danger, onConfirm, onCancel }) {
  const styles = useStyles()
  const [busy, setBusy] = useState(false)
  return (
    <Dialog open={open} onOpenChange={(_e, d) => !d.open && onCancel()}>
      <DialogSurface>
        <DialogBody>
          <DialogTitle>{title}</DialogTitle>
          <DialogContent>{children}</DialogContent>
          <DialogActions>
            <Button appearance="secondary" onClick={onCancel} disabled={busy}>
              Cancel
            </Button>
            <Button
              appearance="primary"
              className={danger ? styles.danger : undefined}
              disabled={busy}
              onClick={async () => {
                setBusy(true)
                try {
                  await onConfirm()
                } finally {
                  setBusy(false)
                }
              }}
            >
              {confirmLabel}
            </Button>
          </DialogActions>
        </DialogBody>
      </DialogSurface>
    </Dialog>
  )
}

export function RemoveEntryDialog() {
  const styles = useStyles()
  const entry = useHistoryStore((s) => s.removing)
  const { remove, cancelRemove } = useHistoryStore.getState()
  const [deleteFile, setDeleteFile] = useState(false)
  useEffect(() => setDeleteFile(false), [entry])

  return (
    <ConfirmDialog
      open={Boolean(entry)}
      title="Remove this download?"
      confirmLabel={deleteFile ? 'Remove and delete file' : 'Remove from history'}
      danger={deleteFile}
      onCancel={cancelRemove}
      onConfirm={() => remove(entry, deleteFile)}
    >
      <div className="mb-3">
        <Body1 block>{entry?.title}</Body1>
      </div>
      <Checkbox
        checked={deleteFile}
        onChange={(_e, d) => setDeleteFile(Boolean(d.checked))}
        label="Also delete the file from disk"
      />
      {!deleteFile && (
        <Body1 block className={styles.muted}>
          The file stays where it is — only the entry goes.
        </Body1>
      )}
    </ConfirmDialog>
  )
}

export function ClearHistoryDialog() {
  const open = useHistoryStore((s) => s.confirmingClear)
  const count = useHistoryStore((s) => s.items.length)
  const { clear, cancelClear } = useHistoryStore.getState()
  return (
    <ConfirmDialog
      open={open}
      title="Clear download history?"
      confirmLabel="Clear history"
      danger
      onCancel={cancelClear}
      onConfirm={clear}
    >
      This removes all {count} entries from YTDD. The downloaded files themselves aren&apos;t
      touched. This can&apos;t be undone.
    </ConfirmDialog>
  )
}

export function HistorySizeDialog({ sizeBytes, onClose }) {
  const mb = (sizeBytes / (1024 * 1024)).toFixed(1)
  return (
    <ConfirmDialog
      open={sizeBytes > 0}
      title="Your download history is getting large"
      confirmLabel="Clear history"
      danger
      onCancel={onClose}
      onConfirm={async () => {
        await useHistoryStore.getState().clear()
        onClose()
      }}
    >
      history.json is {mb} MB. You can clear it now (downloaded files aren&apos;t touched), or
      keep it and deal with it later.
    </ConfirmDialog>
  )
}

export function PlaylistDialog() {
  const open = useDownloadStore((s) => s.playlistPrompt)
  const answer = useDownloadStore.getState().answerPlaylist
  return (
    <Dialog open={open} onOpenChange={(_e, d) => !d.open && answer(null)}>
      <DialogSurface>
        <DialogBody>
          <DialogTitle>This link is part of a playlist</DialogTitle>
          <DialogContent>
            Download just the video in the link, or every video in the playlist? A playlist is
            saved into its own folder, numbered in order.
          </DialogContent>
          <DialogActions>
            <Button appearance="secondary" onClick={() => answer('single')}>
              Just this video
            </Button>
            <Button appearance="primary" onClick={() => answer('playlist')}>
              Entire playlist
            </Button>
          </DialogActions>
        </DialogBody>
      </DialogSurface>
    </Dialog>
  )
}
