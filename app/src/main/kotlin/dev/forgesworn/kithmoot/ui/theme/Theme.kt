package dev.forgesworn.kithmoot.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * The palette.
 *
 * Every pair here is chosen for contrast rather than for fashion, and the rule
 * is absolute: no grey text on a grey ground, anywhere. A room is read across a
 * desk, at a glance, often by someone who has taken their glasses off, so the
 * dimmest text in the interface is still near 8:1 against what it sits on.
 * `onSurfaceVariant` is where interfaces usually give this up - it is the token
 * that Material's own defaults set to a mid-grey - so it is set here to a light
 * slate that stays legible.
 */
private val Ink = Color(0xFF070B0F)
private val InkRaised = Color(0xFF121A22)
private val InkSunken = Color(0xFF1C2732)
private val Parchment = Color(0xFFF7F9FA)
private val ParchmentDim = Color(0xFFCFDAE4)

private val Verdigris = Color(0xFF3FD9BC)
private val VerdigrisDeep = Color(0xFF00594B)
private val Ember = Color(0xFFFFB44D)
private val EmberDeep = Color(0xFF4A2C00)
private val Alarm = Color(0xFFFF6B6B)
private val AlarmDeep = Color(0xFF450A0A)

private val DarkScheme = darkColorScheme(
    primary = Verdigris,
    onPrimary = Color(0xFF00201A),
    primaryContainer = VerdigrisDeep,
    onPrimaryContainer = Color(0xFFB8FFF0),
    secondary = Ember,
    onSecondary = Color(0xFF2A1800),
    secondaryContainer = EmberDeep,
    onSecondaryContainer = Color(0xFFFFE0B8),
    tertiary = Color(0xFF9EC7FF),
    onTertiary = Color(0xFF00274D),
    error = Alarm,
    onError = Color(0xFF2E0000),
    errorContainer = AlarmDeep,
    onErrorContainer = Color(0xFFFFDAD6),
    background = Ink,
    onBackground = Parchment,
    surface = Ink,
    onSurface = Parchment,
    surfaceVariant = InkSunken,
    onSurfaceVariant = ParchmentDim,
    surfaceContainer = InkRaised,
    surfaceContainerHigh = InkSunken,
    surfaceContainerHighest = Color(0xFF25313E),
    outline = Color(0xFF7B8A98),
    outlineVariant = Color(0xFF3A4854),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF00594B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB8FFF0),
    onPrimaryContainer = Color(0xFF002019),
    secondary = Color(0xFF7A4A00),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFE0B8),
    onSecondaryContainer = Color(0xFF2A1800),
    tertiary = Color(0xFF00468F),
    onTertiary = Color(0xFFFFFFFF),
    error = Color(0xFFA3130E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Parchment,
    onBackground = Ink,
    surface = Color(0xFFFFFFFF),
    onSurface = Ink,
    surfaceVariant = Color(0xFFE3EAF0),
    onSurfaceVariant = Color(0xFF2A3540),
    surfaceContainer = Color(0xFFEDF2F6),
    surfaceContainerHigh = Color(0xFFE3EAF0),
    surfaceContainerHighest = Color(0xFFD8E1E9),
    outline = Color(0xFF56636F),
    outlineVariant = Color(0xFFB3C0CB),
)

@Composable
fun KithMootTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
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
    MaterialTheme(
        colorScheme = scheme,
        typography = KithMootTypography,
        content = content,
    )
}
