package dev.forgesworn.kithmoot.session

import android.content.SharedPreferences
import java.net.URI

/*
 * Where a file goes: the web's `file-storage.ts` (kithmoot #527, "public
 * first"). The choice lives in the MEDIA_STORAGE_PREFERENCES preferences, the
 * web's `kithmoot.file-storage.v1`:
 *
 * - version 1 and 2 consents (`origin`, `disclosure`, no `mode`) are read as a
 *   typed server, as before;
 * - version 3 writes `mode` ("shared" or "off"), `disclosure` 3, and for a
 *   shared choice `origin` and `via` ("default" or "typed").
 *
 * A file is never uploaded before the person has acted once on this device,
 * and a failed upload never moves to a different server by itself.
 */

/** The version new choices are written with. Version 3 also says how the server was chosen. */
const val FILE_CONSENT_VERSION: Int = 3

private const val MODE_KEY = "mode"
private const val ORIGIN_KEY = "origin"
private const val DISCLOSURE_KEY = "disclosure"
private const val VIA_KEY = "via"

/** How a server came to be chosen. A default is honoured only while it is still the default. */
enum class FileServerVia(val stored: String) { DEFAULT("default"), TYPED("typed") }

data class StoredFileServer(val origin: String, val via: FileServerVia)

/** Where a file picked now would go. */
sealed interface FileDestination {
    /** A choice the person made: uploads start when a file is picked. */
    data class Server(val origin: String, val via: FileServerVia) : FileDestination
    /** Nothing chosen yet: the one-time notice names this server, and the person's press is the choice. */
    data class Default(val origin: String, val maxBytes: Long?) : FileDestination
    /** Nowhere: the person chose it ([chosen]), or there is no default to offer. */
    data class Off(val chosen: Boolean) : FileDestination
}

/** Said when a file needs a server and the person chose Nowhere (the web's `FILE_STORAGE_REQUIRED`). */
const val FILE_STORAGE_REQUIRED: String = "File uploads are off on this device. Turn them on in Settings › Privacy › Where files go."

/** A stored, valid shared choice, and how it was made, or null. Never a ForgeSworn host. */
fun storedFileServer(prefs: SharedPreferences): StoredFileServer? = runCatching {
    val origin = prefs.getString(ORIGIN_KEY, null) ?: return@runCatching null
    val disclosure = prefs.getInt(DISCLOSURE_KEY, 0)
    val mode = prefs.getString(MODE_KEY, null)
    val valid = runCatching { mediaStorageOrigin(origin) }.getOrNull() ?: return@runCatching null
    // KithMoot no longer sends files to ForgeSworn's storage, so a choice naming it is never a destination.
    if (isForgeSwornHost(valid)) return@runCatching null
    when (disclosure) {
        SUGGESTED_MEDIA_STORAGE_DISCLOSURE, MEDIA_STORAGE_DISCLOSURE ->
            if (mode == null || mode == "shared") StoredFileServer(valid, FileServerVia.TYPED) else null
        FILE_CONSENT_VERSION -> {
            if (mode != "shared") return@runCatching null
            val via = FileServerVia.entries.firstOrNull { it.stored == prefs.getString(VIA_KEY, null) } ?: return@runCatching null
            StoredFileServer(valid, via)
        }
        else -> null
    }
}.getOrNull()

/** True once the person chose Nowhere on this device. */
fun fileUploadsChosenOff(prefs: SharedPreferences): Boolean =
    runCatching { prefs.getString(MODE_KEY, null) == "off" }.getOrDefault(false)

/** The public server entry for an origin, if it is on the list. */
fun publicFileServer(origin: String, servers: List<PublicFileServer> = PUBLIC_FILE_SERVERS): PublicFileServer? =
    servers.firstOrNull { it.origin == origin }

/**
 * The one place that decides where a file goes. First match wins:
 * 1. the person chose Nowhere: off;
 * 2. a stored, valid choice: that server, except a default choice naming a
 *    server that is no longer the default, which falls through so uploads
 *    never move to a different third party without telling the person;
 * 3. a default exists: the one-time notice;
 * 4. otherwise off, with nothing to offer.
 * Android has no Bothy file rooms or group storage yet (the web's other branches).
 */
fun resolveFileDestination(prefs: SharedPreferences, publicServers: List<PublicFileServer> = PUBLIC_FILE_SERVERS): FileDestination {
    if (fileUploadsChosenOff(prefs)) return FileDestination.Off(chosen = true)
    val fallback = publicServers.firstOrNull()
    val stored = storedFileServer(prefs)
    if (stored != null && (stored.via != FileServerVia.DEFAULT || stored.origin == fallback?.origin)) {
        return FileDestination.Server(stored.origin, stored.via)
    }
    return fallback?.let { FileDestination.Default(it.origin, it.maxBytes) } ?: FileDestination.Off(chosen = false)
}

