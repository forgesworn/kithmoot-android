package dev.forgesworn.kithmoot.epoch

import dev.forgesworn.kithmoot.protocol.RekeyNotice
import dev.forgesworn.kithmoot.protocol.EpochGrant
import dev.forgesworn.kithmoot.protocol.decodeEpochGrant
import dev.forgesworn.kithmoot.protocol.encodeEpochRequest
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.session.Fixtures
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.storage.RoomStorageException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import kotlin.test.assertIs
import org.junit.Test
import kotlinx.serialization.json.*

class EpochVaultTest {
    private val initial = ByteArray(32) { 7 }
    private val room = dev.forgesworn.kithmoot.protocol.deriveRoom(initial).roomId
    private val authority = "43".repeat(32)

    @Test fun `pending successor survives restart and only activates after cadence retirement`() {
        val storage = MemoryStorage()
        val vault = EpochVault(storage)
        vault.initialise(room, authority, initial, 100)
        assertArrayEquals(initial, EpochVault(storage).get(room)?.currentSecret)

        val successor = ByteArray(32) { 9 }
        val notice = RekeyNotice(1, listOf("55".repeat(32)), null, false, successor, 101)
        val coordinate = CadenceEpochCoordinate("a".repeat(52), "11".repeat(16), 1, room, 1)
        val pending = vault.beginTransition(room, 0, notice, "aa".repeat(32), coordinate, 102)
        assertEquals(EpochPhase.PENDING_CADENCE_RETIREMENT, pending.phase)
        assertEquals(0, pending.currentEpoch)
        assertArrayEquals(initial, pending.currentSecret)
        assertArrayEquals(successor, pending.pending?.secret)
        assertEquals(coordinate, pending.pending?.cadence)

        val restarted = EpochVault(storage)
        val recovered = restarted.get(room)!!
        assertEquals(EpochPhase.PENDING_CADENCE_RETIREMENT, recovered.phase)
        assertArrayEquals(successor, recovered.pending?.secret)
        restarted.beginTransition(room, 0, notice, "aa".repeat(32), coordinate, 103)
        assertThrows(IllegalArgumentException::class.java) {
            restarted.beginTransition(
                room, 0, RekeyNotice(1, notice.removed, notice.by, notice.closed, ByteArray(32) { 8 }, notice.at),
                "aa".repeat(32), coordinate, 103,
            )
        }

        val active = restarted.activate(room, 1, 104)
        assertEquals(EpochPhase.ACTIVE, active.phase)
        assertEquals(1, active.currentEpoch)
        assertArrayEquals(successor, active.currentSecret)
        assertNull(active.pending)
        assertEquals(active.currentEpoch, EpochVault(storage).activate(room, 1, 105).currentEpoch)
        assertEquals(1, EpochVault(storage).initialise(room, authority, initial.copyOf(), 106).currentEpoch)
    }

    @Test fun `when a granted epoch was left survives a restart`() {
        val storage = MemoryStorage()
        val history = MemoryStorage()
        val vault = EpochVault(storage, history)
        vault.initialise(room, authority, initial, 100)
        val handed = listOf(dev.forgesworn.kithmoot.protocol.RoomEpoch(2, ByteArray(32) { 2 }), dev.forgesworn.kithmoot.protocol.RoomEpoch(3, ByteArray(32) { 3 }))
        vault.remember(room, handed, emptyList(), mapOf(2 to 1_000L, 3 to 2_000L))

        val restarted = EpochVault(storage, history)
        assertEquals(1_000L, restarted.leftAt(room, 2))
        assertEquals(2_000L, restarted.leftAt(room, 3))
        assertNull(restarted.leftAt(room, 1))
        assertArrayEquals(ByteArray(32) { 2 }, restarted.secretAt(room, 2))
        // Remembering the secret again without a time keeps the time it had.
        restarted.remember(room, handed.take(1), emptyList())
        assertEquals(1_000L, EpochVault(storage, history).leftAt(room, 2))
    }

