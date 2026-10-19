package dev.forgesworn.kithmoot.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import android.provider.Settings
import androidx.core.view.WindowCompat

/**
 * The palette: the calm shell's token set (design-calm-shell section 4.2),
 * one value set for web, desktop and here.
 *
 * Every pair is chosen for contrast rather than for fashion, and the rule is
 * absolute: no grey text on a grey ground, anywhere. A room is read across a
 * desk, at a glance, often by someone who has taken their glasses off, so body
 * text is 7:1 or better on every surface it sits on and the dimmest text,
 * `onSurfaceVariant`, stays above 6:1. That token is where interfaces usually
 * give this up (Material's own default is a mid-grey), so it is set to a light
 * slate in dark and a deep slate in light.
 *
 * Material slots map to the design tokens as follows: `primary` is the accent,
 * `surface` the conversation canvas (`--bg`), `surfaceContainer` the chrome
 * (`--surface`), `surfaceContainerHigh` menus and sheets (`--raised`),
 * `secondaryContainer` the selected row (`--selected`), `outline` the control
 * boundary, `outlineVariant` the hairline, `onSurfaceVariant` the muted text
 * and `error` the destructive text. What Material has no slot for is in
 * [KithMootTokens].
 */
private object Dark {
    val bg = Color(0xFF0F1215)
    val surface = Color(0xFF15191D)
    val raised = Color(0xFF1D2226)
    val selected = Color(0xFF262C32)
    val line = Color(0xFF2A3036)
    val controlLine = Color(0xFF707B86)
    val text = Color(0xFFECF0F3)
    val muted = Color(0xFFAFBAC4)
    val accent = Color(0xFF2CC5CC)
    val onAccent = Color(0xFF032326)
    val link = Color(0xFF62D7DC)
    val danger = Color(0xFFFF8A80)
}

private object Light {
    val bg = Color(0xFFFFFFFF)
    val surface = Color(0xFFF3F6F8)
    val raised = Color(0xFFFFFFFF)
    val selected = Color(0xFFE2E9EF)
    val line = Color(0xFFDCE3E9)
    val controlLine = Color(0xFF75828F)
    val text = Color(0xFF102A43)
    val muted = Color(0xFF3F5468)
    val accent = Color(0xFF073B7A)
    val onAccent = Color(0xFFFFFFFF)
    val link = Color(0xFF0A4C96)
    val danger = Color(0xFFA3130E)
}

/**
 * What the Material colour scheme has no slot for. Read it with
 * `MaterialTheme.kithMoot`.
 *
 * @property mine my bubbles, and [onMine] the text on them.
 * @property bubble others' bubbles: `--bubble`, which is not any one of
 * Material's surface containers in both themes (in light it is a grey, while
 * the raised surface is white).
 * @property agent the ink of the "Agent" label, always inside an outlined pill.
 * @property ok call on, checked.
 * @property warn the amber stage of a self-destruct countdown, with [onWarn]
 * on it; it stays amber because the stage is told by its colour as well as its
 * words.
 * @property highContrast Android's "high contrast text" is on: the muted and
 * boundary colours have already been darkened (or lightened) in the scheme,
 * and bubbles gain a one-dp boundary.
 */
@Immutable
data class KithMootTokens(
    val mine: Color,
    val onMine: Color,
    val bubble: Color,
    val agent: Color,
    val ok: Color,
    val warn: Color,
    val onWarn: Color,
    val highContrast: Boolean,
)

private val DarkTokens = KithMootTokens(
    mine = Color(0xFF0C5961), onMine = Color(0xFFECF0F3), bubble = Color(0xFF1D2226),
    agent = Color(0xFFB9AFFF), ok = Color(0xFF6DD18A),
    warn = Color(0xFF4A2C00), onWarn = Color(0xFFF3CD8E), highContrast = false,
)

private val LightTokens = KithMootTokens(
    mine = Color(0xFF073B7A), onMine = Color(0xFFFFFFFF), bubble = Color(0xFFEEF2F5),
    agent = Color(0xFF4B3FB0), ok = Color(0xFF1C6F38),
    warn = Color(0xFFFFE0B8), onWarn = Color(0xFF6B4310), highContrast = false,
)

val LocalKithMootTokens = staticCompositionLocalOf { DarkTokens }

/** The tokens of the current theme. */
val MaterialTheme.kithMoot: KithMootTokens
    @Composable @ReadOnlyComposable get() = LocalKithMootTokens.current

