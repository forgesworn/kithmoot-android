package dev.forgesworn.kithmoot.g5

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.account.Nip55
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

/**
 * Deterministic test signer for the G5 emulator fixture.
 *
 * The instrumentation APK is a separate Android package. Its key is derived
 * from that emulator's Android ID and never leaves this process; two emulator
 * data partitions therefore present distinct NIP-55 identities to KithMoot.
 */
class FixtureSignerActivity : Activity() {
    internal var response: Intent? = null
        private set

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        response = runCatching { answer(this, intent) }.getOrElse { Intent().putExtra("rejected", true) }
        setResult(RESULT_OK, response)
        finish()
    }

    companion object {
        internal fun answer(context: Context, request: Intent): Intent {
        val signer = LocalSigner(identityKey(context))
        val type = requireNotNull(request.getStringExtra("type"))
        val result = Intent().putExtra("package", context.packageName)
        when (type) {
            Nip55.TYPE_GET_PUBLIC_KEY -> result.putExtra("result", signer.pubkey)
            Nip55.TYPE_SIGN_EVENT -> {
                val event = Json.parseToJsonElement(requireNotNull(request.data?.schemeSpecificPart)).jsonObject
                val tags = event.getValue("tags").jsonArray.map { tag ->
                    tag.jsonArray.map { it.jsonPrimitive.content }
                }
                val signed = runBlocking {
                    signer.sign(
                        event.getValue("kind").jsonPrimitive.int,
                        event.getValue("created_at").jsonPrimitive.long,
                        tags,
                        event.getValue("content").jsonPrimitive.content,
                    )
                }
                result.putExtra("event", signed.toJson().toString())
            }
            Nip55.TYPE_NIP44_ENCRYPT -> result.putExtra("result", runBlocking {
                signer.nip44Encrypt(requireNotNull(request.getStringExtra("pubkey")), requireNotNull(request.data?.schemeSpecificPart))
            })
            Nip55.TYPE_NIP44_DECRYPT -> result.putExtra("result", runBlocking {
                signer.nip44Decrypt(requireNotNull(request.getStringExtra("pubkey")), requireNotNull(request.data?.schemeSpecificPart))
            })
            else -> result.putExtra("rejected", true)
        }
        return result
    }

    private fun identityKey(context: Context): ByteArray {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: throw IllegalStateException("fixture signer needs an Android ID")
        return MessageDigest.getInstance("SHA-256")
            .digest("kithmoot-g5-fixture-signer-v1:$androidId".toByteArray(Charsets.UTF_8))
    }
    }
}