    @Test fun `epoch zero conflicts rollbacks and terminal states fail closed`() {
        val storage = MemoryStorage()
        val vault = EpochVault(storage)
        vault.initialise(room, authority, initial, 100)
        vault.initialise(room, authority, initial.copyOf(), 101)
        assertThrows(IllegalArgumentException::class.java) {
            vault.initialise(dev.forgesworn.kithmoot.protocol.deriveRoom(ByteArray(32) { 8 }).roomId, authority, initial, 101)
        }
        assertThrows(IllegalArgumentException::class.java) {
            vault.beginTransition(room, 0, RekeyNotice(2, emptyList(), null, false, ByteArray(32) { 9 }, 102), "aa".repeat(32), null, 102)
        }
        val removed = vault.terminal(
            room, 0, RekeyNotice(1, listOf("55".repeat(32)), null, false, null, 103), "cc".repeat(32), 103,
        )
        assertEquals(EpochPhase.REMOVED, removed.phase)
        assertEquals(listOf("55".repeat(32)), removed.removed)
        assertEquals(EpochPhase.REMOVED, vault.terminal(
            room, 0, RekeyNotice(1, listOf("55".repeat(32)), null, false, null, 103), "cc".repeat(32), 104,
        ).phase)
        assertThrows(IllegalArgumentException::class.java) {
            vault.beginTransition(room, 0, RekeyNotice(1, emptyList(), null, false, ByteArray(32) { 4 }, 104), "bb".repeat(32), null, 104)
        }
    }

    @Test fun `signed catch-up may cross a gap but direct transition may not`() {
        val storage = MemoryStorage()
        val vault = EpochVault(storage)
        vault.initialise(room, authority, initial, 100)
        val current = ByteArray(32) { 12 }
        val notice = RekeyNotice(3, listOf("55".repeat(32)), null, false, current, 101, catchUp = true)

        val pending = vault.beginCatchUp(room, 0, notice, "aa".repeat(32), null, 102)
        assertEquals(3, pending.pending?.epoch)
        assertEquals(0, EpochVault(storage).get(room)?.currentEpoch)
        assertEquals(3, EpochVault(storage).activate(room, 3, 103).currentEpoch)

        assertThrows(IllegalArgumentException::class.java) {
            EpochVault(MemoryStorage()).beginCatchUp(room, 0, notice, "aa".repeat(32), null, 104)
        }
    }

    @Test fun `damaged journal is never replaced by an empty epoch set`() {
        val storage = MemoryStorage("{bad".toByteArray())
        assertThrows(RoomStorageException::class.java) { EpochVault(storage).get(room) }
        assertEquals("{bad", storage.value?.decodeToString())
    }

    @Test fun `reopened creator grants durable current epoch and refuses removed and closed members`() {
        val storage = MemoryStorage()
        val vault = EpochVault(storage)
        val authoritySecret = Fixtures.key(61)
        val authorityPubkey = Schnorr.publicKeyHex(authoritySecret)
        val retained = Fixtures.primary(Fixtures.room(), 1, 2)
        val removed = Fixtures.primary(Fixtures.room(), 3, 4)
        vault.initialise(room, authorityPubkey, initial, 100)
        val successor = ByteArray(32) { 13 }
        vault.beginCatchUp(
            room, 0, RekeyNotice(2, listOf(removed.participant), null, false, successor, 101, catchUp = true),
            "aa".repeat(32), null, 101,
        )
        vault.activate(room, 2, 102)
        val roomKey = dev.forgesworn.kithmoot.protocol.deriveRoom(initial).roomKey
        // The retained member is one the room knows; see the next test for anybody else (#207).
        val responder = EpochRecoveryResponder(EpochVault(storage), room, authoritySecret, roomKey, null, now = { 103 },
            known = { it == retained.participant.lowercase() })

        val retainedRequest = encodeEpochRequest(room, authorityPubkey, roomKey, retained.deviceSecretKey, retained.credential, 103)
        val retainedAnswer = requireNotNull(responder.answer(retainedRequest))
        val current = assertIs<EpochGrant.Current>(
            decodeEpochGrant(retainedAnswer, room, authorityPubkey, retained.deviceSecretKey, retainedRequest.id, 103),
        )
        assertEquals(2, current.epoch)
        assertArrayEquals(successor, current.secret)

        val removedRequest = encodeEpochRequest(room, authorityPubkey, roomKey, removed.deviceSecretKey, removed.credential, 103)
        val removedAnswer = requireNotNull(responder.answer(removedRequest))
        assertEquals(
            EpochGrant.Refused("removed"),
            decodeEpochGrant(removedAnswer, room, authorityPubkey, removed.deviceSecretKey, removedRequest.id, 103),
        )

        vault.terminal(room, 2, RekeyNotice(3, emptyList(), null, true, null, 104), "bb".repeat(32), 104)
        val closedRequest = encodeEpochRequest(room, authorityPubkey, roomKey, retained.deviceSecretKey, retained.credential, 104)
        val closedAnswer = requireNotNull(responder.answer(closedRequest))
        assertEquals(
            EpochGrant.Refused("closed"),
            decodeEpochGrant(closedAnswer, room, authorityPubkey, retained.deviceSecretKey, closedRequest.id, 104),
        )
    }

