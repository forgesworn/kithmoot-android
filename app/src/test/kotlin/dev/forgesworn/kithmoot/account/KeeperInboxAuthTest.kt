package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import java.security.SecureRandom
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.supervisorScope

class KeeperInboxAuthTest {
    private val now = 1_793_577_600L
    private var clock = now
    private val random = SecureRandom()
    private lateinit var vault: MlsVault
    private lateinit var server: FakeWitnessServer
    private lateinit var stores: MemoryCoordinatedStores
    private lateinit var ctx: VaultContext
    private lateinit var alice: LocalSigner
    private lateinit var device: EnrolledDevice
    private val keeperKey = ByteArray(32) { 7 }
    private val keeper = Schnorr.publicKeyHex(keeperKey)
    private val box = ByteArray(32) { it.toByte() }.toHex()
    private val destination = "ws://aaaqeayeaudaocajbifqydiob4ibceqtcqkrmfyydenbwha5dypq/events"
    private var active = true
    private var asked = 0
    private val approve = KeeperInboxConsentPrompt { asked++; assertTrue(it.isCurrent()); ConsentDecision.Approve }

    @BeforeTest fun setup() = runBlocking<Unit> {
        clock = now; active = true; asked = 0
        stores = MemoryCoordinatedStores()
        server = FakeWitnessServer(bytes(32))
        vault = MlsVault.coordinated(VaultCoordination(stores, WitnessChannels { _, _, _ -> server.channel }, FakeVaultWitness()), now = { clock })
        alice = LocalSigner(ByteArray(32) { 3 })
        ctx = vault.context("dev.forgesworn.kithmoot", alice.pubkey)
        val genesis = (vault.beginCoordination(alice.pubkey, bytes(32), server.key) as VaultResult.Ok).value
        server.enrol(genesis.subject, genesis.initialDigest)
        assertEquals(CoordinationStatus.Active, vault.coordinationStatus(alice.pubkey, check = true))
        device = (vault.enrol(ctx, alice, now + 7 * 86_400) as VaultResult.Ok).value
    }

    private fun tags() = listOf(listOf("t", "keeper-inbox-deposit"), listOf("server", destination),
        listOf("device", device.device), listOf("grant", "ab".repeat(16)), listOf("expiration", (now + 86_400).toString()), listOf("status", "active"))
    private fun permission(tags: List<List<String>> = tags(), createdAt: Long = now, content: String = "") = Events.sign(keeperKey, 24242, createdAt, tags, content)
    private fun request(p: NostrEvent = permission()) = KeeperInboxAuthRequest(box, keeper, p, "cd".repeat(32), now + 120)
    private suspend fun sign(req: KeeperInboxAuthRequest = request(), prompt: KeeperInboxConsentPrompt = approve) =
        vault.signKeeperInboxAuthV1(ctx, req, prompt) { active }
    private fun bytes(n: Int) = ByteArray(n).also(random::nextBytes)

    @Test fun `device AUTH uses canonical node route exact tags and vault time with fresh purpose consent`() = runBlocking<Unit> {
        assertIs<VaultResult.Ok<Unit>>(vault.approve(ctx, ConsentScope(ctx.principal, ctx.persona, device.device, box, MlsVault.BOX_METHOD)))
        val advances = server.advances
        val req = request()
        val prompt = KeeperInboxConsentPrompt { scope ->
            asked++
            assertEquals(ctx.principal, scope.principal); assertEquals(ctx.persona, scope.persona)
            assertEquals(device.device, scope.device); assertEquals(box, scope.box); assertEquals(keeper, scope.keeper)
            assertEquals("ab".repeat(16), scope.grant); assertEquals(destination, scope.destination)
            assertEquals(req.permission.id, scope.permissionId); assertEquals(req.expiresAt, scope.expiresAt)
            assertEquals("signKeeperInboxAuthV1/1", scope.method); assertTrue(scope.isCurrent())
            ConsentDecision.Approve
        }
        val reply = assertIs<VaultResult.Ok<KeeperInboxAuthReply>>(sign(req, prompt)).value
        assertEquals(1, reply.v); assertEquals(device.device, reply.device)
        assertEquals(22242, reply.event.kind); assertEquals(now, reply.event.createdAt)
        assertEquals("", reply.event.content); assertEquals(device.device, reply.event.pubkey)
        assertEquals(listOf(listOf("relay", destination), listOf("challenge", req.challenge)), reply.event.tags)
        assertTrue(Events.verify(reply.event))
        assertIs<VaultResult.Ok<KeeperInboxAuthReply>>(vault.acceptKeeperInboxAuthReply(req, reply))
        assertIs<VaultResult.Ok<KeeperInboxAuthReply>>(sign(req, prompt))
        assertEquals(2, asked); assertEquals(advances, server.advances)
    }

