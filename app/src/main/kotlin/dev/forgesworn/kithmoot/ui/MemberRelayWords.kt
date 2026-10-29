package dev.forgesworn.kithmoot.ui

/**
 * The words of the one-time question before this device uses another
 * member's own call relay (a built-in relay) with Hide my IP address on.
 * They match the web client's `chooseMemberRelay` in `app/src/main.ts`, word
 * for word, straight apostrophes included. Not now is the room's existing
 * no-relay refusal, never a direct call.
 */
internal object MemberRelayWords {
    fun title(name: String) = "Use $name's relay?"
    fun message(name: String) = "$name will see your address; others won't."
    const val USE = "Use relay"
    const val NOT_NOW = "Not now"
}
