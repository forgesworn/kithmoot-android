package dev.forgesworn.kithmoot.service

import dev.forgesworn.kithmoot.protocol.DecodeDeliveryReceiptOptions
import dev.forgesworn.kithmoot.protocol.DeliveryReceiptStream
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.KIND_DELIVERY_RECEIPT
import dev.forgesworn.kithmoot.protocol.KindredTier
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RoomPolicy
import dev.forgesworn.kithmoot.protocol.decodeDeliveryReceipt
import dev.forgesworn.kithmoot.protocol.deliveryReceiptIdHash
import dev.forgesworn.kithmoot.session.ChatMessage
import dev.forgesworn.kithmoot.session.ChatReaction
import dev.forgesworn.kithmoot.session.DeliveryReceiptPublisher
import dev.forgesworn.kithmoot.session.DeliveryReceiptWriter
import dev.forgesworn.kithmoot.session.Fixtures
import dev.forgesworn.kithmoot.session.KIND_CHAT
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import dev.forgesworn.kithmoot.session.RoomIdentity
import dev.forgesworn.kithmoot.storage.HeadlessSigning
import dev.forgesworn.kithmoot.storage.RoomStorage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the closed-app listener confirms, and when: a message from somebody else that reaches this phone while
 * KithMoot is closed gets one delivery receipt per stream, signed by this device with a credential it already
 * holds, and nothing is sent where the person, the room or the credential says no.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundReceiptsTest {
    private class MemoryStorage : RoomStorage {
        var bytes: ByteArray? = null
        override fun read(): ByteArray? = bytes?.copyOf()
        override fun write(value: ByteArray) { bytes = value.copyOf() }
        override fun reset() { bytes = null }
    }

    private val room = Fixtures.room()
    private val ann = Fixtures.primary(room, 1, 2)
    private val ben = Fixtures.primary(room, 40, 41)
    private val stream = DeliveryReceiptStream(room.roomId, room.roomKey)
    private val start = 10_000_000L

    private fun TestScope.nowMs() = start + testScheduler.currentTime
    private fun TestScope.nowS() = nowMs() / 1000

    private fun chatEvent(): NostrEvent =
        Events.sign(Fixtures.key(9), KIND_CHAT, 1, listOf(listOf("d", room.roomId)), "sealed")

    private fun message(id: String, sentAt: Long, from: RoomIdentity = ben, reaction: ChatReaction? = null) =
        ChatMessage(id = id, participant = from.participant, device = from.devicePubkey, body = "hello", sentAt = sentAt, reaction = reaction)

    /** A fresh copy of the key every time, as `SavedRoom.headlessSigning` gives: the receipts zero what they are handed. */
    private fun held(identity: PrimaryIdentity = ann) = HeadlessSigning(identity.participant, identity.credential, identity.deviceSecretKey.copyOf())

    private class Harness(val receipts: BackgroundReceipts, val sent: MutableList<NostrEvent>) {
        var enabled = true
        var open = false
        var accepts = true
        var signs: () -> HeadlessSigning? = { null }
        var signingReads = 0
    }

    /** One watch on [room] as Ann, with whatever she holds in [signs]; [sent] is everything it published. */
    private fun TestScope.harness(
        storage: RoomStorage? = MemoryStorage(),
        policy: RoomPolicy? = null,
        participant: String = ann.participant,
        device: String = ann.devicePubkey,
        signs: () -> HeadlessSigning? = { held() },
    ): Harness {
        val sent = mutableListOf<NostrEvent>()
        lateinit var h: Harness
        val receipts = BackgroundReceipts(
            roomId = room.roomId, participant = participant, device = device, policy = policy, streams = listOf(stream),
            acks = storage, scope = backgroundScope,
            enabled = { h.enabled }, roomOpen = { h.open },
            signing = { h.signingReads++; h.signs() },
            publish = { event -> if (h.accepts) { sent += event; true } else false },
            clock = { nowMs() }, context = EmptyCoroutineContext, random = Random(5),
        )
        h = Harness(receipts, sent).also { it.signs = signs }
        receipts.preload()
        return h
    }

    private fun TestScope.settle() { advanceTimeBy(1_100); runCurrent() }

    private fun TestScope.decode(event: NostrEvent, policy: RoomPolicy? = null) =
        assertNotNull(decodeDeliveryReceipt(event, DecodeDeliveryReceiptOptions(room.roomId, listOf(stream), nowS(), policy)))

    @Test fun `a message from another person is confirmed with the credential this device holds`() = runTest {
        val h = harness()
        h.receipts.offer(chatEvent(), message("m1", nowS()))
        runCurrent()
        assertTrue(h.sent.isEmpty(), "nothing goes before the burst has settled")
        settle()
        val event = h.sent.single()
        assertEquals(KIND_DELIVERY_RECEIPT, event.kind)
        assertEquals(room.roomId, event.tagValue("d"))
        // What a reader sees: this person, this device, this message, and an outer key that is neither.
        val receipt = decode(event)
        assertEquals(ann.participant, receipt.participant)
        assertEquals(ann.devicePubkey, receipt.device)
        assertEquals(listOf(deliveryReceiptIdHash("m1")), receipt.got)
        assertFalse(receipt.agent)
        assertTrue(event.pubkey != ann.devicePubkey && event.pubkey != ann.participant)
    }

    @Test fun `a burst of messages shares one receipt, after a short settle and no random wait`() = runTest {
        val h = harness()
        repeat(3) { h.receipts.offer(chatEvent(), message("m$it", nowS())) }
        advanceTimeBy(999); runCurrent()
        assertTrue(h.sent.isEmpty())
        advanceTimeBy(2); runCurrent()
        assertEquals(setOf(deliveryReceiptIdHash("m0"), deliveryReceiptIdHash("m1"), deliveryReceiptIdHash("m2")), decode(h.sent.single()).got.toSet())
    }

    @Test fun `nothing is sent for this person's own messages, on any of their devices, or for rows that are not messages`() = runTest {
        val h = harness()
        h.receipts.offer(chatEvent(), message("mine", nowS(), from = ann))
        h.receipts.offer(chatEvent(), message("other-device", nowS(), from = Fixtures.secondary(room, ann, 3)))
        h.receipts.offer(chatEvent(), message("reaction", nowS(), reaction = ChatReaction("m", ben.participant, "👍", true, 1)))
        assertEquals(0, h.receipts.waiting())
        settle()
        assertTrue(h.sent.isEmpty())
        assertEquals(0, h.signingReads, "the saved room is not read for messages that could never be confirmed")
    }

    @Test fun `nothing is sent with the setting off, and turning it off while one waits drops it`() = runTest {
        val h = harness()
        h.enabled = false
        h.receipts.offer(chatEvent(), message("m1", nowS()))
        assertEquals(0, h.receipts.waiting())
        h.enabled = true
        h.receipts.offer(chatEvent(), message("m2", nowS()))
        assertEquals(1, h.receipts.waiting())
        h.enabled = false
        settle(); advanceTimeBy(60_000); runCurrent()
        assertTrue(h.sent.isEmpty())
        assertEquals(0, h.receipts.waiting())
    }

    @Test fun `a room open in the app confirms for itself, even when it opens while a receipt waits`() = runTest {
        val h = harness()
        h.open = true
        h.receipts.offer(chatEvent(), message("m1", nowS()))
        assertEquals(0, h.receipts.waiting())
        h.open = false
        h.receipts.offer(chatEvent(), message("m2", nowS()))
        h.open = true
        settle(); advanceTimeBy(120_000); runCurrent()
        assertTrue(h.sent.isEmpty())
        // No timer tries again by itself: the open room confirms it, since it was never acknowledged.
        assertEquals(0, h.receipts.waiting())
    }

    @Test fun `a quiet room, and a room that admits nobody without a proof, are never confirmed`() = runTest {
        val quiet = harness(policy = RoomPolicy(KindredTier.OPEN, members = listOf(ann.participant, ben.participant), quiet = true))
        quiet.receipts.offer(chatEvent(), message("m1", nowS()))
        val closed = harness(policy = RoomPolicy(KindredTier.KEN, admitted = listOf("cc".repeat(32))))
        closed.receipts.offer(chatEvent(), message("m1", nowS()))
        settle()
        assertTrue(quiet.sent.isEmpty() && closed.sent.isEmpty())
        assertEquals(0, quiet.receipts.waiting() + closed.receipts.waiting())
    }

    @Test fun `a private conversation is confirmed for its members and nobody else`() = runTest {
        val policy = RoomPolicy(KindredTier.OPEN, members = listOf(ann.participant, ben.participant))
        val members = harness(policy = policy)
        members.receipts.offer(chatEvent(), message("m1", nowS()))
        val stranger = Fixtures.primary(room, 90, 91)
        val outsider = harness(policy = policy, participant = stranger.participant, device = stranger.devicePubkey, signs = { held(stranger) })
        outsider.receipts.offer(chatEvent(), message("m1", nowS()))
        settle()
        assertEquals(1, members.sent.size)
        decode(members.sent.single(), policy)
        assertTrue(outsider.sent.isEmpty())
    }

    @Test fun `no credential valid now means nothing is offered`() = runTest {
        // Valid when the message arrives, lapsed a second later when the receipt is signed: refused then.
        val short = PrimaryIdentity.create(room.roomId, 10_001, 0, Fixtures.key(1), Fixtures.key(2))
        val h = harness(signs = { held(short) })
        h.receipts.offer(chatEvent(), message("m1", nowS()))
        assertEquals(1, h.receipts.waiting())
        settle()
        assertTrue(h.sent.isEmpty())
        // A message that arrives once it has lapsed is not even queued.
        advanceTimeBy(60_000); runCurrent()
        h.receipts.offer(chatEvent(), message("m2", nowS()))
        assertEquals(0, h.receipts.waiting())
        // Nothing to sign with at all, as for an account whose credential was never kept.
        val none = harness(signs = { null })
        none.receipts.offer(chatEvent(), message("m1", nowS()))
        assertEquals(0, none.receipts.waiting())
        settle()
        assertTrue(none.sent.isEmpty() && h.sent.isEmpty())
    }

    @Test fun `a credential that names another device or another person is refused`() = runTest {
        val otherDevice = Fixtures.primary(room, 1, 3)
        val wrongDevice = harness(signs = { held(otherDevice) })
        wrongDevice.receipts.offer(chatEvent(), message("m1", nowS()))
        // The credential is Ann's and valid, but the key in hand is not the device the room was saved with.
        val wrongKey = harness(signs = { HeadlessSigning(ann.participant, ann.credential, Fixtures.key(3).copyOf()) })
        wrongKey.receipts.offer(chatEvent(), message("m1", nowS()))
        // Ben's credential and key under Ann's saved room.
        val wrongPerson = harness(signs = { held(ben) })
        wrongPerson.receipts.offer(chatEvent(), message("m1", nowS()))
        settle()
        assertEquals(0, wrongDevice.receipts.waiting() + wrongKey.receipts.waiting() + wrongPerson.receipts.waiting())
        assertTrue(wrongDevice.sent.isEmpty() && wrongKey.sent.isEmpty() && wrongPerson.sent.isEmpty())
    }

    @Test fun `what this device acknowledged is shared, so a second writer on the same file confirms nothing twice`() = runTest {
        val storage = MemoryStorage()
        val first = harness(storage)
        first.receipts.offer(chatEvent(), message("m1", nowS()))
        settle()
        assertEquals(1, first.sent.size)
        // The watch is rebuilt (midnight, a rekey): its writer starts from the same file.
        val rebuilt = harness(storage)
        rebuilt.receipts.offer(chatEvent(), message("m1", nowS()))
        assertEquals(0, rebuilt.receipts.waiting())
        // The open room's writer reads it too, and confirms the messages it has not, not the ones this did.
        val foreground = DeliveryReceiptWriter(storage, backgroundScope, EmptyCoroutineContext, now = { nowMs() }, random = Random(11))
        val calls = mutableListOf<List<String>>()
        val publisher = DeliveryReceiptPublisher { _, ids, _, _ -> calls += ids; true }
        foreground.received(message("m1", nowS()), room.roomId, ann.participant, publisher)
        foreground.received(message("m2", nowS()), room.roomId, ann.participant, publisher)
        assertEquals(1, foreground.waiting())
        advanceTimeBy(7_000); runCurrent()
        assertEquals(listOf(listOf("m2")), calls)
        // And the other way round: what the open room acknowledged is not confirmed again from the background.
        val later = harness(storage)
        later.receipts.offer(chatEvent(), message("m2", nowS()))
        assertEquals(0, later.receipts.waiting())
    }

    @Test fun `a message from before this device first saw the room, or over a week old, is not confirmed`() = runTest {
        val h = harness()
        h.receipts.offer(chatEvent(), message("history", nowS() - 3_600))
        h.receipts.offer(chatEvent(), message("slack", nowS() - 60))
        assertEquals(1, h.receipts.waiting())
        val week = harness()
        advanceTimeBy(8L * 86_400_000); runCurrent()
        week.receipts.offer(chatEvent(), message("ancient", nowS() - 8L * 86_400))
        assertEquals(0, week.receipts.waiting())
    }

    @Test fun `a receipt a relay did not take is not tried again by a timer, and goes with the message when it is next received`() = runTest {
        val h = harness()
        h.accepts = false
        h.receipts.offer(chatEvent(), message("m1", nowS()))
        settle()
        advanceTimeBy(300_000); runCurrent()
        assertTrue(h.sent.isEmpty())
        assertEquals(0, h.receipts.waiting())
        // The relay is back, and the room's recent chat is read again after a reconnect.
        h.accepts = true
        h.receipts.offer(chatEvent(), message("m1", nowS()))
        settle()
        assertEquals(listOf(deliveryReceiptIdHash("m1")), decode(h.sent.single()).got)
    }

    @Test fun `receipts keep to one every 30 seconds for a stream`() = runTest {
        val h = harness()
        h.receipts.offer(chatEvent(), message("m1", nowS()))
        settle()
        val first = h.sent.single().createdAt
        h.receipts.offer(chatEvent(), message("m2", nowS()))
        settle()
        assertEquals(1, h.sent.size)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(2, h.sent.size)
        assertTrue(h.sent[1].createdAt - first >= 30)
    }

    @Test fun `the saved room is read once for a burst, not for every message`() = runTest {
        val h = harness()
        repeat(20) { h.receipts.offer(chatEvent(), message("m$it", nowS())) }
        assertEquals(1, h.signingReads)
    }

    @Test fun `cancelling the watch drops what waits and sends nothing more`() = runTest {
        val h = harness()
        h.receipts.offer(chatEvent(), message("m1", nowS()))
        h.receipts.cancel()
        settle(); advanceTimeBy(60_000); runCurrent()
        assertTrue(h.sent.isEmpty())
        assertEquals(0, h.receipts.waiting())
    }

    @Test fun `the closed-app listener asks for no receipts`() {
        val filters = feedFilters(listOf(FeedInterest("room", bellTags = setOf("a"), chatTags = setOf(room.roomId), chatSince = 5,
            rekeyAuthor = "b".repeat(64), rekeyTag = "room")), now = 1_000)
        assertTrue(filters.isNotEmpty())
        assertTrue(filters.none { KIND_DELIVERY_RECEIPT in (it.kinds ?: emptyList()) })
        assertTrue(backgroundChatFilter(room.roomId, 0, 1_000).kinds?.contains(KIND_DELIVERY_RECEIPT) != true)
    }
}
