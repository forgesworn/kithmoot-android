package dev.forgesworn.kithmoot.storage

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import dev.forgesworn.kithmoot.session.SecondaryIdentity
import kotlinx.serialization.json.*
import java.io.IOException
import java.util.concurrent.Executors
import javax.crypto.KeyGenerator
import kotlin.test.*

class RoomRecoveryTest {
    private val now = 1_800_000_000L
    private val relays = listOf("wss://relay.example")
    private val base = "https://kithmoot.example/j/"

    private fun room(name: String = "Workshop", persistent: Boolean = false): SavedRoom {
        val secret = Entropy.bytes(32)
        val derived = deriveRoom(secret)
        val who = PrimaryIdentity.create(derived.roomId, now + 3600, now)
        val host = createRoomInvitation(persistent)
        return SavedRoom.create(secret, who, encodeInvitationUrl(base, host.invitation, relays), relays,
            name, now, host, host.invitation.canonicalInviter)
    }

    @Test fun `group members return days later without holding any inviter key`() {
        val original = room(persistent = true)
        val member = PrimaryIdentity.create(original.id, now + 3600, now)
        val saved = SavedRoom.create(original.secret, member, original.joinUrl, relays, "Group", now, null, original.authority)
        val disk = MemoryStorage()
        RoomRepository(disk).save(saved)
        val restored = RoomRepository(disk).get(saved.id)!!
        val later = now + 4 * 24 * 60 * 60
        assertEquals(member.participant, restored.identity(later).participant)
        assertEquals(member.devicePubkey, restored.identity(later).devicePubkey)
        assertContentEquals(original.secret, restored.secret)
        assertTrue(restored.invitation!!.invitation.persistent)
        assertNull(restored.host(later))
        assertFalse(restored.json.containsKey("host"))
        RoomRepository(disk).forget(saved.id)
        assertNull(RoomRepository(disk).get(saved.id))
    }

    @Test fun `old temporary links cannot erase saved group creator authority`() {
        val group = room(persistent = true)
        val oldHost = createRoomInvitation()
        val old = SavedRoom.create(group.secret, group.identity(now), encodeInvitationUrl(base, oldHost.invitation, relays),
            relays, "Old meeting", now + 20, oldHost, group.authority)
        val retained = old.retainingHistory(group)
        assertEquals(group.joinUrl, retained.joinUrl)
        assertEquals(group.host(now)!!.invitation, retained.host(now + 4 * 24 * 60 * 60)!!.invitation)
        assertEquals(group.authority, retained.authority)
    }

    @Test fun `a fresh repository restores the creator and can admit somebody with nobody else online`() {
        val disk = MemoryStorage()
        val original = room()
        val before = original.identity(now)
        RoomRepository(disk).save(original)
        val restored = RoomRepository(disk).get(original.id)!!
        val after = restored.identity(now + 2 * SAVED_CREDENTIAL_TTL)
        assertEquals(before.participant, after.participant)
        assertEquals(before.devicePubkey, after.devicePubkey)
        assertContentEquals(original.secret, restored.secret)
        val host = restored.host(now + 2 * SAVED_CREDENTIAL_TTL)!!
        val requestKey = Entropy.bytes(32)
        val request = encodeInvitationRequest(host.invitation, requestKey, now)
        val grant = encodeInvitationGrant(host, Schnorr.publicKeyHex(requestKey), request.id, restored.secret, now)
        val admission = decodeRoomAdmissionGrant(grant, host.invitation, requestKey, request.id, now)!!
        assertContentEquals(original.secret, admission.secret)
        assertTrue(after.credential.createdAt > before.credential.createdAt)
    }

