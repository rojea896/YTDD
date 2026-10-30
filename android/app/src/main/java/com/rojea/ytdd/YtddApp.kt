package com.rojea.ytdd

import android.app.Application
import kotlinx.coroutines.launch
import com.rojea.ytdd.core.Engine
import com.rojea.ytdd.core.History
import com.rojea.ytdd.core.Prefs
import com.rojea.ytdd.download.Downloads
import com.rojea.ytdd.download.Notify

class YtddApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        History.init(this)
        Downloads.init(this)
        Notify.createChannels(this)
        Engine.start(this)
        Prefs.onAppearanceChanged = {
            kotlinx.coroutines.MainScope().launch { com.rojea.ytdd.shortcuts.YtddWidget.refresh(this@YtddApp) }
        }
        // Pick up the current appearance (and history) after an update or reinstall.
        kotlinx.coroutines.MainScope().launch { com.rojea.ytdd.shortcuts.YtddWidget.refresh(this@YtddApp) }
    }
}
