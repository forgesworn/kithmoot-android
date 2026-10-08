package dev.forgesworn.kithmoot.mls

import dev.forgesworn.kithmoot.account.*
import dev.forgesworn.kithmoot.crypto.*
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.util.UUID
import kotlin.test.*

class VmlsRevocationTest {
    private val at = 1_900_000_000L
    private val member = LocalSigner(Digests.sha256("request-member".toByteArray()))
    private val keeper = LocalSigner(Digests.sha256("request-keeper".toByteArray()))
    private val third = LocalSigner(Digests.sha256("request-other".toByteArray()))
    private val device = "ab".repeat(32)
    private val box = "cd".repeat(32)
    private val session = "ef".repeat(32)
    private fun request() = VmlsRevocationRequest(member.pubkey, keeper.pubkey, device, listOf(session), listOf(box), at, at + 86400)
    private fun vault(): MlsVault = MlsVault(object : MlsVaultStores {
        override val lockName = UUID.randomUUID().toString()
        val values = mutableMapOf<String, MemoryStorage>()
        override fun open(name: String, aad: ByteArray): RoomStorage = values.getOrPut(name + aad.toHex()) { MemoryStorage() }
    }, now = { at })

    @Test fun `identity seal and gift wrap round trip, no sender or device in outer tags`() = runBlocking<Unit> {
        val wrap = VmlsRequestEnvelope.wrap(request(), member, { true })
        assertEquals(listOf(listOf("p", keeper.pubkey)), wrap.tags)
        assertNotEquals(member.pubkey, wrap.pubkey)
        assertTrue(wrap.createdAt in at - 172800..at)
        assertEquals(request(), VmlsRequestEnvelope.unwrap(wrap, keeper, at, { true }))
        assertFailsWith<IllegalArgumentException> { VmlsRequestEnvelope.unwrap(wrap.copy(sig = "00".repeat(64)), keeper, at, { true }) }
        assertFailsWith<IllegalArgumentException> { VmlsRequestEnvelope.unwrap(wrap, third, at, { true }) }
    }

    @Test fun `rumor cannot impersonate a different seal author and is bounded in time`() {
        val r = request()
        assertFailsWith<IllegalArgumentException> { VmlsRevocationRequest.parse(r.rumor().toString(), third.pubkey, keeper.pubkey, at) }
        assertFailsWith<IllegalArgumentException> { VmlsRevocationRequest.parse(r.copy(createdAt = at + 601).rumor().toString(), member.pubkey, keeper.pubkey, at) }
        assertFailsWith<IllegalArgumentException> { r.copy(expiration = at + VMLS_REQUEST_SECONDS + 1) }
        assertFailsWith<IllegalArgumentException> { VmlsRevocationRequest.parse(r.rumor().toString(), member.pubkey, keeper.pubkey, r.expiration) }
        val signedRumor = JsonObject(r.rumor() + ("sig" to JsonPrimitive("00".repeat(64))))
        assertFailsWith<IllegalArgumentException> { VmlsRevocationRequest.parse(signedRumor.toString(), member.pubkey, keeper.pubkey, at) }
    }

    @Test fun `request authority is the local ledger issuer and credential identity, even after departure`() = runBlocking<Unit> {
        val record = VmlsGrantRecord(box, VmlsGrantLedger(MemoryStorage()).plan(keeper, member.pubkey, box, device, at))
        assertTrue(authorisedRevocation(request(), keeper.pubkey, listOf(record), emptyList()))
        assertFalse(authorisedRevocation(request().copy(sender = third.pubkey), keeper.pubkey, listOf(record), emptyList()))
        assertFalse(authorisedRevocation(request().copy(keeper = third.pubkey), third.pubkey, listOf(record), emptyList()))
        assertFalse(authorisedRevocation(request(), keeper.pubkey, listOf(record), listOf(VmlsRoomMember(session, third.pubkey, device, false))))
        // Hints never narrow the keeper's authority: it acts on its own boxes.
        assertTrue(authorisedRevocation(request().copy(boxes = listOf("12".repeat(32))), keeper.pubkey, listOf(record), emptyList()))
    }

