package com.rojea.ytdd.shortcuts

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.rojea.ytdd.MainActivity
import com.rojea.ytdd.QuickActivity
import com.rojea.ytdd.R
import com.rojea.ytdd.download.Downloads

/**
 * Quick Settings tile: copy a link anywhere, pull down the shade, tap. While something downloads
 * the tile turns on and shows progress; tapping it then opens the app.
 */
class PasteTileService : TileService() {
    companion object {
        /** Ask the system to redraw the tile (it calls onStartListening if the shade is open). */
        fun refresh(context: Context) = runCatching {
            requestListeningState(context, ComponentName(context, PasteTileService::class.java))
        }
    }

    override fun onStartListening() {
        val tile = qsTile ?: return
        val running = Downloads.jobs.value.firstOrNull { it.stage.active }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_ui_tray)
        if (running != null) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "Downloading"
            tile.subtitle = (running.progress?.let { "${(it * 100).toInt()}% · " } ?: "") + "tap to open"
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Download link"
            tile.subtitle = "From clipboard"
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val busy = Downloads.jobs.value.any { it.stage.active }
        val intent = if (busy) Intent(this, MainActivity::class.java)
        else Intent(this, QuickActivity::class.java).setAction(QuickActivity.ACTION_PASTE_VIDEO)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, if (busy) 1 else 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
