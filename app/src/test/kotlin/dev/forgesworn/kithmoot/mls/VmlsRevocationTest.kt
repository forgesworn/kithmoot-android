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
        var accepted = false; var offered = 0; var hasList = true
        val channel = VmlsRevocationChannel(vault, { urls, _ -> object : VmlsCarrier {
            override suspend fun publish(event: NostrEvent): Boolean { assertEquals(listOf("wss://keeper.example/"), urls); offered++; return accepted }
            override fun subscribe(filters: List<Filter>) = if (hasList) flowOf(list) else emptyFlow()
            override fun close() {}
        } }, { listOf("wss://directory.example") }, { true }, { at })
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
        hasList = false
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
}
