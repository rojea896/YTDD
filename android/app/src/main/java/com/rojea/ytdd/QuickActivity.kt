package com.rojea.ytdd

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.rojea.ytdd.core.History
import com.rojea.ytdd.core.Kind
import com.rojea.ytdd.core.Links
import com.rojea.ytdd.core.Platform
import com.rojea.ytdd.core.Saved
import com.rojea.ytdd.core.Send
import com.rojea.ytdd.download.Downloads
import com.rojea.ytdd.download.Job
import com.rojea.ytdd.download.Notify
import com.rojea.ytdd.download.Stage
import com.rojea.ytdd.ui.LinkTile
import com.rojea.ytdd.ui.OutlinedPill
import com.rojea.ytdd.ui.SendButtons
import com.rojea.ytdd.ui.T
import com.rojea.ytdd.ui.TextAction
import com.rojea.ytdd.ui.Thumb
import com.rojea.ytdd.ui.UiIcon
import com.rojea.ytdd.ui.YtddProgress
import com.rojea.ytdd.ui.YtddTheme
import com.rojea.ytdd.ui.ago
import com.rojea.ytdd.ui.applySystemBars
import com.rojea.ytdd.ui.details
import com.rojea.ytdd.ui.isDarkUi
import com.rojea.ytdd.ui.readClipboard
import com.rojea.ytdd.ui.statusText
import kotlinx.coroutines.launch

/**
 * A bottom sheet over whatever app you're in. Every quick entry point lands here: Share → YTDD,
 * "Download video" in the text-selection menu, launcher shortcuts, the Quick Settings tile, the
 * widget, and the buttons on notifications.
 *
 * Downloads start immediately — no questions. Hide the sheet any time; the download carries on
 * and a notification brings back the WhatsApp/Share buttons when it's done.
 */
class QuickActivity : ComponentActivity() {
    companion object {
        const val ACTION_PASTE_VIDEO = "com.rojea.ytdd.action.PASTE_VIDEO"
        const val ACTION_PASTE_AUDIO = "com.rojea.ytdd.action.PASTE_AUDIO"
        const val ACTION_SHOW = "com.rojea.ytdd.action.SHOW"
        const val ACTION_WHATSAPP = "com.rojea.ytdd.action.WHATSAPP"
        const val ACTION_SHARE = "com.rojea.ytdd.action.SHARE"
        const val ACTION_PLAY = "com.rojea.ytdd.action.PLAY"
        const val ACTION_RETRY = "com.rojea.ytdd.action.RETRY"
        /** With an id: cancel that download. Without: cancel all of them. */
        const val ACTION_CANCEL = "com.rojea.ytdd.action.CANCEL"
        const val EXTRA_ID = "id"
    }

    private sealed interface Sheet {
        data object Waiting : Sheet
        data class Tracking(val jobId: String) : Sheet
        data class Existing(val item: Saved) : Sheet
        data class Problem(val title: String, val message: String) : Sheet
    }