    @Test fun `sent means one OK true and no list publishes nothing`() = runBlocking<Unit> {
        val vault = vault(); val ctx = vault.context("test", member.pubkey)
        val list = keeper.sign(KIND_DM_RELAYS, at, listOf(listOf("relay", "wss://keeper.example")), "")
        var accepted = false; var offered = 0; var hasList = true; var clock = at
        val channel = VmlsRevocationChannel(vault, { urls, auth -> assertNull(auth, "Sending never offers identity AUTH"); object : VmlsCarrier {
            override suspend fun publish(event: NostrEvent): Boolean { assertEquals(listOf("wss://keeper.example/"), urls); offered++; return accepted }
            override fun subscribe(filters: List<Filter>) = if (hasList) flowOf(list) else emptyFlow()
            override fun close() {}
        } }, { listOf("wss://directory.example") }, { true }, { clock })
        assertFalse(channel.send(ctx, member, request()))
        assertFalse(channel.entries(ctx).outbox.values.single().sent)
        accepted = true
        assertTrue(channel.send(ctx, member, request()))
        assertTrue(channel.entries(ctx).outbox.values.single().sent)
        assertEquals(2, offered)
        assertTrue(channel.send(ctx, member, request()))
        assertEquals(2, offered) // confirmed retry is not published twice
        assertTrue(channel.send(ctx, member, request(), resend = true))
        assertEquals(3, offered) // explicit retry creates a fresh wrap; the keeper dedups the request
        hasList = false; clock += 901
        assertFailsWith<IllegalStateException> { channel.send(ctx, member, request().copy(device = "13".repeat(32))) }
        assertEquals(3, offered)
    }

    @Test fun `a session ending in a signer reply releases no wrap`() = runBlocking<Unit> {
        var current = true
        val signer = object : ParticipantSigner by member {
            override suspend fun nip44Encrypt(peer: String, plaintext: String): String {
                current = false; return member.nip44Encrypt(peer, plaintext)
            }
        }
        assertFailsWith<IllegalStateException> { VmlsRequestEnvelope.wrap(request(), signer, { current }) }
    }

    @Test fun `inbox is capped before signer work and replay never reopens a terminal decision`() = runBlocking<Unit> {
        val vault = vault(); val ctx = vault.context("test", keeper.pubkey)
        val list = keeper.sign(KIND_DM_RELAYS, at, listOf(listOf("relay", "wss://keeper.example")), "")
        val wraps = (1..12).map { VmlsRequestEnvelope.wrap(request(), member, { true }) }
        var decryptions = 0; var clock = at
        val signer = object : ParticipantSigner by keeper {
            override suspend fun nip44Decrypt(peer: String, payload: String): String { decryptions++; return keeper.nip44Decrypt(peer, payload) }
        }
        fun channel() = VmlsRevocationChannel(vault, { _, _ -> object : VmlsCarrier {
            override suspend fun publish(event: NostrEvent) = error("inbox never publishes")
            override fun subscribe(filters: List<Filter>) = if (filters.single().kinds == listOf(KIND_DM_RELAYS)) flowOf(list) else wraps.asFlow()
            override fun close() {}
        } }, { listOf("wss://directory.example") }, { true }, { clock })
        val channel = channel()
        val book = channel.poll(ctx, signer) { true }
        assertEquals(16, decryptions); assertEquals(8, book.seen.size); assertEquals(1, book.inbox.size)
        val key = RevocationBook.key(request())
        channel.decide(ctx, key, RevocationDecision.APPROVED)
        channel.decide(ctx, key, RevocationDecision.DONE)
        clock += 61
        val restarted = channel().poll(ctx, signer) { true }
        assertEquals(24, decryptions); assertEquals(RevocationDecision.DONE, restarted.inbox.getValue(key).decision)
        assertEquals(12, restarted.seen.size)
    }