/** The server's known size limit, when it is on the public list. */
fun destinationLimit(destination: FileDestination): Long? = when (destination) {
    is FileDestination.Default -> destination.maxBytes
    is FileDestination.Server -> publicFileServer(destination.origin)?.maxBytes
    is FileDestination.Off -> null
}

/** "200 MB", for a server's limit. */
fun formatLimit(bytes: Long): String {
    val mb = bytes / 1_048_576.0
    return if (mb == Math.floor(mb)) "${mb.toLong()} MB" else "${"%.1f".format(java.util.Locale.ROOT, mb)} MB"
}

/** The host a person reads, for example blossom.yakihonne.com. */
fun fileHost(origin: String): String = runCatching { URI(origin).host }.getOrNull() ?: origin

/** "Yakihonne LLC" in running text is "Yakihonne". */
fun plainOperator(operator: String): String = operator.replace(Regex(",?\\s+(?:LLC|Ltd\\.?|Inc\\.?)$", RegexOption.IGNORE_CASE), "")

/** The person pressed the notice's button: this device now uses the default, while it stays the default. */
fun acceptDefaultFileServer(prefs: SharedPreferences, origin: String): String {
    val server = mediaStorageOrigin(origin)
    prefs.edit().putString(MODE_KEY, "shared").putInt(DISCLOSURE_KEY, FILE_CONSENT_VERSION)
        .putString(ORIGIN_KEY, server).putString(VIA_KEY, FileServerVia.DEFAULT.stored).apply()
    return server
}

/** Choosing a server in Settings: the public one, or one the person typed. Never a ForgeSworn host. */
fun chooseFileServer(prefs: SharedPreferences, value: String, via: FileServerVia = FileServerVia.TYPED): String {
    val origin = chosenMediaStorage(value)
    prefs.edit().putString(MODE_KEY, "shared").putInt(DISCLOSURE_KEY, FILE_CONSENT_VERSION)
        .putString(ORIGIN_KEY, origin).putString(VIA_KEY, via.stored).apply()
    return origin
}

/** Nowhere. A missing choice now means "never chosen", so turning uploads off writes the choice down. */
fun stopFileUploads(prefs: SharedPreferences) {
    prefs.edit().putString(MODE_KEY, "off").putInt(DISCLOSURE_KEY, FILE_CONSENT_VERSION)
        .remove(ORIGIN_KEY).remove(VIA_KEY).apply()
}

/** The notice's first line. */
fun fileNoticeTitle(origin: String): String = "Files you send are stored on ${fileHost(origin)}"

/** The notice's body, after the legal check (spec section 16). */
fun fileNoticeBody(origin: String): String {
    val operator = publicFileServer(origin)?.operator
    val who = if (operator != null) "It’s a public server run by ${plainOperator(operator)}." else "It’s the server chosen by whoever runs this copy of KithMoot."
    return "$who Files are encrypted on this device first, so the server can’t open them, but anyone in this chat can. " +
        "The server sees your IP address, a key made for this device, when files are sent and opened, and how big they are. " +
        "It decides how long to keep them."
}

/** Why a file is held when uploads are off (the web's S4 and S5 lines), with the action's label. */
fun fileOffLine(destination: FileDestination.Off): Pair<String, String> =
    if (destination.chosen) "File uploads are off on this device." to "Turn on file uploads"
    else "This app has no file server set. Choose one to send files." to "Choose a server"

/** "2.4 MB", "512 KB", "300 B": a file's size in the tray's words. */
fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1_048_576 -> "${(bytes + 512) / 1024} KB"
    else -> {
        val mb = bytes / 1_048_576.0
        val text = "%.1f".format(java.util.Locale.ROOT, mb)
        "${text.removeSuffix(".0")} MB"
    }
}

/**
 * The words for an upload that did not go through (the web's
 * `describeUploadFailure`), from the error the upload threw, or null to keep
 * the error's own. Never suggests a retry on a different server by itself.
 * [own] is a server the person typed, not the public default.
 */
fun describeUploadFailure(error: Throwable, host: String, name: String, sizeText: String, limit: String?, own: Boolean): String? {
    val status = Regex("\\((\\d{3})\\)").find(error.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
    val network = "$host didn’t take $name. Check your connection and try again."
    if (status == null) return if (error is java.io.IOException) network else null
    return when {
        status == 413 -> if (limit != null) "$host takes files up to $limit. $name is $sizeText." else "$host won’t take a file as big as $name ($sizeText)."
        status == 415 -> "$host won’t store $name. Choose another server."
        (status == 401 || status == 403) && own -> "$host didn’t let this device upload. If it’s Wildbloom Node, let KithMoot in again in Settings."
        status >= 400 -> network
        else -> null
    }
}
