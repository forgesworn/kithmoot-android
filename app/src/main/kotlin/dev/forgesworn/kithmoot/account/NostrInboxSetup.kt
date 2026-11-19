package dev.forgesworn.kithmoot.account

/** Public suggestions checked with expiring gift wraps on 19 November 2026.
 * These are an editor draft until the account publishes its own list. */
internal val DEFAULT_NOSTR_INBOX_RELAYS: List<String> = listOf(
    "wss://relay.primal.net/",
    "wss://nos.lol/",
    "wss://relay.ditto.pub/",
)

internal fun nostrInboxRelayDraft(published: List<String>?): String = when {
    published == null -> ""
    published.isEmpty() -> DEFAULT_NOSTR_INBOX_RELAYS.joinToString("\n")
    else -> published.joinToString("\n")
}

/** Missing discovery is not permission to send to guessed relays. Both a
 * message and a node invitation need an inbox and an encrypted sender copy. */
internal fun nostrInboxRelayProblem(own: List<String>, recipient: List<String>): String? = when {
    own.isEmpty() && recipient.isEmpty() -> "No usable published Nostr inbox was found for either account. Set up your inbox relays; the other person must publish theirs in their Nostr app too. Nothing was sent."
    own.isEmpty() -> "No usable published inbox relays were found for your account. Open Inbox relays to check or publish your list, then try again. Nothing was sent."
    recipient.isEmpty() -> "No usable published Nostr inbox was found for this person. Ask them to publish inbox relays in their Nostr app, then try again. Nothing was sent."
    else -> null
}
