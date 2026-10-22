package dev.forgesworn.kithmoot.session

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A paste over [MAX_CHAT_TEXT_LENGTH] is not cut and not dropped: the whole
 * text goes up as an encrypted text document, and the message carries a short
 * preview and the document. This is what the web client does, word for word,
 * so the two read alike in a room. Every client still drops a received body
 * over the limit, so the limit itself does not move.
 */

/** How much of the paste stays readable in the message itself. */
const val LONG_CHAT_PREVIEW_CHARS: Int = 600

/** A message carries at most this many files; the document is one of them. */
const val MAX_CHAT_FILES: Int = 4

/** The preferences MediaComposer keeps the chosen storage server and its consent in. */
const val MEDIA_STORAGE_PREFERENCES: String = "shared-media-storage"

/** The type the web client gives the document, so a room's members open it the same way. */
const val TEXT_DOCUMENT_TYPE: String = "text/plain;charset=utf-8"

private val documentStamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/** Whether [type] names a plain text document, whatever parameters follow the type. */
fun isTextDocumentType(type: String?): Boolean = type?.substringBefore(';')?.trim()?.lowercase() == "text/plain"

/** 12,345 style grouping, whatever the phone's own locale. */
fun formatCharacterCount(count: Int): String = String.format(Locale.UK, "%,d", count)

/** Whether a draft is too long to send as an ordinary message. */
fun chatTextNeedsDocument(text: String): Boolean = text.length > MAX_CHAT_TEXT_LENGTH

/** The note under the composer while the draft is over the limit, or null while it is not. */
fun longChatPasteNote(text: String): String? =
    if (!chatTextNeedsDocument(text)) null
    else "${formatCharacterCount(text.length)} characters pasted. Send will share a short preview and the complete encrypted text document."

/** The message body that stands in for the document: a count, a pointer and the first lines. */
fun longChatPreviewBody(fullText: String): String {
    val head = fullText.take(LONG_CHAT_PREVIEW_CHARS).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
    return "Shared full text (${formatCharacterCount(fullText.length)} characters). " +
        "Open the encrypted document to read or copy everything.\n\n$head…"
}

/** `message-` and the ISO 8601 UTC time with its colons and full stop turned into hyphens, then `.txt`. */
fun longChatDocumentName(at: Instant): String =
    "message-" + documentStamp.format(at).replace(':', '-').replace('.', '-') + ".txt"

/** The paste, sealed exactly as an image is, with nothing of the plaintext left behind. */
fun sealLongChatDocument(fullText: String, at: Instant): SealedMedia {
    val bytes = fullText.toByteArray(Charsets.UTF_8)
    try { return sealMedia(bytes, longChatDocumentName(at), TEXT_DOCUMENT_TYPE) } finally { bytes.fill(0) }
}

/**
 * The storage server the person already chose and allowed under the paperclip,
 * or null when none is set or the disclosure was never accepted. Reads the same
 * two values MediaComposer writes, so nobody is asked twice.
 *
 * Disclosure 2 is a consent a person gave, so it is honoured for any valid
 * origin. Disclosure 1 was written while the app pre-filled its own host, so it
 * is honoured only for a server that is not ForgeSworn's. Every read of the
 * saved choice goes through this rule.
 */
fun rememberedMediaStorage(origin: String?, disclosure: Int): String? {
    if (origin == null || (disclosure != MEDIA_STORAGE_DISCLOSURE && disclosure != SUGGESTED_MEDIA_STORAGE_DISCLOSURE)) return null
    val valid = runCatching { mediaStorageOrigin(origin) }.getOrNull() ?: return null
    // KithMoot no longer sends files to ForgeSworn's storage, so a choice naming it is never a destination.
    return if (isForgeSwornHost(valid)) null else valid
}

private val over = "This message is over ${formatCharacterCount(MAX_CHAT_TEXT_LENGTH)} characters."

/**
 * Why a message over the limit cannot go as a document, or null when it can.
 * It is never sent short and never dropped without a word: the person is told
 * what stops it and what to do.
 */
fun longChatRefusal(
    text: String,
    anonymous: Boolean,
    internet: Boolean,
    storageChosen: Boolean,
    uploading: Boolean,
    stagedFiles: Int,
): String? = when {
    anonymous -> "$over An anonymous room cannot upload an encrypted document. Shorten it to send it."
    !internet -> "$over A document needs an Internet connection, and this room is not using one. Shorten it to send it."
    uploading -> "$over A file is still uploading. Wait for it to finish, then send again."
    text.toByteArray(Charsets.UTF_8).size > MAX_MEDIA_SOURCE_BYTES ->
        "$over It is also too large to send as a document (8 MiB at most). Shorten it to send it."
    stagedFiles >= MAX_CHAT_FILES ->
        "$over A document needs a free file slot, and a message carries at most $MAX_CHAT_FILES files. Remove a file or shorten it."
    !storageChosen ->
        "$over To send it as an encrypted document, first choose a storage server: tap the paperclip, add an image, choose where files go and allow that server. Or shorten it."
    else -> null
}

/**
 * The document, cut into pieces a list can lay out one at a time, so a very
 * long text does not have to be measured in one go. Pieces break at line ends
 * where they can and never inside a surrogate pair. The full text, not these
 * pieces, is what Copy takes.
 */
fun splitDocumentForDisplay(text: String, limit: Int = 4_000): List<String> {
    require(limit >= 2)
    val pieces = ArrayList<String>()
    var start = 0
    while (start < text.length) {
        var end = minOf(start + limit, text.length)
        if (end < text.length) {
            val newline = text.lastIndexOf('\n', end - 1)
            if (newline > start) end = newline + 1
            else if (text[end - 1].isHighSurrogate()) end -= 1
        }
        pieces += text.substring(start, end).removeSuffix("\n")
        start = end
    }
    return pieces
}
