package dev.forgesworn.kithmoot.account

/** Missing discovery is not permission to send to guessed relays. Both a
 * message and a node invitation need an inbox and an encrypted sender copy. */
internal fun nostrInboxRelayProblem(own: List<String>, recipient: List<String>): String? = when {
    own.isEmpty() && recipient.isEmpty() -> "No usable published Nostr inbox was found for either account. Set up your inbox relays; the other person must publish theirs in their Nostr app too. Nothing was sent."
    own.isEmpty() -> "No usable published inbox relays were found for your account. Open Inbox relays to check or publish your list, then try again. Nothing was sent."
    recipient.isEmpty() -> "No usable published Nostr inbox was found for this person. Ask them to publish inbox relays in their Nostr app, then try again. Nothing was sent."
    else -> null
}
