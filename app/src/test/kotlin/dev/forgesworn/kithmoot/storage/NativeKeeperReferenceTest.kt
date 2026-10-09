package dev.forgesworn.kithmoot.storage

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.epoch.*
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.RoomRoute
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import kotlinx.serialization.json.*
import kotlin.test.*

class NativeKeeperReferenceTest {
    private val now = 1_800_000_000L
    private val relays = listOf("wss://fixture.invalid/")
    private val base = "https://fixture.invalid/j/"
    private inner class Rig(val route: RoomRoute = RoomRoute.MIXED, val end: Long? = null, val destruct: Boolean = false) {
        val creation = NativeKeeperCreation.fresh(now, ends = end, destruct = destruct)
        val secret = creation.roomSecret()
        val invitation = creation.invitation()
        val owner = PrimaryIdentity.create(creation.room, now + 3600, now)
        val binding = NativeKeeperBinding(creation.room, creation.authority, owner.participant, owner.devicePubkey,
            route, if (route.internet) relays else emptyList())
        val sourceDisk = MemoryStorage()
        val savedDisk = MemoryStorage()
        fun source() = NativeKeeperJournal.create(sourceDisk, binding, creation, owner.credential) { now }
        fun reopen(saved: SavedRoom) = NativeKeeperJournal.open(sourceDisk, requireNotNull(saved.nativeAuthority)) { now }
        fun saved(invitation: RoomInvitation = this.invitation, who: PrimaryIdentity = owner,
            route: RoomRoute = this.route, relays: List<String> = if (route.internet) this@NativeKeeperReferenceTest.relays else emptyList(),
            ends: Long? = end, destruct: Boolean = this.destruct) = SavedRoom.create(secret, who,
            encodeInvitationUrl(base, invitation, relays), relays, "Native", now, null, invitation.canonicalInviter,
            ends = ends, destruct = destruct, route = route)
    }

    @Test fun sourcePersistsBeforeNonSigningReferenceAndRealRepositoryReopenPreservesOriginalAnswerDebt() {
        for (route in RoomRoute.entries) {
            val r = Rig(route)
            lateinit var original: NostrEvent
            lateinit var request: NostrEvent
            var debt = 0
            r.source().use { source ->
                val saved = r.saved().withNativeAuthority(source)
                RoomRepository(r.savedDisk).saveNew(saved)
                assertNotNull(r.sourceDisk.value)
                assertFalse("host" in saved.json)
                assertEquals(2, Json.parseToJsonElement(r.savedDisk.value!!.toString(Charsets.UTF_8)).jsonObject["version"]!!.jsonPrimitive.int)
                val marker = saved.json.getValue("nativeAuthority").jsonObject
                assertEquals(setOf("v", "profile", "room", "root", "participant", "device", "route", "relays", "pin", "invitation"), marker.keys)
                assertFalse(marker.keys.any { it.contains("key", ignoreCase = true) || it.contains("secret", ignoreCase = true) })
                source.bind { true }
                val lane = if (route.nearby) RekeyLane.NEARBY else RekeyLane.INTERNET
                request = encodeLivePersistentRequest(LivePersistentContext(r.invitation, r.binding.room), Entropy.bytes(32), now)
                original = assertNotNull(source.answer(request, lane)).event
                debt = if (lane == RekeyLane.NEARBY) source.snapshot().nearbyBytes else source.snapshot().internetBytes
            }
            val saved = assertNotNull(RoomRepository(r.savedDisk).get(r.binding.room))
            assertEquals(r.binding.pin, saved.nativeAuthority?.pin)
            assertNull(saved.host(now)); assertEquals(r.owner.devicePubkey, saved.devicePubkey)
            r.reopen(saved).use { source ->
                saved.verifyNativeAuthority(source)
                assertTrue(source.snapshot().suspended)
                assertEquals(debt, if (route.nearby) source.snapshot().nearbyBytes else source.snapshot().internetBytes)
                val persisted = Json.parseToJsonElement(r.sourceDisk.value!!.toString(Charsets.UTF_8)).jsonObject
                    .getValue("answers").jsonArray.single().jsonObject.getValue("answer").jsonObject
                assertEquals(original, NostrEvent.fromJson(persisted))
                source.bind { true }
                val lane = if (route.nearby) RekeyLane.NEARBY else RekeyLane.INTERNET
                assertEquals(original, assertNotNull(source.answer(request, lane)).event)
                val afterRetry = if (route.nearby) source.snapshot().nearbyBytes else source.snapshot().internetBytes
                assertEquals(debt * 2, afterRetry)
                println("NATIVE_REFERENCE_MEASUREMENT {\"case\":\"repository-and-source-reopen\",\"route\":\"${route.stored}\",\"pin\":\"${r.binding.pin}\",\"original\":\"${original.id}\",\"debtBefore\":$debt,\"debtAfterReopen\":$debt,\"debtAfterRetry\":$afterRetry,\"rootKeyInSavedRoom\":false}")
            }
        }
    }

