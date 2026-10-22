package dev.forgesworn.kithmoot.session

import android.content.SharedPreferences
import java.net.URI

/**
 * Where a person's files go is their choice, never ours. KithMoot does not
 * suggest a file server that ForgeSworn runs, and since 22 October 2026 sends
 * none there at all: ForgeSworn's storage takes no new files. This matches
 * the web client's `file-storage.ts`.
 */

/** The version new consents are written with. Version 1 was written while the app pre-filled its own host. */
const val MEDIA_STORAGE_DISCLOSURE: Int = 2

/** Version 1: honoured for any server except a ForgeSworn one, which may never have been a choice. */
internal const val SUGGESTED_MEDIA_STORAGE_DISCLOSURE: Int = 1

/** How to run your own file server. */
const val RUN_YOUR_OWN_FILES_URL: String = "https://github.com/forgesworn/kithmoot/blob/main/docs/run-your-own.md#files"

private val FORGESWORN_DOMAINS = listOf("forgesworn.dev", "kithmoot.app")

/**
 * True for ForgeSworn's own hosts and any name under them. Anything that does
 * not parse is false: a caller that cares must validate the URL itself.
 */
fun isForgeSwornHost(origin: String): Boolean {
    // A trailing dot names the same host in DNS, so it must not be a way round.
    val host = runCatching { URI(origin.trim()).host }.getOrNull()?.lowercase()?.trimEnd('.') ?: return false
    return FORGESWORN_DOMAINS.any { domain -> host == domain || host.endsWith(".$domain") }
}

/**
 * A public Blossom server run by someone else, offered as a convenience.
 * Listing one is not consent to use it.
 */
data class PublicFileServer(val origin: String, val operator: String, val maxBytes: Long, val checked: String)

/**
 * Public file servers run by other people. This is a short list on purpose.
 *
 * An entry qualifies when we can name its operator and find published terms,
 * and a real round trip passed on the `checked` date: upload of an
 * `application/vnd.forgesworn.encrypted` envelope, fetch by hash, and a BUD-02
 * delete. Never a ForgeSworn host (the tests check).
 */
val PUBLIC_FILE_SERVERS: List<PublicFileServer> = listOf(
    PublicFileServer("https://blossom.yakihonne.com", "Yakihonne LLC", 209_715_200L, "2026-10-20"),
)

/** The line a person reads on the button: host, operator and the largest file. */
fun PublicFileServer.label(): String =
    "${URI(origin).host}, run by $operator, files up to ${maxBytes / 1_048_576L} MB"

private const val ORIGIN_KEY = "origin"
private const val DISCLOSURE_KEY = "disclosure"

/** Said once to a device whose choice named a ForgeSworn host, and to anyone who types one (the web's `FORGESWORN_STORAGE_CLOSED`). */
const val FORGESWORN_STORAGE_CLOSED: String = "KithMoot no longer sends files to ForgeSworn’s storage."

/**
 * A stored choice naming a ForgeSworn host, whichever version wrote it, is
 * removed: KithMoot no longer sends files there. True when it had been agreed
 * to, so the caller says why, once. Files already stored there are not
 * touched, and nothing else in the preferences is.
 */
fun retireForgeSwornStorage(prefs: SharedPreferences): Boolean {
    val disclosure = runCatching { prefs.getInt(DISCLOSURE_KEY, 0) }.getOrDefault(0)
    val origin = runCatching { prefs.getString(ORIGIN_KEY, null) }.getOrNull()
    if (origin == null || !isForgeSwornHost(origin)) return false
    prefs.edit().remove(ORIGIN_KEY).remove(DISCLOSURE_KEY).apply()
    return disclosure == SUGGESTED_MEDIA_STORAGE_DISCLOSURE || disclosure == MEDIA_STORAGE_DISCLOSURE
}

/** A server a person chose to send files to: a valid HTTPS origin, never a ForgeSworn host. */
fun chosenMediaStorage(value: String): String =
    mediaStorageOrigin(value).also { require(!isForgeSwornHost(it)) { FORGESWORN_STORAGE_CLOSED } }
