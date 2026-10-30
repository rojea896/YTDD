package com.rojea.ytdd.download

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import com.rojea.ytdd.shortcuts.PasteTileService
import com.rojea.ytdd.shortcuts.YtddWidget

/**
 * Keeps the process alive while anything is downloading — so a download started from the share
 * sheet keeps going after you go back to TikTok. The work itself lives in [Downloads].
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, Notify.FOREGROUND_ID, Notify.foreground(this, Downloads.jobs.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (!started) {
            started = true
            scope.launch {
                var lastWidget = 0L
                Downloads.jobs.sample(700).collect { jobs ->
                    // The widget and tile show live progress, but can't watch the flow themselves.
                    val now = System.currentTimeMillis()
                    if (now - lastWidget > 2000 || jobs.none { it.stage.active }) {
                        lastWidget = now
                        val app = applicationContext
                        kotlinx.coroutines.MainScope().launch { YtddWidget.refresh(app) } // outlives stopSelf()
                        PasteTileService.refresh(this@DownloadService)
                    }
                    if (jobs.none { it.stage.active }) {
                        ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        runCatching {
                            NotificationManagerCompat.from(this@DownloadService)
                                .notify(Notify.FOREGROUND_ID, Notify.foreground(this@DownloadService, jobs))
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun ensureRunning(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
            } catch (e: Exception) {
                // Started from the background (rare: all entry points are activities). The download
                // still runs; it just isn't protected from being killed.
                Log.w("DownloadService", "couldn't start foreground service", e)
            }
        }
    }
}
