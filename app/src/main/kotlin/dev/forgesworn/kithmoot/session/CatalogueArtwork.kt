package dev.forgesworn.kithmoot.session

const val BUILT_IN_ARTWORK_PACK = "kithmoot-original-v1"

// Digests of the reviewed files in assets/chat-art/catalogue.json.
private val bundledArtworkHashes = buildMap {
    ORIGINAL_ART.forEach { art ->
        put("${art.slug}:sticker", art.pngSha256)
        if (art.animated) put("${art.slug}:gif", art.gifSha256)
    }
}

fun catalogueArtwork(item: CatalogueImage): ChatArtwork {
    require(item in searchMediaCatalogue("", item.type == "image/png")) { "Unknown bundled artwork" }
    val kind = if (item.type == "image/gif") "gif" else "sticker"
    return ChatArtwork(BUILT_IN_ARTWORK_PACK, item.slug, kind, bundledArtworkHashes.getValue("${item.slug}:$kind"),
        ORIGINAL_ART.first { it.slug == item.slug }.title)
}

/** Unknown or changed assets stay readable; no URL or remote fallback exists. */
fun resolveCatalogueArtwork(reference: ChatArtwork): CatalogueImage? {
    val normal = normaliseArtwork(reference) ?: return null
    if (normal.pack != BUILT_IN_ARTWORK_PACK || bundledArtworkHashes["${normal.id}:${normal.kind}"] != normal.sha256) return null
    return searchMediaCatalogue("", normal.kind == "sticker").firstOrNull { it.slug == normal.id }
}

/** The compatibility title is redundant beside resolved artwork; captions remain. */
fun artworkMessageText(body: String, artwork: List<ChatArtwork>): String =
    if (artwork.isNotEmpty() && artwork.all { resolveCatalogueArtwork(it) != null } && body == artworkFallback(artwork)) "" else body
