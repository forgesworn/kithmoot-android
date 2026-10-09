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
    @Test fun `original catalogue searches local packaged reactions and never supplies external URLs`() {
        val gifs = searchMediaCatalogue("", false)
        assertEquals(24, gifs.size); assertEquals(24, searchMediaCatalogue("", true).size)
        assertTrue(searchMediaCatalogue("flag", false).isEmpty())
        assertEquals("facepalm", searchMediaCatalogue("facepalm", true).single().slug)
        for (image in gifs) {
            assertTrue(image.asset.startsWith("chat-art/") && !image.asset.contains("://"))
            val file = java.io.File("src/main/assets/${image.asset}")
            assertEquals(image.size, file.length())
            javax.imageio.ImageIO.createImageInputStream(file).use { stream ->
                val reader = javax.imageio.ImageIO.getImageReaders(stream).next()
                try { reader.input = stream; assertTrue(reader.getNumImages(true) > 1); assertEquals(256, reader.getWidth(0)) }
                finally { reader.dispose() }
            }
            assertTrue(EmojiCatalog.accepts(":km_${image.slug}:"))
        }
    }
}
