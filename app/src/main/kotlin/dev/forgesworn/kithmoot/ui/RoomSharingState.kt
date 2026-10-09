package dev.forgesworn.kithmoot.ui

/** Explicit local consent. A saved selection never means sharing is enabled. */
data class RoomSharingState(
    val enabled: Boolean = false,
    val busy: Boolean = false,
    val selected: Set<String> = emptySet(),
    val candidates: List<String> = emptyList(),
    val relays: List<String> = emptyList(),
    val previouslySaved: Boolean = false,
    val error: String? = null,
)