    @Test fun `member reference is separate from keeper authority and survives encoding`() {
        val ref = VmlsMembership.memberGrant(box, device)
        assertFalse(ref.keeper)
        assertNotEquals(VmlsMembership.grantRef(box, device), ref.ref)
        val r = request(); val key = RevocationBook.key(r)
        val book = RevocationBook(outbox = linkedMapOf(key to RevocationEntry(r, sent = true)))
        assertEquals(book.outbox, RevocationBook.decode(book.encode()).outbox)
    }
    @Test fun `paged scan reaches a backdated request below 64 wraps across restarts and ignores forged IDs`() = runBlocking<Unit> {
        val vault = vault(); val ctx = vault.context("test", keeper.pubkey)
        var clock = at
        val list = keeper.sign(KIND_DM_RELAYS, at, listOf(listOf("relay", "wss://keeper.example")), "")
        val target = VmlsRequestEnvelope.wrap(request(), member, { true })
        // Valid signed noise forces the same pagination as ordinary gift-wrapped DMs.
        val noise = (1..72).map { third.sign(1059, at - it, listOf(listOf("p", keeper.pubkey)), "not-encrypted") }
        val backdated = Events.sign(Digests.sha256("outer".toByteArray()), 1059, at - 172801, target.tags,
            Nip44.encrypt(member.sign(13, at, emptyList(), member.nip44Encrypt(keeper.pubkey, request().rumor().toString())).toCompactJson(),
                Nip44.conversationKey(Digests.sha256("outer".toByteArray()), keeper.pubkey.hexToBytes())))
        val forged = backdated.copy(content = "corrupted")
        val future = third.sign(1059, at + 86400, target.tags, "future")
        val source = noise + forged + backdated + future
        val untils = mutableListOf<Long>()
        fun channel() = VmlsRevocationChannel(vault, { _, _ -> object : VmlsCarrier {
            override suspend fun publish(event: NostrEvent) = false
            override fun subscribe(filters: List<Filter>): Flow<NostrEvent> {
                val f = filters.single()
                if (f.kinds == listOf(KIND_DM_RELAYS)) return flowOf(list)
                untils += f.until!!
                return source.filter { it.createdAt <= f.until && it.createdAt >= f.since!! }
                    .sortedByDescending { it.createdAt }.take(f.limit!!).asFlow()
            }
            override fun close() {}
        } }, { listOf("wss://directory.example") }, { true }, { clock })
        repeat(12) { channel().poll(ctx, keeper) { it == request() }; clock += 61 }
        val book = channel().entries(ctx)
        assertEquals(request(), book.inbox.values.single().request)
        assertTrue(backdated.id in book.seen)
        assertFalse(future.id in book.seen)
        assertTrue(untils.any { it < at - 60 })
    }

    @Test fun `full seen set keeps progressing and deferred or declined requests survive newer resends`() = runBlocking<Unit> {
        val vault = vault(); val ctx = vault.context("test", keeper.pubkey)
        var clock = at
        var current = request()
        var wraps = listOf(VmlsRequestEnvelope.wrap(current, member, { true }))
        val list = keeper.sign(KIND_DM_RELAYS, at, listOf(listOf("relay", "wss://keeper.example")), "")
        val full = RevocationBook(seen = (1..1024).associateTo(linkedMapOf()) { it.toString(16).padStart(64, '0') to at })
        assertTrue(vault.keepRevocationRequests(ctx, full.encode()) is VaultResult.Ok)
        fun channel() = VmlsRevocationChannel(vault, { _, _ -> object : VmlsCarrier {
            override suspend fun publish(event: NostrEvent) = false
            override fun subscribe(filters: List<Filter>): Flow<NostrEvent> {
                val f = filters.single()
                return if (f.kinds == listOf(KIND_DM_RELAYS)) flowOf(list) else wraps.filter { it.createdAt <= f.until!! }.asFlow()
            }
            override fun close() {}
        } }, { listOf("wss://directory.example") }, { true }, { clock })
        var c = channel()
        val key = RevocationBook.key(current)
        assertEquals(1, c.poll(ctx, keeper) { true }.inbox.size)
        c.defer(ctx, key)
        c.decide(ctx, key, RevocationDecision.DENIED)
        clock += 61
        current = current.copy(createdAt = clock, expiration = clock + 86400)
        wraps = listOf(VmlsRequestEnvelope.wrap(current, member, { true }))
        // Finish the old scan, then restart at the head, retaining all state through a new channel.
        repeat(3) { c = channel(); c.poll(ctx, keeper) { true }; clock += 61 }
        val book = c.entries(ctx)
        assertEquals(1024, book.seen.size)
        assertEquals(RevocationDecision.PENDING, book.inbox.getValue(key).decision)
        assertEquals(current.expiration, book.inbox.getValue(key).request.expiration)
        assertTrue(book.inbox.getValue(key).deferredUntil > clock)
    }

