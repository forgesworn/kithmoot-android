package dev.forgesworn.kithmoot.ui.theme

import android.content.Context
import androidx.compose.runtime.compositionLocalOf

/**
 * How big the words are, on top of whatever the phone's own text size is.
 *
 * The owner reads the phone without glasses. Everything already scales with
 * the system setting, but the system setting is a chore to find and it makes
 * every other app shout too; this is one tap in the app, remembered on the
 * device, and applied to every screen through the theme. Not a secret, so a
 * plain preference rather than the encrypted store.
 */
enum class TextSize(val label: String, val scale: Float) {
    STANDARD("Standard", 1.0f),
    LARGE("Large", 1.25f),
    HUGE("Huge", 1.5f);

    companion object {
        private const val PREFS = "kithmoot.display"
        private const val KEY = "textSize"

        fun load(context: Context): TextSize {
            val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            return entries.firstOrNull { it.name == saved } ?: STANDARD
        }

        fun save(context: Context, size: TextSize) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, size.name).apply()
        }
    }
}

/** The current choice and the way to change it, for the settings control. */
class TextSizeSetting(val size: TextSize, val set: (TextSize) -> Unit)

val LocalTextSizeSetting = compositionLocalOf { TextSizeSetting(TextSize.STANDARD) {} }