    @Test fun `after a removal the creator answers somebody it does not know 'unknown', once, until they are let in`() {
        val storage = MemoryStorage()
        val vault = EpochVault(storage)
        val authoritySecret = Fixtures.key(62)
        val authorityPubkey = Schnorr.publicKeyHex(authoritySecret)
        val removed = Fixtures.primary(Fixtures.room(), 3, 4)
        val stranger = Fixtures.primary(Fixtures.room(), 7, 8)
        vault.initialise(room, authorityPubkey, initial, 100)
        val successor = ByteArray(32) { 14 }
        vault.beginCatchUp(room, 0, RekeyNotice(1, listOf(removed.participant), null, false, successor, 101, catchUp = true), "aa".repeat(32), null, 101)
        vault.activate(room, 1, 102)
        val roomKey = dev.forgesworn.kithmoot.protocol.deriveRoom(initial).roomKey
        val letIn = mutableSetOf<String>()
        val asked = mutableListOf<String>()
        val responder = EpochRecoveryResponder(EpochVault(storage), room, authoritySecret, roomKey, null, now = { 103 },
            known = { it in letIn }, members = { listOf(stranger.participant) }, onUnknown = { asked += it.participant })

        val request = encodeEpochRequest(room, authorityPubkey, roomKey, stranger.deviceSecretKey, stranger.credential, 103)
        val refusal = requireNotNull(responder.answer(request))
        assertEquals(EpochGrant.Refused("unknown"), decodeEpochGrant(refusal, room, authorityPubkey, stranger.deviceSecretKey, request.id, 103))
        assertEquals(listOf(stranger.participant), asked)
        // Asked again while waiting: not answered again, not reported again.
        assertNull(responder.answer(request))
        assertEquals(1, asked.size)
        // Let in: the same request is now granted, and told who the room knows.
        letIn += stranger.participant.lowercase()
        val grant = assertIs<EpochGrant.Current>(decodeEpochGrant(requireNotNull(responder.answer(request)), room, authorityPubkey, stranger.deviceSecretKey, request.id, 103))
        assertEquals(1, grant.epoch)
        assertEquals(listOf(stranger.participant.lowercase()), grant.members)
    }

    @Test fun `before anybody is removed the creator answers a newcomer as it always did`() {
        val storage = MemoryStorage()
        val vault = EpochVault(storage)
        val authoritySecret = Fixtures.key(63)
        val authorityPubkey = Schnorr.publicKeyHex(authoritySecret)
        val newcomer = Fixtures.primary(Fixtures.room(), 9, 10)
        vault.initialise(room, authorityPubkey, initial, 100)
        vault.beginCatchUp(room, 0, RekeyNotice(1, emptyList(), null, false, ByteArray(32) { 15 }, 101, catchUp = true), "aa".repeat(32), null, 101)
        vault.activate(room, 1, 102)
        val roomKey = dev.forgesworn.kithmoot.protocol.deriveRoom(initial).roomKey
        val responder = EpochRecoveryResponder(EpochVault(storage), room, authoritySecret, roomKey, null, now = { 103 }, known = { false })
        val request = encodeEpochRequest(room, authorityPubkey, roomKey, newcomer.deviceSecretKey, newcomer.credential, 103)
        assertIs<EpochGrant.Current>(decodeEpochGrant(requireNotNull(responder.answer(request)), room, authorityPubkey, newcomer.deviceSecretKey, request.id, 103))
    }