    @Test fun missingOrCorruptIndependentSourceCannotBeReconstructedFromSavedFields() {
        val r = Rig()
        r.source().use { RoomRepository(r.savedDisk).saveNew(r.saved().withNativeAuthority(it)) }
        val saved = assertNotNull(RoomRepository(r.savedDisk).get(r.binding.room))
        val referenceBytes = r.savedDisk.value!!.clone()
        val sourceBytes = r.sourceDisk.value!!.clone()
        r.sourceDisk.value = null
        assertFails { r.reopen(saved) }; assertNull(r.sourceDisk.value); assertNull(saved.host(now))
        r.sourceDisk.value = "corrupt source".toByteArray()
        assertFails { r.reopen(saved) }
        assertEquals("corrupt source", r.sourceDisk.value!!.toString(Charsets.UTF_8))
        assertContentEquals(referenceBytes, r.savedDisk.value)
        r.sourceDisk.value = sourceBytes
        r.reopen(saved).use { saved.verifyNativeAuthority(it) }
    }

    @Test fun policyAndOwnerEditsRefuseBeforeRepositoryWriteAndKeepChargedSourceBytes() {
        val r = Rig()
        lateinit var saved: SavedRoom
        r.source().use { source ->
            saved = r.saved().withNativeAuthority(source)
            RoomRepository(r.savedDisk).saveNew(saved)
            source.bind { true }
            assertNotNull(source.answer(encodeLivePersistentRequest(LivePersistentContext(r.invitation, r.binding.room),
                Entropy.bytes(32), now), RekeyLane.NEARBY))
        }
        val sourceBytes = r.sourceDisk.value!!.clone(); val savedBytes = r.savedDisk.value!!.clone()
        val repository = RoomRepository(r.savedDisk)
        assertFails { repository.update(saved.id) { it.withRoute(RoomRoute.NEARBY) } }
        assertFails { repository.update(saved.id) { it.withRelays(listOf("wss://other.invalid/")) } }
        val differentOwner = r.saved(who = PrimaryIdentity.create(r.binding.room, now + 3600, now))
        assertFails { differentOwner.retainingHistory(saved) }
        val device = JsonObject(saved.json.getValue("identity").jsonObject +
            ("deviceKey" to JsonPrimitive("08".repeat(32))))
        assertFails { SavedRoom.decode(JsonObject(saved.json + ("identity" to device))) }
        assertFails { repository.save(r.saved()) }
        assertFails { repository.update(saved.id) { r.saved() } }
        assertContentEquals(sourceBytes, r.sourceDisk.value); assertContentEquals(savedBytes, r.savedDisk.value)
        repository.update(saved.id) { it.renamed("Changed").inProject("Lab").withPinned(true) }
        assertEquals(saved.nativeAuthority?.pin, repository.get(saved.id)!!.nativeAuthority?.pin)
        r.reopen(repository.get(saved.id)!!).close()
    }

