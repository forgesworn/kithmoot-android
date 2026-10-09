package dev.forgesworn.kithmoot.storage

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.epoch.*
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.RoomRoute
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import kotlinx.serialization.json.*
import kotlin.test.*

class NativeRoomCreationTest {
    private val now = 1_800_000_000L
    private class Store : RoomStorage {
        var value: ByteArray? = null
        var beforeWrite = false
        var afterWrite = false
        var beforeReset = false
        var afterReset = false
        override fun read() = value?.clone()
        override fun write(value: ByteArray) {
            if (beforeWrite) error("write refused before commit")
            this.value = value.clone()
            if (afterWrite) error("write return lost after commit")
        }
        override fun reset() {
            if (beforeReset) error("delete refused before commit")
            value = null
            if (afterReset) error("delete return lost after commit")
        }
    }
    private inner class Rig(val route: RoomRoute = RoomRoute.MIXED) {
        val creation = NativeKeeperCreation.fresh(now)
        val secret = creation.roomSecret()
        val invitation = creation.invitation()
        val who = PrimaryIdentity.create(creation.room, now + 3600, now)
        val relays = if (route.internet) listOf("wss://fixture.invalid/") else emptyList()
        val draft = SavedRoom.create(secret, who, encodeInvitationUrl("https://fixture.invalid/j/", invitation, relays),
            relays, "Creation", now, null, creation.authority, route = route)
        val intent = Store(); val source = Store(); val saved = Store()
        val rooms = RoomRepository(saved)
        var creates = 0; var opens = 0
        fun coordinator() = NativeRoomCreation(intent, rooms,
            { binding, fresh, credential -> creates++; NativeKeeperJournal.create(source, binding, fresh, credential) { now } },
            { binding -> opens++; NativeKeeperJournal.open(source, binding) { now } },
            owner = "creation-test:${creation.room}")
        fun native() = assertNotNull(rooms.get(draft.id))
        fun journal() = NativeKeeperJournal.open(source, requireNotNull(native().nativeAuthority)) { now }
    }

    @Test fun freshCreationPersistsOriginalOwnerReferenceAndSoleSourceAcrossAllRoutes() {
        for (route in RoomRoute.entries) {
            val r = Rig(route)
            r.coordinator().use { coordinator ->
                assertNull(coordinator.pending())
                val saved = coordinator.begin(r.creation, r.draft, r.who.credential)
                assertEquals(r.who.participant, saved.participant); assertEquals(r.who.devicePubkey, saved.devicePubkey)
                assertEquals(r.draft.joinUrl, saved.joinUrl); assertNull(saved.host(now))
                assertNull(r.intent.value); assertNull(coordinator.recover())
                assertEquals(1, r.creates); assertEquals(0, r.opens)
            }
            assertFails { r.creation.roomSecret() }
            r.journal().use { source ->
                r.native().verifyNativeAuthority(source)
                assertTrue(source.snapshot().suspended); assertEquals(0, source.snapshot().epoch)
            }
        }
    }

    @Test fun ambiguousIntentWriteNeverTransfersSourceAndColdMissingSourcePreservesEverything() {
        val r = Rig(); r.intent.afterWrite = true
        r.coordinator().use { coordinator ->
            assertFails { coordinator.begin(r.creation, r.draft, r.who.credential) }
            assertFails { coordinator.recover() }
        }
        assertEquals(0, r.creates); assertNull(r.source.value); assertNull(r.saved.value)
        assertFails { r.creation.invitation() }
        val intent = assertNotNull(r.intent.value).clone(); r.intent.afterWrite = false
        r.coordinator().use { coordinator ->
            assertEquals(r.draft.id, coordinator.pending()?.id)
            assertFalse(assertNotNull(coordinator.pending()).canShareInvite)
            assertFails { coordinator.recover() }
        }
        assertContentEquals(intent, r.intent.value); assertNull(r.source.value); assertNull(r.saved.value)
        assertEquals(0, r.creates); assertEquals(1, r.opens)
    }

    @Test fun preCommitIntentFailureLeavesNoSourceOrSavedRoomAndRequiresAnotherOwner() {
        val r = Rig(); r.intent.beforeWrite = true
        r.coordinator().use { coordinator ->
            assertFails { coordinator.begin(r.creation, r.draft, r.who.credential) }
            assertFails { coordinator.pending() }
        }
        assertNull(r.intent.value); assertNull(r.source.value); assertNull(r.saved.value)
        r.intent.beforeWrite = false
        r.coordinator().use { assertNull(it.recover()) }
        assertEquals(0, r.creates); assertEquals(0, r.opens)
    }

