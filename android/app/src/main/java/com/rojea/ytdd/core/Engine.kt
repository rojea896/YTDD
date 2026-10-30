package com.rojea.ytdd.core

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * yt-dlp + Python + ffmpeg + QuickJS, via youtubedl-android.
 *
 * The bundled yt-dlp goes stale within weeks (sites change constantly), so check for a new one on
 * every launch, hourly while the app stays open, and whenever a download fails — then retry that
 * download once (same as desktop YTDD).
 */
object Engine {
    sealed interface State {
        data object Starting : State
        data class Ready(val version: String?) : State
        data class Failed(val message: String) : State
    }

    private const val TAG = "Engine"
    /** While the app stays open, re-check when it comes back to the front after this long. */
    private const val RECHECK_AFTER_MS = 60 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ready = CompletableDeferred<Unit>()

    private val _state = MutableStateFlow<State>(State.Starting)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _updating = MutableStateFlow(false)
    val updating: StateFlow<Boolean> = _updating.asStateFlow()

    // Swapping the yt-dlp file while a download is reading from it would break that download, so
    // updates wait until nothing is using the engine, and new users wait for the update.
    private val gate = Mutex()
    private val users = AtomicInteger(0)

    private lateinit var appContext: Context

    fun start(context: Context) {
        appContext = context.applicationContext
        scope.launch {
            try {
                // First launch unpacks Python (~a few seconds); later launches are instant.
                YoutubeDL.init(appContext)
                FFmpeg.init(appContext)
                _state.value = State.Ready(YoutubeDL.versionName(appContext))
                ready.complete(Unit)
            } catch (e: Throwable) {
                Log.e(TAG, "init failed", e)
                _state.value = State.Failed(e.message ?: e.toString())
                ready.completeExceptionally(e)
                return@launch
            }
            // Every launch: sites break yt-dlp constantly, and nightly ships fixes within a day.
            update(waitForIdle = false)
        }
    }

    suspend fun awaitReady() = ready.await()

    /** Called when the app returns to the foreground. */
    fun checkIfStale() {
        if (!ready.isCompleted || _updating.value) return
        if (System.currentTimeMillis() - Prefs.lastEngineCheck > RECHECK_AFTER_MS) scope.launch { update(waitForIdle = false) }
    }

    /** Run [block] while holding the engine (blocks updates until it returns). */
    suspend fun <T> use(block: suspend () -> T): T {
        gate.withLock { users.incrementAndGet() }
        try {
            return block()
        } finally {
            users.decrementAndGet()
        }
    }

    /**
     * Fetch the latest yt-dlp for the chosen channel. Returns true if a new version was installed.
     * With [waitForIdle] false it gives up instead of waiting for running downloads.
     */
    suspend fun update(waitForIdle: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        awaitReady()
        gate.withLock {
            while (users.get() > 0) {
                if (!waitForIdle) return@withContext false
                delay(300)
            }
            _updating.value = true
            try {
                val channel = if (Prefs.stableEngine.value) YoutubeDL.UpdateChannel.STABLE
                else YoutubeDL.UpdateChannel.NIGHTLY
                val status = YoutubeDL.updateYoutubeDL(appContext, channel)
                Prefs.lastEngineCheck = System.currentTimeMillis()
                _state.value = State.Ready(YoutubeDL.versionName(appContext))
                Log.i(TAG, "update: $status -> ${YoutubeDL.versionName(appContext)}")
                status == YoutubeDL.UpdateStatus.DONE
            } catch (e: Throwable) {
                Log.w(TAG, "update failed", e)
                false
            } finally {
                _updating.value = false
            }
        }
    }
}
