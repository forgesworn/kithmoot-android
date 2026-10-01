package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Entropy
import org.junit.Assert.*
import org.junit.Test

class DmRelaysTest {
    private val now = 1_800_000_000L
    private val fallback = listOf("wss://nos.lol", "wss://relay.primal.net", "wss://nostr.mom")

    private fun list(sk: ByteArray, relays: List<String>, at: Long = now, kind: Int = KIND_DM_RELAYS) =
        Events.sign(sk, kind, at, relays.map { listOf("relay", it) }, "")

    @Test fun `builds and reads back the NIP-17 list in the web client's canonical form`() {
        val sk = Entropy.bytes(32)
        val event = Events.sign(sk, KIND_DM_RELAYS, now, dmRelayListTags(listOf("wss://relay.trotters.cc/", "wss://Nos.lol")), "")
        assertEquals(listOf("wss://relay.trotters.cc/", "wss://nos.lol/"), parseDmRelayList(event, event.pubkey))
    }

    @Test fun `refuses an unsafe address, an empty list, or too many`() {
        assertThrows(IllegalArgumentException::class.java) { dmRelayListTags(listOf("ws://relay.example")) }
        assertThrows(IllegalArgumentException::class.java) { dmRelayListTags(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { dmRelayListTags((0..MAX_DM_RELAYS).map { "wss://r$it.example" }) }
    }

    @Test fun `reads nothing from another kind, another author or a forgery`() {
        val sk = Entropy.bytes(32)
        val good = list(sk, listOf("wss://a.example"))
        assertEquals(emptyList<String>(), parseDmRelayList(list(sk, listOf("wss://a.example"), kind = 10002)))
        assertEquals(emptyList<String>(), parseDmRelayList(good, "0".repeat(64)))
        assertEquals(emptyList<String>(), parseDmRelayList(good.copy(tags = listOf(listOf("relay", "wss://b.example")))))
    }

    @Test fun `the latest list counts, even an emptied one, and a forged newer one does not`() {
        val sk = Entropy.bytes(32)
        val older = list(sk, listOf("wss://old.example"), now - 10)
        val newer = list(sk, listOf("wss://new.example"), now)
        assertEquals(listOf("wss://new.example/"), latestDmRelayList(listOf(older, newer), newer.pubkey))
        assertEquals(emptyList<String>(), latestDmRelayList(listOf(older, newer, list(sk, listOf("ws://plain.example"), now + 10)), newer.pubkey))
        val forged = list(sk, listOf("wss://forged.example"), now + 20).copy(sig = "0".repeat(128))
        assertEquals(listOf("wss://new.example/"), latestDmRelayList(listOf(newer, forged), newer.pubkey))
    }

    @Test fun `chooses as the reference does`() {
        assertEquals(listOf("wss://nos.lol/", "wss://relay.primal.net/", "wss://nostr.mom/"),
            relaysForPrivateConversation(emptyList(), emptyList(), fallback))
        assertEquals(listOf("wss://shared.example/", "wss://mine1.example/", "wss://theirs2.example/"),
            relaysForPrivateConversation(listOf("wss://mine1.example", "wss://shared.example"),
                listOf("wss://shared.example", "wss://theirs2.example"), fallback))
        assertEquals(listOf("wss://relay.trotters.cc/", "wss://nos.lol/"),
            relaysForPrivateConversation(listOf("wss://relay.trotters.cc"), emptyList(), fallback))
        val many = { who: String -> (0 until MAX_DM_RELAYS).map { "wss://$who$it.example" } }
        val chosen = relaysForPrivateConversation(many("m"), many("t"), fallback)
        assertEquals(MAX_DM_RELAYS, chosen.size)
        assertEquals(listOf("wss://t0.example/", "wss://m0.example/"), chosen.take(2))
    }
}
