package com.rojea.ytdd.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Kind { Video, Audio }

enum class Appearance(val label: String) { Light("Light"), Dark("Dark"), System("Match phone") }

enum class Quality(val height: Int, val label: String, val hint: String) {
    Saver(480, "Data saver", "480p · smallest files"),
    Standard(720, "Standard", "720p · what WhatsApp sends anyway"),
    HD(1080, "HD", "1080p · sharpest"),
}

/** User settings. Deliberately few — everything else is decided for them. */
object Prefs {
    private lateinit var sp: SharedPreferences

    private val _quality = MutableStateFlow(Quality.HD)
    val quality: StateFlow<Quality> = _quality.asStateFlow()

    private val _kind = MutableStateFlow(Kind.Video)
    /** The Video/Audio switch on the home screen; remembered between launches. */
    val kind: StateFlow<Kind> = _kind.asStateFlow()

    private val _appearance = MutableStateFlow(Appearance.Dark)
    /** Dark unless the user picks otherwise — even on a phone set to light mode. */
    val appearance: StateFlow<Appearance> = _appearance.asStateFlow()

    private val _stableEngine = MutableStateFlow(false)
    /** Advanced: yt-dlp stable instead of nightly. Nightly is what yt-dlp recommends. */
    val stableEngine: StateFlow<Boolean> = _stableEngine.asStateFlow()

    private val _tipsDismissed = MutableStateFlow(false)
    val tipsDismissed: StateFlow<Boolean> = _tipsDismissed.asStateFlow()

    fun init(context: Context) {
        sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        _quality.value = Quality.entries.find { it.name == sp.getString("quality", null) } ?: Quality.HD
        _kind.value = Kind.entries.find { it.name == sp.getString("kind", null) } ?: Kind.Video
        _appearance.value = Appearance.entries.find { it.name == sp.getString("appearance", null) } ?: Appearance.Dark
        _stableEngine.value = sp.getBoolean("stableEngine", false)
        _tipsDismissed.value = sp.getBoolean("tipsDismissed", false)
    }

    fun setQuality(q: Quality) { _quality.value = q; sp.edit().putString("quality", q.name).apply() }
    fun setKind(k: Kind) { _kind.value = k; sp.edit().putString("kind", k.name).apply() }
    fun setAppearance(a: Appearance) {
        _appearance.value = a
        sp.edit().putString("appearance", a.name).apply()
        onAppearanceChanged()
    }

    /** Set by the app so the widget can be redrawn in the new colours. */
    var onAppearanceChanged: () -> Unit = {}
    fun setStableEngine(on: Boolean) { _stableEngine.value = on; sp.edit().putBoolean("stableEngine", on).apply() }
    fun dismissTips() { _tipsDismissed.value = true; sp.edit().putBoolean("tipsDismissed", true).apply() }

    var lastEngineCheck: Long
        get() = sp.getLong("lastEngineCheck", 0)
        set(v) = sp.edit().putLong("lastEngineCheck", v).apply()
}
