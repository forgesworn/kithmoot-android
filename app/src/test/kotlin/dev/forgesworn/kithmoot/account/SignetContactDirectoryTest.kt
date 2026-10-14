package dev.forgesworn.kithmoot.account

import dev.forgesworn.signet.contacts.wire.*
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.relay.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class SignetContactDirectoryTest {
    private val a = LocalSigner(ByteArray(32) { 1 })
    private val peer = LocalSigner(ByteArray(32) { 2 }).pubkey
    private val now = 1_800_000_000L
    private val caps = listOf(Capability.READ_DIRECTORY, Capability.BLOCKS_READ)
    private val pairing = PairingV2("a".repeat(32), "b".repeat(64), "c".repeat(32), "d".repeat(32), "wss://relay.example", caps, 3600, now)
    private val projection = ContactProjectionV2(pairing.grantId, caps, ProjectionFrontier(1, 1, now, "e".repeat(32)), now, now + 3600,
        listOf(ProjectedContact("f".repeat(32), identities = listOf(ProjectedIdentity(peer)), displayName = "Bob")))
    private val state = ContactsState(pairing.grantId, projection, now, emptyList(), false)

    @Test fun staleNamesHaveABoundedLifetimeAndBlocksSurviveRevocationAndExpiry() {
        assertEquals(listOf(SignetContact(peer, "Bob")), signetDirectoryView(a.pubkey, pairing, state, true, now).contacts)
        assertEquals("stale", signetDirectoryView(a.pubkey, pairing, state, true, now + 3600).status)
        assertEquals("lapsed", signetDirectoryView(a.pubkey, pairing, state, true, now + 3600 + 30 * 86400).status)
        val revoked = signetDirectoryView(a.pubkey, pairing, state.copy(revoked = true, blockedPubkeys = listOf(peer)), true, now)
        assertEquals("revoked", revoked.status); assertTrue(revoked.contacts.isEmpty()); assertEquals(setOf(peer), revoked.blocked)
        assertTrue(signetDirectoryView(a.pubkey, pairing, state.copy(blockedPubkeys = listOf(peer)), true, now).contacts.isEmpty())
    }
    @Test fun wrongGrantFutureProjectionMissingBlockScopeAndUnreadableCacheCannotAuthoriseMessaging() {
        for (bad in listOf(state.copy(grantId = "0".repeat(32)), state.copy(projection = projection.copy(issuedAt = now + 1)),
            state.copy(projection = projection.copy(scopes = listOf(Capability.READ_DIRECTORY))))) {
            val view = signetDirectoryView(a.pubkey, pairing, bad, true, now)
            assertEquals("unavailable", view.status); assertTrue(view.contacts.isEmpty())
        }
        assertEquals("unavailable", signetDirectoryView(a.pubkey, pairing, state, false, now).status)
    }
    @Test fun selfIsOmittedAndConflictingNamesCannotMasqueradeAsOneContact() {
        val contacts = projection.contacts + projection.contacts.single().copy(contactId = "1".repeat(32), displayName = "Eve") +
            ProjectedContact("2".repeat(32), identities = listOf(ProjectedIdentity(a.pubkey)), displayName = "Me")
        assertEquals(listOf(SignetContact(peer, null)), signetDirectoryView(a.pubkey, pairing, state.copy(projection = projection.copy(contacts = contacts)), true, now).contacts)
    }
    @Test fun malformedSavedBlockListKeepsDirectoryUnavailableAndReleasesWriterOnClose() = runTest {
        var held = false
        val storage = object : ProjectStorage {
            override suspend fun acquire() { held = true }
            override suspend fun release() { held = false }
            override suspend fun load() = buildJsonObject {
                put("v", 1); put("account", a.pubkey); put("pairing", pairing.toJson().stringify())
                put("values", buildJsonObject { put("signet-contacts:state:${pairing.grantId}", "{\"grantId\":\"${pairing.grantId}\",\"blockedPubkeys\":[5],\"revoked\":false}") })
            }.toString()
            override suspend fun save(value: String) = error("Must preserve unreadable storage")
        }
        val noNetwork = object : RoomTransport {
            override fun publish(event: NostrEvent) = error("No network")
            override fun subscribe(filters: List<Filter>): Flow<NostrEvent> = emptyFlow()
        }
        val directory = SignetContactDirectory(a, storage, backgroundScope, { ContactsRelay(noNetwork) {} }, { true }, { now })
        directory.open(); assertEquals("unavailable", directory.state.value.status); assertFalse(directory.allowed(peer)); assertTrue(directory.blocked(peer))
        directory.close(); assertFalse(held)
    }

    @Test fun aNewConfirmedGrantReplacesARevokedGrantAndRetainsBlocksUntilTheNewProjection() = runTest {
        val rail = LocalSigner(ByteArray(32) { 7 })
        val ephemeral = LocalSigner(ByteArray(32) { 8 })
        var raw = buildJsonObject {
            put("v", 1); put("account", a.pubkey); put("pairing", pairing.toJson().stringify())
            put("values", buildJsonObject { put("signet-contacts:state:${pairing.grantId}", state.copy(revoked = true, blockedPubkeys = listOf(peer)).toJson().stringify()) })
        }.toString()
        val storage = object : ProjectStorage {
            override suspend fun acquire() {}
            override suspend fun release() {}
            override suspend fun load() = raw
            override suspend fun save(value: String) { raw = value }
        }
        val events = mutableListOf<NostrEvent>()
        lateinit var directory: SignetContactDirectory
        var keptBlockBeforeProjection = false
        val net = object : RoomTransport {
            override fun publish(event: NostrEvent) = error("Read only")
            override fun subscribe(filters: List<Filter>): Flow<NostrEvent> = emptyFlow()
            override suspend fun queryAvailable(filters: List<Filter>, timeoutMs: Long): List<NostrEvent> {
                if (filters.any { rail.pubkey in it.authors.orEmpty() }) keptBlockBeforeProjection = directory.blocked(peer)
                return events.filter { e -> filters.any { f -> (f.kinds == null || e.kind in f.kinds!!) && (f.authors == null || e.pubkey in f.authors!!) &&
                    f.tags.all { (name, values) -> e.tags.any { it.size >= 2 && it[0] == name.removePrefix("#") && it[1] in values } } } }
            }
        }
        directory = SignetContactDirectory(a, storage, backgroundScope, { ContactsRelay(net) {} }, { true }, { now })
        directory.open(); assertEquals("revoked", directory.state.value.status)
        val request = directory.beginPairing(pairing.relay)
        val grantId = "1".repeat(32)
        val ack = buildPairingAckV2(PairingAckV2(grantId, rail.pubkey, projectionTag(grantId), proposalTag(grantId, a.pubkey),
            pairing.relay, caps, 3600, request.challenge))
        val ackTemplate = ackEventTemplate(ephemeral.pubkey, a.pubkey, now, ephemeral.nip44Encrypt(a.pubkey, ack))
        events.add(ephemeral.sign(ackTemplate.kind, ackTemplate.createdAt, ackTemplate.tags, ackTemplate.content))
        val newProjection = projection.copy(grantId = grantId)
        val envelope = sealVaultPayload(newProjection.toJson().stringify(), SealEnvelopeBackend(rail::nip44Encrypt), a.pubkey)!!
        val template = projectionEventTemplate(rail.pubkey, grantId, now, envelope)
        events.add(rail.sign(template.kind, template.createdAt, template.tags, template.content))
        var confirmed = false
        assertTrue(directory.awaitPairing(request, pairing.relay) { code -> assertEquals(7, code.length); confirmed = true; true })
        assertTrue(confirmed); assertTrue(keptBlockBeforeProjection)
        assertEquals("ready", directory.state.value.status); assertTrue(directory.allowed(peer))
        directory.close()
    }
}
