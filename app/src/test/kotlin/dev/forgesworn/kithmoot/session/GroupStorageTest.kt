package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.GrantReceiver
import dev.forgesworn.kithmoot.protocol.GroupEvent
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.buildGrant
import dev.forgesworn.kithmoot.protocol.buildGroupToken
import dev.forgesworn.kithmoot.protocol.buildReset
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/**
 * The group storage client against fake nodes that hold it to the contract:
 * which node it uses, what it signs, what it never sends, and the words for
 * each way a file has to wait. Mirrors `src/group-storage.test.ts`.
 */
class GroupStorageTest {
    private var clock = 1_800_000_000L
    private val fake = FakeGroupNodes { clock }
    private val authoritySk = ByteArray(32) { 7 }
    private val authority = Schnorr.publicKeyHex(authoritySk)
    private val deviceSk = ByteArray(32) { 9 }
    private val device = Schnorr.publicKeyHex(deviceSk)
    private val room = "2848bdd5c60a79e2dfe667ae9616c1107f61709032b66e556e28f4a96e69450b"
    private val grove = "https://grove.example.net"
    private val heath = "https://heath.example.net:8443/wb"
    private val envelope = ByteArray(70_000) { (it % 251).toByte() }
    private val hash = envelopeHash(envelope)
    private val receiver = GrantReceiver(authority, room, device)
    private var listed = listOf(grove)
    private val said = ArrayList<String>()

    private fun grant(server: String, scopes: List<String> = listOf("upload", "get", "delete"), id: String = "ab".repeat(16),
        at: Long = clock, status: String = "active"): GroupEvent =
        buildGrant(authoritySk, room, device, server, scopes, if ("upload" in scopes) 1_000_000_000L else null, id, at, at + 30 * 86_400, status)

    private fun storage() = GroupStorage(
        nodes = { listed }, deviceSecretKey = deviceSk, receiver = receiver, client = fake.client, now = { clock },
        onNodeOpen = { said += "open:$it" }, onClockWrong = { s, k -> said += "clock:$s:$k" },
        onNoGrant = { said += "no-grant" }, onLockedOut = { said += "locked-out" },
    )

    @Test fun `a granted device stores on the first listed node and reads it back with signed tokens`() = runTest {
        fake.add("grove.example.net", authority)
        receiver.offer(grant("grove.example.net").toJson())
        val storage = storage()
        val stored = storage.upload(envelope, hash)
        assertEquals(GroupUploadResult.Stored("$grove/$hash", grove, "grove.example.net", envelope.size.toLong()), stored)
        assertArrayEquals(envelope, storage.get(hash, prefer = grove, maxBytes = 32L * 1024 * 1024))
        // Probe without a token, the device's own grant posted once, then the upload and the read with tokens.
        assertEquals(listOf("HEAD" to false, "POST" to false, "PUT" to true, "GET" to true), fake.hits.map { it.method to it.token })
        assertEquals(201, fake.hits[1].status)
        assertEquals(listOf<String>(), said)
    }

    @Test fun `every token names the node, the blob and the verb, signed by the room's device key, a second in the past`() = runTest {
        val token = buildGroupToken("upload", hash, "grove.example.net", clock, deviceSk)
        assertEquals(24242, token.kind)
        assertEquals(clock - 1, token.createdAt)
        assertEquals(device, token.pubkey)
        assertEquals(listOf(listOf("t", "upload"), listOf("expiration", "${clock - 1 + 90}"), listOf("server", "grove.example.net"), listOf("x", hash)), token.tags)
        assertEquals("Upload blob $hash to grove.example.net", token.content)
        val delete = buildGroupToken("delete", hash, "grove.example.net", clock, deviceSk, target = "$grove/$hash")
        assertEquals("${clock - 1 + 7 * 86_400 + 1}", delete.tagValue("expiration"))
        assertEquals("$grove/$hash", delete.tagValue("kithmoot-delete-target-v1"))
        val header = groupAuthHeader(token)
        assertTrue(header.startsWith("Nostr "))
        assertEquals(token, NostrEvent.fromJson(Json.parseToJsonElement(Base64.getUrlDecoder().decode(header.substring(6)).toString(Charsets.UTF_8))))
    }