    @Test fun `paired recovery never stores the participant key or extends the credential`() {
        val original = room()
        val owner = original.identity(now) as PrimaryIdentity
        val deviceKey = Entropy.bytes(32)
        val credential = owner.enrol(Schnorr.publicKeyHex(deviceKey), original.id, now + 60, now)
        val secondary = SecondaryIdentity.adopt(credential, deviceKey, original.id, now)!!
        val saved = SavedRoom.create(original.secret, secondary, original.joinUrl, relays, "Paired", now, null, original.authority)
        val disk = MemoryStorage()
        RoomRepository(disk).save(saved)
        assertFalse(disk.value!!.toString(Charsets.UTF_8).contains("participantKey"))
        val reopened = RoomRepository(disk).get(original.id)!!
        assertIs<SecondaryIdentity>(reopened.identity(now + 59))
        assertEquals(credential, reopened.identity(now + 59).credential)
        assertFailsWith<RoomRecoveryException> { reopened.identity(now + 60) }
        assertEquals(1, RoomRepository(disk).list().size)
        assertEquals(owner.participant, reopened.participant)
    }

    @Test fun `an expired admission delegate can still reopen but cannot grant or become the creator`() {
        val original = room()
        val creator = original.host(now)!!
        val requester = Entropy.bytes(32)
        val request = encodeInvitationRequest(creator.invitation, requester, now)
        val grant = encodeInvitationGrant(creator, Schnorr.publicKeyHex(requester), request.id, original.secret, now)
        val admission = decodeRoomAdmissionGrant(grant, creator.invitation, requester, request.id, now)!!
        val saved = SavedRoom.create(original.secret, original.identity(now), original.joinUrl, relays,
            "Member", now, admission.delegate, original.authority)
        assertNotNull(saved.host(now))
        assertNull(saved.host(now + INVITATION_DELEGATION_TTL_SECONDS))
        assertEquals(saved.participant, saved.identity(now + INVITATION_DELEGATION_TTL_SECONDS).participant)
    }

    @Test fun `rotation persists the new creator capability and a replayable signed retirement`() {
        val disk = MemoryStorage()
        val old = room()
        RoomRepository(disk).save(old)
        val next = createRoomInvitation()
        val tombstone = encodeInvitationRetirement(old.invitation!!.invitation, old.host(now)!!.inviterSecretKey, now)
        val rotated = old.rotated(next, encodeInvitationUrl(base, next.invitation, relays), tombstone)
        RoomRepository(disk).save(rotated)
        val restored = RoomRepository(disk).get(old.id)!!
        assertEquals(next.invitation, restored.host(now)!!.invitation)
        assertEquals(old.authority, restored.authority)
        assertEquals(listOf(tombstone), restored.retirements)
        assertTrue(decodeInvitationRetirement(restored.retirements.single(), old.invitation!!.invitation))
        assertNull(RoomRepository(disk).findInvitation(old.joinUrl))
        assertEquals(old.id, RoomRepository(disk).findInvitation(rotated.joinUrl)!!.id)
        assertEquals(old.participant, restored.identity(now).participant)
    }

    @Test fun `retiring a link preserves membership but never restores its host key`() {
        val disk = MemoryStorage()
        val original = room()
        RoomRepository(disk).save(original)
        RoomRepository(disk).update(original.id) { it.invitationRetired() }
        val restored = RoomRepository(disk).get(original.id)!!
        assertNull(restored.host(now))
        assertFalse(restored.json.containsKey("host"))
        assertEquals(original.participant, restored.identity(now).participant)
    }

    @Test fun `a known room key change remains a stop after restart`() {
        val disk = MemoryStorage()
        val original = room()
        RoomRepository(disk).save(original.keysChanged())
        val restored = RoomRepository(disk).get(original.id)!!
        assertNull(restored.host(now))
        assertFailsWith<RoomRecoveryException> { restored.identity(now) }
    }

