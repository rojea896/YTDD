package com.rojea.ytdd.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rojea.ytdd.BuildConfig
import com.rojea.ytdd.R
import com.rojea.ytdd.core.Appearance
import com.rojea.ytdd.core.Engine
import com.rojea.ytdd.core.History
import com.rojea.ytdd.core.Prefs
import com.rojea.ytdd.core.Quality
import com.rojea.ytdd.core.Support
import com.rojea.ytdd.shortcuts.Pinning
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val quality by Prefs.quality.collectAsState()
    val appearance by Prefs.appearance.collectAsState()
    val stable by Prefs.stableEngine.collectAsState()
    val engine by Engine.state.collectAsState()
    val updating by Engine.updating.collectAsState()
    val scope = rememberCoroutineScope()
    var advanced by remember { mutableStateOf(false) }
    var updateMsg by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { UiIcon(R.drawable.ic_ui_back, "Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Section("Video quality", top = 8)
            Quality.entries.forEach { q ->
                SettingRow(onClick = { Prefs.setQuality(q) }) {
                    RadioButton(
                        selected = q == quality, onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = T.onSurface, unselectedColor = T.onSurfaceVariant),
                    )
                    TwoLine(q.label, q.hint)
                }
            }
            Text(
                "MP4 that plays everywhere, saved to Movies/YTDD. MP3 goes to Music/YTDD.",
                style = MaterialTheme.typography.bodySmall, color = T.onSurfaceVariant,
                modifier = Modifier.padding(start = 52.dp, end = 16.dp, top = 4.dp),
            )

            Section("Appearance")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                val colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = T.selected, activeContentColor = T.onSurface, activeBorderColor = T.outline,
                    inactiveContainerColor = Color.Transparent, inactiveContentColor = T.onSurface, inactiveBorderColor = T.outline,
                )
                Appearance.entries.forEachIndexed { i, a ->
                    SegmentedButton(
                        selected = a == appearance, onClick = { Prefs.setAppearance(a) },
                        shape = SegmentedButtonDefaults.itemShape(i, Appearance.entries.size), colors = colors,
                        icon = { if (a == appearance) UiIcon(R.drawable.ic_ui_check, size = 18.dp) },
                    ) { Text(a.label, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
                }
            }

            Section("Shortcuts")
            if (Pinning.canPinWidget(context)) IconRow(R.drawable.ic_ui_grid, "Add widget") { Pinning.pinWidget(context) }
            if (Pinning.canAddTile()) IconRow(R.drawable.ic_ui_tile, "Add Quick Settings tile") { Pinning.addTile(context) }
            if (Pinning.canPinShortcut(context)) IconRow(R.drawable.ic_ui_paste, "Add “Paste link” icon") { Pinning.pinPasteShortcut(context) }

            Section("Support")
            SettingRow(onClick = { Support.open(context) }, minHeight = 64) {
                UiIcon(R.drawable.ic_ui_coffee, tint = T.onSurfaceVariant)
                TwoLine("Buy me a coffee", "YTDD is free. Tips keep it going.")
            }

            HorizontalDivider(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), color = T.outlineVariant)
            SettingRow(onClick = { advanced = !advanced }, minHeight = 56) {
                Text("Advanced", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                UiIcon(if (advanced) R.drawable.ic_ui_up else R.drawable.ic_ui_down, tint = T.onSurfaceVariant)
            }
            if (advanced) {
                SettingRow(minHeight = 72) {
                    val version = (engine as? Engine.State.Ready)?.version ?: "starting…"
                    val checked = Prefs.lastEngineCheck.takeIf { it > 0 }?.let { " · checked " + ago(it) } ?: ""
                    TwoLine("Downloader", updateMsg ?: "$version$checked", Modifier.weight(1f))
                    if (updating) CircularProgressIndicator(Modifier.size(20.dp), color = T.onSurface, strokeWidth = 2.dp)
                    else OutlinedPill("Update", {
                        updateMsg = null
                        scope.launch {
                            val changed = Engine.update(waitForIdle = true)
                            updateMsg = if (changed) "Updated just now" else "Already up to date"
                        }
                    })
                }
                SettingRow(onClick = { Prefs.setStableEngine(!stable) }, minHeight = 72) {
                    TwoLine("Stable releases", "Off: nightly builds get site fixes sooner", Modifier.weight(1f))
                    Switch(
                        checked = stable, onCheckedChange = { Prefs.setStableEngine(it) },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = T.onSurface, checkedThumbColor = T.background, checkedBorderColor = T.onSurface,
                            uncheckedTrackColor = T.surface2, uncheckedThumbColor = T.onSurfaceVariant, uncheckedBorderColor = T.outline,
                        ),
                    )
                }
                SettingRow(onClick = { History.clear() }, minHeight = 72) {
                    TwoLine("Clear download list", "Videos stay in your gallery")
                }
            }
            Text(
                "YTDD ${BuildConfig.VERSION_NAME} · free and open source (GPL-3.0)",
                style = MaterialTheme.typography.bodySmall, color = T.onSurfaceVariant,
                modifier = Modifier.padding(16.dp, 24.dp),
            )
        }
    }
}

@Composable
private fun Section(title: String, top: Int = 24) = Text(
    title, style = MaterialTheme.typography.titleSmall, color = T.onSurfaceVariant,
    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = top.dp, bottom = 8.dp),
)

@Composable
private fun SettingRow(onClick: (() -> Unit)? = null, minHeight: Int = 64, content: @Composable RowScope.() -> Unit) = Row(
    Modifier.fillMaxWidth().heightIn(min = minHeight.dp)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(16.dp),
    content = content,
)

@Composable
private fun IconRow(icon: Int, label: String, onClick: () -> Unit) = SettingRow(onClick = onClick, minHeight = 56) {
    UiIcon(icon, tint = T.onSurfaceVariant)
    Text(label, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun TwoLine(title: String, subtitle: String, modifier: Modifier = Modifier) = Column(modifier) {
    Text(title, style = MaterialTheme.typography.bodyLarge)
    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = T.onSurfaceVariant)
}
