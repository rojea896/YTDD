package com.rojea.ytdd.download

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * "Plays everywhere" = H.264 video + AAC audio in an MP4. That's what WhatsApp, iMessage,
 * Telegram, Instagram, old Windows PCs and car stereos all agree on. HEVC, VP9, AV1 and Opus each
 * fail somewhere (the desktop app found Opus-in-MP4 silently plays with no sound in Premiere).
 */
object Compat {
    data class Probe(
        val videoMime: String?,
        val audioMime: String?,
        val width: Int?,
        val height: Int?,
        val durationSec: Double?,
    )

    fun probe(file: File): Probe {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.path)
            var v: MediaFormat? = null
            var a: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && v == null) v = f
                if (mime.startsWith("audio/") && a == null) a = f
            }
            val dur = (v ?: a)?.takeIf { it.containsKey(MediaFormat.KEY_DURATION) }
                ?.getLong(MediaFormat.KEY_DURATION)?.let { it / 1_000_000.0 }
            return Probe(
                videoMime = v?.getString(MediaFormat.KEY_MIME),
                audioMime = a?.getString(MediaFormat.KEY_MIME),
                width = v?.getInteger(MediaFormat.KEY_WIDTH),
                height = v?.getInteger(MediaFormat.KEY_HEIGHT),
                durationSec = dur,
            )
        } finally {
            ex.release()
        }
    }

    fun isUniversalVideo(file: File, p: Probe): Boolean =
        file.extension.equals("mp4", true) &&
            p.videoMime == MimeTypes.VIDEO_H264 &&
            (p.audioMime == null || p.audioMime == MimeTypes.AUDIO_AAC)

    /**
     * Re-encode to H.264/AAC MP4 with the phone's hardware encoder (Media3 Transformer). Streams
     * that are already H.264 or AAC are copied, not re-encoded.
     */
    suspend fun toUniversalMp4(
        context: Context,
        input: File,
        output: File,
        onProgress: (Float) -> Unit,
    ) {
        // Transformer must be created and driven from a Looper thread.
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val handler = Handler(Looper.getMainLooper())
                val holder = ProgressHolder()
                lateinit var transformer: Transformer
                val poll = object : Runnable {
                    override fun run() {
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress(holder.progress / 100f)
                        }
                        handler.postDelayed(this, 400)
                    }
                }
                transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            handler.removeCallbacks(poll)
                            cont.resume(Unit)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            handler.removeCallbacks(poll)
                            output.delete()
                            cont.resumeWithException(exportException)
                        }
                    })
                    .build()
                cont.invokeOnCancellation {
                    handler.post {
                        handler.removeCallbacks(poll)
                        transformer.cancel()
                        output.delete()
                    }
                }
                transformer.start(MediaItem.fromUri(Uri.fromFile(input)), output.path)
                handler.post(poll)
            }
        }
    }
}
