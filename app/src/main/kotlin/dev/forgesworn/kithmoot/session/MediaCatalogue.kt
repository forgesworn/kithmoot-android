package dev.forgesworn.kithmoot.session

import kotlinx.serialization.json.*
import okhttp3.*
import java.net.URI
import java.util.concurrent.TimeUnit

private val catalogueHttp = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).callTimeout(20, TimeUnit.SECONDS).build()
// Commons rejects generic HTTP-library identities, including OkHttp's default.
private fun catalogueRequest(url: String): Request = Request.Builder().url(url)
    .header("User-Agent", "KithMoot/1.0 (https://kithmoot.app; media catalogue)").build()
data class CatalogueImage(val name: String, val url: String, val type: String, val size: Long, val source: String, val credit: String, val preview: String? = null)
private fun catalogueText(value: String): String = value.replace(Regex("<[^>]*>"), "").filterNot { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() }.trim().take(180)
fun commonsImageUrl(value: String): String? = runCatching {
    val uri = URI(value)
    require(uri.scheme == "https" && uri.host == "upload.wikimedia.org" && uri.path.startsWith("/wikipedia/commons/") && uri.rawUserInfo == null)
    URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toASCIIString()
}.getOrNull()
fun catalogueResults(text: String): List<CatalogueImage> {
    require(text.length <= 1_000_000)
    val pages = Json.parseToJsonElement(text).jsonObject["query"]?.jsonObject?.get("pages")?.jsonObject ?: return emptyList()
    return pages.values.take(24).mapNotNull { value -> runCatching {
        val page = value.jsonObject
        val info = page.getValue("imageinfo").jsonArray[0].jsonObject
        val url = commonsImageUrl(info.getValue("url").jsonPrimitive.content) ?: return@runCatching null
        val type = info.getValue("mime").jsonPrimitive.content; val size = info.getValue("size").jsonPrimitive.long
        val width = info.getValue("width").jsonPrimitive.int; val height = info.getValue("height").jsonPrimitive.int
        require(type in setOf("image/gif", "image/png", "image/webp") && size in 1..MAX_MEDIA_SOURCE_BYTES.toLong() && width in 1..8192 && height in 1..8192 && width.toLong() * height <= 16_000_000)
        val source = info.getValue("descriptionurl").jsonPrimitive.content
        require(source.startsWith("https://commons.wikimedia.org/wiki/File:"))
        val meta = info.getValue("extmetadata").jsonObject
        val license = catalogueText(meta.getValue("LicenseShortName").jsonObject.getValue("value").jsonPrimitive.content)
        require(Regex("^(CC0|Public domain|CC BY(?:-SA)?(?: \\d\\.\\d)?)$", RegexOption.IGNORE_CASE).matches(license))
        val artist = meta["Artist"]?.jsonObject?.get("value")?.jsonPrimitive?.content?.let(::catalogueText).orEmpty().ifEmpty { "Wikimedia Commons" }
        CatalogueImage(catalogueText(page.getValue("title").jsonPrimitive.content.removePrefix("File:")), url, type, size, source, "$artist · $license", info["thumburl"]?.jsonPrimitive?.content?.let(::commonsImageUrl))
    }.getOrNull() }
}
fun searchMediaCatalogue(query: String, stickers: Boolean, client: OkHttpClient = catalogueHttp): List<CatalogueImage> {
    val url = HttpUrl.Builder().scheme("https").host("commons.wikimedia.org").addPathSegments("w/api.php")
        .addQueryParameter("action", "query").addQueryParameter("generator", "search")
        .addQueryParameter("gsrsearch", "${if (stickers) "filemime:image/png" else "filemime:image/gif"} ${query.trim().take(80)}")
        .addQueryParameter("gsrnamespace", "6").addQueryParameter("gsrlimit", "18").addQueryParameter("prop", "imageinfo")
        .addQueryParameter("iiurlwidth", "160").addQueryParameter("iiprop", "url|mime|size|extmetadata").addQueryParameter("format", "json").build()
    client.newCall(catalogueRequest(url.toString())).execute().use { response ->
        check(response.isSuccessful) { "The catalogue could not be reached. Try again." }
        val bytes = checkNotNull(response.body).byteStream().use { it.readNBytes(1_000_001) }
        require(bytes.size <= 1_000_000)
        return catalogueResults(bytes.toString(Charsets.UTF_8))
    }
}
fun downloadCatalogueImage(item: CatalogueImage, client: OkHttpClient = catalogueHttp): ByteArray {
    require(commonsImageUrl(item.url) == item.url && item.size in 1..MAX_MEDIA_SOURCE_BYTES.toLong())
    client.newCall(catalogueRequest(item.url)).execute().use { response ->
        check(response.isSuccessful) { "The selected image could not be downloaded." }
        val bytes = checkNotNull(response.body).byteStream().use { it.readNBytes(MAX_MEDIA_SOURCE_BYTES + 1) }
        require(bytes.size in 1..MAX_MEDIA_SOURCE_BYTES)
        return bytes
    }
}

/** Only bounded Commons thumbnails, loaded after an explicit catalogue search. */
fun downloadCataloguePreview(url: String, client: OkHttpClient = catalogueHttp): ByteArray {
    require(commonsImageUrl(url) == url)
    client.newCall(catalogueRequest(url)).execute().use { response ->
        check(response.isSuccessful)
        val body = checkNotNull(response.body)
        require(body.contentLength() <= 524_288)
        val bytes = body.byteStream().use { it.readNBytes(524_289) }
        require(bytes.size in 1..524_288)
        return bytes
    }
}
