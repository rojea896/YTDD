package com.rojea.ytdd.download

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.rojea.ytdd.core.Kind
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Finished files go into the shared gallery (Movies/YTDD, Music/YTDD) rather than app storage:
 * WhatsApp's Status picker and every other app's attach button read from there.
 */
object Gallery {
    fun publish(context: Context, file: File, kind: Kind, displayName: String, mime: String): Uri {
        val resolver = context.contentResolver
        val (collection, dir) = when (kind) {
            Kind.Video -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                "${Environment.DIRECTORY_MOVIES}/YTDD"
            Kind.Audio -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                "${Environment.DIRECTORY_MUSIC}/YTDD"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("Couldn't create a file in the gallery")
        try {
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out, 1 shl 16) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    /** The name MediaStore actually gave the file (it appends " (1)" on clashes). */
    fun nameOf(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }

    /** Save a small JPEG copy of the thumbnail for the list, notification and widget. */
    fun saveThumbnail(context: Context, url: String?, id: String): String? {
        if (url.isNullOrBlank()) return null
        return runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) YTDD")
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            val bytes = conn.inputStream.use { it.readBytes() }
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            var sample = 1
            while (opts.outWidth / (sample * 2) >= 480) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val dir = File(context.filesDir, "thumbs").apply { mkdirs() }
            val out = File(dir, "$id.jpg")
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            bmp.recycle()
            out.path
        }.onFailure { android.util.Log.w("Gallery", "thumbnail $url: $it") }.getOrNull()
    }
}
