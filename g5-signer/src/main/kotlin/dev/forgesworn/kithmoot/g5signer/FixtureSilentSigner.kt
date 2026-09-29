package dev.forgesworn.kithmoot.g5signer

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import dev.forgesworn.kithmoot.protocol.Events
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The NIP-55 content-provider path a real signer (Amber, Cambium) offers for
 * permissions the person has already approved, limited here to relay
 * authentication (kind 22242) for the configured persona: what an app needs
 * to reconnect to its box while closed. Anything else returns no cursor, which
 * tells the app to ask through the screen as before.
 */
class FixtureSilentSigner : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val context = context ?: return null
        val payload = projection?.getOrNull(0) ?: return null
        val requester = projection.getOrNull(2)
        val key = FixtureKeys.key(context) ?: return null
        return runCatching {
            val event = Json.parseToJsonElement(payload).jsonObject
            val kind = event.getValue("kind").jsonPrimitive.int
            if (kind != AUTH_KIND) return null
            if (requester != null && requester != FixtureKeys.publicKey(key)) return null
            val tags = event.getValue("tags").jsonArray.map { tag -> tag.jsonArray.map { it.jsonPrimitive.content } }
            val signed = Events.sign(key, kind, event.getValue("created_at").jsonPrimitive.long, tags, event.getValue("content").jsonPrimitive.content)
            MatrixCursor(arrayOf("result", "event")).apply { addRow(arrayOf(signed.sig, signed.toJson().toString())) }
        }.getOrNull()
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    private companion object { const val AUTH_KIND = 22242 }
}
