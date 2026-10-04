package dev.forgesworn.kithmoot.update

import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.UpdateManifest
import kotlinx.coroutines.test.runTest
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.assertFailsWith

class UpdateFlowTest {
    @get:Rule val temp = TemporaryFolder()

    private val id = "dev.forgesworn.kithmoot"
    private val origin = "https://updates.test"
    private val manifestUrl = "$origin/android-release.json"
    private val signer = Ed25519PrivateKeyParameters(ByteArray(32) { 1 }, 0)
    private val stranger = Ed25519PrivateKeyParameters(ByteArray(32) { 2 }, 0)
    private val keys = listOf(signer.generatePublicKey().encoded.toHex())
    private val apk = ByteArray(300_000) { (it * 31).toByte() }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun manifest(versionCode: Long = 80, applicationId: String = id, bytes: Long = apk.size.toLong(), sha: String = sha256(apk)) = """
        {
          "schemaVersion": 1,
          "channel": "production",
          "applicationId": "$applicationId",
          "versionName": "0.7.0",
          "versionCode": $versionCode,
          "apkBytes": $bytes,
          "apkSha256": "$sha",
          "downloadFilename": "kithmoot-0.7.0-production.apk"
        }
    """.trimIndent().encodeToByteArray()

    private fun sign(bytes: ByteArray, key: Ed25519PrivateKeyParameters = signer): ByteArray {
        val message = UpdateManifest.signedMessage(UpdateManifest.ANDROID, bytes)
        val sig = Ed25519Signer().run { init(true, key); update(message, 0, message.size); generateSignature() }
        return (Base64.getEncoder().encodeToString(sig) + "\n").encodeToByteArray()
    }

    /** Serves fixed bodies and records every URL asked for. */
    private class FakeNetwork(val files: Map<String, ByteArray>, val chunk: Int = 16 * 1024) : UpdateNetwork {
        val requested = mutableListOf<String>()
        var chunksSent = 0

        override suspend fun fetch(url: String, limit: Int): ByteArray {
            requested += url
            val body = files[url] ?: throw IOException("$url answered 404")
            if (body.size > limit) throw IOException("too large")
            return body
        }

        override suspend fun stream(url: String, sink: (ByteArray, Int) -> Unit) {
            requested += url
            val body = files[url] ?: throw IOException("$url answered 404")
            var offset = 0
            while (offset < body.size) {
                val length = minOf(chunk, body.size - offset)
                chunksSent++
                sink(body.copyOfRange(offset, offset + length), length)
                offset += length
            }
        }
    }

    private fun network(manifest: ByteArray, signature: ByteArray = sign(manifest), served: ByteArray = apk) = FakeNetwork(mapOf(
        manifestUrl to manifest,
        "$manifestUrl.sig" to signature,
        "$origin/apk/kithmoot-0.7.0-production.apk" to served,
    ))

    private fun flow(network: UpdateNetwork, installedFrom: InstalledFrom = InstalledFrom.DIRECT, current: Long = 76) =
        UpdateFlow(network, id, current, installedFrom, keys, origin)

    private val dir: File get() = File(temp.root, "updates")

    @Test
    fun aValidUpdateIsOfferedAndDownloads() = runTest {
        val net = network(manifest())
        val offered = flow(net).check() as UpdateFlow.Check.Offered
        assertFalse(offered.viaZapstore)
        assertEquals(80L, offered.update.versionCode)
        var progress = 0L
        val file = flow(net).download(offered.update, dir) { progress = it }
        assertTrue(apk.contentEquals(file.readBytes()))
        assertEquals(apk.size.toLong(), progress)
        assertEquals("$origin/apk/kithmoot-0.7.0-production.apk", net.requested.last())
    }

    @Test
    fun theSameOrAnOlderVersionIsNotOffered() = runTest {
        assertEquals(UpdateFlow.Check.Current, flow(network(manifest(versionCode = 76))).check())
        assertEquals(UpdateFlow.Check.Current, flow(network(manifest(versionCode = 70))).check())
    }

    @Test
    fun aManifestChangedAfterSigningIsRefusedBeforeAnyApkRequest() = runTest {
        val signed = manifest()
        val changed = manifest(sha = "00".repeat(32))
        val net = network(changed, signature = sign(signed))
        assertFailsWith<UpdateUnverified> { flow(net).check() }
        assertEquals(listOf(manifestUrl, "$manifestUrl.sig"), net.requested)
    }