    @Test fun ambiguousSourceCommitRecoversOriginalSignerIdentityAndLinkWithoutAnotherCreation() {
        for (route in RoomRoute.entries) {
            val r = Rig(route); r.source.afterWrite = true
            r.coordinator().use { assertFails { it.begin(r.creation, r.draft, r.who.credential) } }
            assertEquals(1, r.creates); assertNull(r.saved.value)
            val original = assertNotNull(r.source.value).clone()
            val sourceJson = Json.parseToJsonElement(original.toString(Charsets.UTF_8)).jsonObject
            val signer = sourceJson.getValue("signer").jsonPrimitive.content
            assertFalse(r.intent.value!!.toString(Charsets.UTF_8).contains(signer))
            r.source.afterWrite = false
            r.coordinator().use { coordinator ->
                val saved = assertNotNull(coordinator.recover())
                assertEquals(r.draft.participant, saved.participant); assertEquals(r.draft.devicePubkey, saved.devicePubkey)
                assertEquals(r.draft.authority, saved.authority); assertEquals(r.draft.joinUrl, saved.joinUrl)
            }
            assertContentEquals(original, r.source.value); assertNull(r.intent.value); assertEquals(1, r.creates)
            assertFalse(r.saved.value!!.toString(Charsets.UTF_8).contains(signer))
            r.journal().use { r.native().verifyNativeAuthority(it) }
            println("NATIVE_CREATION_MEASUREMENT {\"case\":\"ambiguous-source-commit-recovery\",\"route\":\"${route.stored}\",\"room\":\"${r.draft.id}\",\"root\":\"${r.draft.authority}\",\"sourceCreates\":${r.creates},\"sourceBytesUnchanged\":true,\"identityUnchanged\":true,\"rootKeyInIntentOrSavedRoom\":false}")
        }
    }

    @Test fun preCommitSourceFailureKeepsIntentButNeverReconstructsConsumedSigner() {
        val r = Rig(); r.source.beforeWrite = true
        r.coordinator().use { assertFails { it.begin(r.creation, r.draft, r.who.credential) } }
        val intent = assertNotNull(r.intent.value).clone()
        assertNull(r.source.value); assertFails { r.creation.roomSecret() }
        r.source.beforeWrite = false
        r.coordinator().use { assertFails { it.recover() } }
        assertContentEquals(intent, r.intent.value); assertNull(r.saved.value); assertEquals(1, r.creates)
    }

    @Test fun ambiguousSavedRoomCommitFinishesSameReferenceWithoutReplacingIdentity() {
        val r = Rig(); r.saved.afterWrite = true
        r.coordinator().use { assertFails { it.begin(r.creation, r.draft, r.who.credential) } }
        val source = assertNotNull(r.source.value).clone(); val saved = assertNotNull(r.saved.value).clone()
        r.saved.afterWrite = false
        r.coordinator().use { coordinator ->
            assertEquals(r.draft.devicePubkey, coordinator.recover()?.devicePubkey)
            assertNull(coordinator.recover())
        }
        assertContentEquals(source, r.source.value); assertContentEquals(saved, r.saved.value)
        assertNull(r.intent.value); assertEquals(1, r.creates)
    }

    @Test fun deleteBeforeCommitRecoveryPreservesLaterMetadataAndExistingSourceDebt() {
        val r = Rig(); r.intent.beforeReset = true
        r.coordinator().use { assertFails { it.begin(r.creation, r.draft, r.who.credential) } }
        r.rooms.update(r.draft.id) { it.renamed("Later name").withPinned(true).invitationRetired() }
        var charged = 0
        r.journal().use { source ->
            source.bind { true }
            assertNotNull(source.answer(encodeLivePersistentRequest(LivePersistentContext(r.invitation, r.draft.id),
                Entropy.bytes(32), now), RekeyLane.NEARBY))
            charged = source.snapshot().nearbyBytes
        }
        val source = r.source.value!!.clone(); val saved = r.saved.value!!.clone()
        r.intent.beforeReset = false
        r.coordinator().use { coordinator ->
            val restored = assertNotNull(coordinator.recover())
            assertEquals("Later name", restored.name); assertTrue(restored.pinned); assertTrue(restored.retired)
        }
        assertContentEquals(source, r.source.value); assertContentEquals(saved, r.saved.value)
        r.journal().use { assertEquals(charged, it.snapshot().nearbyBytes) }
        assertEquals(1, r.creates); assertNull(r.intent.value)
    }

    @Test fun deleteAfterCommitLostReturnReopensWithoutAnyReplacementOrExtraSourceWrite() {
        val r = Rig(); r.intent.afterReset = true
        r.coordinator().use { coordinator ->
            assertFails { coordinator.begin(r.creation, r.draft, r.who.credential) }
            assertFails { coordinator.recover() }
        }
        val saved = r.saved.value!!.clone(); val source = r.source.value!!.clone()
        assertNull(r.intent.value); r.intent.afterReset = false
        r.coordinator().use { assertNull(it.recover()) }
        assertEquals(1, r.creates); assertEquals(0, r.opens)
        assertContentEquals(saved, r.saved.value); assertContentEquals(source, r.source.value)
        r.journal().use { r.native().verifyNativeAuthority(it) }
    }