    @Test fun `history keeps the newest 32 epochs and authority rekeys apart from the journal`() {
        val journal = MemoryStorage()
        val history = MemoryStorage()
        val authoritySecret = Fixtures.key(61)
        val authorityPubkey = Schnorr.publicKeyHex(authoritySecret)
        val vault = EpochVault(journal, history)
        vault.initialise(room, authorityPubkey, initial, 100)
        val before = journal.value?.decodeToString()
        val secrets = (1..40).map { dev.forgesworn.kithmoot.protocol.RoomEpoch(it, ByteArray(32) { b -> (it + b).toByte() }) }
        var previous = dev.forgesworn.kithmoot.protocol.deriveEpoch(dev.forgesworn.kithmoot.protocol.RoomEpoch(0, initial))
        val rekeys = secrets.map { next ->
            dev.forgesworn.kithmoot.protocol.encodeRekeyEvent(room, authoritySecret, previous, next, listOf("44".repeat(32)), emptyList(), 100, commit = true)
                .also { previous = dev.forgesworn.kithmoot.protocol.deriveEpoch(next) }
        }
        vault.remember(room, secrets, rekeys)
        // The journal itself is untouched, so an older build still reads it.
        assertEquals(before, journal.value?.decodeToString())
        val reopened = EpochVault(journal, history)
        assertNull(reopened.secretAt(room, 8))
        assertNull(reopened.rekeyAt(room, 8))
        assertArrayEquals(secrets[8].secret, reopened.secretAt(room, 9))
        assertEquals(rekeys[39].id, reopened.rekeyAt(room, 40)?.id)
        // A rekey not signed by the room's authority is never kept.
        val forged = dev.forgesworn.kithmoot.protocol.encodeRekeyEvent(room, Fixtures.key(62), previous,
            dev.forgesworn.kithmoot.protocol.RoomEpoch(41, ByteArray(32) { 1 }), emptyList(), emptyList(), 100)
        reopened.remember(room, emptyList(), listOf(forged))
        assertNull(EpochVault(journal, history).rekeyAt(room, 41))
        // Without a history store nothing is kept and nothing breaks.
        EpochVault(journal).remember(room, secrets, rekeys)
        assertNull(EpochVault(journal).secretAt(room, 9))
    }

    @Test fun `activation keeps the epoch it leaves, and leaving the room forgets the history`() {
        val journal = MemoryStorage()
        val history = MemoryStorage()
        val vault = EpochVault(journal, history)
        vault.initialise(room, authority, initial, 100)
        val one = ByteArray(32) { 21 }
        val two = ByteArray(32) { 22 }
        vault.beginTransition(room, 0, RekeyNotice(1, emptyList(), null, false, one, 101), "aa".repeat(32), null, 101)
        vault.activate(room, 1, 102)
        assertArrayEquals(one, vault.secretAt(room, 1))
        vault.beginTransition(room, 1, RekeyNotice(2, emptyList(), null, false, two, 103), "bb".repeat(32), null, 103)
        vault.activate(room, 2, 104)
        assertArrayEquals(one, EpochVault(journal, history).secretAt(room, 1))
        assertArrayEquals(two, EpochVault(journal, history).secretAt(room, 2))
        assertNull(vault.secretAt(room, 0))
        vault.terminal(room, 2, RekeyNotice(3, listOf("55".repeat(32)), null, false, null, 105), "cc".repeat(32), 105)
        assertNull(EpochVault(journal, history).secretAt(room, 1))
        assertNull(EpochVault(journal, history).secretAt(room, 2))
    }

    @Test fun `forgetting a room erases its journal and history, and only its own`() {
        val journal = MemoryStorage()
        val history = MemoryStorage()
        val vault = EpochVault(journal, history)
        val otherSecret = ByteArray(32) { 8 }
        val other = dev.forgesworn.kithmoot.protocol.deriveRoom(otherSecret).roomId
        for ((id, secret) in listOf(room to initial, other to otherSecret)) {
            vault.initialise(id, authority, secret, 100)
            vault.beginTransition(id, 0, RekeyNotice(1, emptyList(), null, false, ByteArray(32) { 21 }, 101), "aa".repeat(32), null, 101)
            vault.activate(id, 1, 102)
            vault.beginTransition(id, 1, RekeyNotice(2, emptyList(), null, false, ByteArray(32) { 22 }, 103), "bb".repeat(32), null, 103)
            vault.activate(id, 2, 104)
        }
        vault.forget(room)
        val reopened = EpochVault(journal, history)
        assertNull(reopened.get(room))
        assertNull(reopened.secretAt(room, 1))
        assertEquals(2, reopened.get(other)?.currentEpoch)
        assertArrayEquals(ByteArray(32) { 21 }, reopened.secretAt(other, 1))
        // Forgotten, the room starts again from its link like any room never opened here.
        assertEquals(0, reopened.initialise(room, authority, initial, 105).currentEpoch)
    }

