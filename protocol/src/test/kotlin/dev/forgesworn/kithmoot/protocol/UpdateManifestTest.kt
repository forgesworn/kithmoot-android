package dev.forgesworn.kithmoot.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdateManifestTest {

    private val id = "dev.forgesworn.kithmoot"

    private fun manifest(vararg overrides: Pair<String, String?>): ByteArray {
        val fields = linkedMapOf(
            "schemaVersion" to "1",
            "channel" to "\"production\"",
            "applicationId" to "\"$id\"",
            "versionName" to "\"0.7.0\"",
            "versionCode" to "80",
            "apkBytes" to "2000",
            "apkSha256" to "\"${"cd".repeat(32)}\"",
            "downloadFilename" to "\"kithmoot-0.7.0-production.apk\"",
        )
        for ((key, value) in overrides) if (value == null) fields.remove(key) else fields[key] = value
        return fields.entries.joinToString(",\n", "{\n", "\n}\n") { (k, v) -> "  \"$k\": $v" }.encodeToByteArray()
    }

    private fun refused(vararg overrides: Pair<String, String?>) {
        assertThrows(UpdateManifest.MalformedManifest::class.java) { UpdateManifest.androidUpdate(manifest(*overrides), id, 76) }
    }

    @Test
    fun aNewerReleaseIsOffered() {
        val update = UpdateManifest.androidUpdate(manifest(), id, 76)!!
        assertEquals(80L, update.versionCode)
        assertEquals("0.7.0", update.versionName)
        assertEquals(2000L, update.apkBytes)
        assertEquals("cd".repeat(32), update.apkSha256)
        assertEquals("kithmoot-0.7.0-production.apk", update.downloadFilename)
        assertNull(update.certificateSha256)
    }

    @Test
    fun theSameOrAnOlderReleaseIsNot() {
        assertNull(UpdateManifest.androidUpdate(manifest(), id, 80))
        assertNull(UpdateManifest.androidUpdate(manifest(), id, 81))
    }

    @Test
    fun anythingNotForThisAppOrNotWellFormedIsRefused() {
        refused("schemaVersion" to "2")
        refused("schemaVersion" to "\"1\"")
        refused("schemaVersion" to null)
        refused("applicationId" to "\"dev.forgesworn.kithmoot.debug\"")
        refused("versionCode" to "\"80\"")
        refused("versionCode" to "80.0")
        refused("versionCode" to "8e1")
        refused("downloadFilename" to "\"../kithmoot.apk\"")
        refused("downloadFilename" to "\"kithmoot.apk/x\"")
        refused("downloadFilename" to "\".kithmoot.apk\"")
        refused("downloadFilename" to "\"kithmoot.zip\"")
        refused("apkSha256" to "\"${"CD".repeat(32)}\"")
        refused("apkSha256" to "\"${"cd".repeat(31)}\"")
        refused("apkBytes" to "0")
        refused("apkBytes" to "-1")
        refused("apkBytes" to "2000.5")
        refused("apkBytes" to "${UpdateManifest.MAX_APK_BYTES + 1}")
        refused("currentCertificateSha256" to "\"nope\"")
        assertThrows(UpdateManifest.MalformedManifest::class.java) { UpdateManifest.androidUpdate("[]".encodeToByteArray(), id, 1) }
        assertThrows(UpdateManifest.MalformedManifest::class.java) { UpdateManifest.androidUpdate("{".encodeToByteArray(), id, 1) }
    }

    @Test
    fun theLargestAllowedApkIsAccepted() {
        assertEquals(UpdateManifest.MAX_APK_BYTES, UpdateManifest.androidUpdate(manifest("apkBytes" to "${UpdateManifest.MAX_APK_BYTES}"), id, 76)!!.apkBytes)
    }

    @Test
    fun aBoundedDigestStopsAtItsLimit() {
        val digest = BoundedDigest(3)
        digest.update(byteArrayOf(1, 2))
        digest.update(byteArrayOf(3))
        assertThrows(BoundedDigest.TooLong::class.java) { digest.update(byteArrayOf(4)) }
        // SHA-256 of 01 02 03.
        assertEquals("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81", BoundedDigest(3).apply { update(byteArrayOf(1, 2, 3)) }.finish())
    }
}
