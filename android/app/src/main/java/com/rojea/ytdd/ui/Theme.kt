package com.rojea.ytdd.ui

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rojea.ytdd.core.Appearance
import com.rojea.ytdd.core.Prefs

/**
 * Colour tokens from the redesign (design/YTDD redesign.html, Spec board). Fixed colours — no
 * wallpaper-based dynamic colour. Depth is tonal: background → surface1 → surface3, never shadows.
 */
@Immutable
data class Tokens(
    val background: Color,
    val surface1: Color,
    val surface2: Color,
    val surface3: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val outlineVariant: Color,
    val selected: Color,
    val progressTrack: Color,
    val error: Color,
    /** Coral. Only the main action, progress, and the app icon. */
    val accent: Color = Color(0xFFFF5A5A),
    /** Text on coral. White on coral fails contrast. */
    val onAccent: Color = Color(0xFF2A0505),
    /** Only the WhatsApp glyph. */
    val whatsapp: Color = Color(0xFF1DAA61),
)

val DarkTokens = Tokens(
    background = Color(0xFF131212),
    surface1 = Color(0xFF1C1A1A),
    surface2 = Color(0xFF232121),
    surface3 = Color(0xFF2C2A2A),
    onSurface = Color(0xFFECE6E5),
    onSurfaceVariant = Color(0xFFB3ABA9),
    outline = Color(0xFF55504E),
    outlineVariant = Color(0xFF343030),
    selected = Color(0xFF3A3534),
    progressTrack = Color(0xFF4A2A29),
    error = Color(0xFFFFB4AB),
)

val LightTokens = Tokens(
    background = Color(0xFFFBF8F7),
    surface1 = Color(0xFFF4F0EF),
    surface2 = Color(0xFFEEE9E8),
    surface3 = Color(0xFFE7E2E1),
    onSurface = Color(0xFF1D1B1B),
    onSurfaceVariant = Color(0xFF5C5453),
    outline = Color(0xFFC4BBB9),
    outlineVariant = Color(0xFFE3DCDB),
    selected = Color(0xFFE8DFDD),
    progressTrack = Color(0xFFF6D3D1),
    error = Color(0xFFB3261E),
)

val LocalTokens = staticCompositionLocalOf { DarkTokens }

/** Shorthand: `T.accent` inside composables. */
val T: Tokens
    @Composable get() = LocalTokens.current

private fun scheme(t: Tokens, dark: Boolean) = (if (dark) darkColorScheme() else lightColorScheme()).copy(
    primary = t.accent,
    onPrimary = t.onAccent,
    primaryContainer = t.selected,
    onPrimaryContainer = t.onSurface,
    secondaryContainer = t.selected,
    onSecondaryContainer = t.onSurface,
    background = t.background,
    onBackground = t.onSurface,
    surface = t.background,
    onSurface = t.onSurface,
    surfaceVariant = t.surface2,
    onSurfaceVariant = t.onSurfaceVariant,
    surfaceContainerLowest = t.surface1,
    surfaceContainerLow = t.surface1,
    surfaceContainer = t.surface1,
    surfaceContainerHigh = t.surface2,
    surfaceContainerHighest = t.surface3,
    inverseSurface = t.onSurface,
    inverseOnSurface = t.background,
    outline = t.outline,
    outlineVariant = t.outlineVariant,
    error = t.error,
    scrim = Color.Black,
)

// M3 type scale, Roboto (the platform default), with the sizes the design calls for.
private val Type = Typography().let { m ->
    m.copy(
        titleLarge = m.titleLarge.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Normal),
        titleMedium = m.titleMedium.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
        titleSmall = m.titleSmall.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        bodyLarge = m.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = m.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = m.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
        labelLarge = m.labelLarge.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    )
}

/** The Paste & download label: the only 20sp text in the app. */
val MainButtonText = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium)

@Composable
fun isDarkUi(): Boolean {
    val appearance by Prefs.appearance.collectAsState()
    return when (appearance) {
        Appearance.Light -> false
        Appearance.Dark -> true
        Appearance.System -> isSystemInDarkTheme()
    }
}

@Composable
fun YtddTheme(dark: Boolean = isDarkUi(), content: @Composable () -> Unit) {
    val t = if (dark) DarkTokens else LightTokens
    CompositionLocalProvider(LocalTokens provides t) {
        MaterialTheme(
            colorScheme = scheme(t, dark),
            typography = Type,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(8.dp),
                small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(16.dp),
                large = RoundedCornerShape(20.dp),
                extraLarge = RoundedCornerShape(28.dp),
            ),
            content = content,
        )
    }
}

/** Edge-to-edge with bar icons that match the app's own light/dark choice. */
fun ComponentActivity.applySystemBars(dark: Boolean) {
    val t = android.graphics.Color.TRANSPARENT
    val style = if (dark) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t)
    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
}
