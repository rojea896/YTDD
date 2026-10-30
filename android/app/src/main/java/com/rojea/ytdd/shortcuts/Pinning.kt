package com.rojea.ytdd.shortcuts

import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.rojea.ytdd.QuickActivity
import com.rojea.ytdd.R

/** "Add to home screen" buttons for the widget, the Quick Settings tile and a one-tap icon. */
object Pinning {
    fun canPinWidget(context: Context) =
        AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported

    fun pinWidget(context: Context) {
        AppWidgetManager.getInstance(context)
            .requestPinAppWidget(ComponentName(context, YtddWidgetReceiver::class.java), null, null)
    }

    /** Android 13+ can offer to add the tile directly; older versions need the user to edit QS. */
    fun canAddTile() = Build.VERSION.SDK_INT >= 33

    fun addTile(context: Context, onResult: (Boolean) -> Unit = {}) {
        if (Build.VERSION.SDK_INT < 33) return
        context.getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(context, PasteTileService::class.java),
            "Download link",
            Icon.createWithResource(context, R.drawable.ic_stat_download),
            context.mainExecutor,
        ) { result -> onResult(result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) }
    }

    fun canPinShortcut(context: Context) = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /** A home-screen icon that downloads whatever link is on the clipboard. */
    fun pinPasteShortcut(context: Context) {
        val info = ShortcutInfoCompat.Builder(context, "pinned_paste")
            .setShortLabel("Paste link")
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_video))
            .setIntent(Intent(context, QuickActivity::class.java).setAction(QuickActivity.ACTION_PASTE_VIDEO))
            .build()
        ShortcutManagerCompat.requestPinShortcut(context, info, null)
    }
}
