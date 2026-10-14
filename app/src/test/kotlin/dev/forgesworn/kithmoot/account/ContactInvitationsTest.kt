package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.protocol.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class ContactInvitationsTest {
    private val a = LocalSigner(ByteArray(32) { 1 })
    private val b = LocalSigner(ByteArray(32) { 2 })
    private val fixture = javaClass.getResourceAsStream("/contact-invitations-web.json")!!.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
    private val now = fixture.getValue("now").jsonPrimitive.long
    private val rumor = fixture.getValue("rumor").jsonObject

    @Test fun opensBrowserEnvelopesForBothAccountsAndExportsNativeEnvelopes() = runTest {
        assertEquals(rumor, ContactInvitations.unwrap(NostrEvent.fromJson(fixture.getValue("other")), b, now))
        assertEquals(rumor, ContactInvitations.unwrap(NostrEvent.fromJson(fixture.getValue("own")), a, now))
        val received = ContactInvitations.read(rumor, b.pubkey, now)!!
        assertEquals(fixture.getValue("room").jsonPrimitive.content, received.room)
        val out = java.io.File("build/interop/contact-invitations-android.json")
        out.parentFile!!.mkdirs()
        out.writeText(buildJsonObject {
            put("rumor", rumor)
            put("other", ContactInvitations.wrap(rumor, a, b.pubkey, now) { true }.toJson())
            put("own", ContactInvitations.wrap(rumor, a, a.pubkey, now) { true }.toJson())
        }.toString())
    }
    @Test fun refusesMismatchedMembershipRoomMarkerSignatureAndAuthenticatedSender() = runTest {
        assertNull(ContactInvitations.read(rumor, "f".repeat(64), now))
        assertNull(ContactInvitations.read(JsonObject(rumor + ("content" to JsonPrimitive("https://other.example/"))), b.pubkey, now))
        assertNull(ContactInvitations.unwrap(NostrEvent.fromJson(fixture.getValue("other")).copy(sig = "0".repeat(128)), b, now))
        val attacker = LocalSigner(ByteArray(32) { 4 })
        // The real sender's rumor inside someone else's valid seal is refused.
        val seal = attacker.sign(13, now, emptyList(), attacker.nip44Encrypt(b.pubkey, rumor.toString()))
        val wrap = attacker.sign(1059, now, listOf(listOf("p", b.pubkey)), attacker.nip44Encrypt(b.pubkey, seal.toCompactJson()))
        assertNull(ContactInvitations.unwrap(wrap, b, now))
        val valid = ContactInvitations.read(rumor, a.pubkey, now)!!
        assertFails { ContactInvitations.rumor(a.pubkey, attacker.pubkey, valid.room, valid.link, now) }
    }
    @Test fun withdrewPermissionWhileSignerWasEncrypting() = runTest {
        var allowed = true
        var signed = false
        val delayed = object : ParticipantSigner by a {
            override suspend fun nip44Encrypt(peer: String, plaintext: String): String = a.nip44Encrypt(peer, plaintext).also { allowed = false }
            override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent { signed = true; return a.sign(kind, createdAt, tags, content) }
        }
        assertFails { ContactInvitations.wrap(rumor, delayed, b.pubkey, now) { allowed } }
        assertFalse(signed)
    }
}