    @Test fun `without a grant nothing is sent anywhere and the keeper is asked`() = runTest {
        fake.add("grove.example.net", authority)
        val result = storage().upload(envelope, hash)
        assertEquals(GroupUploadResult.Held(GroupStorageReason.NO_GRANT), result)
        assertTrue(fake.hits.isEmpty())
        assertEquals(listOf("no-grant"), said)
        assertEquals("Group files will be available once the keeper has let this device in.", GroupStorageReason.NO_GRANT.words)
    }

    @Test fun `a node that serves files to anyone is never sent a token`() = runTest {
        fake.add("grove.example.net", authority).open = true
        receiver.offer(grant("grove.example.net").toJson())
        assertEquals(GroupUploadResult.Held(GroupStorageReason.NODE_OPEN), storage().upload(envelope, hash))
        assertEquals(listOf("HEAD" to false), fake.hits.map { it.method to it.token })
        assertEquals(listOf("open:grove.example.net"), said)
    }

    @Test fun `a probe that is redirected is an open node`() = runTest {
        fake.add("grove.example.net", authority).redirect = true
        receiver.offer(grant("grove.example.net").toJson())
        assertEquals(GroupUploadResult.Held(GroupStorageReason.NODE_OPEN), storage().upload(envelope, hash))
        assertFalse(fake.hits.any { it.token })
    }

    @Test fun `a node that is down moves the upload to the next, and with none left the file waits`() = runTest {
        fake.add("grove.example.net", authority).down = true
        fake.add("heath.example.net", authority)
        listed = listOf(grove, heath)
        receiver.offer(grant("grove.example.net").toJson())
        receiver.offer(grant("heath.example.net", id = "cd".repeat(16)).toJson())
        val stored = storage().upload(envelope, hash) as GroupUploadResult.Stored
        assertEquals("$heath/$hash", stored.url)
        fake.nodes.getValue("heath.example.net").down = true
        assertEquals(GroupUploadResult.Held(GroupStorageReason.UNREACHABLE), storage().upload(envelope, hash))
        assertEquals("Waiting for the group’s storage.", GroupStorageReason.UNREACHABLE.words)
    }

    @Test fun `a node that has forgotten the grant is sent it again once, and the request repeated once`() = runTest {
        val node = fake.add("grove.example.net", authority)
        receiver.offer(grant("grove.example.net").toJson())
        val storage = storage()
        assertTrue(storage.upload(envelope, hash) is GroupUploadResult.Stored)
        node.forgetGrants()
        fake.hits.clear()
        assertArrayEquals(envelope, storage.get(hash, maxBytes = 1_000_000))
        assertEquals(listOf("GET" to 403, "POST" to 201, "GET" to 200), fake.hits.map { it.method to it.status })
    }

    @Test fun `a revoked device is told it is no longer let in, and carries the revocation`() = runTest {
        val node = fake.add("grove.example.net", authority)
        val first = grant("grove.example.net")
        receiver.offer(first.toJson())
        receiver.offer(grant("grove.example.net", at = clock + 1, status = "revoked").toJson())
        assertEquals(GroupUploadResult.Held(GroupStorageReason.LOCKED_OUT), storage().upload(envelope, hash))
        assertEquals(listOf("locked-out"), said)
        // Reading asks a node: the revocation goes first, as the device carries it.
        clock += 2
        receiver.offer(grant("grove.example.net", id = "ef".repeat(16)).toJson())
        assertTrue(storage().upload(envelope, hash) is GroupUploadResult.Stored)
        assertTrue("ab".repeat(16) in node.revoked)
    }

    @Test fun `a reset at a node ends this device's older grant there`() = runTest {
        fake.add("grove.example.net", authority)
        receiver.offer(grant("grove.example.net").toJson())
        receiver.offer(buildReset(authoritySk, room, "grove.example.net", clock + 5).toJson())
        assertEquals(GroupUploadResult.Held(GroupStorageReason.LOCKED_OUT), storage().upload(envelope, hash))
    }

