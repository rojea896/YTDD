package com.rojea.ytdd.download

import android.content.Context
import android.util.Log
import androidx.media3.common.MimeTypes
import com.rojea.ytdd.core.Engine
import com.rojea.ytdd.core.History
import com.rojea.ytdd.core.Kind
import com.rojea.ytdd.core.Platform
import com.rojea.ytdd.core.Prefs
import com.rojea.ytdd.core.Saved
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job as CoJob
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID

enum class Stage(val label: String) {
    Queued("Waiting…"),
    Fetching("Reading link…"),
    Downloading("Downloading"),
    Finishing("Finishing up…"),
    Converting("Making it play everywhere…"),
    Saving("Saving to gallery…"),
    Done("Saved"),
    Failed("Failed"),
    Canceled("Canceled");

    val active get() = ordinal <= Saving.ordinal
}

data class Job(
    val id: String,
    val url: String,
    val kind: Kind,
    val platform: Platform,
    val title: String? = null,
    val thumbPath: String? = null,
    val durationSec: Double? = null,
    val stage: Stage = Stage.Queued,
    /** 0..1, or null when there's no meaningful percentage. */
    val progress: Float? = null,
    val etaSec: Long? = null,
    val note: String? = null,
    val error: Errors.Friendly? = null,
    val result: Saved? = null,
)

object Downloads {
    private const val TAG = "Downloads"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slots = Semaphore(2)
    private val running = mutableMapOf<String, CoJob>()
    private val thumbJobs = java.util.concurrent.ConcurrentHashMap<String, CoJob>()
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var appContext: Context

    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    /** Jobs this session: active ones plus finished ones until dismissed. Newest first. */
    val jobs: StateFlow<List<Job>> = _jobs.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun get(id: String) = _jobs.value.find { it.id == id }

    /** Start downloading [url]. Returns the job id. */
    fun start(url: String, kind: Kind): String {
        val job = Job(UUID.randomUUID().toString(), url, kind, Platform.fromUrl(url))
        _jobs.update { listOf(job) + it }
        launch(job.id)
        DownloadService.ensureRunning(appContext)
        return job.id
    }

    fun retry(id: String) {
        val old = get(id) ?: return
        set(id) { Job(it.id, it.url, it.kind, it.platform, it.title, it.thumbPath, it.durationSec) }
        Notify.cancel(appContext, old.id)
        launch(id)
        DownloadService.ensureRunning(appContext)
    }

    fun cancel(id: String) {
        YoutubeDL.destroyProcessById(id)
        synchronized(running) { running[id] }?.cancel()
        set(id) { if (it.stage.active) it.copy(stage = Stage.Canceled, progress = null) else it }
    }

    fun cancelAll() = _jobs.value.filter { it.stage.active }.forEach { cancel(it.id) }

    fun dismiss(id: String) {
        if (get(id)?.stage?.active == true) cancel(id)
        _jobs.update { list -> list.filterNot { it.id == id } }
    }

    private fun launch(id: String) {
        val co = scope.launch { run(id) }
        synchronized(running) { running[id] = co }
        co.invokeOnCompletion { synchronized(running) { if (running[id] === co) running.remove(id) } }
    }

    private fun set(id: String, f: (Job) -> Job) =
        _jobs.update { list -> list.map { if (it.id == id) f(it) else it } }

