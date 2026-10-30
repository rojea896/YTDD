package com.rojea.ytdd.download

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.rojea.ytdd.MainActivity
import com.rojea.ytdd.QuickActivity
import com.rojea.ytdd.R
import com.rojea.ytdd.core.Kind
import com.rojea.ytdd.core.Send
import com.rojea.ytdd.ui.statusText

object Notify {
    const val FOREGROUND_ID = 1
    private const val CH_PROGRESS = "progress"
    private const val CH_DONE = "done"
    private const val BRAND = 0xFFFF5A5A.toInt()

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "Downloading", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress while a download is running"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Ready to send, with WhatsApp and Share buttons"
            }
        )
    }

    private fun quick(context: Context, action: String, id: String?, code: Int): PendingIntent =
        PendingIntent.getActivity(
            context, code,
            Intent(context, QuickActivity::class.java).setAction(action).putExtra(QuickActivity.EXTRA_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    // The progress notification is rebuilt every ~0.7s; don't decode the thumbnail each time.
    private var thumbCache: Pair<String, android.graphics.Bitmap?>? = null
    private fun thumb(path: String?): android.graphics.Bitmap? {
        if (path == null) return null
        thumbCache?.let { (p, b) -> if (p == path) return b }
        return BitmapFactory.decodeFile(path).also { thumbCache = path to it }
    }

    fun foreground(context: Context, jobs: List<Job>): Notification {
        val active = jobs.filter { it.stage.active }
        val first = active.firstOrNull()
        val known = active.mapNotNull { it.progress }
        val b = NotificationCompat.Builder(context, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setColor(BRAND)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
                )
            )
        if (active.size > 1) {
            // Several at once: combined progress, and each title with its own percentage.
            val pct = if (known.isEmpty()) null else (known.sum() / active.size * 100).toInt()
            val eta = active.mapNotNull { it.etaSec }.maxOrNull()
            b.setContentTitle("Downloading ${active.size} videos")
                .setContentText(listOfNotNull(pct?.let { "$it%" }, eta?.let { aboutLeft(it) }).joinToString(" · ").ifEmpty { "Starting…" })
                .setStyle(NotificationCompat.BigTextStyle().bigText(active.joinToString("\n") { j ->
                    (j.title ?: "Getting video info…").take(48) + " — " + (j.progress?.let { "${(it * 100).toInt()}%" } ?: j.stage.label)
                }))
                .addAction(0, "Cancel all", quick(context, QuickActivity.ACTION_CANCEL, null, 9))
        } else if (first != null) {
            b.setContentTitle(first.title ?: "Getting video info…")
                .setContentText(statusText(first))
                .setLargeIcon(thumb(first.thumbPath))
                .addAction(0, "Cancel", quick(context, QuickActivity.ACTION_CANCEL, first.id, first.id.hashCode() + 4))
        } else {
            b.setContentTitle("Downloading").setContentText("Starting…")
        }
        if (known.isEmpty()) b.setProgress(0, 0, true)
        else b.setProgress(1000, (known.sum() / active.size.coerceAtLeast(1) * 1000).toInt(), false)
        return b.build()
    }

    private fun aboutLeft(sec: Long) = when {
        sec < 60 -> "${sec}s left"
        sec < 3600 -> "about ${(sec + 30) / 60} min left"
        else -> "about ${sec / 3600} h left"
    }

    fun done(context: Context, job: Job) {
        val item = job.result ?: return
        val code = job.id.hashCode()
        val b = NotificationCompat.Builder(context, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setColor(BRAND)
            .setContentTitle(item.title)
            .setContentText(job.note ?: if (item.kind == Kind.Audio) "MP3 saved" else "Video saved — ready to send")
            .setAutoCancel(true)
            .setContentIntent(quick(context, QuickActivity.ACTION_SHOW, job.id, code))
        job.thumbPath?.let { p -> BitmapFactory.decodeFile(p)?.let { b.setLargeIcon(it) } }
        if (Send.whatsapp(context) != null) {
            b.addAction(0, "WhatsApp", quick(context, QuickActivity.ACTION_WHATSAPP, job.id, code + 1))
        }
        b.addAction(0, "Share", quick(context, QuickActivity.ACTION_SHARE, job.id, code + 2))
        b.addAction(0, "Play", quick(context, QuickActivity.ACTION_PLAY, job.id, code + 3))
        post(context, code, b.build())
    }

    fun failed(context: Context, job: Job) {
        val code = job.id.hashCode()
        val e = job.error
        val b = NotificationCompat.Builder(context, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setColor(BRAND)
            .setContentTitle("Couldn't save this video")
            .setContentText(e?.title ?: "Something went wrong")
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(job.title, e?.title, e?.message).joinToString("\n")))
            .setLargeIcon(job.thumbPath?.let { BitmapFactory.decodeFile(it) })
            .setAutoCancel(true)
            .setContentIntent(quick(context, QuickActivity.ACTION_SHOW, job.id, code))
        if (e?.retryable != false) {
            b.addAction(0, "Try again", quick(context, QuickActivity.ACTION_RETRY, job.id, code + 1))
        }
        post(context, code, b.build())
    }

    fun cancel(context: Context, id: String) = NotificationManagerCompat.from(context).cancel(id.hashCode())

    private fun post(context: Context, id: Int, n: Notification) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) NotificationManagerCompat.from(context).notify(id, n)
    }
}
