package dev.forgesworn.kithmoot.relay

/**
 * The public relays KithMoot suggests, and new devices start on. The same
 * list as the web app's `src/public-relays.ts` (kithmoot repo), which holds
 * the criteria an entry must meet and the relays left out. A unit test holds
 * this copy to `data/public-relays.json`, kept alongside as a test resource.
 */
data class PublicRelay(
    /** Canonical, `wss://host/`. */
    val url: String,
    /** As its relay information (NIP-11) names it. */
    val operator: String,
    /** One of the relays new devices start on. */
    val default: Boolean,
    /** Held and served kind 1059 without sign-in. */
    val quietRooms: Boolean,
    /** Measured retention band, when measured. */
    val keeps: String? = null,
    /** What the last check did: `read` or `round-trip`. */
    val test: String,
    /** ISO date the last check passed. */
    val checked: String,
)

val PUBLIC_RELAYS: List<PublicRelay> = listOf(
    PublicRelay("wss://relay.trotters.cc/", "ForgeSworn relay", default = true, quietRooms = true, test = "round-trip", checked = "2026-11-01"),
    PublicRelay("wss://relay.primal.net/", "Primal Public Relay", default = true, quietRooms = true, keeps = "under-a-day", test = "read", checked = "2026-10-30"),
    PublicRelay("wss://relay.ditto.pub/", "Ditto Relay", default = true, quietRooms = true, test = "read", checked = "2026-10-30"),
    PublicRelay("wss://offchain.pub/", "offchain.pub", default = true, quietRooms = false, test = "read", checked = "2026-10-30"),
    PublicRelay("wss://nostr.oxtr.dev/", "0xtr relay", default = false, quietRooms = true, test = "read", checked = "2026-10-21"),
    PublicRelay("wss://relay.damus.io/", "damus.io", default = false, quietRooms = false, test = "read", checked = "2026-10-21"),
)

val DEFAULT_PUBLIC_RELAYS: List<PublicRelay> = PUBLIC_RELAYS.filter { it.default }
