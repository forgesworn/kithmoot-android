package dev.forgesworn.kithmoot.session

import android.content.Context

/** All picker content is original artwork packaged in the APK. */
data class CatalogueImage(val slug: String, val name: String, val type: String, val size: Long, val asset: String)
fun searchMediaCatalogue(query: String, stickers: Boolean): List<CatalogueImage> {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotEmpty)
    return ORIGINAL_ART.filter { stickers || it.animated }.filter { art -> words.all { word -> "${art.title} ${art.keywords}".lowercase().contains(word) } }.map { art ->
        val extension = if (stickers) "png" else "gif"
        CatalogueImage(art.slug, "${art.title}.$extension", "image/$extension", if (stickers) art.pngBytes else art.gifBytes, "chat-art/${art.slug}.$extension")
    }
}
fun downloadCatalogueImage(item: CatalogueImage, context: Context): ByteArray {
    require(item in searchMediaCatalogue("", item.type == "image/png")) { "Unknown artwork file." }
    val bytes = context.assets.open(item.asset).use { it.readNBytes(MAX_MEDIA_SOURCE_BYTES + 1) }
    require(bytes.size.toLong() == item.size && bytes.size in 1..MAX_MEDIA_SOURCE_BYTES) { "The artwork file does not match this version of KithMoot." }
    return bytes
}

/** Reduced-motion GIF previews use the rendered animation still, not its sticker. */
fun cataloguePreviewAsset(item: CatalogueImage, reducedMotion: Boolean): String =
    if (item.type == "image/gif" && reducedMotion) {
        val art = ORIGINAL_ART.firstOrNull { it.slug == item.slug && it.animated }
        if (art != null) "chat-art/${art.animationPreview}" else item.asset
    } else item.asset