    private suspend fun run(id: String) {
        val work = File(appContext.cacheDir, "jobs/$id")
        try {
            slots.withPermit {
                set(id) { it.copy(stage = Stage.Fetching) }
                Engine.awaitReady()
                var updated = false
                while (true) {
                    try {
                        val saved = Engine.use { attempt(get(id) ?: return@use null, work) } ?: return
                        set(id) { it.copy(stage = Stage.Done, progress = 1f, result = saved, title = saved.title) }
                        History.add(saved)
                        get(id)?.let { Notify.done(appContext, it) }
                        return
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: YoutubeDL.CanceledException) {
                        return
                    } catch (e: Exception) {
                        if (get(id)?.stage == Stage.Canceled) return
                        Log.w(TAG, "attempt failed", e)
                        val friendly = Errors.explain(e.message ?: e.toString(), get(id)?.platform ?: Platform.Other)
                        // Sites change all the time; a newer yt-dlp usually fixes "it stopped
                        // working". Same as desktop: update, then retry exactly once.
                        val offline = friendly.title == "No internet connection"
                        if (!updated && !offline) {
                            updated = true
                            set(id) { it.copy(stage = Stage.Fetching, progress = null, note = "Updating the downloader…") }
                            if (Engine.update(waitForIdle = true)) {
                                set(id) { it.copy(note = null) }
                                continue
                            }
                        }
                        set(id) { it.copy(stage = Stage.Failed, error = friendly, progress = null, note = null) }
                        get(id)?.let { Notify.failed(appContext, it) }
                        return
                    }
                }
            }
        } catch (e: CancellationException) {
            set(id) { it.copy(stage = Stage.Canceled, progress = null) }
        } finally {
            work.deleteRecursively()
        }
    }