    @Test fun `forged wrong keeper wrong device and malformed permissions never ask`() = runBlocking<Unit> {
        val good = request()
        val variants = listOf(
            good.copy(permission = good.permission.copy(sig = "00".repeat(64))),
            good.copy(keeper = alice.pubkey), good.copy(box = "ef".repeat(32)),
            good.copy(challenge = "bad"), good.copy(v = 2), good.copy(box = box.uppercase()),
            good.copy(expiresAt = now + 121), good.copy(expiresAt = Long.MAX_VALUE),
            request(permission(tags().map { if (it[0] == "device") listOf("device", alice.pubkey) else it })),
            request(permission(tags() + listOf(listOf("other", "x")))),
            request(permission(tags().map { if (it[0] == "grant") listOf("grant", "x".repeat(129)) else it })),
            request(permission(tags() + List(10) { listOf("extra", "x") })),
            request(permission(tags().map { if (it[0] == "status") listOf("t", "keeper-inbox-deposit") else it })),
            request(permission(tags().map { if (it[0] == "server") it + "extra" else it })),
            request(permission(tags().map { if (it[0] == "server") listOf("server", destination + "/") else it })),
            request(permission(tags().map { if (it[0] == "status") listOf("status", "revoked") else it })),
            request(permission(tags().map { if (it[0] == "grant") listOf("grant", "00") else it })),
            request(permission(tags().map { if (it[0] == "expiration") listOf("expiration", (now + 30 * 86_400 + 1).toString()) else it })),
            request(permission(createdAt = now + 301)), request(permission(content = "payload")),
            request(permission(tags().map { if (it[0] == "expiration") listOf("expiration", "0" + (now + 1000)) else it })),
        )
        for (req in variants) assertIs<VaultResult.Refused>(sign(req), req.toString())
        assertEquals(0, asked)
        assertEquals(VaultResult.Refused(VaultRefusal.Expired), sign(request().copy(expiresAt = now)))
        assertEquals(VaultResult.Refused(VaultRefusal.Expired), sign(request(permission(tags().map { if (it[0] == "expiration") listOf("expiration", now.toString()) else it }))))
        assertIs<VaultResult.Ok<KeeperInboxAuthReply>>(sign(request(permission(createdAt = now + 300))))
    }

    @Test fun `paused withdrawn or account changed while prompting releases nothing`() = runBlocking<Unit> {
        assertEquals(VaultResult.Refused(VaultRefusal.Stale), sign(prompt = KeeperInboxConsentPrompt { active = false; assertFalse(it.isCurrent()); ConsentDecision.Approve }))
        active = true
        assertEquals(VaultResult.Refused(VaultRefusal.Stale), sign(prompt = KeeperInboxConsentPrompt { vault.bump(); ConsentDecision.Approve }))
        ctx = vault.context(ctx.principal, alice.pubkey)
        assertEquals(VaultResult.Refused(VaultRefusal.Denied), sign(prompt = KeeperInboxConsentPrompt { ConsentDecision.Deny }))
        assertIs<VaultResult.Ok<KeeperInboxAuthReply>>(sign())
    }