    @Test fun conflictingSavedRoomAndCorruptIntentRefuseWithoutMutation() {
        val r = Rig(); r.saved.beforeWrite = true
        r.coordinator().use { assertFails { it.begin(r.creation, r.draft, r.who.credential) } }
        r.saved.beforeWrite = false
        val oldHost = createRoomInvitation(true)
        val legacy = SavedRoom.create(r.secret, r.who, encodeInvitationUrl("https://fixture.invalid/j/", oldHost.invitation, r.relays),
            r.relays, "Other authority", now, oldHost, oldHost.invitation.canonicalInviter, route = r.route)
        r.rooms.save(legacy)
        val source = r.source.value!!.clone(); val saved = r.saved.value!!.clone(); val originalIntent = r.intent.value!!.clone()
        r.coordinator().use { assertFails { it.recover() } }
        assertContentEquals(source, r.source.value); assertContentEquals(saved, r.saved.value); assertContentEquals(originalIntent, r.intent.value)
        r.intent.value = "invalid intent".toByteArray()
        r.coordinator().use { assertFails { it.pending() }; assertFails { it.recover() } }
        assertEquals("invalid intent", r.intent.value!!.toString(Charsets.UTF_8)); assertEquals(1, r.creates)
    }

    @Test fun duplicateIntentOwnerCannotCloseOrOverwriteFirstAndRejectsUnrelatedCredential() {
        val r = Rig()
        r.coordinator().use { first ->
            assertFails { r.coordinator() }
            assertEquals(r.draft.id, first.begin(r.creation, r.draft, r.who.credential).id)
        }
        r.coordinator().use { assertNull(it.recover()) }
        val wrong = Rig(); val stranger = PrimaryIdentity.create(wrong.draft.id, now + 3600, now)
        wrong.coordinator().use { assertFails { it.begin(wrong.creation, wrong.draft, stranger.credential) } }
        assertNull(wrong.intent.value); assertNull(wrong.source.value); assertNull(wrong.saved.value)
        assertEquals(0, wrong.creates)
    }

    @Test fun forgedIntentBearerAndVersionCannotSubstituteForActualSource() {
        val r = Rig(); r.saved.beforeWrite = true
        r.coordinator().use { assertFails { it.begin(r.creation, r.draft, r.who.credential) } }
        r.saved.beforeWrite = false
        val original = Json.parseToJsonElement(r.intent.value!!.toString(Charsets.UTF_8)).jsonObject
        val source = r.source.value!!.clone()
        val bearer = RoomInvitation(Entropy.bytes(32), r.creation.authority, true)
        val reference = JsonObject(original.getValue("reference").jsonObject + ("invitation" to JsonPrimitive(deriveInvitationId(bearer))))
        val draft = JsonObject(original.getValue("room").jsonObject +
            ("joinUrl" to JsonPrimitive(encodeInvitationUrl("https://fixture.invalid/j/", bearer, r.relays))))
        for (forged in listOf(JsonObject(original + ("v" to JsonPrimitive(2))),
            JsonObject(original + ("v" to JsonPrimitive("1"))),
            JsonObject(original + mapOf("reference" to reference, "room" to draft)))) {
            val bytes = forged.toString().toByteArray(); r.intent.value = bytes
            r.coordinator().use { assertFails { it.recover() } }
            assertContentEquals(bytes, r.intent.value); assertContentEquals(source, r.source.value); assertNull(r.saved.value)
        }
        r.intent.value = original.toString().toByteArray()
        r.coordinator().use { assertNotNull(it.recover()) }
        assertEquals(1, r.creates)
    }

    @Test fun corruptSourceCannotClearUnfinishedIntentOrRecreateAnAuthority() {
        val r = Rig(); r.source.afterWrite = true
        r.coordinator().use { assertFails { it.begin(r.creation, r.draft, r.who.credential) } }
        r.source.afterWrite = false
        val original = r.source.value!!.clone(); val intent = r.intent.value!!.clone()
        r.source.value = "corrupt authority".toByteArray()
        r.coordinator().use { assertFails { it.recover() } }
        assertContentEquals(intent, r.intent.value); assertNull(r.saved.value)
        assertEquals("corrupt authority", r.source.value!!.toString(Charsets.UTF_8)); assertEquals(1, r.creates)
        r.source.value = original
        r.coordinator().use { assertNotNull(it.recover()) }
        assertEquals(1, r.creates)
    }
}