    private val sheet = mutableStateOf<Sheet>(Sheet.Waiting)
    private var pendingPaste: Kind? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBars(dark = true)
        if (!handle(intent)) return
        setContent {
            val dark = isDarkUi()
            LaunchedEffect(dark) { applySystemBars(dark) }
            YtddTheme(dark) { SheetUi() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    // Android only lets an app read the clipboard while one of its windows has focus — so the
    // paste shortcuts wait for focus instead of reading in onCreate. The bottom sheet is a separate
    // window from the activity, so whichever of the two gets focus first does the read.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) readPendingPaste()
    }

    private fun readPendingPaste() {
        val kind = pendingPaste ?: return
        pendingPaste = null
        val url = Links.extract(readClipboard(this))
        if (url == null) {
            sheet.value = Sheet.Problem("Nothing copied", "Copy a link from TikTok, YouTube, Instagram or X, then try again.")
        } else begin(url, kind)
    }

    /** Returns false if the activity already finished (pure trampoline actions). */
    private fun handle(intent: Intent): Boolean {
        val id = intent.getStringExtra(EXTRA_ID)
        val item = id?.let { History.get(it) ?: Downloads.get(it)?.result }
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT) ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
                begin(Links.extract(text), null)
            }
            Intent.ACTION_PROCESS_TEXT -> begin(Links.extract(intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)), null)
            ACTION_PASTE_VIDEO -> paste(Kind.Video)
            ACTION_PASTE_AUDIO -> paste(Kind.Audio)
            ACTION_WHATSAPP, ACTION_SHARE, ACTION_PLAY -> {
                id?.let { Notify.cancel(this, it) }
                if (item != null) when (intent.action) {
                    ACTION_WHATSAPP -> Send.toWhatsApp(this, item)
                    ACTION_SHARE -> Send.share(this, item)
                    else -> Send.play(this, item)
                }
                finish()
                return false
            }
            ACTION_CANCEL -> {
                if (id != null) Downloads.cancel(id) else Downloads.cancelAll()
                finish()
                return false
            }
            ACTION_RETRY -> {
                id?.let { Notify.cancel(this, it) }
                if (id != null && Downloads.get(id) != null) {
                    Downloads.retry(id)
                    sheet.value = Sheet.Tracking(id)
                } else gone()
            }
            ACTION_SHOW -> {
                id?.let { Notify.cancel(this, it) }
                sheet.value = when {
                    id != null && Downloads.get(id) != null -> Sheet.Tracking(id)
                    item != null -> Sheet.Existing(item)
                    else -> gone()
                }
            }
            else -> {
                finish()
                return false
            }
        }
        return true
    }

    private fun gone() = Sheet.Problem("Not available", "This download isn't around any more.").also { sheet.value = it }

    private fun paste(kind: Kind) {
        pendingPaste = kind
        sheet.value = Sheet.Waiting
        if (hasWindowFocus()) readPendingPaste()
    }

    private fun begin(url: String?, requested: Kind?) {
        if (url == null) {
            sheet.value = Sheet.Problem("No link found", "Share the post itself, or copy its link and try again.")
            return
        }
        // Music links default to MP3; everything else to video. One tap switches.
        val kind = requested ?: if (Platform.fromUrl(url) == Platform.SoundCloud || "music.youtube.com" in url) Kind.Audio else Kind.Video
        val existing = History.findExisting(url, kind)
        sheet.value = if (existing != null) Sheet.Existing(existing) else Sheet.Tracking(start(url, kind))
    }

    private fun start(url: String, kind: Kind): String {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        return Downloads.start(url, kind)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SheetUi() {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = rememberCoroutineScope()
        // Slide the sheet away, then end the activity.
        val close: () -> Unit = { scope.launch { state.hide() }.invokeOnCompletion { finish() } }
        val current by sheet
        ModalBottomSheet(
            onDismissRequest = { finish() },
            sheetState = state,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = T.surface1,
            contentColor = T.onSurface,
            tonalElevation = 0.dp,
            scrimColor = Color.Black.copy(alpha = 0.5f),
            dragHandle = {
                Box(Modifier.padding(top = 16.dp, bottom = 2.dp).size(32.dp, 4.dp).clip(CircleShape).background(T.outline))
            },
        ) {
            val sheetFocused = LocalWindowInfo.current.isWindowFocused
            LaunchedEffect(sheetFocused, current) { if (sheetFocused) readPendingPaste() }
            Column(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 24.dp).animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                when (val s = current) {
                    Sheet.Waiting -> Text("Reading your clipboard…", style = MaterialTheme.typography.bodyMedium)
                    is Sheet.Tracking -> TrackingUi(s.jobId, close)
                    is Sheet.Existing -> ExistingUi(s.item, close)
                    is Sheet.Problem -> ProblemUi(s, close)
                }
            }
        }
    }

    @Composable
    private fun Header(thumb: @Composable () -> Unit, title: String, details: String) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            thumb()
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (details.isNotEmpty()) {
                    Text(details, style = MaterialTheme.typography.bodySmall, color = T.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }

    /** A row of actions that starts flush with the sheet's text (text buttons have their own padding). */
    @Composable
    private fun Actions(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) =
        Row(Modifier.fillMaxWidth().offset(x = (-12).dp), verticalAlignment = Alignment.CenterVertically, content = content)

    @Composable
    private fun TrackingUi(jobId: String, close: () -> Unit) {
        val jobs by Downloads.jobs.collectAsState()
        val job = jobs.find { it.id == jobId } ?: return
        when {
            job.stage.active -> {
                Header({ Thumb(job.thumbPath, job.kind, 56.dp) }, job.title ?: "Getting video info…", details(job))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(statusText(job), style = MaterialTheme.typography.bodyMedium)
                    YtddProgress(job.progress)
                    Text("You can hide this. YTDD tells you when it's ready.", style = MaterialTheme.typography.bodySmall, color = T.onSurfaceVariant)
                }
                Actions {
                    val other = if (job.kind == Kind.Video) Kind.Audio else Kind.Video
                    TextAction(
                        if (other == Kind.Audio) "Just the audio" else "Get the video",
                        {
                            Downloads.dismiss(job.id)
                            sheet.value = Sheet.Tracking(Downloads.start(job.url, other))
                        },
                        icon = if (other == Kind.Audio) R.drawable.ic_ui_music else R.drawable.ic_ui_play,
                    )
                    Spacer(Modifier.weight(1f))
                    TextAction("Cancel", { Downloads.dismiss(job.id); close() })
                    Spacer(Modifier.size(8.dp))
                    OutlinedPill("Hide", close, Modifier.offset(x = 12.dp))
                }
            }
            job.stage == Stage.Done && job.result != null -> {
                val item = job.result
                Header({ Thumb(item.thumbPath, item.kind, 56.dp) }, item.title, details(item))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UiIcon(R.drawable.ic_ui_checkc, size = 18.dp)
                    Spacer(Modifier.size(8.dp))
                    Text(job.note ?: "Saved to your gallery", style = MaterialTheme.typography.bodyMedium)
                }
                SendButtons(item, height = 48.dp)
                Actions {
                    TextAction("Play", { Send.play(this@QuickActivity, item) }, icon = R.drawable.ic_ui_play)
                    Spacer(Modifier.weight(1f))
                    TextAction("Done", close, Modifier.offset(x = 24.dp))
                }
            }
            job.stage == Stage.Failed -> FailedUi(job, close)
            else -> LaunchedEffect(Unit) { close() } // canceled
        }
    }

    @Composable
    private fun FailedUi(job: Job, close: () -> Unit) {
        var showDetails by remember { mutableStateOf(false) }
        val e = job.error
        Header(
            { if (job.thumbPath != null) Thumb(job.thumbPath, job.kind, 56.dp) else LinkTile(56) },
            job.title ?: shortUrl(job.url),
            job.platform.label.takeIf { job.platform != Platform.Other }.orEmpty(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            UiIcon(R.drawable.ic_ui_alert, tint = T.error)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(e?.title ?: "Couldn't save this video", style = MaterialTheme.typography.titleMedium)
                Text(e?.message ?: "Try again in a moment.", style = MaterialTheme.typography.bodyMedium, color = T.onSurfaceVariant)
                if (showDetails && e?.detail != null) {
                    Text(e.detail, style = MaterialTheme.typography.bodySmall, color = T.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        Actions {
            if (e?.detail != null) TextAction(if (showDetails) "Hide details" else "Details", { showDetails = !showDetails })
            Spacer(Modifier.weight(1f))
            TextAction("Close", close)
            if (e?.retryable != false) {
                Spacer(Modifier.size(8.dp))
                OutlinedPill("Try again", { Downloads.retry(job.id) }, Modifier.offset(x = 12.dp), icon = R.drawable.ic_ui_redo)
            }
        }
    }

    @Composable
    private fun ExistingUi(item: Saved, close: () -> Unit) {
        Header({ Thumb(item.thumbPath, item.kind, 56.dp) }, item.title, details(item))
        Text("Already in your gallery, saved ${ago(item.createdAt)}.", style = MaterialTheme.typography.bodyMedium)
        SendButtons(item, height = 48.dp)
        Actions {
            TextAction("Play", { Send.play(this@QuickActivity, item) }, icon = R.drawable.ic_ui_play)
            Spacer(Modifier.weight(1f))
            TextAction("Download again", { sheet.value = Sheet.Tracking(start(item.url, item.kind)) })
            TextAction("Done", close, Modifier.offset(x = 24.dp))
        }
    }

    @Composable
    private fun ProblemUi(p: Sheet.Problem, close: () -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(T.surface2), contentAlignment = Alignment.Center) {
                UiIcon(R.drawable.ic_ui_paste, tint = T.onSurfaceVariant)
            }
            Spacer(Modifier.size(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(p.title, style = MaterialTheme.typography.titleMedium)
                Text(p.message, style = MaterialTheme.typography.bodyMedium, color = T.onSurfaceVariant)
            }
        }
        Actions {
            TextAction("Open YTDD", {
                startActivity(Intent(this@QuickActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
            })
            Spacer(Modifier.weight(1f))
            OutlinedPill("OK", close, Modifier.offset(x = 12.dp))
        }
    }

    private fun shortUrl(url: String) =
        url.substringAfter("://").removePrefix("www.").substringBefore('?').trimEnd('/')
}
