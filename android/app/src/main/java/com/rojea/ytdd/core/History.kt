package com.rojea.ytdd.core

import android.content.Context
import android.net.Uri
import com.rojea.ytdd.shortcuts.YtddWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class Saved(
    val id: String,
    val url: String,
    val title: String,
    val platform: Platform,
    val kind: Kind,
    /** content:// Uri in MediaStore — the file lives in the gallery, not in app storage. */
    val uri: String,
    val fileName: String,
    val mime: String,
    val sizeBytes: Long,
    val durationSec: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
    val createdAt: Long,
    /** Small local copy of the thumbnail, so the list and widget work offline. */
    val thumbPath: String? = null,
) {
    val contentUri: Uri get() = Uri.parse(uri)
}

/** Finished downloads, newest first. Kept as one small JSON file. */
object History {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var file: File
    private lateinit var appContext: Context

    private val _items = MutableStateFlow<List<Saved>>(emptyList())
    val items: StateFlow<List<Saved>> = _items.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        file = File(appContext.filesDir, "history.json")
        _items.value = read(appContext)
    }

    /** Also used by the widget, which can run in a fresh process before init(). */
    fun read(context: Context): List<Saved> = runCatching {
        json.decodeFromString<List<Saved>>(File(context.filesDir, "history.json").readText())
    }.getOrDefault(emptyList())

    fun get(id: String): Saved? = _items.value.find { it.id == id }

    /** The most recent download of this exact link, if its file is still in the gallery. */
    fun findExisting(url: String, kind: Kind): Saved? =
        _items.value.firstOrNull { it.url == url && it.kind == kind }?.takeIf { exists(it) }

    /** Newest first; a re-download of the same link replaces the older entry. */
    fun add(item: Saved) = change {
        listOf(item) + it.filterNot { o -> o.id == item.id || (o.url == item.url && o.kind == item.kind) }
    }

    /** Forget an entry. With [deleteFile] it also removes the video from the gallery. */
    fun remove(item: Saved, deleteFile: Boolean) {
        if (deleteFile) runCatching { appContext.contentResolver.delete(item.contentUri, null, null) }
        item.thumbPath?.let { File(it).delete() }
        change { list -> list.filterNot { it.id == item.id } }
    }

    fun clear() {
        _items.value.forEach { s -> s.thumbPath?.let { File(it).delete() } }
        change { emptyList() }
    }

    /** Drop entries whose file was deleted from the gallery (by the user, in another app). */
    fun prune() = scope.launch {
        val gone = _items.value.filterNot { exists(it) }
        if (gone.isNotEmpty()) {
            gone.forEach { g -> g.thumbPath?.let { File(it).delete() } }
            change { list -> list.filterNot { it in gone } }
        }
    }

    private fun exists(item: Saved): Boolean = runCatching {
        appContext.contentResolver.query(item.contentUri, arrayOf("_id"), null, null, null)
            ?.use { it.count > 0 } ?: false
    }.getOrDefault(true) // if we can't tell, keep it

    private fun change(transform: (List<Saved>) -> List<Saved>) {
        _items.update { transform(it).take(500) }
        val snapshot = _items.value
        scope.launch {
            val tmp = File(file.parentFile, "history.json.tmp")
            tmp.writeText(json.encodeToString(snapshot))
            tmp.renameTo(file)
            YtddWidget.refresh(appContext)
        }
    }
}
