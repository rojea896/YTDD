package com.rojea.ytdd.shortcuts

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider as Solid
import com.rojea.ytdd.QuickActivity
import com.rojea.ytdd.R
import com.rojea.ytdd.core.Appearance
import com.rojea.ytdd.core.History
import com.rojea.ytdd.core.Kind
import com.rojea.ytdd.core.Prefs
import com.rojea.ytdd.core.Saved
import com.rojea.ytdd.core.Send
import com.rojea.ytdd.download.Downloads
import com.rojea.ytdd.download.Job
import com.rojea.ytdd.ui.details

class YtddWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = YtddWidget()
}

class YtddWidget : GlanceAppWidget() {
    companion object {
        private val SMALL = DpSize(110.dp, 50.dp)     // 2×1: Paste link
        private val MEDIUM = DpSize(250.dp, 50.dp)    // 4×1: + MP3
        private val LARGE_2 = DpSize(250.dp, 180.dp)  // 4×2: + 2 downloads
        private val LARGE_3 = DpSize(250.dp, 250.dp)  // 4×3: + 3 downloads

        suspend fun refresh(context: Context) = runCatching { YtddWidget().updateAll(context) }
    }

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM, LARGE_2, LARGE_3))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val whatsapp = Send.whatsapp(context) != null
        provideContent {
            // Collected here, not read once above: a Glance session lives on between updates, so
            // values captured outside the composition would go stale.
            val history by History.items.collectAsState()
            val jobs by Downloads.jobs.collectAsState()
            val appearance by Prefs.appearance.collectAsState()
            val items = history.take(3)
            val running = jobs.firstOrNull { it.stage.active }
            val paths = (items.map { it.thumbPath } + running?.thumbPath).filterNotNull()
            val thumbs = remember(paths) { paths.associateWith { decodeSmall(it) } }
            WidgetBody(context, items, running, thumbs, whatsapp, appearance)
        }
    }
}

private fun decodeSmall(path: String): Bitmap? = runCatching {
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 3 })
}.getOrNull()

/** Widget colour tokens from the redesign; follows the app's Appearance setting. */
private class W(private val appearance: Appearance) {
    private fun pick(day: Long, night: Long) = when (appearance) {
        Appearance.Light -> ColorProvider(day = Color(day), night = Color(day))
        Appearance.Dark -> ColorProvider(day = Color(night), night = Color(night))
        Appearance.System -> ColorProvider(day = Color(day), night = Color(night))
    }
    val body = pick(0xFFFBF8F7, 0xFF1E1C1C)
    val on = pick(0xFF1D1B1B, 0xFFECE6E5)
    val onVariant = pick(0xFF5C5453, 0xFFB3ABA9)
    val track = pick(0xFFF6D3D1, 0xFF4A2A29)
    val tonal = pick(0xFFEFEAE9, 0xFF2A2727)
    val accent = Solid(Color(0xFFFF5A5A))
    val onAccent = Solid(Color(0xFF2A0505))
    val whatsapp = Solid(Color(0xFF1DAA61))
    // Glance has no border modifier, so the outlined MP3 button is a shape drawable.
    val mp3Background = when (appearance) {
        Appearance.Light -> R.drawable.bg_widget_mp3_light
        else -> R.drawable.bg_widget_mp3_dark
    }
}

private fun quick(context: Context, action: String, id: String? = null): Action =
    actionStartActivity(
        Intent(context, QuickActivity::class.java).setAction(action).apply {
            // Distinct data per button, so each gets its own PendingIntent.
            data = Uri.parse("ytdd://widget/$action/${id ?: ""}")
            id?.let { putExtra(QuickActivity.EXTRA_ID, it) }
        }
    )

@Composable
private fun WidgetBody(
    context: Context,
    items: List<Saved>,
    running: Job?,
    thumbs: Map<String, Bitmap?>,
    whatsapp: Boolean,
    appearance: Appearance,
) {
    val w = W(appearance)
    val size = LocalSize.current
    val small = size.width < 200.dp
    val rows = when {
        small || size.height < 120.dp -> 0
        size.height < 230.dp -> 2
        else -> 3
    }

    Column(GlanceModifier.fillMaxSize().background(w.body).cornerRadius(24.dp).padding(12.dp)) {
        Row(
            if (rows == 0) GlanceModifier.fillMaxSize() else GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                GlanceModifier.defaultWeight().then(if (rows == 0) GlanceModifier.fillMaxSize() else GlanceModifier.height(48.dp))
                    .background(w.accent).cornerRadius(24.dp)
                    .clickable(quick(context, QuickActivity.ACTION_PASTE_VIDEO)).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(ImageProvider(R.drawable.ic_ui_paste), null, GlanceModifier.size(24.dp), colorFilter = ColorFilter.tint(w.onAccent))
                Spacer(GlanceModifier.width(10.dp))
                Text(
                    if (small) "Paste link" else "Paste link & download",
                    style = TextStyle(color = w.onAccent, fontSize = 15.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )
            }
            if (!small) {
                Spacer(GlanceModifier.width(8.dp))
                Box(
                    GlanceModifier.size(48.dp).background(ImageProvider(w.mp3Background))
                        .clickable(quick(context, QuickActivity.ACTION_PASTE_AUDIO)),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(ImageProvider(R.drawable.ic_ui_music), "Get MP3", GlanceModifier.size(20.dp), colorFilter = ColorFilter.tint(w.on))
                }
            }
        }

        if (rows > 0) {
            Spacer(GlanceModifier.height(4.dp))
            if (items.isEmpty() && running == null) {
                Box(GlanceModifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Copy a link, then tap the button. Your last 3 downloads show up here.",
                        style = TextStyle(color = w.onVariant, fontSize = 14.sp, textAlign = TextAlign.Center),
                    )
                }
            } else {
                running?.let { DownloadingRow(context, it, thumbs, w) }
                items.take(if (running != null) rows - 1 else rows).forEach { SavedRow(context, it, thumbs, whatsapp, w) }
            }
        }
    }
}

