package dev.forgesworn.kithmoot.session

import java.net.URI
import java.util.Locale

/** The site this person chose for share links and browser sign-in return. */
class WebAppAddress private constructor(val origin: String) {
    val joinBase: String get() = "$origin/j/"
    val signInCallback: String get() = "$origin/signet/"

    /** Change only the presentation base of an already validated saved room. */
    fun roomLink(savedLink: String): String {
        val fragment = savedLink.substringAfter('#', "")
        require(fragment.isNotBlank()) { "The saved room has no invitation payload." }
        return "$joinBase#$fragment"
    }

    companion object {
        const val DEFAULT_ORIGIN = "https://kithmoot.forgesworn.dev"
        val Default = parse(DEFAULT_ORIGIN)

        fun parse(value: String): WebAppAddress {
            val uri = URI(value.trim())
            require(uri.scheme.equals("https", ignoreCase = true) && uri.host != null &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") &&
                (uri.port == -1 || uri.port in 1..65535)) {
                "Use an HTTPS site address without a path, query or fragment."
            }
            val origin = URI("https", null, uri.host.lowercase(Locale.ROOT),
                if (uri.port == 443) -1 else uri.port, null, null, null).toASCIIString()
            return WebAppAddress(origin)
        }
    }
}
