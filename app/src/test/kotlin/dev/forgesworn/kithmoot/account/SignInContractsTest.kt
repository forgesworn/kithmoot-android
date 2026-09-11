package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.KIND_DEVICE_CREDENTIAL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SignInContractsTest {
    private val remote = Entropy.bytes(32)
    private val remotePubkey = Schnorr.publicKeyHex(remote)

    @Test fun `Bothy permissions add authentication and grant signing to the base request`() {
        val permissions = Json.parseToJsonElement(Nip55.permissions(listOf(22242, 24242))).jsonArray
        assertEquals(listOf("20460", "22242", "24242"), permissions.take(3).map {
            it.jsonObject.getValue("kind").jsonPrimitive.content
        })
        assertEquals(listOf("nip44_encrypt", "nip44_decrypt"), permissions.drop(3).map {
            it.jsonObject.getValue("type").jsonPrimitive.content
        })
    }

    @Test fun `a bunker link names the signer, its relays and the secret`() {
        val pointer = BunkerPointer.parse("bunker://$remotePubkey?relay=wss%3A%2F%2Frelay.example&relay=wss://two.example&secret=s3cret")!!
        assertEquals(remotePubkey, pointer.remotePubkey)
        assertEquals(listOf("wss://relay.example", "wss://two.example"), pointer.relays)
        assertEquals("s3cret", pointer.secret)
        assertNull(BunkerPointer.parse("bunker://$remotePubkey"), "no relay, nowhere to reach it")
        assertNull(BunkerPointer.parse("nostrconnect://$remotePubkey?relay=wss://x"))
        assertNull(BunkerPointer.parse("bunker://notakey?relay=wss://x"))
    }

    @Test fun `the Signet link carries a nostrconnect invitation and an https callback on the site`() {
        val client = "cd".repeat(32)
        val uri = SignetSignIn.nostrConnectUri(client, listOf("wss://relay.damus.io", "wss://nos.lol"), "s3cret")
        assertEquals("nostrconnect://$client?relay=wss%3A%2F%2Frelay.damus.io&relay=wss%3A%2F%2Fnos.lol&secret=s3cret&perms=sign_event%3A20460%2Cnip44_encrypt%2Cnip44_decrypt&name=KithMoot&url=https%3A%2F%2Fkithmoot.forgesworn.dev", uri)
        val url = SignetSignIn.url(uri)
        assertTrue(url.startsWith("https://mysignet.app/?nostrconnect=nostrconnect%3A%2F%2F$client"), url)
        assertTrue(url.endsWith("&callback=https%3A%2F%2Fkithmoot.forgesworn.dev%2Fsignet%2F"), url)
        // Signet checks the callback's origin against the invitation's url: both are the site.
        assertEquals(java.net.URI(SignetSignIn.CALLBACK).host, java.net.URI(SignetSignIn.APP_URL).host)
    }

    @Test fun `the bridge page sends the app an outcome and nothing else`() {
        assertEquals(SignetSignIn.Outcome.APPROVED, SignetSignIn.parse("kithmoot://signet?status=approved"))
        assertEquals(SignetSignIn.Outcome.DENIED, SignetSignIn.parse("kithmoot://signet?status=denied"))
        assertNull(SignetSignIn.parse("kithmoot://signet?status=maybe"))
        assertNull(SignetSignIn.parse("kithmoot://join#abc"), "a room link is not a sign-in")
        assertNull(SignetSignIn.parse("https://evil.example/?status=approved"))
    }

    @Test fun `a bunker pointer writes itself back as a link`() {
        val pointer = BunkerPointer(remotePubkey, listOf("wss://relay.damus.io"), "s3cret")
        assertEquals(pointer, BunkerPointer.parse(pointer.toUri()))
    }

    @Test fun `a signer app answers with an npub or hex, and with a whole event or just a signature`() {
        assertEquals(remotePubkey, Nip55.publicKeyFromResult(npubOf(remotePubkey)))
        assertEquals(remotePubkey, Nip55.publicKeyFromResult(remotePubkey))
        assertNull(Nip55.publicKeyFromResult("nope"))

        val unsigned = unsignedEventJson(remotePubkey, KIND_DEVICE_CREDENTIAL, 1_800_000_000, listOf(listOf("d", "room")), "")
        val signed = Events.sign(remote, KIND_DEVICE_CREDENTIAL, 1_800_000_000, listOf(listOf("d", "room")), "")
        assertEquals(signed, Nip55.signedEventFromResult(signed.toJson().toString(), null, unsigned))
        val fromSignature = Nip55.signedEventFromResult(null, signed.sig, unsigned)!!
        assertEquals(signed, fromSignature)
        assertTrue(Events.verify(fromSignature))
        assertNull(Nip55.signedEventFromResult(null, "zz", unsigned))
    }

    @Test fun `a remote signer's answer is held to the request`() {
        val tags = listOf(listOf("d", "room"))
        val good = Events.sign(remote, KIND_DEVICE_CREDENTIAL, 1_800_000_000, tags, "")
        assertEquals(good, checkedSignedEvent(good, remotePubkey, KIND_DEVICE_CREDENTIAL, 1_800_000_000, tags, ""))
        val other = Events.sign(Entropy.bytes(32), KIND_DEVICE_CREDENTIAL, 1_800_000_000, tags, "")
        assertFailsWith<SignerException> { checkedSignedEvent(other, remotePubkey, KIND_DEVICE_CREDENTIAL, 1_800_000_000, tags, "") }
        val changed = Events.sign(remote, KIND_DEVICE_CREDENTIAL, 1_800_000_000, listOf(listOf("d", "another")), "")
        assertFailsWith<SignerException> { checkedSignedEvent(changed, remotePubkey, KIND_DEVICE_CREDENTIAL, 1_800_000_000, tags, "") }
        assertFailsWith<SignerException> { checkedSignedEvent(good.copy(sig = "0".repeat(128)), remotePubkey, KIND_DEVICE_CREDENTIAL, 1_800_000_000, tags, "") }
    }

    @Test fun `the account record round trips through json`() = runTest {
        val bunker = NostrAccount(remotePubkey, "bunker", bunkerUri = "bunker://$remotePubkey?relay=wss://r", clientSecretKey = Entropy.bytes(32), displayName = "Robin", signedInAt = 5)
        val back = NostrAccount.fromJson(bunker.toJson())
        assertEquals(bunker.pubkey, back.pubkey); assertEquals(bunker.bunkerUri, back.bunkerUri)
        assertContentEquals(bunker.clientSecretKey, back.clientSecretKey); assertEquals("Robin", back.displayName)
        assertFailsWith<IllegalArgumentException> { NostrAccount.fromJson(NostrAccount(remotePubkey, "bunker").toJson()) }
        assertFailsWith<IllegalArgumentException> { NostrAccount.fromJson(NostrAccount(remotePubkey, "nip55").toJson()) }
        assertEquals("NostrAccount(${shortNpub(remotePubkey)}, bunker)", bunker.toString(), "no secret in the string form")
    }
}