    @Test fun `deadline clock change and credential revocation during prompt release nothing`() = runBlocking<Unit> {
        assertEquals(VaultResult.Refused(VaultRefusal.Expired), sign(prompt = KeeperInboxConsentPrompt { clock = now + 120; ConsentDecision.Approve }))
        clock = now
        assertEquals(VaultResult.Refused(VaultRefusal.Malformed), sign(prompt = KeeperInboxConsentPrompt { clock = now - 1; ConsentDecision.Approve }))
        clock = now
        assertEquals(VaultResult.Refused(VaultRefusal.Revoked), sign(prompt = KeeperInboxConsentPrompt { vault.revokeCredential(ctx, device.credentialId); ConsentDecision.Approve }))
    }

    @Test fun `acceptance rejects copies cross challenge expiry revocation and stale operation`() = runBlocking<Unit> {
        val req = request()
        val reply = assertIs<VaultResult.Ok<KeeperInboxAuthReply>>(sign(req)).value
        assertEquals(VaultResult.Refused(VaultRefusal.Unauthorised), vault.acceptKeeperInboxAuthReply(req, KeeperInboxAuthReply(reply.device, reply.event.copy())))
        assertEquals(VaultResult.Refused(VaultRefusal.Replay), vault.acceptKeeperInboxAuthReply(req.copy(challenge = "ef".repeat(32)), reply))
        assertEquals(VaultResult.Refused(VaultRefusal.Replay), vault.acceptKeeperInboxAuthReply(req.copy(expiresAt = now + 119), reply))
        active = false
        assertEquals(VaultResult.Refused(VaultRefusal.Stale), vault.acceptKeeperInboxAuthReply(req, reply))
        active = true; clock = now + 120
        assertEquals(VaultResult.Refused(VaultRefusal.Expired), vault.acceptKeeperInboxAuthReply(req, reply))
        clock = now - 1
        assertEquals(VaultResult.Refused(VaultRefusal.Malformed), vault.acceptKeeperInboxAuthReply(req, reply))
        clock = now
        vault.revokeCredential(ctx, device.credentialId)
        assertEquals(VaultResult.Refused(VaultRefusal.Revoked), vault.acceptKeeperInboxAuthReply(req, reply))
    }

    @Test fun `deadline reached after signing still releases no AUTH and witness refusal never prompts`() = runBlocking<Unit> {
        var checks = 0
        val quietApprove = KeeperInboxConsentPrompt { ConsentDecision.Approve }
        val late = vault.signKeeperInboxAuthV1(ctx, request(), quietApprove) {
            checks++
            // Initial, first record, post-prompt, signing record, then release acceptance.
            if (checks == 5) clock = now + 120
            true
        }
        assertEquals(VaultResult.Refused(VaultRefusal.Expired), late)
        clock = now
        server.mode = FakeWitnessServer.Mode.Down
        val restarted = MlsVault.coordinated(VaultCoordination(stores, WitnessChannels { _, _, _ -> server.channel }, FakeVaultWitness()), now = { clock })
        assertEquals(VaultResult.Refused(VaultRefusal.WitnessPending), restarted.signKeeperInboxAuthV1(restarted.context(ctx.principal, alice.pubkey), request(), approve) { true })
        assertEquals(0, asked)
    }

    @Test fun `permission input is snapshotted before the prompt and exact permission provenance is retained`() = runBlocking<Unit> {
        val mutable = tags().map { it.toMutableList() }.toMutableList()
        val req = request(permission(mutable))
        val original = req.snapshot()!!
        val reply = assertIs<VaultResult.Ok<KeeperInboxAuthReply>>(sign(req, KeeperInboxConsentPrompt {
            mutable[1][1] = "ws://other/events"
            ConsentDecision.Approve
        })).value
        assertIs<VaultResult.Ok<KeeperInboxAuthReply>>(vault.acceptKeeperInboxAuthReply(original, reply))
        assertEquals(VaultResult.Refused(VaultRefusal.Replay), vault.acceptKeeperInboxAuthReply(req, reply))
        val otherPermission = request(permission(createdAt = now - 1))
        assertEquals(VaultResult.Refused(VaultRefusal.Replay), vault.acceptKeeperInboxAuthReply(otherPermission, reply))
    }

