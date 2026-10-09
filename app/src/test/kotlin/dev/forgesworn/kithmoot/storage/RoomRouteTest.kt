package dev.forgesworn.kithmoot.storage

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.account.SignerException
import dev.forgesworn.kithmoot.notifications.*
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.RoomRoute
import dev.forgesworn.kithmoot.service.*
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class RoomRouteTest {
    private val now = 1_800_000_000L
    private val secret = ByteArray(32) { 4 }
    private val relays = listOf("wss://fixture.invalid/")
    private fun room(who: PrimaryIdentity = PrimaryIdentity.create(deriveRoom(secret).roomId, now + 3600, now)) =
        SavedRoom.create(secret, who, encodeJoinUrl("https://fixture.invalid/j/", secret, relays), relays,
            "Room", now, null, null)

    @Test fun `fresh entry cannot overwrite a saved room and rollback matches its exact identity`() {
        val repository = RoomRepository(MemoryStorage())
        val original = room(); val replacement = room()
        repository.saveNew(original)
        assertFailsWith<IllegalStateException> { repository.saveNew(replacement) }
        repository.forgetIfIdentity(original.id, replacement.participant, replacement.devicePubkey)
        assertEquals(original.participant, repository.get(original.id)?.participant)
        repository.forgetIfIdentity(original.id, original.participant, original.devicePubkey)
        assertNull(repository.get(original.id))
    }

    @Test fun `a relay free saved room remains nearby only across reopening`() {
        val who = PrimaryIdentity.create(deriveRoom(secret).roomId, now + 3600, now)
        val offline = SavedRoom.create(secret, who, encodeJoinUrl("https://fixture.invalid/j/", secret, emptyList()),
            emptyList(), "Nearby", now, null, null, route = RoomRoute.NEARBY)
        val repository = RoomRepository(MemoryStorage()); repository.saveNew(offline)
        assertTrue(repository.get(offline.id)!!.relays.isEmpty())
        assertEquals(RoomRoute.NEARBY, repository.get(offline.id)!!.route)
        assertFailsWith<IllegalArgumentException> { offline.withRoute(RoomRoute.INTERNET) }
        assertFailsWith<IllegalArgumentException> { offline.withRoute(RoomRoute.MIXED) }
    }

    @Test fun `route survives repository reopen and bookmark refresh without changing room identity`() {
        val store = MemoryStorage(); val original = room(); val saved = original.withRoute(RoomRoute.NEARBY)
        RoomRepository(store).save(saved)
        val reopened = checkNotNull(RoomRepository(store).get(saved.id))
        assertEquals(RoomRoute.NEARBY, reopened.route); assertEquals(RoomRoute.NEARBY, reopened.summary(now).route)
        assertEquals(original.id, reopened.id); assertEquals(original.participant, reopened.participant)
        assertContentEquals(original.secret, reopened.secret)
        assertEquals(RoomRoute.NEARBY, original.retainingHistory(reopened).route)
        assertEquals(RoomRoute.INTERNET, reopened.withRoute(RoomRoute.INTERNET).route)
        assertEquals(RoomRoute.INTERNET, original.route)
    }

    @Test fun `unknown or nonstring route refuses the stored room instead of enabling internet`() {
        for (bad in listOf(JsonPrimitive("automatic"), JsonPrimitive(0), JsonNull)) {
            assertFails { SavedRoom.decode(JsonObject(room().json + ("route" to bad))) }
        }
    }

    @Test fun `nearby room cannot use background relay delivery or notification reply`() {
        val candidate = DeliveryCandidate("room", anonymous = false, quiet = false, ended = false,
            epochId = "epoch", allowsInternet = false)
        assertEquals(DeliveryExclusion.NEARBY, deliveryExclusion(candidate) { false })
        val reply = ReplyRoom(anonymous = false, quiet = false, ended = false, hasEpoch = true,
            needsProof = false, account = ReplyAccount.SIGNED_IN, credentialExpiresAt = now + 3600, allowsInternet = false)
        assertFalse(canReplyFromNotice(reply, now))
        assertNull(deliveryExclusion(candidate.copy(allowsInternet = true)) { false })
        assertTrue(canReplyFromNotice(reply.copy(allowsInternet = true), now))
    }

    @Test fun `account offline reopen keeps valid device authority but cannot contact a signer`() = runTest {
        var calls = 0
        val local = LocalSigner(ByteArray(32) { 7 })
        val signer = object : ParticipantSigner by local {
            override val method = "bunker"
            override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent {
                calls++; return local.sign(kind, createdAt, tags, content)
            }
        }
        val who = PrimaryIdentity.createWith(signer, deriveRoom(secret).roomId, now + 3600, now)
        val saved = room(who).withRoute(RoomRoute.NEARBY)
        assertTrue(saved.viaAccount); assertEquals(1, calls)
        val offline = saved.offlineIdentity(now, signer.pubkey) as PrimaryIdentity
        assertEquals(who.devicePubkey, offline.devicePubkey); assertEquals(who.credential.id, offline.credential.id)
        assertFailsWith<SignerException> { offline.signer.sign(0, now, emptyList(), "") }
        assertFailsWith<SignerException> { offline.signer.nip44Encrypt(signer.pubkey, "") }
        assertFailsWith<SignerException> { offline.signer.nip44Decrypt(signer.pubkey, "") }
        assertFailsWith<RoomRecoveryException> { saved.offlineIdentity(now, null) }
        assertFailsWith<RoomRecoveryException> { saved.offlineIdentity(now + 3600, signer.pubkey) }
        assertEquals(1, calls)
    }

    @Test fun `nearby mode cannot silently discard self destruct cleanup authority`() {
        val host = createRoomInvitation(persistent = true)
        val who = PrimaryIdentity.create(deriveRoom(secret).roomId, now + 3600, now)
        val saved = SavedRoom.create(secret, who, encodeInvitationUrl("https://fixture.invalid/j/", host.invitation, relays),
            relays, "Conference", now, host, host.invitation.canonicalInviter, ends = now + 3600, destruct = true)
        assertFailsWith<IllegalArgumentException> { saved.withRoute(RoomRoute.NEARBY) }
        assertEquals(RoomRoute.MIXED, saved.withRoute(RoomRoute.MIXED).route)
    }
}
