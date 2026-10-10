package dev.forgesworn.kithmoot.storage

import dev.forgesworn.kithmoot.epoch.*
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.RoomRoute
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import java.io.IOException
import kotlin.test.*

class NativeSavedResetTest {
    private class Store : RoomStorage {
        var value: ByteArray? = null
        var resets = 0
        override fun read() = value?.clone()
        override fun write(value: ByteArray) { this.value = value.clone() }
        override fun reset() { resets++; value = null }
    }
    private val absent = NativeSavedReset.Inventory(emptySet())
    private fun corrupt() = Store().also { it.value = "broken ciphertext".toByteArray() }

    @Test fun corruptLegacyOnlyIndexCanBeExplicitlyResetAfterIndependentAbsenceProof() {
        val disk = corrupt(); val rooms = RoomRepository(disk)
        assertTrue(NativeSavedReset.records(rooms) { absent }.isEmpty())
        assertEquals(0, disk.resets)
        NativeSavedReset.requireCleared(absent); rooms.reset()
        assertEquals(1, disk.resets); assertTrue(rooms.list().isEmpty())
    }

    @Test fun backupNewFileKeyOnlyAndCreationStateEachProtectTheCorruptIndex() {
        val source = "kithmoot.keeper-authority." + "a".repeat(64)
        val courier = "kithmoot.keeper-rekeys." + "b".repeat(64)
        for (inventory in listOf(
            NativeSavedReset.names(listOf("$source.vault.bak"), emptyList()),
            NativeSavedReset.names(listOf("$source.vault.new"), emptyList()),
            NativeSavedReset.names(emptyList(), listOf(source)),
            NativeSavedReset.names(emptyList(), listOf(courier)),
            NativeSavedReset.names(listOf("kithmoot.native-creation.v1.vault"), emptyList()),
            NativeSavedReset.names(listOf("kithmoot.keeper-authority.future.vault"), emptyList()))) {
            val disk = corrupt(); val before = disk.value!!.clone()
            assertFails { NativeSavedReset.records(RoomRepository(disk)) { NativeSavedReset.Inventory(inventory) } }
            assertEquals(0, disk.resets); assertContentEquals(before, disk.value)
        }
    }

    @Test fun failedInspectionCannotBecomeAbsenceOrDeleteUnreadableStorage() {
        val disk = corrupt()
        assertFailsWith<IOException> { NativeSavedReset.records(RoomRepository(disk)) { throw IOException("Keystore unavailable") } }
        assertEquals(0, disk.resets)
    }

    @Test fun activeOwnerProtectsEvenMissingNativeFilesAndKeys() {
        val disk = corrupt()
        assertFails { NativeSavedReset.records(RoomRepository(disk)) { NativeSavedReset.Inventory(emptySet(), true) } }
        assertEquals(0, disk.resets)
    }

    @Test fun readableEmptyIndexCannotHideUnreferencedNativeAuthority() {
        val disk = Store(); val rooms = RoomRepository(disk)
        assertFails { NativeSavedReset.records(rooms) { NativeSavedReset.Inventory(setOf("kithmoot.keeper-authority.orphan")) } }
        assertEquals(0, disk.resets)
    }

    @Test fun actualNativeReferenceNamesTheOnlyPermittedCleanupAudience() {
        val at = 1_800_000_000L
        val fresh = NativeKeeperCreation.fresh(at)
        val base = fresh.roomSecret(); val invitation = fresh.invitation()
        val who = PrimaryIdentity.create(fresh.room, at + 3600, at)
        val binding = NativeKeeperBinding(fresh.room, fresh.authority, who.participant, who.devicePubkey, RoomRoute.NEARBY, emptyList())
        val saved = NativeKeeperJournal.create(Store(), binding, fresh, who.credential) { at }.use { source ->
            SavedRoom.create(base, who, encodeInvitationUrl("https://fixture.invalid/j/", invitation, emptyList()), emptyList(),
                "Reset fixture", at, null, binding.authority, route = RoomRoute.NEARBY).withNativeAuthority(source)
        }
        val disk = Store(); val rooms = RoomRepository(disk); rooms.saveNew(saved)
        val expected = NativeSavedReset.aliases(saved)
        assertEquals(2, expected.size)
        assertEquals(listOf(saved.id), NativeSavedReset.records(rooms) { NativeSavedReset.Inventory(expected) }.map { it.id })
        assertFails { NativeSavedReset.records(rooms) { NativeSavedReset.Inventory(expected + "kithmoot.keeper-rekeys.unreferenced") } }
        assertEquals(saved.json["nativeAuthority"], rooms.get(saved.id)!!.json["nativeAuthority"])
        assertEquals(0, disk.resets)
        base.fill(0); invitation.bearer.fill(0)
    }

    @Test fun unrelatedAccountAndLegacyAliasesAreNotIndependentNativeState() {
        assertTrue(NativeSavedReset.names(listOf("kithmoot.rooms.v1.vault", "kithmoot.accounts.vault"),
            listOf("kithmoot.rooms.v1", "another.application.keeper-authority.a")).isEmpty())
    }
}