    private suspend fun attempt(job: Job, work: File): Saved {
        work.deleteRecursively()
        work.mkdirs()

        val req = YoutubeDLRequest(job.url).apply {
            addOption("--no-playlist")
            addOption("--playlist-items", "1") // a post with several videos: take the first
            addOption("--no-write-playlist-metafiles")
            addOption("--write-info-json")
            addOption("--no-mtime")
            addOption("--newline")
            addOption("--windows-filenames") // files get sent on to PCs too
            addOption("--concurrent-fragments", 4)
            addOption("-P", work.absolutePath)
            addOption("-o", "%(title).60B.%(ext)s")
            when (job.kind) {
                Kind.Video -> {
                    val h = Prefs.quality.value.height
                    addOption("-f", "bv*+ba/b")
                    // Same idea as desktop YTDD: pick the resolution first, then prefer
                    // H.264 + AAC so most downloads need no conversion at all.
                    addOption("-S", "res:$h,fps,hdr:sdr,vcodec:h264,acodec:aac,ext:mp4:m4a")
                    addOption("--merge-output-format", "mp4")
                }
                Kind.Audio -> {
                    addOption("-f", "ba/b")
                    addOption("-x")
                    addOption("--audio-format", "mp3")
                    addOption("--audio-quality", "2")
                    addOption("--embed-metadata")
                }
            }
        }

        var infoSeen = false
        runInterruptible {
            YoutubeDL.execute(req, job.id) { progress, eta, line ->
                // yt-dlp logs "Writing video metadata" before writing the file; by the first
                // [download] line it's on disk.
                if (!infoSeen && line.startsWith("[download]")) {
                    infoSeen = true
                    readInfo(job.id, work)
                }
                when {
                    line.startsWith("[download]") && progress > 0f -> set(job.id) {
                        it.copy(stage = Stage.Downloading, progress = (progress / 100f).coerceIn(0f, 1f),
                            etaSec = eta.takeIf { e -> e > 0 }, note = null)
                    }
                    line.startsWith("[Merger]") || line.startsWith("[ExtractAudio]") ||
                        line.startsWith("[FixupM") || line.startsWith("[Metadata]") ->
                        set(job.id) { it.copy(stage = Stage.Finishing, progress = null, etaSec = null) }
                }
            }
        }
        if (!infoSeen) readInfo(job.id, work)

        var file = work.listFiles()!!
            .filter { it.isFile && !it.name.endsWith(".json") && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            .maxByOrNull { it.length() } ?: error("yt-dlp finished but no file was produced")

        var note: String? = null
        var probe: Compat.Probe? = null
        if (job.kind == Kind.Video) {
            probe = Compat.probe(file)
            if (!Compat.isUniversalVideo(file, probe)) {
                Log.i(TAG, "converting ${file.name}: ${probe.videoMime} / ${probe.audioMime}")
                set(job.id) { it.copy(stage = Stage.Converting, progress = 0f, etaSec = null) }
                val out = File(work, file.nameWithoutExtension + ".converted.mp4")
                try {
                    Compat.toUniversalMp4(appContext, file, out) { p -> set(job.id) { it.copy(progress = p) } }
                    val dest = File(work, file.nameWithoutExtension + ".mp4")
                    file.delete()
                    out.renameTo(dest)
                    file = dest
                    probe = Compat.probe(file)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Better a file in its original format than no file at all.
                    Log.w(TAG, "conversion failed", e)
                    note = "Kept the original format — this phone couldn't convert it."
                }
            }
        }

        set(job.id) { it.copy(stage = Stage.Saving, progress = null, etaSec = null) }
        thumbJobs.remove(job.id)?.let { withTimeoutOrNull(5000) { it.join() } }
        val current = get(job.id) ?: job
        val mime = when {
            job.kind == Kind.Audio -> if (file.extension.equals("mp3", true)) MimeTypes.AUDIO_MPEG else "audio/*"
            file.extension.equals("mp4", true) -> MimeTypes.VIDEO_MP4
            file.extension.equals("webm", true) -> MimeTypes.VIDEO_WEBM
            else -> "video/*"
        }
        val uri = Gallery.publish(appContext, file, job.kind, file.name, mime)
        val audioDuration = if (job.kind == Kind.Audio) runCatching { Compat.probe(file).durationSec }.getOrNull() else null
        return Saved(
            id = job.id,
            url = job.url,
            title = current.title ?: file.nameWithoutExtension,
            platform = current.platform,
            kind = job.kind,
            uri = uri.toString(),
            fileName = Gallery.nameOf(appContext, uri) ?: file.name,
            mime = mime,
            sizeBytes = file.length(),
            durationSec = probe?.durationSec ?: audioDuration,
            width = probe?.width,
            height = probe?.height,
            createdAt = System.currentTimeMillis(),
            thumbPath = current.thumbPath,
        ).also { if (note != null) set(job.id) { it.copy(note = note) } }
    }

    /** Pull title/thumbnail out of yt-dlp's .info.json as soon as it's written. */
    private fun readInfo(id: String, work: File) {
        val f = work.listFiles()?.firstOrNull { it.name.endsWith(".info.json") }
            ?: run { Log.w(TAG, "no info.json in ${work.list()?.toList()}"); return }
        val info = runCatching { json.parseToJsonElement(f.readText()).jsonObject }
            .onFailure { Log.w(TAG, "info.json unreadable", it) }.getOrNull() ?: return
        fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull
        val title = (info.str("title") ?: info.str("fulltitle"))?.lineSequence()?.firstOrNull()?.trim()?.take(140)
        val platform = get(id)?.platform?.takeIf { it != Platform.Other }
            ?: info.str("webpage_url")?.let { Platform.fromUrl(it) } ?: Platform.Other
        val duration = info["duration"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
        set(id) { it.copy(title = title?.ifBlank { null } ?: it.title, platform = platform, durationSec = duration ?: it.durationSec) }
        // yt-dlp's pick is a guess (old YouTube videos have no maxres image), so fall back through
        // the full list, best first.
        val candidates = (listOfNotNull(info.str("thumbnail")) +
            (info["thumbnails"] as? JsonArray).orEmpty().reversed()
                .mapNotNull { (it as? JsonObject)?.str("url") }).distinct().take(6)
        thumbJobs[id] = scope.launch {
            val path = candidates.firstNotNullOfOrNull { Gallery.saveThumbnail(appContext, it, id) }
            if (path != null) set(id) { it.copy(thumbPath = path) }
            else Log.w(TAG, "no thumbnail from ${candidates.size} candidates")
        }
    }
}