private val DarkScheme = darkColorScheme(
    primary = Dark.accent,
    onPrimary = Dark.onAccent,
    primaryContainer = DarkTokens.mine,
    onPrimaryContainer = DarkTokens.onMine,
    secondary = Dark.accent,
    onSecondary = Dark.onAccent,
    secondaryContainer = Dark.selected,
    onSecondaryContainer = Dark.text,
    tertiary = Dark.link,
    onTertiary = Dark.onAccent,
    error = Dark.danger,
    onError = Color(0xFF2E0000),
    errorContainer = Color(0xFF450A0A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Dark.bg,
    onBackground = Dark.text,
    surface = Dark.bg,
    onSurface = Dark.text,
    surfaceVariant = Dark.selected,
    onSurfaceVariant = Dark.muted,
    surfaceContainerLowest = Dark.bg,
    surfaceContainerLow = Dark.bg,
    surfaceContainer = Dark.surface,
    surfaceContainerHigh = Dark.raised,
    surfaceContainerHighest = Dark.selected,
    outline = Dark.controlLine,
    outlineVariant = Dark.line,
)

private val LightScheme = lightColorScheme(
    primary = Light.accent,
    onPrimary = Light.onAccent,
    primaryContainer = LightTokens.mine,
    onPrimaryContainer = LightTokens.onMine,
    secondary = Light.accent,
    onSecondary = Light.onAccent,
    secondaryContainer = Light.selected,
    onSecondaryContainer = Light.text,
    tertiary = Light.link,
    onTertiary = Light.onAccent,
    error = Light.danger,
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Light.bg,
    onBackground = Light.text,
    surface = Light.bg,
    onSurface = Light.text,
    surfaceVariant = Light.selected,
    onSurfaceVariant = Light.muted,
    surfaceContainerLowest = Light.bg,
    surfaceContainerLow = Light.bg,
    surfaceContainer = Light.surface,
    surfaceContainerHigh = Light.raised,
    surfaceContainerHighest = Light.selected,
    outline = Light.controlLine,
    outlineVariant = Light.line,
)

/**
 * Increased contrast (Android's "high contrast text"): muted text and the
 * boundary colours take the stronger values from the design's section 4.2, and
 * the hairline takes the control-line value.
 */
private fun ColorScheme.highContrast(dark: Boolean): ColorScheme {
    val controlLine = if (dark) Color(0xFF9AA5B0) else Color(0xFF4E5B68)
    return copy(
        onSurfaceVariant = if (dark) Color(0xFFD4DCE3) else Color(0xFF24384C),
        outline = controlLine,
        outlineVariant = if (dark) Dark.controlLine else Light.controlLine,
    )
}

/** Whether the system's "high contrast text" setting is on (read where Android keeps it; off if unreadable). */
@Composable
private fun rememberHighContrastText(): Boolean {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(configuration) {
        runCatching { Settings.Secure.getInt(context.contentResolver, "high_text_contrast_enabled", 0) == 1 }.getOrDefault(false)
    }
}

@Composable
fun KithMootTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    textScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val highContrast = rememberHighContrastText()
    val base = if (darkTheme) DarkScheme else LightScheme
    val scheme = if (highContrast) base.highContrast(darkTheme) else base
    val tokens = (if (darkTheme) DarkTokens else LightTokens).copy(highContrast = highContrast)
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // Only the icon polarity is set. The bars themselves are transparent
            // under edge-to-edge and the screens draw their own ground behind
            // them, which is what keeps the header one continuous colour.
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    // The text size choice rides on the font scale, so every sp in the app
    // grows with it and nothing laid out in dp moves. The product is capped at
    // Android's own ceiling (see effectiveFontScale).
    val density = LocalDensity.current
    MaterialTheme(
        colorScheme = scheme,
        typography = KithMootTypography,
    ) {
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, effectiveFontScale(density.fontScale, textScale)),
            LocalKithMootTokens provides tokens,
            content = content,
        )
    }
}

/**
 * The title style for a top bar, held to 1.5 times the standard size however
 * large the text is set, so the title never clips inside the bar (F2, F13).
 * Body text keeps scaling; only bar titles are capped.
 */
@Composable
fun cappedTitleStyle(): TextStyle {
    val base = MaterialTheme.typography.titleLarge
    val fontScale = LocalDensity.current.fontScale
    return base.copy(fontSize = base.fontSize * (minOf(fontScale, TITLE_SCALE_CAP) / fontScale))
}

private const val TITLE_SCALE_CAP = 1.5f
