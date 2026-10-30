package com.rojea.ytdd.ui

import android.text.format.DateUtils
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.rojea.ytdd.R
import com.rojea.ytdd.core.Kind
import com.rojea.ytdd.core.Platform
import com.rojea.ytdd.core.Saved
import com.rojea.ytdd.core.Send
import com.rojea.ytdd.download.Job
import com.rojea.ytdd.download.Stage
import java.io.File
import java.util.Locale

/** One of the One UI-style vector icons (res/drawable/ic_ui_*). */
@Composable
fun UiIcon(@DrawableRes id: Int, contentDescription: String? = null, size: Dp = 24.dp, tint: Color = T.onSurface, modifier: Modifier = Modifier) =
    Icon(painterResource(id), contentDescription, modifier.size(size), tint = tint)

/** 40dp pill with a 1dp outline — the design's button for important secondary actions. */
@Composable
fun OutlinedPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    iconTint: Color = T.onSurface,
    height: Dp = 40.dp,
) = OutlinedButton(
    onClick = onClick,
    modifier = modifier.height(height),
    shape = CircleShape,
    border = BorderStroke(1.dp, T.outline),
    colors = ButtonDefaults.outlinedButtonColors(contentColor = T.onSurface),
    contentPadding = PaddingValues(horizontal = 20.dp),
) { PillContent(text, icon, iconTint) }

/** Text-only action (Cancel, Done, Play…). */
@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null) =
    TextButton(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = T.onSurface),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) { PillContent(text, icon, T.onSurface) }

@Composable
private fun RowScope.PillContent(text: String, @DrawableRes icon: Int?, iconTint: Color) {
    if (icon != null) {
        UiIcon(icon, size = 18.dp, tint = iconTint)
        Spacer(Modifier.width(8.dp))
    }
    Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Coral bar with a 4dp gap before the track, as drawn in the design. Null = indeterminate. */
@Composable
fun YtddProgress(progress: Float?, modifier: Modifier = Modifier) {
    val m = modifier.fillMaxWidth().height(4.dp)
    if (progress == null) {
        LinearProgressIndicator(m, color = T.accent, trackColor = T.progressTrack, strokeCap = StrokeCap.Round, gapSize = 4.dp)
    } else {
        LinearProgressIndicator(
            progress = { progress }, modifier = m, color = T.accent, trackColor = T.progressTrack,
            strokeCap = StrokeCap.Round, gapSize = 4.dp, drawStopIndicator = {},
        )
    }
}

@Composable
fun Thumb(path: String?, kind: Kind, size: Dp, corner: Dp = 12.dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(corner)).background(T.surface2),
        contentAlignment = Alignment.Center,
    ) {
        UiIcon(if (kind == Kind.Audio) R.drawable.ic_ui_music else R.drawable.ic_ui_play, size = 20.dp, tint = T.onSurfaceVariant)
        if (path != null) {
            AsyncImage(File(path), null, Modifier.size(size), contentScale = ContentScale.Crop)
        }
    }
}

/** WhatsApp (status or chat) and Share, side by side. WhatsApp only when it's installed. */
@Composable
fun SendButtons(item: Saved, modifier: Modifier = Modifier, height: Dp = 40.dp) {
    val context = LocalContext.current
    val hasWhatsApp = remember { Send.whatsapp(context) != null }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (hasWhatsApp) {
            OutlinedPill("WhatsApp", { Send.toWhatsApp(context, item) }, Modifier.weight(1f), R.drawable.ic_ui_wa, T.whatsapp, height)
        }
        OutlinedPill("Share", { Send.share(context, item) }, Modifier.weight(1f), R.drawable.ic_ui_share, height = height)
    }
}

fun statusText(job: Job): String {
    job.note?.let { if (job.stage.active) return it }
    return when (job.stage) {
        Stage.Downloading -> buildString {
            append("Downloading")
            job.progress?.let { append(" · ${(it * 100).toInt()}%") }
            job.etaSec?.let { if (it in 1..86_400) append(" · ${fmtDuration(it.toDouble())} left") }
        }
        Stage.Converting -> "Making it play everywhere" + (job.progress?.let { " · ${(it * 100).toInt()}%" } ?: "")
        else -> job.stage.label
    }
}

fun fmtDuration(sec: Double): String {
    val s = sec.toLong()
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
    else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

fun fmtSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 100L shl 20 -> "${bytes shr 20} MB"
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble()).replace(".0 MB", " MB")
    else -> "${(bytes / 1024).coerceAtLeast(1)} KB"
}

fun ago(time: Long): String =
    if (System.currentTimeMillis() - time < 60_000) "just now"
    else DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
        .replace(" minutes", " min").replace(" minute", " min")

private fun siteLabel(p: Platform) = p.label.takeIf { p != Platform.Other }?.removeSuffix(" / Twitter")

/** "TikTok · 0:10 · 5.0 MB" */
fun details(item: Saved): String = listOfNotNull(
    siteLabel(item.platform),
    item.durationSec?.let { fmtDuration(it) },
    if (item.kind == Kind.Audio) "MP3" else null,
    fmtSize(item.sizeBytes),
).joinToString(" · ")

/** "TikTok · 0:10" while it's still downloading. */
fun details(job: Job): String = listOfNotNull(
    siteLabel(job.platform) ?: android.net.Uri.parse(job.url).host?.removePrefix("www."),
    job.durationSec?.let { fmtDuration(it) },
).joinToString(" · ")
