package dev.forgesworn.kithmoot.g5

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.account.Nip55
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.security.MessageDigest

/** Debug-only NIP-55 signer for the two-emulator G5 rehearsal. */
class DebugFixtureSignerActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val result = try { answer(intent) } catch (_: Exception) { Intent().putExtra("rejected", true) }
        setResult(RESULT_OK, result)
        finish()
    }

    private fun answer(request: Intent): Intent {
        val key = MessageDigest.getInstance("SHA-256").digest(
            "kithmoot-g5-fixture-signer-v1:${Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)}".toByteArray()
        )
        val signer = LocalSigner(key)
        val result = Intent().putExtra("package", packageName)
        when (request.getStringExtra("type")) {
            Nip55.TYPE_GET_PUBLIC_KEY -> result.putExtra("result", signer.pubkey)
            Nip55.TYPE_SIGN_EVENT -> {
                val event = Json.parseToJsonElement(requireNotNull(request.data?.schemeSpecificPart)).jsonObject
                val tags = event.getValue("tags").jsonArray.map { tag -> tag.jsonArray.map { it.jsonPrimitive.content } }
                val signed = runBlocking { signer.sign(event.getValue("kind").jsonPrimitive.int, event.getValue("created_at").jsonPrimitive.long, tags, event.getValue("content").jsonPrimitive.content) }
                result.putExtra("event", signed.toJson().toString())
            }
            Nip55.TYPE_NIP44_ENCRYPT -> result.putExtra("result", runBlocking { signer.nip44Encrypt(requireNotNull(request.getStringExtra("pubkey")), requireNotNull(request.data?.schemeSpecificPart)) })
            Nip55.TYPE_NIP44_DECRYPT -> result.putExtra("result", runBlocking { signer.nip44Decrypt(requireNotNull(request.getStringExtra("pubkey")), requireNotNull(request.data?.schemeSpecificPart)) })
            else -> result.putExtra("rejected", true)
        }
        return result
    }
}
