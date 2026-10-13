package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.protocol.KindredTier
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RoomDrops
import dev.forgesworn.kithmoot.protocol.RoomPolicy
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.HybridRoomTransport
import dev.forgesworn.kithmoot.relay.RoomTransport
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Two people in a quiet room over one relay: what the relay holds is gift
 * wraps and nothing of the chat; what the other person reads is the chat.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuietTransportTest {

    private val room = Fixtures.room()
    private val ada = Fixtures.primary(room, 1, 2)
    private val rowan = Fixtures.primary(room, 3, 4)
    private val policy = RoomPolicy(KindredTier.OPEN, null, listOf(ada.participant, rowan.participant), quiet = true)
    private var clock = 498216L * 3600 + 10

    private fun TestScope.quiet(relay: FakeRelay, who: PrimaryIdentity, slot: Int = 0, restore: QuietTransport.QuietState? = null, onState: (QuietTransport.QuietState) -> Unit = {}, key: ByteArray = room.roomKey) =
        QuietTransport(relay.transport(), key, who.participant, policy.members!!, slot, backgroundScope, intervalSeconds = 60, now = { clock }, restore = restore, onState = onState, ticking = false, slotOffset = { 0 })

    private fun chat(from: PrimaryIdentity, text: String) = encodeChatEvent(
        body = text, participant = from.participant, credential = from.credential, roomId = room.roomId,
        roomKey = room.roomKey, deviceSecretKey = from.deviceSecretKey, sentAt = clock,
    )

    @Test fun `trusted relay lane cannot confirm an unseen quiet event`() = runTest {
        val relay = FakeRelay()
        val sender = quiet(relay, ada)
        val reader = quiet(relay, rowan)
        backgroundScope.launch { reader.subscribe(listOf(Filter(kinds = listOf(KIND_CHAT)))).collect {} }
        runCurrent()
        val message = chat(ada, "retain until actually received")
        assertTrue(relay.transport().receivedEventConfirmsPublication(message.id))
        assertFalse(reader.receivedEventConfirmsPublication(message.id))
        sender.publish(message)
        assertFalse(reader.receivedEventConfirmsPublication(message.id))
        sender.tick(); runCurrent()
        assertTrue(reader.receivedEventConfirmsPublication(message.id))
        sender.stop(); reader.stop()
        assertFalse(reader.receivedEventConfirmsPublication(message.id))
    }

    @Test fun `guarded node send queues and retains uncertainty until its quiet wrap arrives`() = runTest {
        val relay = FakeRelay()
        val reader = quiet(relay, rowan)
        val sender = quiet(relay, ada)
        backgroundScope.launch { reader.subscribe(listOf(Filter(kinds = listOf(KIND_CHAT)))).collect {} }
        runCurrent()
        val epoch = clock / 3600
        val lease = dev.forgesworn.kithmoot.cadence.StoredCadenceLease(
            dev.forgesworn.kithmoot.cadence.CadenceLeasePlan("a".repeat(52), room.roomId, room.roomId,
                1, rowan.devicePubkey, "22".repeat(16), 1, "11".repeat(16), "{}", epoch, epoch + 2, 0, 8),
            dev.forgesworn.kithmoot.cadence.CadenceOwnership.BOX_OWNED, null, clock)
        var queued = 0
        var confirmed = 0
        val handoff = object : dev.forgesworn.kithmoot.cadence.GuardedCadenceHandoff {
            override fun queue(lease: dev.forgesworn.kithmoot.cadence.StoredCadenceLease,
                event: NostrEvent, publishBefore: Long, stillAllowed: () -> Boolean): java.util.concurrent.CompletableFuture<Unit> {
                assertTrue(stillAllowed()); queued++
                return java.util.concurrent.CompletableFuture.completedFuture(Unit)
            }
            override fun confirmed(event: NostrEvent) { confirmed++ }
            override fun revoked(event: NostrEvent) = error("authority was not revoked")
        }
        val transport = dev.forgesworn.kithmoot.cadence.CadenceRoomTransport(reader, backgroundScope,
            leaseAt = { lease }, retain = { error("ordinary queue") },
            queue = { _, _ -> error("ordinary queue") }, release = { error("premature release") },
            ownershipGeneration = { 0 }, now = { clock }, guardedHandoff = handoff)
        val message = chat(ada, "node ACK alone must retain draft")
        assertFailsWith<dev.forgesworn.kithmoot.relay.PublicationUnconfirmedException> {
            transport.publishConfirmedBounded(message, transport.publicationGeneration(), clock + 60, { true }, 100)
        }
        assertEquals(1, queued)
        assertEquals(0, confirmed)
        sender.publish(message); sender.tick(); runCurrent()
        assertTrue(transport.publishConfirmedBounded(message, transport.publicationGeneration(), clock + 60, { true }, 100))
        assertEquals(1, queued, "Reconciliation must not offer a second publication")
        assertEquals(1, confirmed)
        sender.stop(); reader.stop()
    }

    @Test fun `stored drops arriving before the chat collector remain readable without another publication`() = runTest {
        val relay = FakeRelay().apply { replays = true }
        val message = dropUnder(relay, ada, room.roomKey, "recording sent before restart")
        val published = relay.published.map { it.id }
        val reader = quiet(relay, rowan)
        val flow = reader.subscribe(listOf(Filter(kinds = listOf(KIND_CHAT))))
        // The outer stored stream can answer before RoomSession starts its
        // chat collector. Receiving the same wrap later must not be required.
        runCurrent()
        val seen = mutableListOf<NostrEvent>()
        backgroundScope.launch { flow.collect { seen += it } }
        runCurrent()
        assertEquals(listOf(message), seen)
        assertEquals(published, relay.published.map { it.id })
        reader.stop()
        val afterStop = mutableListOf<NostrEvent>()
        backgroundScope.launch { flow.collect { afterStop += it } }
        runCurrent()
        assertTrue(afterStop.isEmpty(), "Closing the room must clear its retained plaintext replay")
    }

    @Test fun `a drop opening across room stop cannot refill plaintext replay or delivery proof`() = runTest {
        val relay = FakeRelay()
        val message = dropUnder(relay, ada, room.roomKey, "late authenticated recording")
        val wrap = relay.published.single()
        var stopDuringReceive = false
        lateinit var reader: QuietTransport
        reader = QuietTransport(relay.transport(), room.roomKey, rowan.participant, policy.members!!,
            0, backgroundScope, ticking = false, slotOffset = { 0 }, now = {
                if (stopDuringReceive) { stopDuringReceive = false; reader.stop() }
                clock
            })
        val flow = reader.subscribe(listOf(Filter(kinds = listOf(KIND_CHAT))))
        val seen = mutableListOf<NostrEvent>()
        backgroundScope.launch { flow.collect { seen += it } }
        runCurrent()
        // Stop after receive's first lifecycle check, before decryption and
        // replay retention. This models a room close racing an ingress callback.
        stopDuringReceive = true
        relay.publish(wrap); runCurrent()
        assertTrue(seen.isEmpty())
        assertFalse(reader.receivedEventConfirmsPublication(message.id))
        val afterStop = mutableListOf<NostrEvent>()
        backgroundScope.launch { flow.collect { afterStop += it } }
        runCurrent()
        assertTrue(afterStop.isEmpty())
    }

    private fun TestScope.hybridReader(mesh: FakeRelay, relay: FakeRelay): Pair<QuietTransport, HybridRoomTransport> {
        val internet = object : RoomTransport by relay.transport() {
            override fun receivedViaRelays(eventId: String) = listOf("wss://fixture.invalid/")
        }
        val hybrid = HybridRoomTransport(mesh.transport(), internet) { clock }
        return QuietTransport(hybrid, room.roomKey, rowan.participant, policy.members!!, 0,
            backgroundScope, intervalSeconds = 60, now = { clock }, ticking = false,
            slotOffset = { 0 }) to hybrid
    }

    @Test fun `unwrapped relay event retains the outer hybrid delivery evidence`() = runTest {
        val relay = FakeRelay()
        val sender = quiet(relay, ada)
        val (reader, hybrid) = hybridReader(FakeRelay(), relay)
        val seen = mutableListOf<NostrEvent>()
        backgroundScope.launch { reader.subscribe(listOf(Filter(kinds = listOf(KIND_CHAT)))).collect { seen += it } }
        runCurrent()
        val message = chat(ada, "relay delivery")
        sender.publish(message); sender.tick(); runCurrent()
        assertEquals(listOf(message), seen)
        assertFalse(hybrid.receivedEventConfirmsPublication(message.id))
        assertTrue(reader.receivedEventConfirmsPublication(message.id))
        assertEquals(listOf("wss://fixture.invalid/"), reader.receivedViaRelays(message.id))
        sender.stop(); reader.stop()
    }

    @Test fun `mesh first wrap gains relay evidence without rendering a second message`() = runTest {
        val mesh = FakeRelay(); val relay = FakeRelay()
        val sender = quiet(mesh, ada)
        val (reader, _) = hybridReader(mesh, relay)
        val seen = mutableListOf<NostrEvent>()
        backgroundScope.launch { reader.subscribe(listOf(Filter(kinds = listOf(KIND_CHAT)))).collect { seen += it } }
        runCurrent()
        val message = chat(ada, "mesh then relay")
        sender.publish(message); sender.tick(); runCurrent()
        assertEquals(listOf(message), seen)
        assertFalse(reader.receivedEventConfirmsPublication(message.id))
        assertTrue(reader.receivedViaRelays(message.id).isEmpty())
        relay.publish(mesh.published.single()); runCurrent()
        assertTrue(reader.receivedEventConfirmsPublication(message.id))
        assertEquals(listOf("wss://fixture.invalid/"), reader.receivedViaRelays(message.id))
        assertEquals(listOf(message), seen)
        sender.stop(); reader.stop()
    }

    @Test fun `a new authenticated wrap of a seen inner event can prove relay delivery`() = runTest {
        val mesh = FakeRelay(); val relay = FakeRelay()
        val sender = quiet(mesh, ada)
        val (reader, _) = hybridReader(mesh, relay)
        val seen = mutableListOf<NostrEvent>()
        backgroundScope.launch { reader.subscribe(listOf(Filter(kinds = listOf(KIND_CHAT)))).collect { seen += it } }
        runCurrent()
        val message = chat(ada, "same inner event")
        sender.publish(message); sender.tick(); runCurrent()
        assertFalse(reader.receivedEventConfirmsPublication(message.id))
        val original = mesh.published.single()
        clock += 60
        sender.publish(message); sender.tick(); runCurrent()
        val retry = mesh.published.last()
        assertTrue(original.id != retry.id)
        relay.publish(retry); runCurrent()
        assertTrue(reader.receivedEventConfirmsPublication(message.id))
        assertEquals(listOf("wss://fixture.invalid/"), reader.receivedViaRelays(message.id))
        assertEquals(listOf(message), seen)
        sender.stop(); reader.stop()
    }

    @Test
    fun `chat crosses as gift wraps and the other side reads it`() = runTest {
        val relay = FakeRelay()
        val a = quiet(relay, ada)
        val b = quiet(relay, rowan)
        val seen = mutableListOf<NostrEvent>()
        val collector = launch { b.subscribe(listOf(Filter(kinds = listOf(KIND_CHAT), tags = mapOf("#d" to listOf(room.roomId))))).collect { seen += it } }
        runCurrent()

        val message = chat(ada, "meet at the mill")
        a.publish(message)
        assertEquals(1, a.pending)
        // Nothing leaves before the slot's moment; then one wrap, and the chat is read.
        a.tick(); runCurrent()
        assertEquals(1, relay.countOfKind(RoomDrops.GIFT_WRAP_KIND))
        assertEquals(0, relay.countOfKind(KIND_CHAT))
        assertEquals(0, a.pending)
        assertEquals(listOf(message.id), seen.map { it.id })
        val wrap = relay.published.single { it.kind == RoomDrops.GIFT_WRAP_KIND }
        assertEquals(listOf("p"), wrap.tags.map { it[0] })
        assertTrue(wrap.pubkey != ada.devicePubkey && wrap.pubkey != ada.participant)

        // An empty slot posts a filler the same size; the reader shows nothing new.
        clock += 60
        a.tick(); runCurrent()
        val wraps = relay.published.filter { it.kind == RoomDrops.GIFT_WRAP_KIND }
        assertEquals(2, wraps.size)
        assertEquals(wraps[0].content.length, wraps[1].content.length)
        assertEquals(1, seen.size)

        // The relay replaying the wrap costs nothing and shows nothing twice.
        relay.publish(wrap); runCurrent()
        assertEquals(1, seen.size)
        collector.cancel(); a.stop(); b.stop()
    }

    @Test
    fun `plain kinds pass straight through, mixed filters are split`() = runTest {
        val relay = FakeRelay()
        val a = quiet(relay, ada)
        val roster = NostrEvent(20461, clock, listOf(listOf("d", room.roomId)), "x", "0".repeat(64), "1".repeat(64), "2".repeat(128))
        val seen = mutableListOf<Int>()
        val collector = launch { a.subscribe(listOf(Filter(kinds = listOf(KIND_CHAT, 20461), tags = mapOf("#d" to listOf(room.roomId))))).collect { seen += it.kind } }
        runCurrent()
        assertFalse(a.receivedEventConfirmsPublication(roster.id))
        a.publish(roster); runCurrent()
        assertTrue(a.receivedEventConfirmsPublication(roster.id))
        assertEquals(listOf(20461), seen)
        assertEquals(1, relay.countOfKind(20461))
        collector.cancel(); a.stop()
        assertFalse(a.receivedEventConfirmsPublication(roster.id))
    }

    @Test
    fun `a non-member and a third device read but cannot post`() = runTest {
        val relay = FakeRelay()
        val stranger = Fixtures.primary(room, 70, 71)
        val s = quiet(relay, stranger)
        assertFalse(s.canSend)
        assertFailsWith<IllegalStateException> { s.publish(chat(stranger, "hi")) }
        val third = quiet(relay, ada, slot = 2)
        assertFalse(third.canSend)
        assertFailsWith<IllegalStateException> { third.publish(chat(ada, "hi")) }
        assertTrue(quiet(relay, ada, slot = 1).canSend)
        s.stop(); third.stop()
    }

    @Test
    fun `counters are kept between visits and a waiting message survives a restart`() = runTest {
        val relay = FakeRelay()
        val states = mutableListOf<QuietTransport.QuietState>()
        val a = quiet(relay, ada, onState = { states += it })
        val m1 = chat(ada, "one")
        val m2 = chat(ada, "two")
        a.publish(m1); a.publish(m2)
        a.tick(); runCurrent()
        assertEquals(1, a.pending)
        val kept = states.last()
        assertEquals(listOf(m2.id), kept.queued.map { it.id })
        assertEquals(1, kept.used.getValue(ada.participant).counters.size)
        // A fresh transport from the kept state: one message waiting, one counter spent.
        val again = quiet(relay, ada, restore = kept)
        assertEquals(1, again.pending)
        assertEquals(kept.used.getValue(ada.participant).counters, again.exportState().used.getValue(ada.participant).counters)
        clock += 60
        again.tick(); runCurrent()
        assertEquals(0, again.pending)
        assertEquals(2, relay.countOfKind(RoomDrops.GIFT_WRAP_KIND))
        assertEquals(2, again.exportState().used.getValue(ada.participant).counters.size)
        a.stop(); again.stop()
    }

    @Test
    fun `eight messages an hour from one device, the ninth waits for the next epoch`() = runTest {
        val relay = FakeRelay()
        val a = quiet(relay, ada)
        repeat(9) { a.publish(chat(ada, "m$it")) }
        repeat(9) { a.tick(); runCurrent(); clock += 60 }
        assertEquals(1, a.pending)
        assertEquals(9, relay.countOfKind(RoomDrops.GIFT_WRAP_KIND)) // eight drops and one filler
        clock += 3600
        a.tick(); runCurrent()
        assertEquals(0, a.pending)
        a.stop()
    }

    @Test
    fun `a wrap the web library made is read here as its chat`() = runTest {
        // room-drop-web.json: a wrap nostr-deaddrop produced for the vector
        // test keys, its inner a kind 1460 signed by member A. A copy, never
        // an edit. The room key and members here are the fixture's.
        val fixture = kotlinx.serialization.json.Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/room-drop-web.json")).bufferedReader().use { it.readText() }).let { it as kotlinx.serialization.json.JsonObject }
        val roomKey = (fixture.getValue("roomKeyHex") as kotlinx.serialization.json.JsonPrimitive).content.hexToBytes()
        val member = (fixture.getValue("member") as kotlinx.serialization.json.JsonPrimitive).content
        val wrap = NostrEvent.fromJson(fixture.getValue("wrap"))
        val inner = NostrEvent.fromJson(fixture.getValue("inner"))
        clock = (fixture.getValue("now") as kotlinx.serialization.json.JsonPrimitive).content.toLong()
        val relay = FakeRelay()
        val reader = QuietTransport(relay.transport(), roomKey, rowan.participant, listOf(member, rowan.participant), 0, backgroundScope, intervalSeconds = 60, now = { clock }, ticking = false, slotOffset = { 0 })
        val seen = mutableListOf<NostrEvent>()
        val collector = launch { reader.subscribe(listOf(Filter(kinds = listOf(KIND_CHAT)))).collect { seen += it } }
        runCurrent()
        relay.publish(wrap); runCurrent()
        assertEquals(listOf(inner.id), seen.map { it.id })
        assertEquals(inner.content, seen.single().content)
        collector.cancel(); reader.stop()
    }

    @Test
    fun `a message too long for the bucket is refused to the caller`() = runTest {
        val relay = FakeRelay()
        val a = quiet(relay, ada)
        assertFailsWith<IllegalArgumentException> { a.publish(chat(ada, "x".repeat(2000)).let { it.copy(content = it.content + "y".repeat(6000)) }) }
        assertEquals(0, a.pending)
        a.stop()
    }

    @Test
    fun `a refused slot retains its exact wrap and message until a receipt`() = runTest {
        val relay = FakeRelay().apply { confirmsPublications = false }
        val delegate = relay.transport()
        val offered = mutableListOf<NostrEvent>()
        val transport = object : dev.forgesworn.kithmoot.relay.RoomTransport by delegate {
            override suspend fun publishConfirmed(event: NostrEvent, timeoutMs: Long): Boolean {
                offered += event
                return delegate.publishConfirmed(event, timeoutMs)
            }
        }
        val a = QuietTransport(transport, room.roomKey, ada.participant, policy.members!!, 0,
            backgroundScope, intervalSeconds = 60, now = { clock }, ticking = false, slotOffset = { 0 })
        val message = chat(ada, "wait for the receipt")
        a.publish(message)
        a.tick(); a.tick()
        assertEquals(1, a.pending)
        assertTrue(relay.published.isEmpty())
        assertEquals(1, offered.map { it.id }.distinct().size)
        assertEquals(1, a.exportState().used.getValue(ada.participant).counters.size)
        relay.confirmsPublications = true
        a.tick()
        assertEquals(0, a.pending)
        assertEquals(1, relay.countOfKind(RoomDrops.GIFT_WRAP_KIND))
        assertEquals(0, relay.countOfKind(KIND_CHAT))
        assertEquals(1, offered.map { it.id }.distinct().size)
        a.stop()
    }

    @Test
    fun `an unsupported receipt cannot silently use fire and forget`() = runTest {
        val relay = FakeRelay()
        val transport = object : dev.forgesworn.kithmoot.relay.RoomTransport by relay.transport() {
            override suspend fun publishConfirmed(event: NostrEvent, timeoutMs: Long): Boolean =
                throw UnsupportedOperationException("no durable receipt")
        }
        val a = QuietTransport(transport, room.roomKey, ada.participant, policy.members!!, 0,
            backgroundScope, intervalSeconds = 60, now = { clock }, ticking = false, slotOffset = { 0 })
        a.publish(chat(ada, "keep the retry copy"))
        a.tick()
        assertEquals(1, a.pending)
        assertTrue(relay.published.isEmpty())
        a.stop()
    }

    @Test
    fun `a failed durable removal preserves the accepted wrap for exact retry`() = runTest {
        val relay = FakeRelay()
        var storageLocked = false
        val delegate = relay.transport()
        var failAfterReceipt = true
        val transport = object : dev.forgesworn.kithmoot.relay.RoomTransport by delegate {
            override suspend fun publishConfirmed(event: NostrEvent, timeoutMs: Long): Boolean =
                delegate.publishConfirmed(event, timeoutMs).also { if (failAfterReceipt) storageLocked = true }
        }
        val a = QuietTransport(transport, room.roomKey, ada.participant, policy.members!!, 0,
            backgroundScope, intervalSeconds = 60, now = { clock }, ticking = false, slotOffset = { 0 },
            onState = { if (storageLocked) error("storage locked") })
        val message = chat(ada, "persist the receipt before removal")
        a.publish(message)
        assertFailsWith<IllegalStateException> { a.tick() }
        assertEquals(listOf(message.id), a.queuedEvents().map { it.id })
        val accepted = relay.published.single()
        failAfterReceipt = false
        storageLocked = false
        a.tick()
        assertEquals(0, a.pending)
        assertEquals(setOf(accepted.id), relay.published.map { it.id }.toSet())
        assertEquals(1, a.exportState().used.getValue(ada.participant).counters.size)
        a.stop()
    }

    @Test
    fun `confirmed quiet send means its exact inner event was durably queued`() = runTest {
        val relay = FakeRelay()
        val states = mutableListOf<QuietTransport.QuietState>()
        var storageLocked = false
        val a = quiet(relay, ada, onState = { if (storageLocked) error("storage locked") else states += it })
        val message = chat(ada, "kept before the slot")
        assertTrue(a.publishConfirmed(message))
        assertEquals(listOf(message.id), states.single().queued.map { it.id })
        assertEquals(listOf(message.id), a.queuedEvents().map { it.id })
        storageLocked = true
        assertFailsWith<IllegalStateException> { a.confirmQueued(message.id) }
        assertEquals(1, a.pending)
        storageLocked = false
        assertTrue(a.confirmQueued(message.id))
        assertEquals(emptyList<String>(), states.last().queued.map { it.id })
        assertEquals(0, a.pending)
        val refusing = quiet(relay, ada, onState = { error("storage locked") })
        assertFailsWith<IllegalStateException> { refusing.publishConfirmed(chat(ada, "not kept")) }
        assertEquals(0, refusing.pending)
        a.stop(); refusing.stop()
    }

    @Test
    fun `a box pending message never returns to the phone timer before its receipt is durable`() = runTest {
        val relay = FakeRelay()
        val states = mutableListOf<QuietTransport.QuietState>()
        var storageLocked = false
        val a = quiet(relay, ada, onState = { if (storageLocked) error("storage locked") else states += it })
        val message = chat(ada, "waiting for Bothy's receipt")

        a.retainForBox(message)
        assertEquals(setOf(message.id), states.single().boxPending)
        a.tick(); runCurrent()
        assertEquals(1, relay.countOfKind(RoomDrops.GIFT_WRAP_KIND))
        assertEquals(1, a.pending)

        storageLocked = true
        assertFailsWith<IllegalStateException> { a.confirmQueued(message.id) }
        assertEquals(setOf(message.id), a.exportState().boxPending)
        storageLocked = false
        assertTrue(a.confirmQueued(message.id))
        assertEquals(emptySet(), states.last().boxPending)
        assertEquals(0, a.pending)
        a.stop()
    }

    @Test
    fun `rekey rejects every old quiet event and starts the successor with empty counters`() = runTest {
        val relay = FakeRelay()
        val states = mutableListOf<QuietTransport.QuietState>()
        val rejected = mutableListOf<NostrEvent>()
        val a = QuietTransport(
            relay.transport(), room.roomKey, ada.participant, policy.members!!, 0, backgroundScope,
            intervalSeconds = 60, now = { clock }, ticking = false, slotOffset = { 0 },
            onState = { states += it }, onRekeyed = { rejected += it },
        )
        val old = chat(ada, "written before the successor")
        a.publish(old)

        a.beginRekey()
        assertFailsWith<IllegalStateException> { a.publish(chat(ada, "blocked")) }
        a.rekey(ByteArray(32) { 22 })
        assertEquals(listOf(old.id), rejected.map { it.id })
        assertEquals(0, a.pending)
        assertTrue(states.last().used.isEmpty())
        assertTrue(states.last().queued.isEmpty())
        assertTrue(states.last().boxPending.isEmpty())

        a.completeRekey()
        a.publish(chat(ada, "written after the successor"))
        assertEquals(1, a.pending)
        a.stop()
    }

    @Test
    fun `a box owned epoch suppresses the phones real wrap and filler`() = runTest {
        val relay = FakeRelay()
        val delegatedEpoch = dev.forgesworn.kithmoot.protocol.DeadDrop.epochIndexAt(clock)
        val a = QuietTransport(
            relay.transport(), room.roomKey, ada.participant, policy.members!!, 0, backgroundScope,
            intervalSeconds = 60, now = { clock }, ticking = false, slotOffset = { 0 },
            reservedCounters = { epoch -> if (epoch == delegatedEpoch) (0 until 8).toSet() else emptySet() },
        )
        a.publish(chat(ada, "the box will carry this"))
        a.tick(); runCurrent()
        assertEquals(0, relay.countOfKind(RoomDrops.GIFT_WRAP_KIND))
        assertEquals(1, a.pending)
        clock += 3600
        a.tick(); runCurrent()
        assertEquals(1, relay.countOfKind(RoomDrops.GIFT_WRAP_KIND))
        assertEquals(0, a.pending)
        a.stop()
    }

    private fun epochKey(n: Int) = if (n == 0) room.roomKey else ByteArray(32) { (100 + n).toByte() }

    private suspend fun QuietTransport.follow(key: ByteArray) { beginRekey(); rekey(key); completeRekey() }

    private fun TestScope.chatSeenBy(transport: QuietTransport): MutableList<NostrEvent> {
        val seen = mutableListOf<NostrEvent>()
        backgroundScope.launch { transport.subscribe(listOf(Filter(kinds = listOf(KIND_CHAT)))).collect { seen += it } }
        return seen
    }

    /** One drop under [key] from [who], posted at its slot. */
    private suspend fun TestScope.dropUnder(relay: FakeRelay, who: PrimaryIdentity, key: ByteArray, text: String): NostrEvent {
        val sender = quiet(relay, who, key = key)
        val message = chat(who, text)
        sender.publish(message)
        sender.tick(); runCurrent()
        sender.stop()
        return message
    }

    @Test
    fun `a drop sealed under the epoch just left still opens, and nothing is sent under that key`() = runTest {
        val relay = FakeRelay()
        val lagging = quiet(relay, ada)
        val moved = quiet(relay, rowan)
        val seenByLagging = chatSeenBy(lagging)
        val seenByMoved = chatSeenBy(moved)
        runCurrent()
        moved.follow(epochKey(1))

        val late = chat(ada, "sent before my phone followed")
        lagging.publish(late)
        lagging.tick(); runCurrent()
        assertEquals(listOf(late.id), seenByMoved.map { it.id })

        // What the moved device posts goes under the new key only: the device
        // still on the old key cannot open it.
        val fresh = chat(rowan, "on the new key")
        moved.publish(fresh)
        moved.tick(); runCurrent()
        assertEquals(listOf(late.id, fresh.id), seenByMoved.map { it.id })
        assertFalse(fresh.id in seenByLagging.map { it.id })
        lagging.stop(); moved.stop()
    }

    @Test
    fun `only the last few epochs left are opened`() = runTest {
        val relay = FakeRelay()
        val reader = quiet(relay, rowan)
        val seen = chatSeenBy(reader)
        runCurrent()
        for (n in 1..MAX_PAST_EPOCHS + 1) reader.follow(epochKey(n))

        dropUnder(relay, ada, epochKey(0), "five epochs back")
        clock += 60
        val kept = dropUnder(relay, ada, epochKey(1), "four epochs back")
        assertEquals(listOf(kept.id), seen.map { it.id })
        reader.stop()
    }

    @Test
    fun `the session decides which left epochs are read`() = runTest {
        val relay = FakeRelay()
        val reader = quiet(relay, rowan)
        val seen = chatSeenBy(reader)
        runCurrent()
        reader.follow(epochKey(1))
        reader.keepPast(emptyList())

        dropUnder(relay, ada, epochKey(0), "on an epoch the session let go")
        assertTrue(seen.isEmpty())
        reader.stop()
    }

    @Test
    fun `an epoch handed over later is read from the stored stream`() = runTest {
        val relay = FakeRelay().apply { answersQueries = true }
        // Said at epoch 1, which this device was carried over and never held.
        val said = dropUnder(relay, ada, epochKey(1), "said while you were away")
        val reader = quiet(relay, rowan, key = epochKey(2))
        val seen = chatSeenBy(reader)
        runCurrent()
        assertTrue(seen.isEmpty())

        reader.keepPast(listOf(epochKey(1)))
        runCurrent()
        assertEquals(listOf(said.id), seen.map { it.id })

        // Handing over the same epoch again reads nothing twice.
        reader.keepPast(listOf(epochKey(1)))
        runCurrent()
        assertEquals(1, seen.size)
        reader.stop()
    }
}
