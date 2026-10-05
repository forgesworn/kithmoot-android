package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import java.security.SecureRandom
import java.util.Base64
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * `signBoxRequestV1` (contract §6.2.1, P3-03b-3a): NIP-98 authentication of
 * `/vmls/v1/` requests by the persona's MLS device key, on the coordinated
 * vault with the Kotlin model of the core and a fake witness.
 */
class BoxRequestAuthTest {
    private val now = 1_793_577_600L
    private val principal = "dev.forgesworn.kithmoot"
    private val random = SecureRandom()
    private var clock = now

    private lateinit var stores: MemoryCoordinatedStores
    private lateinit var server: FakeWitnessServer
    private lateinit var vault: MlsVault
    private lateinit var alice: LocalSigner
    private lateinit var ctx: VaultContext
    private lateinit var subject: ByteArray
    private lateinit var device: EnrolledDevice
    private val box = bytes(32).toHex()
    private val body = """{"v":1,"mailboxes":[]}""".toByteArray()
    private var asked = 0
    private val approve = ConsentPrompt { asked++; ConsentDecision.Approve }
    private val deny = ConsentPrompt { asked++; ConsentDecision.Deny }

    @BeforeTest fun setup() = runBlocking<Unit> {
        clock = now
        stores = MemoryCoordinatedStores()
        server = FakeWitnessServer(bytes(32))
        vault = MlsVault.coordinated(VaultCoordination(stores, WitnessChannels { _, _, _ -> server.channel }, FakeVaultWitness()), now = { clock })
        alice = LocalSigner(secret())
        ctx = vault.context(principal, alice.pubkey)
        subject = bytes(32)
        val genesis = (vault.beginCoordination(alice.pubkey, subject, server.key) as VaultResult.Ok).value
        server.enrol(genesis.subject, genesis.initialDigest)
        assertEquals(CoordinationStatus.Active, vault.coordinationStatus(alice.pubkey, check = true))
        device = (vault.enrol(ctx, alice, now + 7 * 86_400) as VaultResult.Ok).value
    }

    private fun fetch(b: String = box) = BoxRequest(b, "POST", "/vmls/v1/fetch", Digests.sha256(body).toHex())
    private fun seq() = server.subjects.getValue(subject.toHex()).seq

    private fun event(reply: BoxRequestReply): NostrEvent {
        assertTrue(reply.authorization.startsWith("Nostr "))
        val json = Json.parseToJsonElement(String(Base64.getDecoder().decode(reply.authorization.removePrefix("Nostr ")))).jsonObject
        fun text(name: String) = json.getValue(name).jsonPrimitive.content
        return NostrEvent(
            json.getValue("kind").jsonPrimitive.long.toInt(), json.getValue("created_at").jsonPrimitive.long,
            json.getValue("tags").jsonArray.map { tag -> tag.jsonArray.map { (it as JsonPrimitive).content } },
            text("content"), text("pubkey"), text("id"), text("sig"),
        )
    }

    @Test fun `the device key signs exactly what the box verifies, at the vault's own time`() = runBlocking<Unit> {
        val reply = (vault.signBoxRequestV1(ctx, fetch(), approve) as VaultResult.Ok).value
        val event = event(reply)
        assertTrue(Events.verify(event))
        assertEquals(27235, event.kind)
        assertEquals("", event.content)
        assertEquals(device.device, event.pubkey)
        assertEquals(device.device, reply.device)
        assertEquals(now, event.createdAt)
        assertEquals(
            listOf(
                listOf("u", "http://${linkNodeBase32(box.chunked(2).map { it.toInt(16).toByte() }.toByteArray())}/vmls/v1/fetch"),
                listOf("method", "POST"),
                listOf("payload", Digests.sha256(body).toHex()),
            ),
            event.tags,
        )
    }

    @Test fun `the u tag names the node in link-core's form, from an independent vector`() = runBlocking<Unit> {
        val fixed = ByteArray(32) { it.toByte() }.toHex()
        val reply = (vault.signBoxRequestV1(ctx, fetch(fixed), approve) as VaultResult.Ok).value
        assertEquals(listOf("u", "http://aaaqeayeaudaocajbifqydiob4ibceqtcqkrmfyydenbwha5dypq/vmls/v1/fetch"), event(reply).tags[0])
    }

