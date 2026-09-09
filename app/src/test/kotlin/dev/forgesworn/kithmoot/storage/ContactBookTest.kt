package dev.forgesworn.kithmoot.storage

import dev.forgesworn.kithmoot.protocol.Lane
import dev.forgesworn.kithmoot.protocol.laneOfRelays
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The contact book against cards the web library made (`contact-book-web.json`,
 * generated from nostr-contact-card 0.3.0): a card held makes its box a
 * sheltered relay, a later card keeps the serial pin, a fresh address from
 * the box is accepted only above it and only under the endorsed node id.
 */
class ContactBookTest {

    private val fixture: JsonObject by lazy {
        val stream = requireNotNull(javaClass.getResourceAsStream("/contact-book-web.json")) { "contact-book-web.json is missing" }
        Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }
    private val now get() = fixture.getValue("now").jsonPrimitive.long
    private fun s(key: String) = fixture.getValue(key).jsonPrimitive.content
    private fun bytes(key: String): ByteArray = Base64.getUrlDecoder().decode(s(key))

    @Test fun `a card marks its holder and makes their box a sheltered relay, and forgetting it undoes both`() {
        val disk = MemoryStorage()
        val book = ContactBook(disk)
        assertEquals(emptyList(), book.list())
        assertEquals(Lane.PUBLIC, laneOfRelays(listOf("wss://box.rowan.example"), book.circleRelays()))

        val refused = book.add("https://kithmoot.test/j/#not-a-card", now)
        assertTrue(refused is ContactBook.Added.Refused && refused.step == 1)
        assertEquals("This is not a contact card: it does not decode as one.", (refused as ContactBook.Added.Refused).words)
        assertEquals(emptyList(), book.list())

        val added = book.add(s("cardSerial1"), now)
        assertTrue(added is ContactBook.Added.Ok, (added as? ContactBook.Added.Refused)?.let { "step ${it.step} ${it.reason}" } ?: "ok")
        assertFalse(added.replaced)
        assertEquals(s("rowan"), added.contact.p)
        assertEquals("Rowan", added.contact.name)
        assertEquals(1, added.contact.boxes.size)
        assertEquals(1L, added.contact.boxes[0].highestSerial)
        assertEquals("card", added.contact.boxes[0].source)

        assertEquals(setOf("wss://box.rowan.example"), book.circleRelays())
        assertEquals(Lane.SHELTERED, laneOfRelays(listOf("wss://box.rowan.example"), book.circleRelays()))
        // A room on the box and a public relay: the weakest lane wins.
        assertEquals(Lane.PUBLIC, laneOfRelays(listOf("wss://box.rowan.example", "wss://nos.lol"), book.circleRelays()))
        assertEquals("Rowan", book.get(s("rowan").uppercase())?.name)

        // Survives a reopen: what was written is what is read.
        assertEquals("Rowan", ContactBook(disk).list().single().name)

        book.forget(s("rowan"))
        assertEquals(emptyList(), book.list())
        assertEquals(Lane.PUBLIC, laneOfRelays(listOf("wss://box.rowan.example"), book.circleRelays()))
    }

    @Test fun `a later card from the same person replaces the first and keeps the serial pin for the same node`() {
        val book = ContactBook(MemoryStorage())
        book.add(s("cardSerial3"), now)
        assertEquals(3L, book.list().single().boxes[0].highestSerial)
        // An older card of the same node, with a lower serial, does not lower the pin.
        val again = book.add(s("cardSerial1"), now + 1) as ContactBook.Added.Ok
        assertTrue(again.replaced)
        assertEquals(3L, again.contact.boxes[0].highestSerial)
        assertEquals(1, book.list().size)
        // A different node id is a new pin and starts afresh.
        val moved = book.add(s("cardOtherNode"), now + 2) as ContactBook.Added.Ok
        assertEquals(1L, moved.contact.boxes[0].highestSerial)
        assertEquals(setOf("wss://other.rowan.example"), book.circleRelays())
    }

    @Test fun `a fresh address from the box is accepted only above the pinned serial and under the endorsed node`() {
        val book = ContactBook(MemoryStorage())
        book.add(s("cardSerial3"), now)
        val rowan = s("rowan"); val box = s("box")
        assertTrue(book.refreshBox(rowan, box, bytes("freshSerial2"), now).isFailure, "serial 2 is below the pin at 3")
        assertTrue(book.refreshBox(rowan, box, bytes("freshOtherNodeSerial9"), now).isFailure, "a card from another node is not this box")
        assertTrue(book.refreshBox(rowan, "ab".repeat(32), bytes("freshSerial5"), now).isFailure, "no such box")
        val ok = book.refreshBox(rowan, box, bytes("freshSerial5"), now).getOrThrow()
        assertEquals(5L, ok.highestSerial)
        assertEquals("refreshed", ok.source)
        assertEquals(listOf("wss://moved.rowan.example"), ok.relays)
        assertEquals(setOf("wss://moved.rowan.example"), book.circleRelays())
        assertEquals(now, book.list().single().boxes[0].refreshedAt)
    }

    @Test fun `a card with no box adds a contact and no sheltered relay`() {
        val book = ContactBook(MemoryStorage())
        val added = book.add(s("cardNoBox"), now) as ContactBook.Added.Ok
        assertEquals(0, added.contact.boxes.size)
        assertEquals(emptySet(), book.circleRelays())
        assertNotNull(book.get(s("rowan")))
    }

    @Test fun `an expired card is refused at the step the draft names`() {
        val book = ContactBook(MemoryStorage())
        val r = book.add(s("cardSerial1"), now + 8 * 86400)
        assertTrue(r is ContactBook.Added.Refused && r.step == 3)
    }

    @Test fun `a corrupt entry is dropped rather than trusted`() {
        val disk = MemoryStorage()
        val book = ContactBook(disk)
        book.add(s("cardSerial1"), now)
        val text = disk.value!!.decodeToString()
        // A node id that is not one: the whole contact goes, because it decides which relays are shown as sheltered.
        disk.value = text.replace("\"nodeId\":\"", "\"nodeId\":\"zz").encodeToByteArray()
        assertEquals(emptyList(), ContactBook(disk).list())
        assertEquals(emptySet(), ContactBook(disk).circleRelays())
        // Not JSON at all: unavailable, said so, nothing minted over it.
        disk.value = "not json".encodeToByteArray()
        assertTrue(runCatching { ContactBook(disk).list() }.exceptionOrNull() is RoomStorageException)
    }

    @Test fun `the rendezvous secret is made once and forgotten on rotation`() {
        val disk = MemoryStorage()
        val book = ContactBook(disk)
        val first = book.rendezvousSecret()
        assertEquals(32, first.size)
        assertTrue(first.contentEquals(book.rendezvousSecret()))
        assertTrue(first.contentEquals(ContactBook(disk).rendezvousSecret()))
        book.rotateRendezvous()
        assertFalse(first.contentEquals(book.rendezvousSecret()))
        // The secret never sits beside a contact's fields as anything but itself.
        assertNull(book.get("zz"))
    }

    @Test fun `relay URLs compare as the lane check compares them`() {
        assertEquals("wss://box.rowan.example", ContactBook.normalise("wss://BOX.rowan.example/"))
        assertEquals("wss://box.rowan.example:8443/r", ContactBook.normalise("wss://box.rowan.example:8443/r/"))
        assertNull(ContactBook.normalise("not a url"))
    }
}