    @Test fun `short deadline rollback is refused during prompt and later acceptance`() = runBlocking<Unit> {
        val req = request().copy(expiresAt = now + 60)
        assertEquals(VaultResult.Refused(VaultRefusal.Busy), sign(req, KeeperInboxConsentPrompt { clock--; ConsentDecision.Approve }))
        clock = now
        val reply = assertIs<VaultResult.Ok<KeeperInboxAuthReply>>(sign(req)).value
        clock--
        assertEquals(VaultResult.Refused(VaultRefusal.Busy), vault.acceptKeeperInboxAuthReply(req, reply))
    }

    @Test fun `renewing the same device credential cannot rebind pending consent or an existing reply`() = runBlocking<Unit> {
        val first = sign(prompt = KeeperInboxConsentPrompt {
            assertIs<VaultResult.Ok<EnrolledDevice>>(vault.renewCredential(ctx, alice, device.credentialExpiresAt + 86_400))
            ConsentDecision.Approve
        })
        assertEquals(VaultResult.Refused(VaultRefusal.Stale), first)
        val req = request()
        val originalId = assertIs<VaultResult.Ok<EnrolledDevice>>(vault.device(ctx)).value.credentialId
        val reply = assertIs<VaultResult.Ok<KeeperInboxAuthReply>>(sign(req)).value
        assertIs<VaultResult.Ok<EnrolledDevice>>(vault.renewCredential(ctx, alice, device.credentialExpiresAt + 2 * 86_400))
        assertIs<VaultResult.Ok<Unit>>(vault.revokeCredential(ctx, originalId))
        assertEquals(VaultResult.Refused(VaultRefusal.Stale), vault.acceptKeeperInboxAuthReply(req, reply))
    }

    @Test fun `actual job cancellation after prompt and during final guard cannot return AUTH`() = runBlocking<Unit> {
        supervisorScope {
            var returned = false
            val pending = async {
                sign(prompt = KeeperInboxConsentPrompt { currentCoroutineContext().cancel(); ConsentDecision.Approve })
                returned = true
            }
            assertFailsWith<CancellationException> { pending.await() }
            assertFalse(returned)
        }
        supervisorScope {
            var returned = false
            val pending = async {
                val own = currentCoroutineContext()
                var checks = 0
                vault.signKeeperInboxAuthV1(ctx, request(), KeeperInboxConsentPrompt { ConsentDecision.Approve }) {
                    // Cancel at the final acceptor current/deadline check.
                    if (++checks == 7) own.cancel()
                    true
                }
                returned = true
            }
            assertFailsWith<CancellationException> { pending.await() }
            assertFalse(returned)
        }
    }

    @Test fun `original credential expiry is enforced after the final confirmed read`() = runBlocking<Unit> {
        device = assertIs<VaultResult.Ok<EnrolledDevice>>(vault.enrol(ctx, alice, now + 30, replace = true)).value
        var checks = 0
        val result = vault.signKeeperInboxAuthV1(ctx, request(), KeeperInboxConsentPrompt { ConsentDecision.Approve }) {
            if (++checks == 7) clock = now + 30
            true
        }
        assertEquals(VaultResult.Refused(VaultRefusal.Expired), result)
    }

    @Test fun `uncoordinated vault and expired credential refuse and cancellation propagates`() = runBlocking<Unit> {
        val plain = MlsVault(stores, now = { clock })
        assertEquals(VaultResult.Refused(VaultRefusal.Unsupported), plain.signKeeperInboxAuthV1(plain.context(ctx.principal, alice.pubkey), request(), approve) { true })
        assertFailsWith<CancellationException> { sign(prompt = KeeperInboxConsentPrompt { throw CancellationException("cancelled") }) }
        assertFailsWith<CancellationException> { vault.signKeeperInboxAuthV1(ctx, request(), approve) { throw CancellationException("cancelled") } }
        clock = device.credentialExpiresAt
        val req = request(permission(tags().map { if (it[0] == "expiration") listOf("expiration", (clock + 1000).toString()) else it }, createdAt = clock)).copy(expiresAt = clock + 120)
        assertEquals(VaultResult.Refused(VaultRefusal.Expired), sign(req))
    }
}
