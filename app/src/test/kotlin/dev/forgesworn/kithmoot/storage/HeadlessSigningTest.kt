package dev.forgesworn.kithmoot.storage

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.createRoomInvitation
import dev.forgesworn.kithmoot.protocol.deriveRoom
import dev.forgesworn.kithmoot.protocol.encodeInvitationUrl
import dev.forgesworn.kithmoot.session.PendingChatOutbox
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import dev.forgesworn.kithmoot.session.SecondaryIdentity
import dev.forgesworn.kithmoot.session.decodeChatEvent
import dev.forgesworn.kithmoot.session.encodeChatEvent
import dev.forgesworn.kithmoot.session.enrolNow
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** A signer app: never reachable from a notification reply, and counted to prove it is not asked. */
private class CountingSigner(key: ByteArray) : ParticipantSigner {
    private val local = LocalSigner(key)
    override val pubkey = local.pubkey
    override val method = "nip55"
    var signatures = 0
    override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent { signatures++; return local.sign(kind, createdAt, tags, content) }
    override suspend fun nip44Encrypt(peer: String, plaintext: String) = local.nip44Encrypt(peer, plaintext)
    override suspend fun nip44Decrypt(peer: String, payload: String) = local.nip44Decrypt(peer, payload)
}

/** What a reply from a notification is signed with, and that the room reads it as an open room's own message. */
class HeadlessSigningTest {
    private val now = 1_800_000_000L
    private val relays = listOf("wss://relay.example")
    private val base = "https://kithmoot.example/j/"

    private fun saved(secret: ByteArray, identity: dev.forgesworn.kithmoot.session.RoomIdentity): SavedRoom {
        val host = createRoomInvitation(false)
        return SavedRoom.create(secret, identity, encodeInvitationUrl(base, host.invitation, relays), relays, "Workshop", now, host, null)
    }

    /** Sealed and signed as [sendReplyInBackground][dev.forgesworn.kithmoot.service.sendReplyInBackground] does, then read back and kept. */
    private suspend fun replyKeeps(room: SavedRoom, at: Long) {
        val signing = room.headlessSigning(at)!!
        val keys = deriveRoom(room.secret)
        val event = encodeChatEvent("On my way", signing.participant, signing.credential, keys.roomId, keys.roomKey,
            signing.deviceSecretKey, at, credentialRoomId = room.id)
        val message = decodeChatEvent(event, keys.roomId, keys.roomKey, at, room.policy, credentialRoomId = room.id)
        assertNotNull(message)
        assertEquals(room.participant, message.participant)
        assertEquals(room.devicePubkey, message.device)
        assertEquals("On my way", message.body)
        val outbox = PendingChatOutbox(MemoryStorage(), room.id, room.participant, room.devicePubkey)
        outbox.retain(keys.roomId, event)
        assertEquals(event.id, outbox.pending()?.event?.id)
    }

    @Test fun `a key held here mints a credential for the reply`() = runTest {
        val secret = Entropy.bytes(32)
        val room = saved(secret, PrimaryIdentity.create(deriveRoom(secret).roomId, now + 3600, now))
        val later = now + 86_400 * 30
        assertTrue(room.headlessSigning(later)!!.credentialExpiresAt > later)
        replyKeeps(room, later)
    }

    @Test fun `an account room replies on its kept credential and never asks the signer`() = runTest {
        val secret = Entropy.bytes(32)
        val signer = CountingSigner(Entropy.bytes(32))
        val who = PrimaryIdentity.createWith(signer, deriveRoom(secret).roomId, now + SAVED_CREDENTIAL_TTL, now)
        val room = saved(secret, who)
        assertTrue(room.viaAccount)
        assertEquals(1, signer.signatures)
        assertEquals(who.credential.id, room.headlessSigning(now + 3600)!!.credential.id)
        replyKeeps(room, now + 3600)
        assertEquals(1, signer.signatures, "a reply is signed by the device, not the account")
        assertNull(room.headlessSigning(now + SAVED_CREDENTIAL_TTL), "once it lapses only the signer could make another")
    }

    @Test fun `a paired device replies until its pairing lapses`() = runTest {
        val secret = Entropy.bytes(32)
        val owner = PrimaryIdentity.create(deriveRoom(secret).roomId, now + 3600, now)
        val deviceKey = Entropy.bytes(32)
        val credential = owner.enrolNow(Schnorr.publicKeyHex(deviceKey), deriveRoom(secret).roomId, now + 600, now)
        val room = saved(secret, SecondaryIdentity.adopt(credential, deviceKey, deriveRoom(secret).roomId, now)!!)
        assertEquals(now + 600, room.headlessSigning(now)!!.credentialExpiresAt)
        replyKeeps(room, now + 599)
        assertNull(room.headlessSigning(now + 600))
    }

    @Test fun `its text form holds no secret`() {
        val secret = Entropy.bytes(32)
        val room = saved(secret, PrimaryIdentity.create(deriveRoom(secret).roomId, now + 3600, now))
        assertEquals("HeadlessSigning(redacted)", room.headlessSigning(now).toString())
    }
}
