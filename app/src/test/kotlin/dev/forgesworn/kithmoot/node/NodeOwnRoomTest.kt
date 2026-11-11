package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.protocol.NodeRooms
import dev.forgesworn.kithmoot.protocol.deriveRoom
import kotlin.test.*

/** Whether a node link is put to the person as a stranger's, or goes straight to the keeper. */
class NodeOwnRoomTest {
    private val now = 1_900_000_000L
    private val root = LocalSigner(ByteArray(32) { 3 })
    private val service = LocalSigner(ByteArray(32) { 5 })
    private val account = LocalSigner(ByteArray(32) { 8 }).pubkey
    private val stranger = LocalSigner(ByteArray(32) { 9 }).pubkey
    private val room = deriveRoom(ByteArray(32) { 6 }).roomId
    private val node = requireNotNull(NodeRooms.read(NodeRooms.sign(root.secretKeyForStorage(), room,
        "https://node.example", service.pubkey, now).toJson(), now))

    private fun keeps(account: String? = this.account, listed: Collection<String> = emptyList(), savedAs: String? = null,
        custody: Boolean = false) = accountKeepsNodeRoom(node, account, listed, savedAs, custody)

    @Test fun `a stranger with nothing of the room is asked to confirm`() {
        assertFalse(keeps())
        assertFalse(keeps(listed = listOf(deriveRoom(ByteArray(32) { 7 }).roomId)))
    }

    @Test fun `the account's room list, a copy saved as the account, or custody held here make it the account's room`() {
        assertTrue(keeps(listed = listOf(room)))
        assertTrue(keeps(savedAs = account))
        assertTrue(keeps(custody = true))
    }

    @Test fun `a copy saved as another identity is not the account's`() {
        assertFalse(keeps(savedAs = stranger))
    }

    @Test fun `with nobody signed in nothing counts, whatever this phone holds`() {
        assertFalse(keeps(account = null, listed = listOf(room), savedAs = stranger, custody = true))
    }
}