    @Test fun `a wrong clock is said once and stops the device asking until the room is opened again`() = runTest {
        fake.add("grove.example.net", authority).skew = 3_600
        receiver.offer(grant("grove.example.net").toJson())
        val storage = storage()
        assertEquals(GroupUploadResult.Held(GroupStorageReason.CLOCK), storage.upload(envelope, hash))
        assertEquals(listOf("clock:grove.example.net:3600"), said)
        val before = fake.hits.size
        assertEquals(GroupUploadResult.Held(GroupStorageReason.CLOCK), storage.upload(envelope, hash))
        assertEquals(before, fake.hits.size)
        assertTrue(GroupStorageReason.CLOCK.final)
    }

    @Test fun `a descriptor that does not describe what was sent is a refusal`() = runTest {
        fake.add("grove.example.net", authority).lyingDescriptor = true
        receiver.offer(grant("grove.example.net").toJson())
        assertEquals(GroupUploadResult.Held(GroupStorageReason.REFUSED), storage().upload(envelope, hash))
    }

    @Test fun `reads refuse a file over the limit, and say when nobody has it`() = runTest {
        fake.add("grove.example.net", authority)
        receiver.offer(grant("grove.example.net").toJson())
        val storage = storage()
        storage.upload(envelope, hash)
        try { storage.get(hash, maxBytes = 1_000); fail() } catch (e: GroupStorageException) { assertEquals(GroupStorageReason.REFUSED, e.reason) }
        try { storage.get("00".repeat(32), maxBytes = 1_000_000); fail() } catch (e: GroupStorageException) {
            assertEquals(GroupStorageReason.ABSENT, e.reason)
            assertEquals("This file is on the group's computers, and none of them can be reached right now.", e.message)
        }
    }

    @Test fun `only https nodes this app can reach are used, and nothing else is ever asked`() = runTest {
        fake.add("127.0.0.1", authority)
        fake.add("grove.example.net", authority)
        listed = listOf("http://127.0.0.1:8080", grove)
        receiver.offer(grant("127.0.0.1", id = "cd".repeat(16)).toJson())
        receiver.offer(grant("grove.example.net").toJson())
        assertTrue(storage().upload(envelope, hash) is GroupUploadResult.Stored)
        assertEquals(setOf("grove.example.net"), fake.hits.map { it.host }.toSet())
    }

    @Test fun `a delete spends its stored token once and confirms the file is gone`() = runTest {
        fake.add("grove.example.net", authority)
        receiver.offer(grant("grove.example.net").toJson())
        val storage = storage()
        storage.upload(envelope, hash)
        val url = "$grove/$hash"
        val token = buildGroupToken("delete", hash, "grove.example.net", clock, deviceSk, target = url)
        assertEquals(GroupBlobDeleteOutcome.Deleted(), deleteGroupBlob(url, token, fake.client) { u, h -> storage.head(u.removeSuffix("/$h"), h) })
        assertEquals(GroupBlobDeleteOutcome.Failed("grove.example.net holds the file again."), deleteGroupBlob(url, token, fake.client))
        // Without a signed look the node's 401 to every read leaves it unconfirmed.
        storage.upload(envelope, hash)
        clock += 1
        val again = buildGroupToken("delete", hash, "grove.example.net", clock, deviceSk, target = url)
        assertTrue((deleteGroupBlob(url, again, fake.client) as GroupBlobDeleteOutcome.Deleted).detail!!.contains("not confirmed"))
        // A token made for another URL is refused before anything is sent.
        val before = fake.hits.size
        assertEquals("File deletion authorisation could not be verified.",
            (deleteGroupBlob("$grove/${"00".repeat(32)}", again, fake.client) as GroupBlobDeleteOutcome.Failed).detail)
        assertEquals(before, fake.hits.size)
    }

    @Test fun `the descriptor check holds the node to the base, the hash and the size`() {
        val ok = """{"url":"https://example.org:8443/wb/$hash.bin","sha256":"$hash","size":5}"""
        assertTrue(descriptorFits(ok, "https://example.org:8443/wb", hash, 5))
        assertFalse(descriptorFits(ok, "https://example.org:8443/wb", hash, 6))
        assertFalse(descriptorFits(ok, "https://example.org:8443/other", hash, 5))
        assertFalse(descriptorFits(ok.replace("8443", "9443"), "https://example.org:8443/wb", hash, 5))
        assertFalse(descriptorFits(ok.replace(".bin", "?x=1"), "https://example.org:8443/wb", hash, 5))
    }
}
