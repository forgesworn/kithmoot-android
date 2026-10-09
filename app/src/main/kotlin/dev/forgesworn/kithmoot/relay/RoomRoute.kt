package dev.forgesworn.kithmoot.relay

/** A local room preference, never an invitation permission or a relay hint. */
enum class RoomRoute(val stored: String, val label: String, val internet: Boolean, val nearby: Boolean) {
    INTERNET("internet", "Internet", true, false),
    NEARBY("nearby", "Nearby only", false, true),
    MIXED("mixed", "Nearby + Internet", true, true);

    companion object {
        fun fromStored(value: String?): RoomRoute = if (value == null) INTERNET else
            entries.firstOrNull { it.stored == value } ?: error("Unknown room connection mode")
    }
}