    @Test fun bookmarkRefreshKeepsLocalBindingInvitationAndLifecycleAcrossOldHints() {
        for (route in RoomRoute.entries) {
            val r = Rig(route)
            r.source().use { source ->
                val previous = r.saved().withNativeAuthority(source).withEpochHint(3).invitationRetired()
                val refreshed = r.saved(route = RoomRoute.INTERNET, relays = listOf("wss://old-hint.invalid/"))
                    .retainingHistory(previous)
                assertEquals(previous.json.getValue("nativeAuthority"), refreshed.json.getValue("nativeAuthority"))
                assertEquals(route, refreshed.route); assertEquals(previous.relays, refreshed.relays)
                assertTrue(refreshed.retired); assertEquals(3, refreshed.epochHint); assertNull(refreshed.host(now))
                val old = r.saved(invitation = createRoomInvitation().invitation).retainingHistory(previous)
                assertEquals(previous.joinUrl, old.joinUrl)
                val moved = r.saved().retainingHistory(previous.keysChanged())
                assertTrue(moved.movedOn); assertFailsWith<RoomRecoveryException> { moved.identity(now) }
                RoomRepository(r.savedDisk).save(refreshed)
                assertTrue(RoomRepository(r.savedDisk).get(previous.id)!!.retired)
            }
        }
    }

    @Test fun malformedOrForgedReferencesAndDualAuthoritiesRefuseWithoutRewrite() {
        val r = Rig()
        r.source().use { source ->
            val saved = r.saved().withNativeAuthority(source)
            val marker = saved.json.getValue("nativeAuthority").jsonObject
            val mutations = listOf(
                JsonObject(marker + ("v" to JsonPrimitive(2))), JsonObject(marker + ("v" to JsonPrimitive("1"))),
                JsonObject(marker + ("pin" to JsonPrimitive("00".repeat(32)))),
                JsonObject(marker + ("root" to JsonPrimitive("ab".repeat(32)))),
                JsonObject(marker + ("participant" to JsonPrimitive("ab".repeat(32)))),
                JsonObject(marker + ("device" to JsonPrimitive("ab".repeat(32)))),
                JsonObject(marker + ("route" to JsonPrimitive("automatic"))),
                JsonObject(marker + ("invitation" to JsonPrimitive("00".repeat(32)))),
                JsonObject(marker + ("extra" to JsonPrimitive(true))),
                JsonObject(marker - "pin"),
                JsonObject(marker + ("relays" to JsonArray(listOf(JsonPrimitive("wss://fixture.invalid/"))))),
                JsonObject(marker + ("relays" to JsonArray(listOf(JsonPrimitive(7))))), JsonNull)
            for ((index, bad) in mutations.withIndex()) {
                val room = JsonObject(saved.json + ("nativeAuthority" to bad))
                val bytes = buildJsonObject { put("version", 2); put("rooms", JsonArray(listOf(room))) }.toString().toByteArray()
                r.savedDisk.value = bytes.clone()
                assertFailsWith<RoomStorageException>("Mutation $index must refuse") { RoomRepository(r.savedDisk).list() }
                assertContentEquals(bytes, r.savedDisk.value)
            }
            val dual = JsonObject(saved.json + ("host" to buildJsonObject {
                put("key", "07".repeat(32)); put("delegation", JsonArray(emptyList()))
            }))
            assertFails { SavedRoom.decode(dual) }
            assertNotNull(source.epoch()) // Refusal never closes the existing source owner.
        }
    }

