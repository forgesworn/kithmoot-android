package dev.forgesworn.kithmoot.callrelay

import dev.forgesworn.kithmoot.storage.RoomStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RelayIdentityTest {
    @get:Rule val folder = TemporaryFolder()
    private val participant = "1".repeat(64)
    private val relays = listOf("wss://relay.example.com/")

    private class MemoryStorage : RoomStorage {
        var value: ByteArray? = null
        override fun read() = value?.copyOf()
        override fun write(value: ByteArray) { this.value = value.copyOf() }
        override fun reset() { value = null }
    }

    @Test fun `a new identity has two secrets, the account, the relays and a listener port`() {
        val identity = RelayIdentity.create(participant, relays)
        assertTrue(Regex("^[0-9a-f]{64}$").matches(identity.turnSecret))
        assertTrue(Regex("^[0-9a-f]{64}$").matches(identity.pairingPublicKey))
        assertNotEquals(identity.turnSecret, identity.pairingSecret)
        assertEquals(participant, identity.participant)
        assertEquals(relays, identity.controlRelays)
        assertTrue(identity.listenPort in RelayCaps.LISTEN_PORTS)
    }

    @Test fun `a pairing secret that is not a valid key is drawn again`() {
        val draws = ArrayDeque(listOf(ByteArray(32), ByteArray(32) { 0xff.toByte() }, ByteArray(32) { 7 }, ByteArray(4) { 0xff.toByte() }, ByteArray(32) { 9 }))
        val identity = RelayIdentity.create(participant, relays) { size -> draws.removeFirst().also { assertEquals(size, it.size) } }
        assertEquals("07".repeat(32), identity.pairingSecret)
        assertEquals("09".repeat(32), identity.turnSecret)
        assertEquals(RelayCaps.LISTEN_PORTS.first + (0xffffffffL % 20000).toInt(), identity.listenPort)
    }

    @Test fun `it is kept and read back whole, and never shown`() {
        val storage = MemoryStorage()
        val store = RelayIdentityStore(storage, folder.root)
        assertNull(store.read())
        val identity = RelayIdentity.create(participant, relays)
        store.save(identity)
        val back = store.read()!!
        assertEquals(identity.turnSecret, back.turnSecret)
        assertEquals(identity.pairingSecret, back.pairingSecret)
        assertEquals(identity.listenPort, back.listenPort)
        assertFalse(back.toString().contains(identity.turnSecret))
        assertFalse(back.toString().contains(identity.pairingSecret))
    }

    @Test fun `removing it forgets the keys and the node's state, but keeps the mapping record`() {
        val storage = MemoryStorage()
        val store = RelayIdentityStore(storage, folder.root)
        store.save(RelayIdentity.create(participant, relays))
        val state = File(folder.root, RelayIdentityStore.STATE_FILE).apply { writeText("{}") }
        val record = File(folder.root, RelayIdentityStore.STATE_FILE + ".mappings").apply { writeText("{}") }
        store.remove()
        assertNull(store.read())
        assertFalse(state.exists())
        assertTrue(record.exists())
    }

    @Test fun `anything malformed is refused without naming a value`() {
        val good = String(RelayIdentity.create(participant, relays).encode())
        for (bad in listOf(
            good.replace("\"v\":1", "\"v\":2"),
            good.replace("\"listenPort\":", "\"extra\":1,\"listenPort\":"),
            good.replace(Regex("\"listenPort\":[0-9]+"), "\"listenPort\":80"),
            good.replace("wss://relay.example.com/", "ws://relay.example.com/"),
            good.replace(Regex("\"pairingSecret\":\"[0-9a-f]+\""), "\"pairingSecret\":\"${"0".repeat(64)}\""),
            "not json",
        )) {
            val error = assertThrows(IllegalStateException::class.java) { RelayIdentity.decode(bad.toByteArray()) }
            assertEquals("The call relay's saved keys could not be read.", error.message)
        }
    }
}
