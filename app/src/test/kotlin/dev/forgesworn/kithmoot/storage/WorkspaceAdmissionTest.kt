package dev.forgesworn.kithmoot.storage

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkspaceAdmissionTest {
    private class External : ParticipantSigner {
        private val local = LocalSigner(ByteArray(32) { 1 })
        override val pubkey = local.pubkey
        override val method = "nip55"
        var signatures = 0
        override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent {
            signatures++; return local.sign(kind, createdAt, tags, content)
        }
        override suspend fun nip44Encrypt(peer: String, plaintext: String) = local.nip44Encrypt(peer, plaintext)
        override suspend fun nip44Decrypt(peer: String, payload: String) = local.nip44Decrypt(peer, payload)
    }
    private suspend fun saved(signer: External): SavedRoom {
        val secret = ByteArray(32) { 9 }; val room = deriveRoom(secret); val relays = listOf("wss://private.example")
        val who = PrimaryIdentity.createWith(signer, room.roomId, 200, 100)
        return SavedRoom.create(secret, who, encodeJoinUrl("https://kithmoot.example/j/", secret, relays), relays, "Admitted", 100, null, null)
    }
    @Test fun expiredSigningCredentialStillAuthenticatesDeliberatelyRetainedReadingWithoutRenewal() = runTest {
        val signer = External(); val room = saved(signer)
        assertTrue(room.workspaceAdmission(signer.pubkey, 500))
        assertEquals(1, signer.signatures)
        assertFalse(room.workspaceAdmission(null, 500)); assertFalse(room.workspaceAdmission("a".repeat(64), 500))
        assertEquals(1, signer.signatures)
    }
    @Test fun bookmarkWithoutKeptCredentialDoesNotAdmitAndAReplacedBindingIsRejected() = runTest {
        val signer = External(); val room = saved(signer)
        val identity = room.json.getValue("identity").jsonObject
        val missing = SavedRoom.decode(JsonObject(room.json + ("identity" to JsonObject(identity - "credential"))))
        assertFalse(missing.workspaceAdmission(signer.pubkey, 500))
        val other = JsonObject(identity + ("participant" to JsonPrimitive("a".repeat(64))))
        assertFailsWith<IllegalArgumentException> { SavedRoom.decode(JsonObject(room.json + ("identity" to other))) }
    }
    @Test fun forgottenAuthorityAndRoomsNeverOpenedDoNotAdmit() = runTest {
        val signer = External(); val room = saved(signer)
        assertFalse(room.keysChanged().workspaceAdmission(signer.pubkey, 500))
        assertFalse(room.invitationRetired().workspaceAdmission(signer.pubkey, 500))
        val unopened = SavedRoom.decode(JsonObject(room.json + ("openedAt" to JsonPrimitive(0))))
        assertFalse(unopened.workspaceAdmission(signer.pubkey, 500))
    }
}