    @Test fun `the same request signed again in the same second is a new event, and the vault never runs far ahead`() = runBlocking<Unit> {
        val ids = mutableSetOf<String>()
        val times = mutableListOf<Long>()
        repeat(31) {
            val e = event((vault.signBoxRequestV1(ctx, fetch(), approve) as VaultResult.Ok).value)
            ids += e.id
            times += e.createdAt
        }
        assertEquals(31, ids.size)
        assertEquals((now..now + 30).toList(), times)
        assertEquals(VaultResult.Refused(VaultRefusal.Busy), vault.signBoxRequestV1(ctx, fetch(), approve))
        // Another request is its own sequence; and once the clock moves on, so does this one.
        assertEquals(now, event((vault.signBoxRequestV1(ctx, BoxRequest(box, "POST", "/vmls/v1/ack", bytes(32).toHex()), approve) as VaultResult.Ok).value).createdAt)
        clock = now + 60
        assertEquals(now + 60, event((vault.signBoxRequestV1(ctx, fetch(), approve) as VaultResult.Ok).value).createdAt)
    }

    @Test fun `a withdrawn approval is asked again, and an approval the witness cannot take signs nothing`() = runBlocking<Unit> {
        assertIs<VaultResult.Ok<BoxRequestReply>>(vault.signBoxRequestV1(ctx, fetch(), approve))
        val scope = ConsentScope(principal, alice.pubkey, device.device, box, MlsVault.BOX_METHOD)
        assertIs<VaultResult.Ok<Unit>>(vault.withdraw(ctx, scope))
        assertIs<VaultResult.Ok<BoxRequestReply>>(vault.signBoxRequestV1(ctx, fetch(), approve))
        assertEquals(2, asked)
        val other = bytes(32).toHex()
        server.mode = FakeWitnessServer.Mode.LoseAnswer
        assertEquals(VaultResult.Refused(VaultRefusal.WitnessPending), vault.signBoxRequestV1(ctx, fetch(other), approve))
        assertEquals(3, asked)
    }

    @Test fun `an uncoordinated vault signs no box request`() = runBlocking<Unit> {
        val plain = MlsVault(MemoryCoordinatedStores(), now = { clock })
        assertEquals(VaultResult.Refused(VaultRefusal.Unsupported), plain.signBoxRequestV1(plain.context(principal, alice.pubkey), fetch(), approve))
        assertEquals(0, asked)
    }

    @Test fun `a new box is asked once, the approval is witnessed, and later requests take no round trip`() = runBlocking<Unit> {
        val before = seq()
        assertIs<VaultResult.Ok<BoxRequestReply>>(vault.signBoxRequestV1(ctx, fetch(), approve))
        assertEquals(1, asked)
        assertEquals(before + 1, seq())
        val advances = server.advances
        val slot = BoxRequest(box, "PUT", "/vmls/v1/slots/${bytes(32).toHex()}/3", bytes(32).toHex())
        assertIs<VaultResult.Ok<BoxRequestReply>>(vault.signBoxRequestV1(ctx, slot, approve))
        assertEquals(1, asked)
        assertEquals(advances, server.advances)
        // Another box is another scope.
        assertIs<VaultResult.Ok<BoxRequestReply>>(vault.signBoxRequestV1(ctx, fetch(bytes(32).toHex()), approve))
        assertEquals(2, asked)
    }

    @Test fun `a denial signs nothing, keeps nothing, and the next request asks again`() = runBlocking<Unit> {
        val before = seq()
        assertEquals(VaultResult.Refused(VaultRefusal.Denied), vault.signBoxRequestV1(ctx, fetch(), deny))
        assertEquals(before, seq())
        assertIs<VaultResult.Ok<BoxRequestReply>>(vault.signBoxRequestV1(ctx, fetch(), approve))
        assertEquals(2, asked)
    }

