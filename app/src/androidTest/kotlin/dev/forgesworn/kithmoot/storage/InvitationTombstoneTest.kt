package dev.forgesworn.kithmoot.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.forgesworn.kithmoot.account.ConsentDecision
import dev.forgesworn.kithmoot.account.ConsentPrompt
import dev.forgesworn.kithmoot.account.EngineSession
import dev.forgesworn.kithmoot.account.EngineSessions
import dev.forgesworn.kithmoot.account.EngineStep
import dev.forgesworn.kithmoot.account.EnrolledDevice
import dev.forgesworn.kithmoot.account.Hosted
import dev.forgesworn.kithmoot.account.InvitationConsentPrompt
import dev.forgesworn.kithmoot.account.InvitationSignInput
import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.account.MlsVault
import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.account.RendezvousReceipt
import dev.forgesworn.kithmoot.account.SessionHost
import dev.forgesworn.kithmoot.account.StoredRendezvousChild
import dev.forgesworn.kithmoot.account.VaultResult
import dev.forgesworn.kithmoot.account.hostedStep
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.vmls.ffi.VmlsBindingRequest
import dev.forgesworn.vmls.ffi.VmlsCredential
import dev.forgesworn.vmls.ffi.VmlsException
import dev.forgesworn.vmls.ffi.VmlsInvitationTarget
import dev.forgesworn.vmls.ffi.VmlsReturnRoute
import dev.forgesworn.vmls.ffi.inspectInvitation
import dev.forgesworn.vmls.ffi.prepareCreateInvitationProfile
import dev.forgesworn.vmls.ffi.prepareInvitationCapability
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Goal 1 acceptance row: the vault's tombstone list reaches the engine's
 * invitation gates (Vennel PRs #348/#349's adapter parameters). A real
 * invitation is issued by the real engine under a coordinated vault and fake
 * witness (no box), the inviter's credential is then tombstoned in the vault,
 * and inspecting the same bytes with the vault's list is refused
 * `CredentialRevoked`; the empty-list control still passes.
 */
@RunWith(AndroidJUnit4::class)
class InvitationTombstoneTest {
    private lateinit var context: Context
    private lateinit var prefix: String
    private lateinit var witness: FakeEd25519Witness
    private val principal = "dev.forgesworn.kithmoot"
    private val random = SecureRandom()
    private val identity = LocalSigner(ByteArray(32).also { random.nextBytes(it) })
    private val now get() = System.currentTimeMillis() / 1000
    private val approve = ConsentPrompt { ConsentDecision.Approve }
    private var credential: NostrEvent? = null

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        prefix = "kithmoot.tombstone-test.${UUID.randomUUID().toString().take(8)}"
        witness = FakeEd25519Witness()
    }

    @After fun cleanup() {
        context.noBackupFilesDir.listFiles().orEmpty().filter { it.name.startsWith("$prefix.") }.forEach { it.delete() }
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keys.aliases().toList().filter { it.startsWith("$prefix.") }.forEach(keys::deleteEntry)
    }

    private fun <R> Hosted<R>.released(): R = (this as? Hosted.Released)?.value ?: error("The vault held or refused this step: $this")

    /** A valid BIP-340 secret, as the labs draw them. */
    private fun secret(): ByteArray {
        while (true) { val k = ByteArray(32).also(random::nextBytes); if (runCatching { Schnorr.publicKey(k) }.isSuccess) return k }
    }

    private suspend fun enrolDevice(v: MlsVault): EnrolledDevice {
        val signer = object : ParticipantSigner by identity {
            override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent =
                identity.sign(kind, createdAt, tags, content).also { credential = it }
        }
        return (v.enrol(v.context(principal, identity.pubkey), signer, now + 86_400) as VaultResult.Ok).value
    }

    @Test fun a_tombstoned_inviters_invitation_is_refused_at_inspect_time() = runBlocking {
        val persona = identity.pubkey
        val vault = VmlsLab.coordinatedVault(context, prefix, witness, persona, random)
        val device = enrolDevice(vault)
        val signing = checkNotNull(credential) { "enrolment captured no device credential" }
        val rzSecret = secret()
        val rz = Schnorr.publicKey(rzSecret)
        val child = StoredRendezvousChild(
            RendezvousReceipt(persona, "b".repeat(64), rz.toHex(), 1, now + 86_400), rzSecret.copyOf())

        // A real lone-member group on the invitation profile, witnessed like production's.
        val sessions = EngineSessions(device.device.hexToBytes(), rz)
        val host = SessionHost(vault, sessions)
        val homeBox = ByteArray(32).also(random::nextBytes)
        val request = bindingRequest(signing, homeBox, now)
        val pending = prepareCreateInvitationProfile(sessions.platform, now.toULong(), request, ByteArray(32).also(random::nextBytes))
        var sessionHex: String? = null
        pending.use {
            val sign = pending.request()
            val signature = signBinding(vault, persona, sign)
            host.create(persona) {
                val created = pending.complete(now.toULong(), sign.operation, signature)
                sessionHex = created.session.id().toHex()
                EngineSession(created.session) to hostedStep(created.step)
            }.released()
        }
        val session = checkNotNull(sessionHex)

        // A real issued invitation for a fabricated but valid joiner.
        val joiner = LocalSigner(secret())
        val joinerDevice = Schnorr.publicKey(secret())
        val joinerRz = Schnorr.publicKey(secret())
        val target = VmlsInvitationTarget(
            joiner.pubkey.hexToBytes(), joinerDevice, joinerRz,
            ByteArray(32).also(random::nextBytes), VmlsReturnRoute.PrivateOob, (now + 86_400).toULong())
        val pendingInvitation = host.step(persona, session.hexToBytes()) { s ->
            EngineStep(null, s.inner.prepareInvitation(now.toULong(), target))
        }.released()
        val invitation: ByteArray
        pendingInvitation.use { pending ->
            val sign = pending.request()
            val input = InvitationSignInput(sign.operation, sign.body, sign.digest, sign.contextBytes, sign.contextCommitment, sign.expiresAt.toLong())
            val signed = vault.signInvitationAuthorizationV1(vault.context(principal, persona), input, InvitationConsentPrompt { ConsentDecision.Approve }) { child }
            val reply = when (signed) { is VaultResult.Ok -> signed.value; is VaultResult.Refused -> error("invitation signing refused: ${signed.refusal}") }
            val signature = when (val accepted = vault.acceptInvitationSignReply(input, reply)) {
                is VaultResult.Ok -> accepted.value.signature.hexToBytes()
                is VaultResult.Refused -> error("invitation reply refused: ${accepted.refusal}")
            }
            host.step(persona, session.hexToBytes()) { s ->
                hostedStep(s.inner.completeInvitation(now.toULong(), pending, sign.operation, signature))
            }.released()
            invitation = host.step(persona, session.hexToBytes()) { s ->
                EngineStep(null, s.inner.issuedInvitation(sign.context.invitationId, now.toULong()))
            }.released()
        }

        // Control: with an empty list the invitation inspects clean.
        val clean = inspectInvitation(now.toULong(), invitation, emptyList())
        assertTrue(clean.context.inviterPersona.contentEquals(persona.hexToBytes()))

        // The vault tombstones the inviter's credential; its list then refuses the same bytes.
        val ctx = vault.context(principal, persona)
        assertTrue(vault.revokeCredential(ctx, device.credentialId) is VaultResult.Ok)
        val tombstones = (vault.revokedCredentials(ctx) as VaultResult.Ok).value
        assertEquals(listOf(device.credentialId), tombstones)
        val refusal = try {
            inspectInvitation(now.toULong(), invitation, tombstones.map { it.hexToBytes() })
            fail("a tombstoned inviter's invitation must be refused")
        } catch (engine: VmlsException.Engine) {
            engine.code
        }
        assertEquals("CredentialRevoked", refusal)

        // The same list, bound through the wired EngineSessions platform, gates the capability prepare equally.
        val wired = EngineSessions(device.device.hexToBytes(), rz, revokedCredentialIds = tombstones)
        try {
            prepareInvitationCapability(wired.platform, now.toULong(), request, invitation, persona.hexToBytes())
            fail("a platform built from the vault's tombstones must not prepare this capability")
        } catch (engine: VmlsException.Engine) {
            assertEquals("CredentialRevoked", engine.code)
        }
    }
}