    @Test fun `inbox storage budget cannot wedge decisions`() = runBlocking<Unit> {
        val vault = vault(); val ctx = vault.context("test", keeper.pubkey)
        var clock = at
        val list = keeper.sign(KIND_DM_RELAYS, at, listOf(listOf("relay", "wss://keeper.example")), "")
        val hints = (1..64).map { it.toString(16).padStart(64, '0') }
        val large = (1..20).map { request().copy(device = (2000 + it).toString(16).padStart(64, '0'), sessions = hints, boxes = hints) }
        val wraps = large.map { VmlsRequestEnvelope.wrap(it, member, { true }) }
        val channel = VmlsRevocationChannel(vault, { _, _ -> object : VmlsCarrier {
            override suspend fun publish(event: NostrEvent) = true
            override fun subscribe(filters: List<Filter>): Flow<NostrEvent> {
                val f = filters.single()
                return if (f.kinds == listOf(KIND_DM_RELAYS)) flowOf(list) else wraps.filter { it.createdAt <= f.until!! }.asFlow()
            }
            override fun close() {}
        } }, { listOf("wss://directory.example") }, { true }, { clock })
        repeat(4) { channel.poll(ctx, keeper) { true }; clock += 61 }
        val book = channel.entries(ctx)
        assertTrue(book.inbox.size in 1..19)
        channel.decide(ctx, book.inbox.keys.first(), RevocationDecision.APPROVED)
        channel.decide(ctx, book.inbox.keys.first(), RevocationDecision.UNAVAILABLE)
        assertTrue(channel.entries(ctx).encode().size < MlsVault.MAX_REQUEST_BYTES)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `real carrier ends a small page on EOSE and verifies before pool dedup`() = kotlinx.coroutines.test.runTest {
        val sockets = dev.forgesworn.kithmoot.support.FakeSocketFactory()
        val carrier = RelayCarrier(listOf("wss://keeper.example"), backgroundScope, sockets = sockets)
        try {
            val result = async { carrier.readPage(Filter(kinds = listOf(1059), limit = 64)) }
            testScheduler.runCurrent(); sockets.openAll(); testScheduler.runCurrent()
            val socket = sockets.opened.single()
            val id = socket.requestedSubscriptions().single()
            val good = VmlsRequestEnvelope.wrap(request(), member, { true })
            socket.deliverEvent(id, good.copy(content = "forged copy under the same id"))
            socket.deliverEvent(id, good)
            socket.deliverRaw("[\"EOSE\",\"$id\"]")
            testScheduler.runCurrent()
            assertEquals(listOf(good), result.await())
            assertEquals(0, testScheduler.currentTime)
        } finally { carrier.close() }
    }

    @Test fun `a busy relay cannot advance another relay's cursor past a request`() = runBlocking<Unit> {
        val vault = vault(); val ctx = vault.context("test", keeper.pubkey)
        var clock = at
        val list = keeper.sign(KIND_DM_RELAYS, at, listOf(listOf("relay", "wss://busy.example"), listOf("relay", "wss://quiet.example")), "")
        val target = VmlsRequestEnvelope.wrap(request(), member, { true })
        val noise = (1..72).map { third.sign(1059, at - it, listOf(listOf("p", keeper.pubkey)), "noise") }
        val queried = mutableListOf<String>()
        val channel = VmlsRevocationChannel(vault, { urls, _ -> object : VmlsCarrier {
            override suspend fun publish(event: NostrEvent) = false
            override fun subscribe(filters: List<Filter>): Flow<NostrEvent> {
                val f = filters.single()
                if (f.kinds == listOf(KIND_DM_RELAYS)) return flowOf(list)
                assertEquals(1, urls.size); queried += urls.single()
                return (if (urls.single().contains("busy")) noise else listOf(target))
                    .filter { it.createdAt <= f.until!! }.sortedByDescending { it.createdAt }.take(f.limit!!).asFlow()
            }
            override fun close() {}
        } }, { listOf("wss://directory.example") }, { true }, { clock })
        channel.poll(ctx, keeper) { true }; clock += 61
        val book = channel.poll(ctx, keeper) { true }
        assertEquals(listOf("wss://busy.example/", "wss://quiet.example/"), queried)
        assertEquals(request(), book.inbox.values.single().request)
    }

}