    @Test fun `forgetting one room and renaming another keeps their identities separate`() {
        val disk = MemoryStorage()
        val first = room("First")
        val second = room("Second")
        val repository = RoomRepository(disk)
        repository.save(first)
        repository.save(second)
        repository.update(second.id) { it.renamed("Garden") }
        repository.forget(first.id)
        val restored = RoomRepository(disk)
        assertNull(restored.get(first.id))
        assertEquals("Garden", restored.list().single().name)
        assertEquals(second.participant, restored.get(second.id)!!.identity(now).participant)
    }

    @Test fun `failed writes preserve the previous identity and invitation`() {
        val disk = MemoryStorage()
        val original = room()
        val repository = RoomRepository(disk)
        repository.save(original)
        val before = disk.value!!.copyOf()
        disk.failWrites = true
        assertFailsWith<RoomStorageException> { repository.update(original.id) { it.renamed("Changed") } }
        assertContentEquals(before, disk.value)
        assertEquals(original.participant, RoomRepository(disk).get(original.id)!!.participant)
    }

    @Test fun `corruption and unknown versions block new identities until an explicit reset`() {
        for (broken in listOf("not json", "{\"version\":2,\"rooms\":[]}")) {
            val disk = MemoryStorage().apply { value = broken.toByteArray() }
            val repository = RoomRepository(disk)
            assertFailsWith<RoomStorageException> { repository.list() }
            assertFailsWith<RoomStorageException> { repository.save(room()) }
            assertContentEquals(broken.toByteArray(), disk.value)
            repository.reset()
            repository.save(room())
            assertEquals(1, repository.list().size)
        }
    }

    @Test fun `swapped room secrets and duplicate ids fail closed`() {
        val first = room()
        val other = room()
        val wrong = JsonObject(first.json.toMutableMap().apply { put("secret", other.json.getValue("secret")) })
        for (rooms in listOf(JsonArray(listOf(wrong)), JsonArray(listOf(first.json, first.json)))) {
            val disk = MemoryStorage().apply { value = buildJsonObject { put("version", 1); put("rooms", rooms) }.toString().toByteArray() }
            assertFailsWith<RoomStorageException> { RoomRepository(disk).list() }
        }
    }

    @Test fun `concurrent saves do not lose another room`() {
        val disk = MemoryStorage()
        val repository = RoomRepository(disk)
        val rooms = (1..8).map { room("Room $it") }
        val executor = Executors.newFixedThreadPool(4)
        try { executor.invokeAll(rooms.map { r -> java.util.concurrent.Callable { repository.save(r) } }).forEach { it.get() } }
        finally { executor.shutdown() }
        assertEquals(rooms.map { it.id }.toSet(), repository.list().map { it.id }.toSet())
    }

    @Test fun `encryption survives a new cipher instance and uses a fresh nonce each time`() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cipher = RoomCipher { key }
        val plain = "private room access".toByteArray()
        val first = cipher.encrypt(plain)
        val second = cipher.encrypt(plain)
        assertFalse(first.contentEquals(second))
        assertContentEquals(plain, RoomCipher { key }.decrypt(first))
        assertFalse(first.toString(Charsets.UTF_8).contains("private room access"))
    }

    @Test fun `tampering and a missing key never fall back to a new key`() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cipher = RoomCipher { key }
        val sealed = cipher.encrypt("private".toByteArray())
        for (index in listOf(0, 1, sealed.lastIndex)) {
            val damaged = sealed.copyOf().apply { this[index] = (this[index].toInt() xor 1).toByte() }
            assertFails { cipher.decrypt(damaged) }
        }
        val missing = RoomCipher { create -> assertFalse(create); throw IOException("Missing key") }
        assertFailsWith<IOException> { missing.decrypt(sealed) }
    }
}

private class MemoryStorage : RoomStorage {
    var value: ByteArray? = null
    var failWrites = false
    override fun read(): ByteArray? = value?.copyOf()
    override fun write(value: ByteArray) {
        if (failWrites) throw IOException("Simulated disk failure")
        this.value = value.copyOf()
    }
    override fun reset() { value = null }
}
