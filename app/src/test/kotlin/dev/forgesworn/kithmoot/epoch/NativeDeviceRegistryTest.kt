package dev.forgesworn.kithmoot.epoch

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.RoomRoute
import dev.forgesworn.kithmoot.session.Fixtures
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import dev.forgesworn.kithmoot.storage.RoomStorage
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class NativeDeviceRegistryTest {
    private class Store : RoomStorage {
        var bytes: ByteArray? = null
        var afterWrite: ((ByteArray) -> Unit)? = null
        override fun read() = bytes?.clone()
        override fun write(value: ByteArray) { bytes = value.clone(); afterWrite?.invoke(value) }
        override fun reset() = error("Never reset source accounting")
    }
    private class Rig(ownerExpires: Long = 10_000) : AutoCloseable {
        var at = 1000L
        val creation = NativeKeeperCreation.fresh(at)
        val base = creation.roomSecret(); val room = deriveRoom(base)
        val owner = PrimaryIdentity.create(room.roomId, ownerExpires, at,
            participantSecretKey = Fixtures.key(5), deviceSecretKey = Fixtures.key(6))
        val member = PrimaryIdentity.create(room.roomId, 10_000, at,
            participantSecretKey = Fixtures.key(1), deviceSecretKey = Fixtures.key(2))
        val binding = NativeKeeperBinding(room.roomId, creation.authority, owner.participant, owner.devicePubkey,
            RoomRoute.MIXED, listOf("wss://fixture.invalid/"))
        val store = Store()
        fun create() = NativeKeeperJournal.create(store, binding, creation, owner.credential) { at }
        fun open() = NativeKeeperJournal.open(store, binding) { at }
        fun request(device: ByteArray = member.deviceSecretKey, credential: NostrEvent = member.credential) =
            encodeEpochRequest(room.roomId, binding.authority, room.roomKey, device, credential, at)
        fun credential(seed: Int) = createDeviceCredential(Fixtures.key(1), Schnorr.publicKeyHex(Fixtures.key(seed)), room.roomId, 10_000, at)
        fun root() = Json.parseToJsonElement(checkNotNull(store.bytes).toString(Charsets.UTF_8)).jsonObject
        fun devices() = root().getValue("devices").jsonArray
        fun approve(source: NativeKeeperJournal) {
            assertNotNull(source.answerEpoch(request(), RekeyLane.INTERNET))
            source.approve(member.participant)
        }
        override fun close() { creation.close(); base.fill(0); room.roomKey.fill(0); store.bytes?.fill(0) }
    }

    @Test fun offlineDeviceSurvivesCacheExpiryAndReopenAndMissingDuplicateExtraAudiencesRefuseWithoutSigning() {
        Rig().use { r ->
            val offlineKey = Fixtures.key(7); val offline = r.credential(7)
            val requests = mutableListOf<String>()
            r.create().use { source ->
                source.bind { true }; r.approve(source); r.at++
                for ((device, credential) in listOf(r.member.deviceSecretKey to r.member.credential, offlineKey to offline)) {
                    val ask = r.request(device, credential); requests += ask.id
                    val grant = assertNotNull(source.answerEpoch(ask, RekeyLane.INTERNET))
                    assertIs<EpochGrant.Current>(decodeEpochGrant(grant.event, r.room.roomId, r.binding.authority, device, ask.id, r.at))
                    assertTrue(source.canHandoff(grant)); source.offered(grant)
                }
                assertEquals(3, r.devices().size)
            }
            r.at += EPOCH_MAX_AGE_SECONDS + 1
            r.open().use { source ->
                source.bind { true }
                assertNotNull(source.answerEpoch(r.request(r.owner.deviceSecretKey, r.owner.credential), RekeyLane.INTERNET))
                assertTrue(r.root().getValue("answers").jsonArray.none {
                    it.jsonObject.getValue("request").jsonObject.getValue("id").jsonPrimitive.content in requests
                })
                assertEquals(3, r.devices().size)
                val before = checkNotNull(r.store.bytes).clone()
                for (audience in listOf(listOf(r.owner.credential, r.member.credential),
                    listOf(r.owner.credential, r.member.credential, r.member.credential),
                    listOf(r.owner.credential, r.member.credential, r.credential(8)))) {
                    assertFails { source.prepareRekey(audience) }
                    assertTrue(before.contentEquals(r.store.bytes))
                    assertTrue(source.snapshot().pending.isEmpty()); assertFalse(source.persistenceFailed())
                }
                val current = source.epoch(); val keys = deriveEpoch(current)
                try {
                    val event = source.prepareRekey(listOf(r.owner.credential, r.member.credential, offline)).single()
                    assertTrue(Events.verify(event))
                    val notice = assertNotNull(decodeRekeyEvent(event, r.room.roomId, r.binding.authority, keys, offlineKey))
                    try { assertNotNull(notice.secret); assertTrue(r.member.participant in notice.members.orEmpty()) }
                    finally { notice.secret?.fill(0) }
                } finally { current.secret.fill(0); keys.key.fill(0) }
            }
        }
    }

    @Test fun approvalNeedsQualifiedEvidenceAndAmbiguousApprovalRetainsTheAudienceWithoutGranting() {
        Rig().use { r ->
            r.create().use { source ->
                source.bind { true }; val before = checkNotNull(r.store.bytes).clone()
                assertFails { source.approve(r.member.participant) }
                assertTrue(before.contentEquals(r.store.bytes))
                assertNull(source.answerEpoch(r.request().copy(sig = "00".repeat(64)), RekeyLane.INTERNET))
                assertFails { source.approve(r.member.participant) }
                assertNotNull(source.answerEpoch(r.request(), RekeyLane.INTERNET))
                r.store.afterWrite = { error("committed approval return lost") }
                assertFails { source.approve(r.member.participant) }; assertTrue(source.persistenceFailed())
            }
            r.store.afterWrite = null
            r.open().use { source ->
                assertTrue(r.member.participant in source.snapshot().members); assertEquals(2, r.devices().size)
                source.bind { true }
                assertFails { source.prepareRekey(listOf(r.owner.credential)) }
                assertTrue(source.snapshot().pending.isEmpty())
            }
        }
    }

    @Test fun aNewDeviceIsCommittedBeforeAnyGrantAndLostWriteReturnCannotIssueOne() {
        Rig().use { r ->
            val key = Fixtures.key(7); val credential = r.credential(7); val ask = r.request(key, credential)
            r.create().use { source ->
                source.bind { true }; r.approve(source)
                r.store.afterWrite = { bytes ->
                    val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                    if (root.getValue("devices").jsonArray.size == 3) error("committed registry return lost")
                }
                assertFails { source.answerEpoch(ask, RekeyLane.INTERNET) }; assertTrue(source.persistenceFailed())
                assertEquals(3, r.devices().size)
                assertTrue(r.root().getValue("answers").jsonArray.none {
                    it.jsonObject.getValue("request").jsonObject.getValue("id").jsonPrimitive.content == ask.id
                })
            }
            r.store.afterWrite = null
            r.open().use { source ->
                source.bind { true }; val handoff = assertNotNull(source.answerEpoch(ask, RekeyLane.INTERNET))
                assertTrue(source.canHandoff(handoff)); assertEquals(3, r.devices().size)
                assertIs<EpochGrant.Current>(decodeEpochGrant(handoff.event, r.room.roomId, r.binding.authority, key, ask.id, r.at))
            }
        }
    }

    @Test fun aRefreshedCredentialCannotExtendAnOriginalGrantAndExpiredBindingsSurviveReopen() {
        Rig(ownerExpires = 1003).use { r ->
            val old = r.request(r.owner.deviceSecretKey, r.owner.credential)
            lateinit var original: NativeKeeperJournal.Handoff
            r.create().use { source ->
                source.bind { true }; original = assertNotNull(source.answerEpoch(old, RekeyLane.INTERNET))
                r.at++
                val refreshed = createDeviceCredential(Fixtures.key(5), r.owner.devicePubkey, r.room.roomId, 2000, r.at)
                val fresh = r.request(r.owner.deviceSecretKey, refreshed)
                val current = assertNotNull(source.answerEpoch(fresh, RekeyLane.INTERNET))
                r.at = 1004
                assertFalse(source.canHandoff(original)); assertNull(source.answerEpoch(old, RekeyLane.INTERNET))
                assertTrue(source.canHandoff(current))
                val kept = r.root().getValue("answers").jsonArray.single {
                    it.jsonObject.getValue("request").jsonObject.getValue("id").jsonPrimitive.content == old.id
                }.jsonObject
                assertEquals(original.event, NostrEvent.fromJson(kept.getValue("answer")))
                assertEquals(1, kept.getValue("offers").jsonPrimitive.int)
            }
            r.at = 2001
            r.open().use { source ->
                assertEquals(1, r.devices().size); source.bind { true }
                val before = checkNotNull(r.store.bytes).clone()
                assertFails { source.prepareRekey(listOf(r.owner.credential)) }
                assertTrue(before.contentEquals(r.store.bytes)); assertTrue(source.snapshot().pending.isEmpty())
            }
        }
    }

    @Test fun oldSchemaAndCorruptRegistryRefuseWithoutRewritingHistoryOrKeys() {
        Rig().use { r ->
            r.create().close(); val original = r.root()
            val old = JsonObject(original - "devices" + ("v" to JsonPrimitive(3))).toString().toByteArray()
            r.store.bytes = old.clone()
            assertFailsWith<NativeKeeperMigrationRequiredException> { r.open() }
            assertTrue(old.contentEquals(r.store.bytes))
            val entry = original.getValue("devices").jsonArray.single().jsonObject
            val broken = JsonObject(original + ("devices" to JsonArray(listOf(JsonObject(entry +
                ("participant" to JsonPrimitive("ff".repeat(32)))))))).toString().toByteArray()
            r.store.bytes = broken.clone(); assertFails { r.open() }
            assertTrue(broken.contentEquals(r.store.bytes))
        }
    }

    @Test fun ownerEvidenceIsFrozenAndOversizedValidCredentialsRefuseBeforeConsumingCreation() {
        Rig().use { r ->
            val oversized = Events.sign(Fixtures.key(5), KIND_DEVICE_CREDENTIAL, r.at,
                r.owner.credential.tags, "x".repeat(5000))
            assertIs<CredentialCheck.Valid>(verifyDeviceCredential(oversized, r.room.roomId, r.at))
            assertFails { NativeKeeperJournal.create(r.store, r.binding, r.creation, oversized) { r.at } }
            assertNull(r.store.bytes)
            val frozen = r.owner.credential.toCompactJson()
            r.create().use { source ->
                source.bind { true }
                @Suppress("UNCHECKED_CAST")
                val tag = r.owner.credential.tags.first() as MutableList<String>
                tag[1] = "ff".repeat(32)
                assertFalse(Events.verify(r.owner.credential))
                assertEquals(frozen, NostrEvent.fromJson(r.devices().single().jsonObject.getValue("credential")).toCompactJson())
            }
            r.open().use { source ->
                source.bind { true }
                val credential = NostrEvent.fromJson(r.devices().single().jsonObject.getValue("credential"))
                assertTrue(Events.verify(source.prepareRekey(listOf(credential)).single()))
            }
        }
    }

    @Test fun theHistoricalRegistryRefusesCapacityWithoutEvictingDevicesAndCannotTruncateRekeySeals() {
        Rig().use { r ->
            r.create().use { source ->
                source.bind { true }; r.approve(source)
                for (seed in 30..155) {
                    r.at += 11
                    val key = Fixtures.key(seed); val credential = r.credential(seed)
                    val ask = r.request(key, credential)
                    val grant = assertNotNull(source.answerEpoch(ask, RekeyLane.INTERNET))
                    assertTrue(source.canHandoff(grant)); source.offered(grant)
                }
                assertEquals(128, r.devices().size)
                val bindings = r.devices().map { it.jsonObject.getValue("device").jsonPrimitive.content }
                r.at += 11
                assertNull(source.answerEpoch(r.request(Fixtures.key(156), r.credential(156)), RekeyLane.INTERNET))
                assertEquals(bindings, r.devices().map { it.jsonObject.getValue("device").jsonPrimitive.content })
                val before = checkNotNull(r.store.bytes).clone()
                assertFails { source.prepareRekey(listOf(r.owner.credential)) }
                assertTrue(before.contentEquals(r.store.bytes)); assertTrue(source.snapshot().pending.isEmpty())
            }
            r.open().use { assertEquals(128, r.devices().size) }
        }
    }
}
