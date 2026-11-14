package dev.forgesworn.kithmoot.ui.start

import java.net.URI

/**
 * A link on the short-link page, `https://<host>/n/#…`, moved to the web
 * app's join page beside it, `https://<host>/j/#…`, the way that page sends a
 * browser on. A kept or synced copy then names the page every reader opens.
 * The query and fragment are kept as they are; any other link comes back
 * unchanged.
 */
internal fun onJoinPage(link: String): String = runCatching {
    val uri = URI(link.trim())
    val path = uri.rawPath
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.isOpaque || uri.rawAuthority == null || path == null) return@runCatching link
    val parent = when {
        path.endsWith("/n/") -> path.dropLast(2)
        path.endsWith("/n") -> path.dropLast(1)
        else -> return@runCatching link
    }
    buildString {
        append("https://").append(uri.rawAuthority).append(parent).append("j/")
        uri.rawQuery?.let { append('?').append(it) }
        uri.rawFragment?.let { append('#').append(it) }
    }
}.getOrDefault(link)
