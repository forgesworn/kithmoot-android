package dev.forgesworn.kithmoot.session

const val BUILT_IN_ARTWORK_PACK = "kithmoot-original-v1"

// Digests of the reviewed files in assets/chat-art/catalogue.json.
private val bundledArtworkHashes = mapOf(
    "laugh:sticker" to "a925cfc6ca719a278ac535313e41d77d11724f06a0647c3281343a640427bb18",
    "facepalm:sticker" to "d7de0056e35eff759d81f1625e84a5138cd1ef6633ff6d78b61a973c0fd18a84",
    "mindblown:sticker" to "09e445ff4fd58fb02c77d1e89c774fc07fd46eecc50cdd1aa16d268eb1e0fdc3",
    "cool:sticker" to "4a8d92d2b279e6d3d0a6abfee85b6748b7f9c019a4a25d17963fb8a02094cfe6",
    "shrug:sticker" to "ecbde6b7b59fa7ef9b3da3b8d9d8670667ee2b9baaf5e06477638b774abf4124",
    "celebrate:sticker" to "1af5ce78732f3a0c9e0b9e1e33002432579f6349f398d2b4575bc15516792e68",
    "angry:sticker" to "c1e0b2bb6f2f8dafbbfed1ff6905d53fc394a372f4e53509324214459eafa32f",
    "love:sticker" to "82c69f1b7f5d2fc29802f5a9781622a8a260087200ad913a525fbfe1402f1541",
    "cry:sticker" to "f851975bcb2fd0abdd53f8204be98561b09265b13e0507feb0f02c9881e3da31",
    "sideeye:sticker" to "4bc95bcd8ce4eeb8fe50338277f747bae48fda16529b803f19227074bfc9f69b",
    "popcorn:sticker" to "39a5acc4e8bafbbdce2862033b3bdc3c33f65be48c2806fa2c2587e302ead4c2",
    "micdrop:sticker" to "a0255288079b2205435f462d277875842ba8f2a8e0d197616818c839142c546d",
    "thumbsup:sticker" to "0bdef53915a911f151a53217d88359301f6405f3d2d01389b203e8cd678460ff",
    "thumbsdown:sticker" to "b28be8d27599f7971dcd12a193504f6ff8220830daa49ab3dc0768e506d33916",
    "slowclap:sticker" to "5e88bf8504de1deacb421a2e5b7a0d439635d84e0bcdd517842bccf3a0723c6c",
    "eyeroll:sticker" to "0fd217057d381e08357bb020c8063b52bf0e8dac7167cd3c52dc6ae578530931",
    "waiting:sticker" to "2cecbd5ad2294092c9cc89785528b2310025f239e102662409a272130201a610",
    "exhausted:sticker" to "49193696fbd2a378371f48c649b70f2e4f4646eb836d87842323f4e9a94172f0",
    "wtf:sticker" to "152e053ca93f4992525cf6fe6a73c3a3576b86ba06d7c16f6e9608eb2301ebef",
    "melting:sticker" to "e83309850cf3499de2191b3b1f12fc7d062ceb4b5327637c3ec8ea2faf93791e",
    "plotting:sticker" to "e96b173bf188fe8ad6c435fbe24c98156319ab2d825631da9f4e6569f546d512",
    "moon:sticker" to "456f171f213383a98f5768291e746fc51c52d8cc70cf75958b77c9be1effcca6",
    "coffee:sticker" to "fa77ef4cf74855a53992310c045ac7a983909a82b07a674c761e3b6d13390989",
    "coffee:gif" to "1ec70ce57a315e6dd13e8d451543eb529786a792f218121c9bfd67db718a61f1",
    "handshake:sticker" to "5e34b849c9d790fc48e8933bec22921c2dda67007d478315bf4d57748087d87c",
)

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
