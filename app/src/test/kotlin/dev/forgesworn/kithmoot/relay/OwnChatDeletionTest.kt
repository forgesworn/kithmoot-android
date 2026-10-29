package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.DeliveryReceiptStream
import dev.forgesworn.kithmoot.protocol.EncodeDeliveryReceiptOptions
import dev.forgesworn.kithmoot.protocol.EpochKeys
import dev.forgesworn.kithmoot.protocol.KIND_DELIVERY_RECEIPT
import dev.forgesworn.kithmoot.protocol.deliveryReceiptOuterSecretKey
import dev.forgesworn.kithmoot.protocol.encodeDeliveryReceipt
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RoomEpoch
import dev.forgesworn.kithmoot.protocol.deriveEpoch
import dev.forgesworn.kithmoot.session.CONTROL_CHANNEL
import dev.forgesworn.kithmoot.session.ChatEvents
import dev.forgesworn.kithmoot.session.Fixtures
import dev.forgesworn.kithmoot.session.KIND_CHAT
import dev.forgesworn.kithmoot.session.chatStreamsOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Taking back a device's own chat envelope 2 messages when its room self-destructs. Each is signed by
 * an outer key made for it alone, so the author query finds none; the room's chat streams are read
 * and one kind 5 per message the device wrote goes out under that message's outer key.
 */
class OwnChatDeletionTest {
    private val room = Fixtures.room()
    private val me = Fixtures.primary(room, 1, 2)
    private val other = Fixtures.primary(room, 40, 41)
    private val epoch = deriveEpoch(RoomEpoch(1, ByteArray(32) { (90 + it).toByte() }))
    private val main = ChatEvents.Stream(room.roomId, room.roomKey)
    private val control = ChatEvents.Stream(room.roomId, room.roomKey, CONTROL_CHANNEL)
    private val epochMain = ChatEvents.Stream(epoch.id, epoch.key)
    private val unnamed = ChatEvents.Stream(room.roomId, room.roomKey, "somewhere-else")
    private val now = 1_800_000_000L
    private val streams get() = chatStreamsOf(listOf(EpochKeys(0, room.roomId, room.roomKey), epoch))

    /** A relay that answers by author, stream, kind and time, and honours a deletion only from the event's own author (NIP-09). */
    private class Relay(val held: MutableList<NostrEvent>) : RoomTransport {
        val published = mutableListOf<NostrEvent>()
        val filters = mutableListOf<Filter>()
        var accept = true
        var failStreamQueries = false
        override fun publish(event: NostrEvent) = error("deletions must be confirmed")
        override fun subscribe(filters: List<Filter>): Flow<NostrEvent> = emptyFlow()
        override suspend fun queryAvailable(filters: List<Filter>, timeoutMs: Long): List<NostrEvent> {
            this.filters += filters
            val f = filters.single()
            if (failStreamQueries && "#d" in f.tags) error("relay closed")
            return held.filter { e ->
                (f.authors == null || e.pubkey in f.authors) && (f.kinds == null || e.kind in f.kinds) &&
                    f.tags.all { (name, values) -> e.tags.any { it.firstOrNull() == name.removePrefix("#") && it.getOrNull(1) in values } } &&
                    (f.until == null || e.createdAt <= f.until)
            }.sortedByDescending { it.createdAt }.take(f.limit ?: Int.MAX_VALUE)
        }
        override suspend fun publishConfirmed(event: NostrEvent, timeoutMs: Long): Boolean {
            published += event
            if (!accept) return false
            val gone = event.tags.filter { it[0] == "e" }.map { it[1] }.toSet()
            held.removeAll { it.id in gone && it.pubkey == event.pubkey }
            held += event
            return true
        }
    }

    private fun kind5s(relay: Relay) = relay.published.filter { it.kind == 5 }
    private fun named(request: NostrEvent) = request.tags.filter { it[0] == "e" }.map { it[1] }

