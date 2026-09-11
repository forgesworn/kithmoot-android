package dev.forgesworn.kithmoot.account

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import dev.forgesworn.kithmoot.protocol.NostrEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The one thing a signer intent needs from the activity: start it and hand
 * back what came out. The activity owns the result launcher; the signer, which
 * lives in the view model, only knows how to ask.
 */
fun interface Nip55Bridge {
    /** The result intent, or null when the person backed out. */
    suspend fun request(intent: Intent): Intent?
}

/** A signer app installed on this phone that answers `nostrsigner:` intents. */
data class InstalledSigner(val packageName: String, val label: String)

/** Every app that will take a `nostrsigner:` intent, so the person can pick one by name. */
fun installedSigners(context: Context): List<InstalledSigner> {
    val probe = Intent(Intent.ACTION_VIEW, Uri.parse("${Nip55.SCHEME}:"))
    val manager = context.packageManager
    val found = manager.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
    return found.map { info ->
        InstalledSigner(info.activityInfo.packageName, info.loadLabel(manager).toString().ifBlank { info.activityInfo.packageName })
    }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
}

/**
 * A person whose key lives in a signer app on this phone.
 *
 * Signing tries the signer's content provider first, which answers without a
 * screen once the app has been approved there, and falls back to the intent,
 * which brings the signer up to ask. `get_public_key` always goes by intent:
 * it is the moment the person chooses the account, and it should be seen.
 */
class Nip55Signer(
    override val pubkey: String,
    val packageName: String,
    private val context: Context,
    private val bridge: Nip55Bridge,
) : ParticipantSigner {
    override val method: String get() = "nip55"

    override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent {
        val unsigned = unsignedEventJson(pubkey, kind, createdAt, tags, content)
        val signed = viaProvider(Nip55.TYPE_SIGN_EVENT, unsigned, null)?.let { (result, event) ->
            Nip55.signedEventFromResult(event, result, unsigned)
        } ?: run {
            val answer = ask(Nip55.TYPE_SIGN_EVENT, unsigned) ?: throw SignerException("Signing was cancelled in ${appLabel()}.")
            Nip55.signedEventFromResult(answer.getStringExtra("event"), answer.getStringExtra("result") ?: answer.getStringExtra("signature"), unsigned)
                ?: throw SignerException("${appLabel()} did not return a signed event.")
        }
        return checkedSignedEvent(signed, pubkey, kind, createdAt, tags, content)
    }

    override suspend fun nip44Encrypt(peer: String, plaintext: String): String = crypt(Nip55.TYPE_NIP44_ENCRYPT, peer, plaintext)
    override suspend fun nip44Decrypt(peer: String, payload: String): String = crypt(Nip55.TYPE_NIP44_DECRYPT, peer, payload)

    /** Show the signer when an optional product journey needs more authority than ordinary rooms. */
    suspend fun requestPermissions(kinds: Collection<Int>) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("${Nip55.SCHEME}:")).apply {
            `package` = packageName
            putExtra("type", Nip55.TYPE_GET_PUBLIC_KEY)
            putExtra("id", java.util.UUID.randomUUID().toString())
            putExtra("current_user", pubkey)
            putExtra("permissions", Nip55.permissions(kinds))
        }
        val answer = bridge.request(intent) ?: throw SignerException("Bothy permission was cancelled in ${appLabel()}.")
        if (answer.getBooleanExtra("rejected", false)) throw SignerException("${appLabel()} declined Bothy permission.")
        val confirmed = Nip55.publicKeyFromResult(answer.getStringExtra("result") ?: answer.getStringExtra("signature"))
        if (confirmed != pubkey) throw SignerException("${appLabel()} answered for a different account.")
    }

    private suspend fun crypt(type: String, peer: String, payload: String): String {
        viaProvider(type, payload, peer)?.first?.let { return it }
        val answer = ask(type, payload, peer) ?: throw SignerException("Cancelled in ${appLabel()}.")
        return answer.getStringExtra("result")?.takeIf { it.isNotEmpty() } ?: throw SignerException("${appLabel()} returned nothing.")
    }

    private suspend fun ask(type: String, payload: String, peer: String? = null): Intent? {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("${Nip55.SCHEME}:$payload")).apply {
            `package` = packageName
            putExtra("type", type)
            putExtra("id", java.util.UUID.randomUUID().toString())
            putExtra("current_user", pubkey)
            if (peer != null) putExtra("pubkey", peer)
        }
        val answer = bridge.request(intent) ?: return null
        if (answer.getBooleanExtra("rejected", false)) throw SignerException("${appLabel()} declined to sign.")
        return answer
    }

    /**
     * The silent path. Amber's contract, which Cambium follows: query
     * `content://<package>.<TYPE>` with the payload, the other party's key and
     * our own key as the projection; a null cursor means "ask by intent".
     */
    private suspend fun viaProvider(type: String, payload: String, peer: String?): Pair<String?, String?>? = withContext(Dispatchers.IO) {
        val uri = Uri.parse("content://$packageName.${type.uppercase()}")
        runCatching {
            context.contentResolver.query(uri, arrayOf(payload, peer ?: "", pubkey), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val result = cursor.getColumnIndex("result").takeIf { it >= 0 }?.let(cursor::getString)
                    ?: cursor.getColumnIndex("signature").takeIf { it >= 0 }?.let(cursor::getString)
                val event = cursor.getColumnIndex("event").takeIf { it >= 0 }?.let(cursor::getString)
                if (result == null && event == null) null else result to event
            }
        }.getOrNull()
    }

    private fun appLabel(): String = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    companion object {
        /** Asks the signer app which key it holds. The person picks the account there. */
        suspend fun connect(context: Context, bridge: Nip55Bridge, packageName: String): Nip55Signer {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("${Nip55.SCHEME}:")).apply {
                `package` = packageName
                putExtra("type", Nip55.TYPE_GET_PUBLIC_KEY)
                putExtra("permissions", Nip55.permissions())
            }
            val answer = bridge.request(intent) ?: throw SignerException("Sign-in was cancelled.")
            if (answer.getBooleanExtra("rejected", false)) throw SignerException("The signer app declined.")
            val pubkey = Nip55.publicKeyFromResult(answer.getStringExtra("result") ?: answer.getStringExtra("signature"))
                ?: throw SignerException("The signer app did not return a public key.")
            val chosen = answer.getStringExtra("package")?.takeIf { it.isNotBlank() } ?: packageName
            return Nip55Signer(pubkey, chosen, context, bridge)
        }
    }
}
