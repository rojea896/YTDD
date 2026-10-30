package com.rojea.ytdd.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rojea.ytdd.R
import com.rojea.ytdd.core.Engine
import com.rojea.ytdd.core.History
import com.rojea.ytdd.core.Kind
import com.rojea.ytdd.core.Links
import com.rojea.ytdd.core.Prefs
import com.rojea.ytdd.core.Saved
import com.rojea.ytdd.core.Send
import com.rojea.ytdd.download.Downloads
import com.rojea.ytdd.download.Job
import com.rojea.ytdd.download.Stage
import kotlinx.coroutines.launch

fun readClipboard(context: Context): CharSequence? =
    context.getSystemService(ClipboardManager::class.java).primaryClip
        ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)

@Composable
fun HomeScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val jobs by Downloads.jobs.collectAsState()
    val history by History.items.collectAsState()
    val kind by Prefs.kind.collectAsState()
    val engine by Engine.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var link by rememberSaveable { mutableStateOf("") }
    var typing by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }

    // Running and failed jobs; finished ones are already in History.
    val pending = jobs.filter { it.stage.active || it.stage == Stage.Failed }

    // Open the list when a download finishes while you're watching, so WhatsApp/Share are right there.
    val doneCount = jobs.count { it.stage == Stage.Done }
    var seenDone by remember { mutableIntStateOf(doneCount) }
    LaunchedEffect(doneCount) {
        if (doneCount > seenDone) expanded = true
        seenDone = doneCount
    }

    fun go(url: String) {
        link = ""
        typing = false
        focus.clearFocus()
        val existing = History.findExisting(url, kind)
        if (existing != null) {
            scope.launch {
                val r = snackbar.showSnackbar("You already have this one", "Get it again", withDismissAction = true)
                if (r == SnackbarResult.ActionPerformed) Downloads.start(url, kind)
            }
        } else {
            Downloads.start(url, kind)
        }
    }

    fun pasteAndDownload(text: CharSequence?) {
        val url = Links.extract(text)
        if (url != null) go(url)
        else scope.launch {
            snackbar.showSnackbar(
                if (text.isNullOrBlank()) "Nothing copied. Copy a link from TikTok, YouTube, Instagram or X first."
                else "That doesn't look like a link."
            )
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = T.background) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("YTDD", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onOpenSettings) { UiIcon(R.drawable.ic_ui_gear, "Settings") }
            }

            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { pasteAndDownload(readClipboard(context)) },
                    modifier = Modifier.fillMaxWidth().height(80.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = T.accent, contentColor = T.onAccent),
                ) {
                    UiIcon(R.drawable.ic_ui_paste, size = 28.dp, tint = T.onAccent)
                    Spacer(Modifier.width(14.dp))
                    Text("Paste & download", style = MainButtonText)
                }
                KindSwitch(kind)
                if (typing) {
                    LinkField(
                        link, { link = it },
                        onGo = { pasteAndDownload(link) },
                        onDone = { if (link.isBlank()) typing = false },
                    )
                } else {
                    Row(Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "YouTube, TikTok, Instagram, X and 1,000+ more",
                            style = MaterialTheme.typography.bodySmall, color = T.onSurfaceVariant, modifier = Modifier.weight(1f),
                        )
                        TextAction("Type a link", { typing = true }, icon = R.drawable.ic_ui_kbd)
                    }
                }
                val e = engine
                when (e) {
                    Engine.State.Starting -> Hint("Setting up for the first time. This takes a few seconds.")
                    is Engine.State.Failed -> Hint("The downloader couldn't start: ${e.message}", T.error)
                    is Engine.State.Ready -> Unit
                }
            }

            if (history.isEmpty() && pending.isEmpty()) {
                EmptyState(Modifier.weight(1f))
            } else {
                // Opening unrolls the list down from the Downloads card; closing rolls it back up.
                // The list is laid out at full height and revealed top-down, so nothing reflows.
                val open by animateFloatAsState(
                    targetValue = if (expanded) 1f else 0f,
                    animationSpec = tween(if (expanded) 420 else 320, easing = Emphasized),
                    label = "downloads",
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (open < 1f) {
                        Column(Modifier.graphicsLayer { alpha = (1f - open * 2.5f).coerceIn(0f, 1f) }) {
                            FoldCard(pending, history) { expanded = true }
                            SupportHint(onOpenSettings)
                        }
                    }
                    // Always composed (0 high when closed), so opening only changes a height —
                    // no list to build or thumbnails to decode on the first frame of the animation.
                    run {
                        Column(
                            Modifier.clipToBounds()
                                .layout { measurable, constraints ->
                                    val p = measurable.measure(constraints)
                                    layout(p.width, (p.height * open).roundToInt()) { p.place(0, 0) }
                                }
                                .fillMaxSize()
                                .graphicsLayer { alpha = (open * 2f).coerceAtMost(1f) },
                        ) {
                            Row(
                                Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp).fillMaxWidth().height(48.dp)
                                    .clip(CircleShape).clickable { expanded = false },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Downloads", style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.width(8.dp))
                                Text("${history.size} saved", style = MaterialTheme.typography.bodySmall, color = T.onSurfaceVariant, modifier = Modifier.weight(1f))
                                Chevron(R.drawable.ic_ui_up, "Hide downloads", Modifier.graphicsLayer { rotationZ = (1f - open) * 180f })
                            }
                            LazyColumn(
                                Modifier.weight(1f),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                // animateItem: rows slide when a download is added, finishes or is removed.
                                items(pending, key = { "job-" + it.id }) { JobCard(it, Modifier.animateItem()) }
                                items(history, key = { "saved-" + it.id }) { DownloadCard(it, Modifier.animateItem()) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

@Composable
private fun Hint(text: String, color: Color = T.onSurfaceVariant) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)

@Composable
private fun KindSwitch(kind: Kind) {
    val colors = SegmentedButtonDefaults.colors(
        activeContainerColor = T.selected, activeContentColor = T.onSurface, activeBorderColor = T.outline,
        inactiveContainerColor = Color.Transparent, inactiveContentColor = T.onSurface, inactiveBorderColor = T.outline,
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        listOf(Kind.Video to "Video", Kind.Audio to "Audio (MP3)").forEachIndexed { i, (k, label) ->
            val selected = kind == k
            SegmentedButton(
                selected = selected,
                onClick = { Prefs.setKind(k) },
                shape = SegmentedButtonDefaults.itemShape(i, 2),
                colors = colors,
                icon = {
                    UiIcon(
                        when {
                            selected -> R.drawable.ic_ui_check
                            k == Kind.Audio -> R.drawable.ic_ui_music
                            else -> R.drawable.ic_ui_play
                        },
                        size = 18.dp,
                    )
                },
            ) { Text(label, style = MaterialTheme.typography.labelLarge) }
        }
    }
}

@Composable
private fun LinkField(value: String, onChange: (String) -> Unit, onGo: () -> Unit, onDone: () -> Unit) {
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { requester.requestFocus() }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().focusRequester(requester).onFocusChanged {
            if (focused && !it.isFocused) onDone()
            focused = it.isFocused
        },
        label = { Text("Link") },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        textStyle = MaterialTheme.typography.bodyLarge,
        trailingIcon = { IconButton(onClick = onGo) { UiIcon(R.drawable.ic_ui_tray, "Download this link") } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = T.onSurface, unfocusedBorderColor = T.outline,
            focusedLabelColor = T.onSurfaceVariant, unfocusedLabelColor = T.onSurfaceVariant, cursorColor = T.onSurface,
        ),
    )
}

@Composable
private fun Chevron(icon: Int, description: String, modifier: Modifier = Modifier) {
    Box(modifier.size(40.dp).clip(CircleShape).background(T.surface3), contentAlignment = Alignment.Center) {
        UiIcon(icon, description, size = 20.dp)
    }
}

/** The collapsed downloads section: one card that summarises everything. */
@Composable
private fun FoldCard(pending: List<Job>, history: List<Saved>, onOpen: () -> Unit) {
    val running = pending.firstOrNull { it.stage.active }
    val failed = pending.count { it.stage == Stage.Failed }
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth().heightIn(min = 64.dp)
            .clip(RoundedCornerShape(20.dp)).background(T.surface1).clickable(onClick = onOpen)
            .padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)
            .animateContentSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (running != null) {
            Thumb(running.thumbPath, running.kind, 40.dp, corner = 10.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(statusText(running), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                YtddProgress(running.progress)
                val others = pending.count { it.stage.active } - 1
                val line = listOfNotNull(
                    "+ $others more downloading".takeIf { others > 0 },
                    "+ ${history.size} saved".takeIf { history.isNotEmpty() },
                ).joinToString(" · ")
                if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodySmall, color = T.onSurfaceVariant)
            }
        } else {
            ThumbStack(history.take(3))
            Column(Modifier.weight(1f)) {
                Text("Downloads", style = MaterialTheme.typography.titleSmall)
                val line = listOfNotNull(
                    "${history.size} saved".takeIf { history.isNotEmpty() },
                    history.firstOrNull()?.let { "newest " + ago(it.createdAt) },
                    "$failed couldn't be saved".takeIf { failed > 0 },
                ).joinToString(" · ")
                Text(line, style = MaterialTheme.typography.bodySmall, color = T.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Chevron(R.drawable.ic_ui_down, "Show downloads")
    }
}

/** A quiet nudge under the folded list; the actual link lives in Settings. */
@Composable
private fun SupportHint(onOpenSettings: () -> Unit) {
    Row(
        Modifier.padding(top = 12.dp).fillMaxWidth().clickable(onClick = onOpenSettings).padding(vertical = 8.dp)
            .graphicsLayer { alpha = 0.6f },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UiIcon(R.drawable.ic_ui_coffee, size = 16.dp, tint = T.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text("Enjoying YTDD? You can support it in Settings.", style = MaterialTheme.typography.bodySmall, color = T.onSurfaceVariant)
    }
}

@Composable
private fun ThumbStack(items: List<Saved>) {
    if (items.isEmpty()) return
    Box(Modifier.width((32 + 22 * (items.size - 1)).dp).height(32.dp)) {
        items.forEachIndexed { i, s ->
            Box(
                Modifier.offset(x = (22 * i).dp).size(32.dp).clip(RoundedCornerShape(9.dp))
                    .border(2.dp, T.surface1, RoundedCornerShape(9.dp))
            ) { Thumb(s.thumbPath, s.kind, 32.dp, corner = 9.dp) }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, bottom = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(T.surface2), contentAlignment = Alignment.Center) {
            UiIcon(R.drawable.ic_ui_tray, tint = T.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        Text("No downloads yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            "In any app, tap Share, then YTDD. Your videos land here and in your gallery.",
            style = MaterialTheme.typography.bodyMedium, color = T.onSurfaceVariant, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CardFrame(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(T.surface1)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(12.dp).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun CardHeader(thumb: @Composable () -> Unit, title: String, details: String, trailing: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        thumb()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(details, style = MaterialTheme.typography.bodySmall, color = T.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.offset(x = 4.dp, y = (-8).dp)) { trailing() }
    }
}

@Composable
private fun DownloadCard(item: Saved, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    CardFrame(modifier, onClick = { Send.play(context, item) }) {
        CardHeader({ Thumb(item.thumbPath, item.kind, 64.dp) }, item.title, details(item)) {
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) { UiIcon(R.drawable.ic_ui_more, "More", 20.dp) }
                DropdownMenu(
                    expanded = menu, onDismissRequest = { menu = false },
                    shape = RoundedCornerShape(16.dp), containerColor = T.surface3,
                    tonalElevation = 0.dp, shadowElevation = 0.dp, border = BorderStroke(1.dp, T.outlineVariant),
                    modifier = Modifier.width(216.dp),
                ) {
                    MenuItem("Play", R.drawable.ic_ui_play) { menu = false; Send.play(context, item) }
                    MenuItem("Copy link", R.drawable.ic_ui_link) { menu = false; Send.copyLink(context, item) }
                    MenuItem("Remove from list", R.drawable.ic_ui_x) { menu = false; History.remove(item, deleteFile = false) }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = T.outlineVariant)
                    MenuItem("Delete from phone", R.drawable.ic_ui_trash) { menu = false; History.remove(item, deleteFile = true) }
                }
            }
        }
        SendButtons(item)
    }
}

@Composable
private fun MenuItem(text: String, icon: Int, onClick: () -> Unit) = DropdownMenuItem(
    text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
    leadingIcon = { UiIcon(icon) },
    onClick = onClick,
    modifier = Modifier.height(48.dp),
)

@Composable
private fun JobCard(job: Job, modifier: Modifier = Modifier) {
    CardFrame(modifier) {
        CardHeader(
            thumb = {
                if (job.stage == Stage.Failed && job.thumbPath == null) LinkTile(64)
                else Thumb(job.thumbPath, job.kind, 64.dp)
            },
            title = job.title ?: if (job.stage.active) "Getting video info…" else job.url,
            details = details(job),
        ) {
            IconButton(onClick = { Downloads.dismiss(job.id) }, modifier = Modifier.size(40.dp)) {
                UiIcon(R.drawable.ic_ui_x, if (job.stage.active) "Cancel" else "Dismiss", 20.dp)
            }
        }
        if (job.stage.active) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(statusText(job), style = MaterialTheme.typography.bodyMedium)
                YtddProgress(job.progress)
            }
        } else {
            val e = job.error
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                UiIcon(R.drawable.ic_ui_alert, tint = T.error)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(e?.title ?: "Couldn't save this video", style = MaterialTheme.typography.titleSmall)
                    e?.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = T.onSurfaceVariant) }
                }
            }
            if (e?.retryable != false) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedPill("Try again", { Downloads.retry(job.id) }, icon = R.drawable.ic_ui_redo)
                }
            }
        }
    }
}

@Composable
fun LinkTile(size: Int) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(12.dp)).background(T.surface2), contentAlignment = Alignment.Center) {
        UiIcon(R.drawable.ic_ui_link, tint = T.onSurfaceVariant)
    }
}
