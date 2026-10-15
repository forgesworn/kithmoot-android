package dev.forgesworn.kithmoot.ui

import dev.forgesworn.kithmoot.relay.RelayChoice
import kotlin.test.Test
import kotlin.test.assertEquals

class RelayChoicePersistenceTest {
    private val guest = listOf(
        RelayChoice("wss://both.example"),
        RelayChoice("wss://read.example", read = true, write = false),
        RelayChoice("wss://write.example", read = false, write = true),
        RelayChoice("wss://off.example", read = false, write = false),
    )

    /** What the Settings save writes, applied to a store that outlives the process. */
    private fun save(store: MutableMap<String, String>, pubkey: String?, choices: List<RelayChoice>) =
        store.putAll(relayChoiceEntries(pubkey, choices))

    /** What the choices in force are after a cold start with no account restored. */
    private fun coldStart(store: Map<String, String>): List<RelayChoice> {
        val (relays, choices) = coldStartRelays(store::get)
        return relayChoicesFor(relays, choices)
    }

    @Test
    fun `a guest's read and write flags survive a restart`() {
        val store = mutableMapOf<String, String>()
        save(store, null, guest)

        // A new process sees only what was committed.
        assertEquals(guest, coldStart(store.toMap()))
    }

    @Test
    fun `a signed-in account's flags survive a restart`() {
        val store = mutableMapOf<String, String>()
        save(store, "a".repeat(64), guest)

        assertEquals(guest, savedRelayChoices(store.toMap()::get, "a".repeat(64)))
    }

    @Test
    fun `nothing saved starts on the defaults with both flags on`() {
        assertEquals(DEFAULT_RELAYS.map { RelayChoice(it) }, coldStart(emptyMap()))
    }

    @Test
    fun `an unreadable guest list falls back to the saved relay addresses`() {
        val store = mutableMapOf<String, String>()
        save(store, null, guest)
        store[relayChoicesKey(null)] = "not json"

        assertEquals(listOf("wss://both.example", "wss://read.example", "wss://write.example").map { RelayChoice(it) }, coldStart(store))
    }

    @Test
    fun `a guest list an account has since replaced is not applied to the newer relays`() {
        val store = mutableMapOf<String, String>()
        save(store, null, guest)
        val later = listOf(RelayChoice("wss://other.example", read = true, write = false), RelayChoice("wss://second.example", read = false, write = true))
        save(store, "b".repeat(64), later)

        // Signed out and restarted: the addresses last saved are in use, and the
        // older guest flags describe different relays.
        assertEquals(listOf("wss://other.example", "wss://second.example").map { RelayChoice(it) }, coldStart(store))
    }
}