    @Test fun `only the box's own routes, in its own forms, are signed`() = runBlocking<Unit> {
        val id = bytes(32).toHex()
        val hash = bytes(32).toHex()
        val empty = Digests.sha256(ByteArray(0)).toHex()
        val good = listOf(
            "PUT" to "/vmls/v1/mailboxes/$id/records", "POST" to "/vmls/v1/fetch", "POST" to "/vmls/v1/ack",
            "PUT" to "/vmls/v1/packages/$id",
            "PUT" to "/vmls/v1/slots/$id/0", "PUT" to "/vmls/v1/slots/$id/4294967295",
            "POST" to "/vmls/v1/slots/$id/12/status",
        )
        for ((method, path) in good) assertIs<VaultResult.Ok<BoxRequestReply>>(vault.signBoxRequestV1(ctx, BoxRequest(box, method, path, hash), approve), path)
        // The box takes no body on these two.
        for ((method, path) in listOf("DELETE" to "/vmls/v1/packages/$id", "GET" to "/vmls/v1/capabilities")) {
            assertIs<VaultResult.Ok<BoxRequestReply>>(vault.signBoxRequestV1(ctx, BoxRequest(box, method, path, empty), approve), path)
            assertEquals(VaultResult.Refused(VaultRefusal.Malformed), vault.signBoxRequestV1(ctx, BoxRequest(box, method, path, hash), approve), path)
        }
        val bad = listOf(
            "GET" to "/vmls/v1/fetch", "POST" to "/vmls/v1/fetch?x=1", "POST" to "/vmls/v1/fetch/",
            "PUT" to "/vmls/v1/mailboxes/${id.uppercase()}/records", "PUT" to "/vmls/v1/mailboxes/${id.take(62)}/records",
            "PUT" to "/vmls/v1/slots/$id/4294967296", "PUT" to "/vmls/v1/slots/$id/01", "PUT" to "/vmls/v1/slots/$id/-1",
            "POST" to "/vmls/v1/slots/$id/1/status/x", "POST" to "/vmls/v1/../fetch", "POST" to "/cadence/v1/leases/$id",
            "post" to "/vmls/v1/fetch", "PATCH" to "/vmls/v1/ack", "GET" to "/vmls/v1/capabilities#",
        )
        val asks = asked
        for ((method, path) in bad) assertEquals(VaultResult.Refused(VaultRefusal.Malformed), vault.signBoxRequestV1(ctx, BoxRequest(box, method, path, hash), approve), "$method $path")
        assertEquals(VaultResult.Refused(VaultRefusal.Malformed), vault.signBoxRequestV1(ctx, BoxRequest(box.uppercase(), "POST", "/vmls/v1/fetch", hash), approve))
        assertEquals(VaultResult.Refused(VaultRefusal.Malformed), vault.signBoxRequestV1(ctx, BoxRequest(box, "POST", "/vmls/v1/fetch", "00"), approve))
        assertEquals(asks, asked, "a malformed request is never put to the person")
    }

    @Test fun `a revoked or expired credential signs nothing`() = runBlocking<Unit> {
        assertIs<VaultResult.Ok<BoxRequestReply>>(vault.signBoxRequestV1(ctx, fetch(), approve))
        clock = device.credentialExpiresAt
        assertEquals(VaultResult.Refused(VaultRefusal.Expired), vault.signBoxRequestV1(ctx, fetch(), approve))
        clock = now
        assertIs<VaultResult.Ok<Unit>>(vault.revokeCredential(ctx, device.credentialId))
        assertEquals(VaultResult.Refused(VaultRefusal.Revoked), vault.signBoxRequestV1(ctx, fetch(), approve))
    }

    @Test fun `with no device, a stale context or the witness unconfirmed nothing is signed`() = runBlocking<Unit> {
        // Bob is enrolled at the witness but has no device.
        val bob = LocalSigner(secret())
        val bobGenesis = (vault.beginCoordination(bob.pubkey, bytes(32), server.key) as VaultResult.Ok).value
        server.enrol(bobGenesis.subject, bobGenesis.initialDigest)
        assertEquals(CoordinationStatus.Active, vault.coordinationStatus(bob.pubkey, check = true))
        assertEquals(VaultResult.Refused(VaultRefusal.Unauthorised), vault.signBoxRequestV1(vault.context(principal, bob.pubkey), fetch(), approve))
        // Nor anyone the witness has not enrolled.
        val carol = LocalSigner(secret())
        assertEquals(VaultResult.Refused(VaultRefusal.WitnessPending), vault.signBoxRequestV1(vault.context(principal, carol.pubkey), fetch(), approve))
        val stale = ctx
        vault.bump()
        assertEquals(VaultResult.Refused(VaultRefusal.Stale), vault.signBoxRequestV1(stale, fetch(), approve))
        // A restarted vault must hear from the witness before it signs.
        server.mode = FakeWitnessServer.Mode.Down
        val restarted = MlsVault.coordinated(VaultCoordination(stores, WitnessChannels { _, _, _ -> server.channel }, FakeVaultWitness()), now = { clock })
        assertEquals(VaultResult.Refused(VaultRefusal.WitnessPending), restarted.signBoxRequestV1(restarted.context(principal, alice.pubkey), fetch(), approve))
        assertEquals(0, asked)
    }

    @Test fun `node ids are written in link-core's base32`() {
        assertEquals("aaaqeayeaudaocajbifqydiob4ibceqtcqkrmfyydenbwha5dypq", linkNodeBase32(ByteArray(32) { it.toByte() }))
        assertEquals("777777777777777777777777777777777777777777777777777q", linkNodeBase32(ByteArray(32) { -1 }))
    }

    private fun bytes(n: Int) = ByteArray(n).also(random::nextBytes)
    private fun secret(): ByteArray { while (true) { val k = bytes(32); if (runCatching { Schnorr.publicKey(k) }.isSuccess) return k } }
}
