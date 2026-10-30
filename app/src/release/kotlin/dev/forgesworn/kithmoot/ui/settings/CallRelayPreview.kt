package dev.forgesworn.kithmoot.ui.settings

/**
 * The release build's half of the call relay screen previews (settings spec,
 * 11.5): there are none. The debug source set holds the real list, so none of
 * its states or words are in a release APK.
 */
internal object CallRelayPreviews {
    class Entry(val id: String, val label: String, val facts: (nowMs: Long) -> RelayFacts)

    @Suppress("UNUSED_PARAMETER")
    fun entries(debug: Boolean): List<Entry> = emptyList()

    @Suppress("UNUSED_PARAMETER")
    fun facts(id: String, nowMs: Long): RelayFacts? = null
}
