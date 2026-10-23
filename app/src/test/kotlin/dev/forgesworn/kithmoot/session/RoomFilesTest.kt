package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.GrantReceiver
import dev.forgesworn.kithmoot.protocol.RoomStorageRecord
import dev.forgesworn.kithmoot.protocol.buildGrant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Where a file sent in a room goes, and what the room says about it (S5b). */
class RoomFilesTest {
    private val own = FileDestination.Server("https://files.example", FileServerVia.TYPED)

    @Test fun `a listed room uses its own nodes whatever this device chose, even Nowhere`() {
        val listed = RoomStorageRecord(listOf("https://grove.example.net"), 5)
        for (device in listOf(own, FileDestination.Off(chosen = true), FileDestination.Default("https://blossom.yakihonne.com", 1L))) {
            assertEquals(RoomFiles.Group(listed.nodes), resolveRoomFiles(listed) { device })
        }
    }

    @Test fun `a withdrawn room keeps files off and never falls back to the device's server`() {
        assertEquals(RoomFiles.Withdrawn, resolveRoomFiles(RoomStorageRecord(emptyList(), 6)) { own })
        assertEquals(GROUP_NO_STORAGE, roomFilesLine(RoomStorageRecord(emptyList(), 6)))
        assertEquals("This group has no storage right now.", GROUP_NO_STORAGE)
    }

    @Test fun `a room that names no storage goes the way this device chose`() {
        assertEquals(RoomFiles.Device(own), resolveRoomFiles(null) { own })
        assertNull(roomFilesLine(null))
        assertEquals("Files in this room go to the group’s own storage.", roomFilesLine(RoomStorageRecord(listOf("https://grove.example.net"), 1)))
    }

    @Test fun `only https nodes that are not onion are ones this app can reach`() {
        assertEquals(listOf("https://grove.example.net"), reachableGroupNodes(listOf("http://127.0.0.1:8080", "https://grove.example.net",
            "http://abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwx.onion")))
    }

    @Test fun `grants running out within a week with none renewed are worth a word, and the words name the day`() {
        val sk = ByteArray(32) { 3 }
        val device = "ab".repeat(32)
        val room = "2848bdd5c60a79e2dfe667ae9616c1107f61709032b66e556e28f4a96e69450b"
        val receiver = GrantReceiver(Schnorr.publicKeyHex(sk), room, device)
        val nodes = listOf("https://grove.example.net")
        val at = 1_800_000_000L
        assertFalse(holdsAnyGrant(receiver, nodes, at))
        assertNull(grantsEndingSoon(receiver, nodes, at))
        receiver.offer(buildGrant(sk, room, device, "grove.example.net", listOf("get"), null, "cd".repeat(16), at, at + 10 * 86_400).toJson())
        assertTrue(holdsAnyGrant(receiver, nodes, at))
        assertNull(grantsEndingSoon(receiver, nodes, at), "ten days left")
        assertEquals(at + 10 * 86_400, grantsEndingSoon(receiver, nodes, at + 4 * 86_400))
        assertEquals("The group’s keeper has not been online for a while. Group files stop working on 25 January 2027 unless it comes back.",
            grantsEndingWords(at + 10 * 86_400, ZoneOffset.UTC, Locale.UK))
    }
}