    @Test
    fun aManifestSignedByAnUntrustedKeyIsRefused() = runTest {
        val net = network(manifest(), signature = sign(manifest(), stranger))
        assertFailsWith<UpdateUnverified> { flow(net).check() }
        assertEquals(listOf(manifestUrl, "$manifestUrl.sig"), net.requested)
    }

    @Test
    fun aSignedManifestForAnotherApplicationIsRefused() = runTest {
        assertFailsWith<UpdateUnverified> { flow(network(manifest(applicationId = "dev.forgesworn.other"))).check() }
    }

    @Test
    fun aMissingSignatureIsAFailedCheckNotAnUnverifiedOne() = runTest {
        val net = FakeNetwork(mapOf(manifestUrl to manifest()))
        assertFailsWith<IOException> { flow(net).check() }
    }

    @Test
    fun anApkWithTheWrongHashIsDeleted() = runTest {
        val tampered = apk.copyOf().also { it[1234] = (it[1234] + 1).toByte() }
        val net = network(manifest(), served = tampered)
        val offered = flow(net).check() as UpdateFlow.Check.Offered
        assertFailsWith<UpdateUnverified> { flow(net).download(offered.update, dir) }
        assertFalse(File(dir, "kithmoot-0.7.0-production.apk").exists())
    }

    @Test
    fun aShortApkIsDeleted() = runTest {
        val net = network(manifest(), served = apk.copyOf(apk.size - 1))
        val offered = flow(net).check() as UpdateFlow.Check.Offered
        assertFailsWith<UpdateUnverified> { flow(net).download(offered.update, dir) }
        assertFalse(File(dir, "kithmoot-0.7.0-production.apk").exists())
    }

    @Test
    fun anOversizedApkIsCutOffAndDeleted() = runTest {
        // The server sends ten times what the signed manifest pins.
        val net = network(manifest(), served = ByteArray(apk.size * 10))
        val offered = flow(net).check() as UpdateFlow.Check.Offered
        assertFailsWith<UpdateUnverified> { flow(net).download(offered.update, dir) }
        assertFalse(File(dir, "kithmoot-0.7.0-production.apk").exists())
        // Stopped at the first chunk past the pinned size, not at the end.
        assertEquals(apk.size / net.chunk + 1, net.chunksSent)
    }

    @Test
    fun aZapstoreInstallIsOfferedButNeverDownloads() = runTest {
        val net = network(manifest())
        val zapstore = flow(net, InstalledFrom.ZAPSTORE)
        val offered = zapstore.check() as UpdateFlow.Check.Offered
        assertTrue(offered.viaZapstore)
        assertFailsWith<IllegalStateException> { zapstore.download(offered.update, dir) }
        assertEquals(listOf(manifestUrl, "$manifestUrl.sig"), net.requested)
        assertFalse(dir.exists())
    }

    private val update = UpdateManifest.AndroidUpdate("0.7.0", 80, 2000, "cd".repeat(32), "k.apk", certificateSha256 = "aa".repeat(32))
    private val running = setOf("bb".repeat(32))

    @Test
    fun anArchiveMustBeOurPackageAtTheManifestVersionSignedByOurKey() {
        val good = ArchiveFacts(id, 80, signers = setOf("aa".repeat(32)), certificates = setOf("aa".repeat(32), "bb".repeat(32)))
        assertNull(archiveRefusal(good, id, update, running))
        assertEquals("the download is not an APK Android can read", archiveRefusal(null, id, update, running))
        assertEquals("the download is another application", archiveRefusal(good.copy(packageName = "dev.other"), id, update, running))
        assertEquals("the download is a different version", archiveRefusal(good.copy(versionCode = 79), id, update, running))
        assertEquals("the download is signed by a different key", archiveRefusal(good.copy(certificates = setOf("aa".repeat(32))), id, update, running))
        assertEquals("the download is signed by a different key", archiveRefusal(good, id, update, emptySet()))
        assertEquals("the download is not signed by the release key", archiveRefusal(good.copy(signers = setOf("bb".repeat(32))), id, update, running))
        assertNull(archiveRefusal(good.copy(signers = setOf("bb".repeat(32))), id, update.copy(certificateSha256 = null), running))
    }
}
