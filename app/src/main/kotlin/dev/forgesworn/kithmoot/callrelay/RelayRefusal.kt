package dev.forgesworn.kithmoot.callrelay

/**
 * Why a Turn on was refused before the relay process ever had the request.
 * The settings page words each from its code (`ui/settings/CallRelayWords.kt`);
 * nothing here is shown as it is.
 */
internal enum class RelayRefusal { HAND_PAIRED, NO_ACCOUNT, OTHER_ACCOUNT, NO_CONTROL_RELAYS, KEYS_UNREADABLE, START_FAILED }
