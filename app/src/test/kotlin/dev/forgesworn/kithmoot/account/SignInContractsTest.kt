package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.KIND_DEVICE_CREDENTIAL
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SignInContractsTest {
    private val remote = Entropy.bytes(32)
    private val remotePubkey = Schnorr.publicKeyHex(remote)

    @Test fun `a bunker link names the signer, its relays and the secret`() {
        val pointer = BunkerPointer.parse("bunker://$remotePubkey?relay=wss%3A%2F%2Frelay.example&relay=wss://two.example&secret=s3cret")!!
        assertEquals(remotePubkey, pointer.remotePubkey)
        assertEquals(listOf("wss://relay.example", "wss://two.example"), pointer.relays)
        assertEquals("s3cret", pointer.secret)
        assertNull(BunkerPointer.parse("bunker://$remotePubkey"), "no relay, nowhere to reach it")
        assertNull(BunkerPointer.parse("nostrconnect://$remotePubkey?relay=wss://x"))
        assertNull(BunkerPointer.parse("bunker://notakey?relay=wss://x"))
    }

    @Test fun `the Signet callback carries the key and the bunker`() {
        val bunker = "bunker://$remotePubkey?relay=wss://relay.example&secret=abc"
        val link = "kithmoot://signet?pubkey=$remotePubkey&signature=00&eventId=11&bunker=" + java.net.URLEncoder.encode(bunker, "UTF-8") + "&display_name=Robin"
        val result = SignetSignIn.parse(link)
        assertIs<SignetSignIn.Callback.SignedIn>(result)
        assertEquals(remotePubkey, result.pubkey)
        assertEquals(bunker, result.bunkerUri)
        assertEquals("Robin", result.displayName)
        assertEquals(SignetSignIn.Callback.Denied, SignetSignIn.parse("kithmoot://signet?error=denied"))
        assertIs<SignetSignIn.Callback.Failed>(SignetSignIn.parse("kithmoot://signet?signature=00"))
        assertNull(SignetSignIn.parse("kithmoot://join#abc"), "a room link is not a sign-in")
        assertNull(SignetSignIn.parse("https://evil.example/?pubkey=$remotePubkey"))
    }

    @Test fun `the Signet sign-in url is what signet-login builds`() {
        val challenge = "ab".repeat(32)
        val url = SignetSignIn.url(challenge, at = 1_800_000_000)
        assertTrue(url.startsWith("https://mysignet.app/?auth=1&challenge=$challenge&origin=https%3A%2F%2Fkithmoot.forgesworn.dev&name=KithMoot&callback=kithmoot%3A%2F%2Fsignet&t=1800000000"), url)
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
