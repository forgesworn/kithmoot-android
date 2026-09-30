package dev.forgesworn.kithmoot.media.effects

import android.content.SharedPreferences

/**
 * What you can put behind yourself, and whether anything is swimming in it.
 *
 * The four scenes are the web client's four seas, the same photographs and the
 * same wording, so somebody who has used one client recognises the other. They
 * are generated rather than taken, deliberately: a stock photograph of
 * somebody's office is a picture of a real place, and the point of this feature
 * is to stop publishing pictures of real places.
 *
 * The fish are not one of these. They are a switch over whichever sea has been
 * picked, because "which picture" and "is anything swimming in it" are two
 * questions and folding them into one list meant answering the first to get at
 * the second.
 */
enum class SeaScene(val label: String, val asset: String?) {
    /** A plain dark fill and no picture: the default, so a camera turned on
     *  without a choice shows the person and not their room. */
    PLAIN("Plain dark", null),
    LAGOON("Lagoon", "backgrounds/sea-lagoon.webp"),
    CORAL("Coral garden", "backgrounds/sea-coral.webp"),
    DEEP("Deep blue", "backgrounds/sea-deep.webp"),
    SAND("White sand", "backgrounds/sea-sand.webp"),
    ;

    companion object {
        /** The stored name, or null for anything this version does not know -
         *  a scene removed in a later version leaves the background off rather
         *  than crashing a call. */
        fun byName(name: String?): SeaScene? = entries.firstOrNull { it.name == name }
    }
}

/** The fish photographs, in the order the web client loads them. Every sprite
 *  faces LEFT: that is a contract with the files rather than a preference, so
 *  "which way is it swimming" is one mirror rather than two sets of artwork. */
val FISH_SPRITES = listOf(
    "backgrounds/fish/tang.webp",
    "backgrounds/fish/clownfish.webp",
    "backgrounds/fish/regal.webp",
    "backgrounds/fish/damsel.webp",
    "backgrounds/fish/butterfly.webp",
    "backgrounds/fish/anthias.webp",
)

/**
 * What the person has chosen: a scene, or nothing at all.
 *
 * `scene == null` is the off state. Off is not a mode the compositor handles;
 * it is the compositor never being asked, which is what makes the pass-through
 * free. See `BackgroundProcessor`. A device that has never chosen gets
 * [BackgroundPreference.DEFAULT], not this constructor's off.
 */
data class BackgroundChoice(val scene: SeaScene? = null, val fish: Boolean = true) {
    val on: Boolean get() = scene != null
    /** Fish swim over a sea. Over the plain fill they would look like a fault. */
    val showsFish: Boolean get() = fish && scene?.asset != null
}

/**
 * A tiny string/boolean store, so the choice can be tested without Android's
 * `SharedPreferences` - a plain unit test's stub of it always returns the
 * defaults, see `testOptions.unitTests.returnDefaultValues` in
 * `app/build.gradle.kts`. The same shape `media/CallVolume.kt` uses.
 */
interface BackgroundStore {
    fun getString(key: String, default: String?): String?
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putString(key: String, value: String)
    fun putBoolean(key: String, value: Boolean)
    fun remove(key: String)
}

/** Backed by the app's ordinary per-device display preferences - the same
 *  `kithmoot.display` store `ui/theme/TextSize.kt` uses for the same kind of
 *  setting: local, not secret, remembered on this device only. */
class SharedPreferencesBackgroundStore(private val prefs: SharedPreferences) : BackgroundStore {
    override fun getString(key: String, default: String?): String? = prefs.getString(key, default)
    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun putBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }
}

/**
 * The chosen background, remembered between calls on this device.
 *
 * Remembered rather than asked for each time: somebody who has a reason to hide
 * their room has that reason on Tuesday as well, and a setting that resets is a
 * setting that publishes a room the once.
 *
 * A device that has never chosen gets the plain dark fill. It used to get off,
 * to spare the battery a segmenter nobody asked for, and the owner's own first
 * call on a phone published their room. The web client starts blurred for the
 * same reason; there is no blur here, so the dark fill stands in for it. Off is
 * stored as its own value, so a person who turns it off keeps it off. A device
 * that turned it off before off was stored gets the dark fill once, and can
 * turn it off again.
 */
class BackgroundPreference(private val store: BackgroundStore) {

    fun load(): BackgroundChoice {
        val stored = store.getString(KEY_SCENE, null)
            ?: return DEFAULT.copy(fish = store.getBoolean(KEY_FISH, DEFAULT.fish))
        return BackgroundChoice(
            scene = if (stored == OFF) null else SeaScene.byName(stored) ?: DEFAULT.scene,
            fish = store.getBoolean(KEY_FISH, true),
        )
    }

    fun save(choice: BackgroundChoice) {
        store.putString(KEY_SCENE, choice.scene?.name ?: OFF)
        store.putBoolean(KEY_FISH, choice.fish)
    }

    companion object {
        /** What a camera starts with before anybody has chosen. */
        val DEFAULT = BackgroundChoice(scene = SeaScene.PLAIN, fish = true)
        private const val KEY_SCENE = "background:scene"
        private const val KEY_FISH = "background:fish"
        private const val OFF = "OFF"
    }
}