    @Test fun `one request goes out per message this device wrote, each under that message's outer key`() = runTest {
        val a = ChatEvents.v2(room, me, main, "a", sentAt = now - 50)
        val b = ChatEvents.v2(room, me, control, "b", sentAt = now - 40)
        val c = ChatEvents.v2(room, me, epochMain, "c", sentAt = now - 30)
        val v1 = ChatEvents.v1(room, me, main, "old", sentAt = now - 20)
        val theirs = ChatEvents.v2(room, other, main, "theirs", sentAt = now - 10)
        val forged = ChatEvents.v2(room, me, main, "forged", sentAt = now - 9, outerSk = Entropy.bytes(32))
        val notGiven = ChatEvents.v2(room, me, unnamed, "unnamed", sentAt = now - 8)
        val relay = Relay(mutableListOf(a, b, c, v1, theirs, forged, notGiven))

        val report = Nip09Deletion.deleteOwnEvents(relay, me.deviceSecretKey, { now }, streams = streams)

        // The author query takes the envelope 1 line under the device key; each envelope 2 message goes alone under its own.
        val requests = kind5s(relay)
        assertEquals(4, requests.size)
        val byAuthor = requests.associateBy { it.pubkey }
        assertEquals(listOf(v1.id), named(byAuthor.getValue(me.devicePubkey)))
        for ((own, messageId, stream) in listOf(Triple(a, "a", main), Triple(b, "b", control), Triple(c, "c", epochMain))) {
            val request = byAuthor.getValue(own.pubkey)
            assertEquals(listOf(listOf("e", own.id), listOf("k", "1460")), request.tags)
            assertEquals(now, request.createdAt)
            assertEquals(Nip09Deletion.CONTENT, request.content)
            assertTrue(Events.verify(request))
            assertEquals(Schnorr.publicKeyHex(ChatEvents.outerKey(me.deviceSecretKey, stream.id, messageId)), request.pubkey)
        }
        assertEquals(setOf(a.pubkey, b.pubkey, c.pubkey, me.devicePubkey), byAuthor.keys)
        // Nothing of anybody else's, no forgery and nothing outside the streams given is named.
        assertTrue(requests.flatMap(::named).none { it in setOf(theirs.id, forged.id, notGiven.id) })
        assertEquals(setOf(theirs.id, forged.id, notGiven.id), relay.held.filter { it.kind == KIND_CHAT }.map { it.id }.toSet())
        assertEquals(Nip09Deletion.Report(found = 4, requested = 4, failed = 0, complete = true, reached = true), report)
    }

    @Test fun `without streams only the author query runs, as before`() = runTest {
        val own = ChatEvents.v2(room, me, main, "a", sentAt = now - 50)
        val v1 = ChatEvents.v1(room, me, main, "old", sentAt = now - 20)
        val relay = Relay(mutableListOf(own, v1))
        val report = Nip09Deletion.deleteOwnEvents(relay, me.deviceSecretKey, { now })
        assertTrue(relay.filters.all { it.authors == listOf(me.devicePubkey) })
        assertEquals(listOf(listOf(v1.id)), kind5s(relay).map(::named))
        assertEquals(1, report.requested)
        assertTrue(own in relay.held)
    }

    @Test fun `a refusal is counted against the message, and each message is still asked for`() = runTest {
        val a = ChatEvents.v2(room, me, main, "a", sentAt = now - 50)
        val b = ChatEvents.v2(room, me, main, "b", sentAt = now - 40)
        val relay = Relay(mutableListOf(a, b)).also { it.accept = false }
        val report = Nip09Deletion.deleteOwnEvents(relay, me.deviceSecretKey, { now }, streams = streams)
        assertEquals(2, kind5s(relay).size)
        assertEquals(Nip09Deletion.Report(found = 2, requested = 0, failed = 2, complete = false, reached = true), report)
        assertTrue(relay.held.containsAll(listOf(a, b)))
    }

    @Test fun `a stream that cannot be read leaves the author pass done and the run incomplete`() = runTest {
        val v1 = ChatEvents.v1(room, me, main, "old", sentAt = now - 20)
        val relay = Relay(mutableListOf(v1, ChatEvents.v2(room, me, main, "a", sentAt = now - 50))).also { it.failStreamQueries = true }
        val report = Nip09Deletion.deleteOwnEvents(relay, me.deviceSecretKey, { now }, streams = streams)
        assertEquals(Nip09Deletion.Report(found = 1, requested = 1, failed = 0, complete = false, reached = true), report)
    }

