package dev.forgesworn.kithmoot.epoch

import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.RoomRoute
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import dev.forgesworn.kithmoot.storage.RoomStorage
import org.junit.Test
import kotlin.test.*

class NativeRekeyPreflightTest {
    private class Store : RoomStorage {
        var bytes: ByteArray? = null
        override fun read() = bytes?.clone()
        override fun write(value: ByteArray) { bytes = value.clone() }
        override fun reset() = error("Never reset source accounting")
    }
    private fun key(seed: Int) = ByteArray(32).apply { this[30] = (seed ushr 8).toByte(); this[31] = seed.toByte() }
    private inner class Rig : AutoCloseable {
        var at = 1000L
        val creation = NativeKeeperCreation.fresh(at)
        val base = creation.roomSecret(); val room = deriveRoom(base)
        val owner = PrimaryIdentity.create(room.roomId, 10_000, at, key(1), key(2))
        val binding = NativeKeeperBinding(room.roomId, creation.authority, owner.participant, owner.devicePubkey,
            RoomRoute.MIXED, listOf("wss://fixture.invalid/"))
        val store = Store()
        var source = NativeKeeperJournal.create(store, binding, creation, owner.credential) { at }.also { it.bind { true } }
        fun approve(identity: PrimaryIdentity) {
            val ask = encodeEpochRequest(room.roomId, binding.authority, room.roomKey,
                identity.deviceSecretKey, identity.credential, at)
            assertNotNull(source.answerEpoch(ask, RekeyLane.INTERNET)); source.approve(identity.participant)
        }
        override fun close() { source.close(); creation.close(); base.fill(0); room.roomKey.fill(0); store.bytes?.fill(0) }
    }

    @Test fun forgedForeignReplacedAndConsumedHandlesNeverConferAuthority() {
        Rig().use { a -> Rig().use { b ->
            val first = a.source.preflightMembers(); val before = a.store.bytes!!.clone()
            assertFails { a.source.prepareRekey(NativeKeeperJournal.RekeyProposal()) }
            assertFails { b.source.prepareRekey(first) }
            val replacement = a.source.preflightMembers()
            assertFails { a.source.prepareRekey(first) }; assertTrue(before.contentEquals(a.store.bytes))
            assertTrue(a.source.snapshot().pending.isEmpty())
            val signed = a.source.prepareRekey(replacement).single(); assertTrue(Events.verify(signed))
            val committed = a.store.bytes!!.clone()
            assertFails { a.source.prepareRekey(replacement) }; assertTrue(committed.contentEquals(a.store.bytes))
        } }
    }

    @Test fun aDurableRevisionChangeInvalidatesAProposalWithoutAnotherSourceWrite() {
        Rig().use { r ->
            val proposal = r.source.preflightMembers()
            val ask = encodeEpochRequest(r.room.roomId, r.binding.authority, r.room.roomKey,
                r.owner.deviceSecretKey, r.owner.credential, r.at)
            assertNotNull(r.source.answerEpoch(ask, RekeyLane.NEARBY))
            val before = r.store.bytes!!.clone()
            assertFailsWith<NativeRekeyRefusedException> { r.source.prepareRekey(proposal) }
            assertTrue(before.contentEquals(r.store.bytes)); assertTrue(r.source.snapshot().pending.isEmpty())
            assertFalse(r.source.persistenceFailed())
        }
    }

    @Test fun proposalHandlesDoNotSurviveTheActualSourceOwnerReopen() {
        Rig().use { r ->
            val handle = r.source.preflightMembers(); r.source.close()
            r.source = NativeKeeperJournal.open(r.store, r.binding) { r.at }.also { it.bind { true } }
            val before = r.store.bytes!!.clone()
            assertFails { r.source.prepareRekey(handle) }; assertTrue(before.contentEquals(r.store.bytes))
            assertTrue(Events.verify(r.source.prepareRekey(r.source.preflightMembers()).single()))
        }
    }

    @Test fun aCredentialCanExpireBetweenProposalAndSubmissionAndRefuseBeforeCommit() {
        Rig().use { r ->
            val member = PrimaryIdentity.create(r.room.roomId, r.at + 1, r.at, key(3), key(4))
            r.approve(member); val handle = r.source.preflightMembers(); val before = r.store.bytes!!.clone()
            r.at += 2
            assertFailsWith<NativeRekeyRefusedException> { r.source.prepareRekey(handle) }
            assertTrue(before.contentEquals(r.store.bytes)); assertTrue(r.source.snapshot().pending.isEmpty())
            assertFalse(r.source.persistenceFailed())
        }
    }

    @Test fun aQualifiedFrozenProposalCannotBeAlteredByMutableCallerTags() {
        Rig().use { r ->
            val handle = r.source.preflightRekey(listOf(r.owner.credential))
            @Suppress("UNCHECKED_CAST")
            val tag = r.owner.credential.tags.first() as MutableList<String>
            tag[1] = "ff".repeat(32)
            assertFalse(Events.verify(r.owner.credential))
            assertTrue(Events.verify(r.source.prepareRekey(handle).single()))
        }
    }

    @Test fun aCompleteBoundedAudienceWithLargeValidRemovalListRefusesBeforeCommit() {
        Rig().use { r ->
            val members = (10..136).map { seed ->
                r.at += 11
                PrimaryIdentity.create(r.room.roomId, 10_000, r.at, key(seed), key(seed + 256)).also { r.approve(it) }
            }
            val before = r.store.bytes!!.clone()
            assertEquals(128, r.source.snapshot().members.size)
            val gone = members.take(96).map { it.participant }
            val remaining = listOf(r.owner.credential) + members.drop(96).map { it.credential }
            assertEquals(32, remaining.size)
            assertFails { r.source.preflightRekey(remaining, removed = gone) }
            assertFails { r.source.preflightMembers(removed = gone) }
            assertTrue(before.contentEquals(r.store.bytes)); assertTrue(r.source.snapshot().pending.isEmpty())
            assertFalse(r.source.persistenceFailed())
        }
    }
}
