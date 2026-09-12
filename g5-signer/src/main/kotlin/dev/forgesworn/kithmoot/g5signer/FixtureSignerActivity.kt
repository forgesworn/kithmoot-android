package dev.forgesworn.kithmoot.g5signer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.protocol.Events
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.security.MessageDigest

class FixtureSignerActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val answer = try { answer(intent) } catch (_: Exception) { Intent().putExtra("rejected", true) }
        setResult(RESULT_OK, answer)
        finish()
    }

    private fun answer(request: Intent): Intent {
        val result = Intent().putExtra("package", packageName)
        val type = request.getStringExtra("type")
        if (type == CONFIGURE_FIXTURE) {
            val persona = requireNotNull(request.getStringExtra("persona")).also {
                require(it == "alice" || it == "bob")
            }
            require(getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putString(PERSONA, persona).commit())
            return result.putExtra("configured", true)
        }
        val persona = requireNotNull(getSharedPreferences(PREFERENCES, MODE_PRIVATE).getString(PERSONA, null))
        val key = MessageDigest.getInstance("SHA-256").digest(
            "kithmoot-g5-fixture-signer-v2:$persona".toByteArray(Charsets.UTF_8)
        )
        when (type) {
            "get_public_key" -> result.putExtra("result", Schnorr.publicKeyHex(key))
            "sign_event" -> {
                val event = Json.parseToJsonElement(requireNotNull(request.data?.schemeSpecificPart)).jsonObject
                val tags = event.getValue("tags").jsonArray.map { tag -> tag.jsonArray.map { it.jsonPrimitive.content } }
                val signed = Events.sign(key, event.getValue("kind").jsonPrimitive.int, event.getValue("created_at").jsonPrimitive.long, tags, event.getValue("content").jsonPrimitive.content)
                result.putExtra("event", signed.toJson().toString())
            }
            "nip44_encrypt" -> result.putExtra("result", Nip44.encrypt(requireNotNull(request.data?.schemeSpecificPart), Nip44.conversationKey(key, requireNotNull(request.getStringExtra("pubkey")).hexToBytes())))
            "nip44_decrypt" -> result.putExtra("result", Nip44.decrypt(requireNotNull(request.data?.schemeSpecificPart), Nip44.conversationKey(key, requireNotNull(request.getStringExtra("pubkey")).hexToBytes())))
            else -> result.putExtra("rejected", true)
        }
        return result
    }

    private companion object {
        const val CONFIGURE_FIXTURE = "g5_configure"
        const val PREFERENCES = "g5-fixture-signer"
        const val PERSONA = "persona"
    }
}
