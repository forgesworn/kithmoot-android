package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.*
import dev.forgesworn.kithmoot.protocol.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.util.Base64
import kotlin.test.*

class RichMediaTest {
    @Test fun `full emoji catalogue and member artwork decode independently of picker membership`() {
        assertTrue(EmojiCatalog.entries.size > 3700)
        for (emoji in listOf("🫶🏽", "🇬🇧", "🏴‍☠️", "💯", ":600_facepalm:")) {
            assertTrue(EmojiCatalog.accepts(emoji))
            assertNotNull(parseReaction(ChatReaction("target", "ab".repeat(32), emoji, true, 1).toJson()))
        }
        for (value in listOf("hello", "<img>", "👍👍", ":600_unknown:")) assertFalse(EmojiCatalog.accepts(value))
    }
    @Test fun `native sealed GIF bytes authenticate through the shared envelope reader`() {
        val source = "GIF89a synthetic fixture".toByteArray()
        val sealed = sealMedia(source, "../../party.gif", "image/gif")
        val attachment = ChatAttachment("https://files.example/${sealed.hash}", sealed.hash, sealed.key, sealed.name, sealed.type, sealed.envelope.size.toLong())
        val opened = openAttachment(sealed.envelope, attachment)
        assertEquals("party.gif", opened.name); assertEquals("image/gif", opened.type); assertContentEquals(source, opened.bytes)
        val output = java.io.File("build/reports/android-gif-envelope.enc"); output.parentFile.mkdirs(); output.writeBytes(sealed.envelope)
        java.io.File("build/reports/android-gif-envelope.json").writeText(attachment.toJson().toString())
        assertFalse(sealed.envelope.toString(Charsets.ISO_8859_1).contains("synthetic fixture"))
    }
    @Test fun `upload sends only ciphertext and a scoped device authorisation and refuses substituted descriptors`() {
        val sealed = sealMedia("GIF89a test".toByteArray(), "party.gif", "image/gif")
        val key = ByteArray(32) { 1 }; val auth = mediaAuthorisation("upload", sealed.hash, "https://files.example", key, 100, 400)
        var substituted = false
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); assertEquals("https://files.example/upload", request.url.toString()); assertEquals("PUT", request.method)
            val event = NostrEvent.fromJson(Json.parseToJsonElement(String(Base64.getDecoder().decode(request.header("Authorization")!!.removePrefix("Nostr ")))))
            assertTrue(Events.verify(event)); assertEquals("upload", event.tagValue("t")); assertEquals(sealed.hash, event.tagValue("x")); assertEquals("files.example", event.tagValue("server"))
            val buffer = Buffer(); request.body!!.writeTo(buffer); assertContentEquals(sealed.envelope, buffer.readByteArray())
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(201).message("Created")
                .body(buildJsonObject { put("url", if (substituted) "https://evil.example/${sealed.hash}" else "https://files.example/${sealed.hash}"); put("sha256", sealed.hash); put("size", sealed.envelope.size) }.toString().toResponseBody()).build()
        }.build()
        assertEquals(sealed.hash, uploadMedia(sealed, "https://files.example", auth, client).sha256)
        substituted = true
        assertFails { uploadMedia(sealed, "https://files.example", auth, client) }
        for (origin in listOf("http://files.example", "https://user:password@files.example", "https://files.example/path", "https://files.example?query=1")) assertFails { mediaStorageOrigin(origin) }
    }
    @Test fun `files are not declared deleted until the host confirms absence`() {
        val hash = "ab".repeat(32); val auth = mediaAuthorisation("delete", hash, "https://files.example", ByteArray(32) { 1 }, 100, 400)
        var missing = false
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (request.method == "HEAD" && missing) 404 else 200).message("Result").body("".toResponseBody()).build()
        }.build()
        assertFalse(deleteUploadedMedia("https://files.example", hash, auth, client))
        missing = true; assertTrue(deleteUploadedMedia("https://files.example", hash, auth, client))
    }
    @Test fun `Donkey GIF search reduced motion and legacy emoji stay local`() {
        val donkey = searchMediaCatalogue("donkey", false)
        assertEquals(listOf("donkey-laugh", "donkey-facepalm", "donkey-bitcoin"), donkey.map { it.slug })
        donkey.forEach { image ->
            assertTrue(image.name.startsWith("Donkey"))
            assertEquals("chat-art/${image.slug}.png", cataloguePreviewAsset(image, true))
            assertEquals(image.asset, cataloguePreviewAsset(image, false))
            val preview = java.io.File("src/main/assets/${cataloguePreviewAsset(image, true)}").readBytes()
            assertContentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), preview.copyOfRange(0, 8))
            assertEquals(512, java.nio.ByteBuffer.wrap(preview, 16, 4).int)
            assertEquals(512, java.nio.ByteBuffer.wrap(preview, 20, 4).int)
        }
        assertEquals(24, ORIGINAL_EMOJIS.size)
        assertTrue(EmojiCatalog.accepts(":km_laugh:"))
        assertFalse(EmojiCatalog.accepts(":km_donkey-laugh:"))
        assertTrue(java.io.File("src/main/assets/chat-art/laugh.gif").isFile)
        assertTrue(searchMediaCatalogue("", false).none { it.slug == "laugh" })
        val legacy = ORIGINAL_ART.first { it.slug == "laugh" }
        val hiddenGif = ChatArtwork(BUILT_IN_ARTWORK_PACK, legacy.slug, "gif", legacy.gifSha256, legacy.title)
        assertNull(resolveCatalogueArtwork(hiddenGif))
        assertEquals("GIF: Laugh", artworkFallback(listOf(hiddenGif)))
    }

    @Test fun `original catalogue searches local packaged reactions and never supplies external URLs`() {
        val gifs = searchMediaCatalogue("", false)
        assertEquals(listOf("coffee", "donkey-laugh", "donkey-facepalm", "donkey-bitcoin"), gifs.map { it.slug }); assertEquals(27, searchMediaCatalogue("", true).size)
        assertEquals("chat-art/coffee-animation.png", cataloguePreviewAsset(gifs.first { it.slug == "coffee" }, true))
        assertEquals("chat-art/coffee.gif", cataloguePreviewAsset(gifs.first { it.slug == "coffee" }, false))
        assertEquals("donkey-facepalm", searchMediaCatalogue("facepalm", false).single().slug)
        assertTrue(java.io.File("src/main/assets/chat-art/coffee-animation.png").isFile)
        assertTrue(searchMediaCatalogue("flag", false).isEmpty())
        assertEquals(setOf("facepalm", "donkey-facepalm"), searchMediaCatalogue("facepalm", true).map { it.slug }.toSet())
        for (image in gifs) {
            assertTrue(image.asset.startsWith("chat-art/") && !image.asset.contains("://"))
            val file = java.io.File("src/main/assets/${image.asset}")
            assertEquals(image.size, file.length())
            val bytes = file.readBytes()
            assertEquals("GIF89a", bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII))
            assertEquals(512, (bytes[6].toInt() and 255) or ((bytes[7].toInt() and 255) shl 8))
            assertEquals(512, (bytes[8].toInt() and 255) or ((bytes[9].toInt() and 255) shl 8))
            assertTrue(bytes.toString(Charsets.ISO_8859_1).contains("NETSCAPE2.0"))
            if (!image.slug.startsWith("donkey-")) assertTrue(EmojiCatalog.accepts(":km_${image.slug}:"))
        }
    }
}