    @Test fun `the sweep keeps saved rooms, and keeps everything when the saved list cannot be read`() {
        val journal = MemoryStorage()
        val history = MemoryStorage()
        val vault = EpochVault(journal, history)
        val otherSecret = ByteArray(32) { 8 }
        val other = dev.forgesworn.kithmoot.protocol.deriveRoom(otherSecret).roomId
        for ((id, secret) in listOf(room to initial, other to otherSecret)) {
            vault.initialise(id, authority, secret, 100)
            vault.beginTransition(id, 0, RekeyNotice(1, emptyList(), null, false, ByteArray(32) { 21 }, 101), "aa".repeat(32), null, 101)
            vault.activate(id, 1, 102)
        }
        assertThrows(RoomStorageException::class.java) {
            vault.retainOnly { throw RoomStorageException(java.io.IOException("unreadable")) }
        }
        assertEquals(1, EpochVault(journal, history).get(room)?.currentEpoch)
        assertEquals(1, EpochVault(journal, history).get(other)?.currentEpoch)

        vault.retainOnly { setOf(other) }
        assertNull(EpochVault(journal, history).get(room))
        assertNull(EpochVault(journal, history).secretAt(room, 1))
        assertArrayEquals(ByteArray(32) { 21 }, EpochVault(journal, history).secretAt(other, 1))

        vault.reset()
        assertNull(journal.value)
        assertNull(history.value)
        assertNull(vault.get(other))
        assertNull(vault.secretAt(other, 1))
    }

    @Test fun `receiver activation cause survives reopen and later pending and terminal states`() {
        val storage = MemoryStorage(); val vault = EpochVault(storage)
        vault.initialise(room, authority, initial, 100)
        val original = "aa".repeat(32)
        val first = RekeyNotice(1, emptyList(), null, false, ByteArray(32) { 9 }, 101)
        vault.beginTransition(room, 0, first, original, null, 101); vault.activate(room, 1, 102)
        assertEquals(original, EpochVault(storage).get(room)!!.activationCause)
        val second = RekeyNotice(2, emptyList(), null, false, ByteArray(32) { 10 }, 103)
        vault.beginTransition(room, 1, second, "bb".repeat(32), null, 103)
        assertEquals(original, EpochVault(storage).get(room)!!.activationCause)
        vault.activate(room, 2, 104)
        val terminal = RekeyNotice(3, emptyList(), null, true, null, 105)
        vault.terminal(room, 2, terminal, "cc".repeat(32), 105)
        val closed = EpochVault(storage).get(room)!!
        assertEquals("bb".repeat(32), closed.activationCause); assertEquals("cc".repeat(32), closed.terminalCause)
        assertEquals(2, Json.parseToJsonElement(storage.value!!.decodeToString()).jsonObject.getValue("version").jsonPrimitive.int)
    }

    @Test fun `matching successor secret cannot substitute a different activation cause or removals`() {
        val storage = MemoryStorage(); val vault = EpochVault(storage)
        vault.initialise(room, authority, initial, 100)
        val notice = RekeyNotice(1, emptyList(), null, false, ByteArray(32) { 9 }, 101)
        vault.beginTransition(room, 0, notice, "aa".repeat(32), null, 101); vault.activate(room, 1, 102)
        assertEquals(1, vault.beginTransition(room, 0, notice, "aa".repeat(32), null, 103).currentEpoch)
        assertThrows(IllegalArgumentException::class.java) {
            vault.beginTransition(room, 0, notice, "bb".repeat(32), null, 103)
        }
        assertThrows(IllegalArgumentException::class.java) {
            vault.beginTransition(room, 0, RekeyNotice(1, listOf("dd".repeat(32)), null, false, notice.secret, 101), "aa".repeat(32), null, 103)
        }
    }

    @Test fun `legacy receiver state reads without inventing activation evidence`() {
        val storage = MemoryStorage(); val vault = EpochVault(storage)
        vault.initialise(room, authority, initial, 100)
        val notice = RekeyNotice(1, emptyList(), null, false, ByteArray(32) { 9 }, 101)
        vault.beginTransition(room, 0, notice, "aa".repeat(32), null, 101); vault.activate(room, 1, 102)
        val root = Json.parseToJsonElement(storage.value!!.decodeToString()).jsonObject
        storage.value = buildJsonObject {
            put("version", 1); put("rooms", JsonArray(root.getValue("rooms").jsonArray.map { JsonObject(it.jsonObject - "activationCause") }))
        }.toString().toByteArray()
        val legacy = EpochVault(storage).get(room)!!
        assertEquals(1, legacy.currentEpoch); assertNull(legacy.activationCause)
        storage.value = JsonObject(root + ("version" to JsonPrimitive("2"))).toString().toByteArray()
        assertThrows(RoomStorageException::class.java) { EpochVault(storage).get(room) }
    }

    private class MemoryStorage(initial: ByteArray? = null) : RoomStorage {
        var value = initial?.copyOf()
        override fun read(): ByteArray? = value?.copyOf()
        override fun write(value: ByteArray) { this.value = value.copyOf() }
        override fun reset() { value = null }
    }
}
