package dev.forgesworn.kithmoot.ui

import dev.forgesworn.kithmoot.relay.RelayChoice
import dev.forgesworn.kithmoot.relay.RelaySelection

private const val RELAY_SETTINGS = "relaySettings"

/** Where one account's relay choices live in the display preferences; [pubkey] null is the device with no account. */
internal fun relayChoicesKey(pubkey: String?): String = "relayChoices.${pubkey ?: "visitor"}"

/**
 * What a save of validated [clean] choices writes, to be committed together:
 * the choices with their read and write flags, and the bare URLs of the
 * relays still in use, which is all the start state reads at launch.
 */
internal fun relayChoiceEntries(pubkey: String?, clean: List<RelayChoice>): Map<String, String> = mapOf(
    relayChoicesKey(pubkey) to RelaySelection.encode(clean),
    RELAY_SETTINGS to enabledRelayText(clean),
)

/** The relays still in use, one address a line. */
internal fun enabledRelayText(clean: List<RelayChoice>): String = clean.filter { it.read || it.write }.joinToString("\n") { it.url }

/** The choices saved for [pubkey], or null when there are none or they no longer validate. */
internal fun savedRelayChoices(read: (String) -> String?, pubkey: String?): List<RelayChoice>? =
    read(relayChoicesKey(pubkey))?.let { runCatching { RelaySelection.decode(it) }.getOrNull() }

/**
 * The relays text and choices a launch starts from, before any account is
 * restored. Someone with no account saved their choices as a visitor, flags
 * and unticked relays included; those are put back while they still describe
 * the relays last saved, so a later save by an account is not overlaid by
 * older flags. An account's own choices come back when it is restored.
 */
internal fun coldStartRelays(read: (String) -> String?): Pair<String, List<RelayChoice>> {
    val relays = read(RELAY_SETTINGS) ?: DEFAULT_RELAYS.joinToString("\n")
    val visitor = savedRelayChoices(read, null)?.takeIf { enabledRelayText(it) == relays }
    return relays to visitor.orEmpty()
}

/** The choices in force: those held, else every relay in [relays] with both flags on. */
internal fun relayChoicesFor(relays: String, choices: List<RelayChoice>): List<RelayChoice> = choices.ifEmpty {
    runCatching { parseRelays(relays).ifEmpty { DEFAULT_RELAYS }.map { RelayChoice(it) } }
        .getOrDefault(DEFAULT_RELAYS.map { RelayChoice(it) })
}