    @Test fun `a full page of other members' messages is paged back past, and an older message of this device is still found`() = runTest {
        val noise = Entropy.bytes(32)
        val others = (0 until Nip09Deletion.QUERY_LIMIT + 200).map { Events.sign(noise, KIND_CHAT, now - 1_000 + it, listOf(listOf("d", main.id)), "x") }
        val old = ChatEvents.v2(room, me, main, "old", sentAt = now - 5_000)
        val older = ChatEvents.v2(room, me, main, "older", sentAt = now - 6_000)
        val relay = Relay((others + old + older).toMutableList())

        val report = Nip09Deletion.deleteOwnEvents(relay, me.deviceSecretKey, { now }, streams = streams)

        assertEquals(setOf(old.id, older.id), kind5s(relay).flatMap(::named).toSet())
        assertEquals(2, report.requested)
        assertTrue(report.complete)
        val reads = relay.filters.filter { "#d" in it.tags }
        assertEquals(2, reads.size, "a short second page stops the run")
        assertEquals(null, reads[0].until)
        assertEquals(others.minOf { it.createdAt } + 200, reads[1].until, "back from the oldest of the first page")
        assertTrue(reads.all { it.kinds == listOf(KIND_CHAT, KIND_DELIVERY_RECEIPT) && it.limit == Nip09Deletion.QUERY_LIMIT })
    }

    @Test fun `streams are asked for twenty to a filter`() = runTest {
        val many = chatStreamsOf(listOf(EpochKeys(0, room.roomId, room.roomKey)), (0 until 45).map { "channel-$it" })
        val relay = Relay(mutableListOf())
        val report = Nip09Deletion.deleteOwnEvents(relay, me.deviceSecretKey, { now }, streams = many)
        assertEquals(listOf(20, 20, 5), relay.filters.filter { "#d" in it.tags }.map { it.tags.getValue("#d").size })
        assertEquals(Nip09Deletion.Report(complete = true, reached = true), report)
        assertFalse(kind5s(relay).isNotEmpty())
    }

    private fun receipt(who: dev.forgesworn.kithmoot.session.PrimaryIdentity, stream: ChatEvents.Stream, createdAt: Long, index: Int = 0) =
        encodeDeliveryReceipt(EncodeDeliveryReceiptOptions(roomId = room.roomId, stream = DeliveryReceiptStream(stream.id, stream.key),
            deviceSecretKey = who.deviceSecretKey, credential = who.credential, messageIds = listOf("m-$createdAt"),
            createdAt = createdAt, index = index))

    @Test fun `this device's delivery receipts go too, each under its own derived key, and nobody else's`() = runTest {
        val mine = receipt(me, main, 50_000)
        val split = receipt(me, epochMain, 50_100, index = 1)
        val theirs = receipt(other, main, 50_200)
        val elsewhere = receipt(me, unnamed, 50_300)
        val relay = Relay(mutableListOf(mine, split, theirs, elsewhere))

        val report = Nip09Deletion.deleteOwnEvents(relay, me.deviceSecretKey, { now }, streams = streams)

        val requests = kind5s(relay)
        assertEquals(setOf(mine.id, split.id), requests.flatMap(::named).toSet())
        for ((receipt, stream, index) in listOf(Triple(mine, main, 0), Triple(split, epochMain, 1))) {
            val request = requests.single { named(it) == listOf(receipt.id) }
            assertEquals(listOf(listOf("e", receipt.id), listOf("k", "1465")), request.tags)
            assertTrue(Events.verify(request))
            assertEquals(Schnorr.publicKeyHex(deliveryReceiptOuterSecretKey(me.deviceSecretKey, stream.id, receipt.createdAt, index)), request.pubkey)
        }
        assertTrue(theirs in relay.held && elsewhere in relay.held)
        assertEquals(Nip09Deletion.Report(found = 2, requested = 2, failed = 0, complete = true, reached = true), report)
    }
}
