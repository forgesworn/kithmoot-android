package dev.forgesworn.kithmoot.vectors

import dev.forgesworn.kithmoot.protocol.UpdateManifest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signed update manifests against the web repository's
 * `vectors/update-manifest.json`, copied verbatim. The desktop updater checks
 * the same file, so a message built differently on either side fails here.
 */
class UpdateManifestVectorsTest {

    private val root: JsonObject by lazy { Json.parseToJsonElement(resource("/update-manifest.json").decodeToString()).jsonObject }
    private val cases by lazy { root.getValue("cases").jsonArray.map { it.jsonObject } }
    private val testKeys by lazy { root.getValue("trustedKeys").jsonArray.map { it.jsonPrimitive.content } }

    private fun resource(path: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream(path)) { "$path is missing" }.use { it.readBytes() }

    @Test
    fun everyCaseVerifiesOrFailsAsRecorded() {
        assertEquals(16, cases.size)
        assertEquals(setOf(UpdateManifest.ANDROID, UpdateManifest.DESKTOP), cases.map { it.text("label") }.toSet())
        for (c in cases) {
            val verified = UpdateManifest.verify(
                c.text("label"), c.text("manifest").encodeToByteArray(), c.text("signature").encodeToByteArray(), testKeys,
            )
            assertEquals(c.text("description"), c.getValue("valid").jsonPrimitive.boolean, verified)
        }
        assertTrue("both labels carry a valid case", cases.filter { it.getValue("valid").jsonPrimitive.boolean }.map { it.text("label") }.toSet().size == 2)
    }

    @Test
    fun theCompiledInKeysAcceptNoTestVector() {
        for (c in cases) {
            assertFalse(c.text("description"), UpdateManifest.verify(
                c.text("label"), c.text("manifest").encodeToByteArray(), c.text("signature").encodeToByteArray(),
            ))
        }
    }

    @Test
    fun aTrailingNewlineOnTheSignatureFileIsAllowed() {
        val c = cases.first { it.text("label") == UpdateManifest.ANDROID && it.getValue("valid").jsonPrimitive.boolean }
        val manifest = c.text("manifest").encodeToByteArray()
        assertTrue(UpdateManifest.verify(UpdateManifest.ANDROID, manifest, (c.text("signature") + "\n").encodeToByteArray(), testKeys))
        assertFalse(UpdateManifest.verify(UpdateManifest.ANDROID, manifest, (" " + c.text("signature")).encodeToByteArray(), testKeys))
        assertFalse(UpdateManifest.verify(UpdateManifest.ANDROID, manifest, c.text("signature").trimEnd('=').encodeToByteArray(), testKeys))
    }

    @Test
    fun aMalformedKeyIsSkippedRatherThanThrown() {
        val c = cases.first { it.text("label") == UpdateManifest.ANDROID && it.getValue("valid").jsonPrimitive.boolean }
        val manifest = c.text("manifest").encodeToByteArray()
        val signature = c.text("signature").encodeToByteArray()
        assertFalse(UpdateManifest.verify(UpdateManifest.ANDROID, manifest, signature, listOf("zz", "00")))
        assertTrue(UpdateManifest.verify(UpdateManifest.ANDROID, manifest, signature, listOf("not hex") + testKeys))
        assertFalse(UpdateManifest.verify("elsewhere.json", manifest, signature, testKeys))
    }

    /** The release manifest as published, with its `.sig`, copied from the web
     *  repository's `site/`: the real keys accept the real thing. */
    @Test
    fun theCompiledInKeysAcceptAPublishedRelease() {
        val manifest = resource("/update/android-release.json")
        val signature = resource("/update/android-release.json.sig")
        assertTrue(UpdateManifest.verify(UpdateManifest.ANDROID, manifest, signature))
        assertFalse(UpdateManifest.verify(UpdateManifest.DESKTOP, manifest, signature))
        assertFalse(UpdateManifest.verify(UpdateManifest.ANDROID, manifest + '\n'.code.toByte(), signature))
        val update = UpdateManifest.androidUpdate(manifest, "dev.forgesworn.kithmoot", currentVersionCode = 1)
        assertEquals("kithmoot-${update!!.versionName}-production.apk", update.downloadFilename)
    }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
}