    @Test fun nativeRepositoryRejectsDowngradedHeaderAndLegacyRoomsStayCompatible() {
        val r = Rig()
        r.source().use { source ->
            val native = r.saved().withNativeAuthority(source)
            val other = Rig(RoomRoute.INTERNET).saved()
            val repository = RoomRepository(r.savedDisk)
            repository.save(other)
            assertEquals(1, Json.parseToJsonElement(r.savedDisk.value!!.toString(Charsets.UTF_8)).jsonObject["version"]!!.jsonPrimitive.int)
            repository.save(native)
            assertEquals(2, Json.parseToJsonElement(r.savedDisk.value!!.toString(Charsets.UTF_8)).jsonObject["version"]!!.jsonPrimitive.int)
            val good = r.savedDisk.value!!.clone()
            val json = Json.parseToJsonElement(good.toString(Charsets.UTF_8)).jsonObject
            val downgraded = JsonObject(json + ("version" to JsonPrimitive(1))).toString().toByteArray()
            r.savedDisk.value = downgraded
            assertFailsWith<RoomStorageException> { repository.list() }
            assertFailsWith<RoomStorageException> { repository.save(other.renamed("Changed")) }
            assertContentEquals(downgraded, r.savedDisk.value)
            r.savedDisk.value = good
            assertEquals(2, RoomRepository(r.savedDisk).list().size)
            repository.forget(native.id)
            assertEquals(1, Json.parseToJsonElement(r.savedDisk.value!!.toString(Charsets.UTF_8)).jsonObject["version"]!!.jsonPrimitive.int)
            assertEquals(other.id, RoomRepository(r.savedDisk).list().single().id)
        }
    }

    @Test fun referenceInstallationRequiresFreshPersistedSourceAndExactSignedWelcomeLifetime() {
        val r = Rig(end = now + 3600, destruct = true)
        r.source().use { source ->
            assertFails { r.saved(ends = null).withNativeAuthority(source) }
            assertFails { r.saved(destruct = false).withNativeAuthority(source) }
            val saved = r.saved().withNativeAuthority(source)
            assertEquals(now + 3600, saved.ends); assertTrue(saved.destruct)
            source.bind { true }; source.prepareRekey(listOf(r.owner.credential))
            assertFails { r.saved().withNativeAuthority(source) }
        }
    }

    @Test fun legacyHostingCannotTransferThroughOrdinaryMarkerInstallationOrSave() {
        val r = Rig()
        r.source().use { source ->
            val native = r.saved().withNativeAuthority(source)
            val oldHost = createRoomInvitation(true)
            val legacy = SavedRoom.create(r.secret, r.owner, encodeInvitationUrl(base, oldHost.invitation, relays),
                relays, "Legacy", now, oldHost, oldHost.invitation.canonicalInviter, route = r.route)
            assertFails { legacy.withNativeAuthority(source) }
            val repository = RoomRepository(r.savedDisk); repository.save(legacy)
            val before = r.savedDisk.value!!.clone()
            assertFails { repository.save(native) }
            assertContentEquals(before, r.savedDisk.value)
            assertNotNull(repository.get(legacy.id)!!.host(now))
        }
    }

    @Test fun sourceReopenChecksActualInvitationNotOnlyPublicBindingPin() {
        val r = Rig()
        lateinit var saved: SavedRoom
        r.source().use { saved = r.saved().withNativeAuthority(it) }
        val different = RoomInvitation(Entropy.bytes(32), r.binding.authority, true)
        val marker = JsonObject(saved.json.getValue("nativeAuthority").jsonObject +
            ("invitation" to JsonPrimitive(deriveInvitationId(different))))
        val replaced = SavedRoom.decode(JsonObject(saved.json + mapOf(
            "joinUrl" to JsonPrimitive(encodeInvitationUrl(base, different, relays)), "nativeAuthority" to marker)))
        assertEquals(saved.nativeAuthority?.pin, replaced.nativeAuthority?.pin)
        r.reopen(replaced).use { source -> assertFails { replaced.verifyNativeAuthority(source) } }
        r.reopen(saved).use { source -> saved.verifyNativeAuthority(source) }
    }
}