@Composable
private fun RowThumb(path: String?, kind: Kind, thumbs: Map<String, Bitmap?>, w: W) {
    val bmp = path?.let { thumbs[it] }
    if (bmp != null) {
        Image(ImageProvider(bmp), null, GlanceModifier.size(40.dp).cornerRadius(10.dp), contentScale = ContentScale.Crop)
    } else {
        Box(GlanceModifier.size(40.dp).background(w.tonal).cornerRadius(10.dp), contentAlignment = Alignment.Center) {
            Image(
                ImageProvider(if (kind == Kind.Audio) R.drawable.ic_ui_music else R.drawable.ic_ui_play), null,
                GlanceModifier.size(18.dp), colorFilter = ColorFilter.tint(w.onVariant),
            )
        }
    }
}

@Composable
private fun IconAction(icon: Int, description: String, tint: androidx.glance.unit.ColorProvider, action: Action) =
    Box(GlanceModifier.size(48.dp).cornerRadius(24.dp).clickable(action), contentAlignment = Alignment.Center) {
        Image(ImageProvider(icon), description, GlanceModifier.size(20.dp), colorFilter = ColorFilter.tint(tint))
    }

@Composable
private fun SavedRow(context: Context, item: Saved, thumbs: Map<String, Bitmap?>, whatsapp: Boolean, w: W) {
    Row(
        GlanceModifier.fillMaxWidth().height(56.dp).padding(start = 4.dp)
            .clickable(quick(context, QuickActivity.ACTION_SHOW, item.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowThumb(item.thumbPath, item.kind, thumbs, w)
        Spacer(GlanceModifier.width(12.dp))
        Column(GlanceModifier.defaultWeight()) {
            Text(item.title, maxLines = 1, style = TextStyle(color = w.on, fontSize = 14.sp, fontWeight = FontWeight.Medium))
            Text(details(item), maxLines = 1, style = TextStyle(color = w.onVariant, fontSize = 12.sp))
        }
        if (whatsapp) IconAction(R.drawable.ic_ui_wa, "Send to WhatsApp", w.whatsapp, quick(context, QuickActivity.ACTION_WHATSAPP, item.id))
        IconAction(R.drawable.ic_ui_share, "Share", w.on, quick(context, QuickActivity.ACTION_SHARE, item.id))
    }
}

@Composable
private fun DownloadingRow(context: Context, job: Job, thumbs: Map<String, Bitmap?>, w: W) {
    Row(
        GlanceModifier.fillMaxWidth().height(56.dp).padding(start = 4.dp)
            .clickable(quick(context, QuickActivity.ACTION_SHOW, job.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowThumb(job.thumbPath, job.kind, thumbs, w)
        Spacer(GlanceModifier.width(12.dp))
        Column(GlanceModifier.defaultWeight()) {
            Text(job.title ?: "Getting video info…", maxLines = 1, style = TextStyle(color = w.on, fontSize = 14.sp, fontWeight = FontWeight.Medium))
            Spacer(GlanceModifier.height(4.dp))
            val p = job.progress
            if (p != null) LinearProgressIndicator(p, GlanceModifier.fillMaxWidth().height(4.dp), w.accent, w.track)
            else LinearProgressIndicator(GlanceModifier.fillMaxWidth().height(4.dp), w.accent, w.track)
            Spacer(GlanceModifier.height(4.dp))
            Text(
                "Downloading" + (p?.let { " · ${(it * 100).toInt()}%" } ?: ""),
                maxLines = 1, style = TextStyle(color = w.onVariant, fontSize = 12.sp),
            )
        }
        IconAction(R.drawable.ic_ui_x, "Cancel", w.on, quick(context, QuickActivity.ACTION_CANCEL, job.id))
    }
}
