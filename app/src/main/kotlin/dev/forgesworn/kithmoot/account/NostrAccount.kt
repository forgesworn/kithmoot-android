package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.storage.RoomStorageException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * The Nostr account this phone is signed in as, and how to reach its key.
 *
 * One per app, like the web client: sign in once, and every room started or
 * joined afterwards is joined as this person. What is kept is the least that
 * gets the signer back: the package name of a signer app, or the bunker link
 * and this phone's client key, or, for a pasted key, the key itself. A saved
 * room made under the account records only the public key, and needs the
 * account to be signed in to open.
 */
data class NostrAccount(
    val pubkey: String,
    /** `nip55`, `bunker` or `local`. */
    val method: String,
    val signerPackage: String? = null,
    val bunkerUri: String? = null,
    val clientSecretKey: ByteArray? = null,
    val secretKey: ByteArray? = null,
    val displayName: String? = null,
    val signedInAt: Long = 0,
) {
    val npub: String get() = npubOf(pubkey)

    internal fun toJson(): JsonObject = buildJsonObject {
        put("v", 1)
        put("pubkey", pubkey)
        put("method", method)
        signerPackage?.let { put("signerPackage", it) }
        bunkerUri?.let { put("bunkerUri", it) }
        clientSecretKey?.let { put("clientKey", it.toHex()) }
        secretKey?.let { put("secretKey", it.toHex()) }
        displayName?.let { put("displayName", it) }
        put("signedInAt", signedInAt)
    }

    override fun toString(): String = "NostrAccount(${shortNpub(pubkey)}, $method)"

    companion object {
        internal fun fromJson(json: JsonObject): NostrAccount {
            fun text(key: String) = json[key]?.jsonPrimitive?.content
            val account = NostrAccount(
                pubkey = requireNotNull(text("pubkey")).also { require(it.matches(Regex("[0-9a-f]{64}"))) },
                method = requireNotNull(text("method")).also { require(it in setOf("nip55", "bunker", "local")) },
                signerPackage = text("signerPackage"),
                bunkerUri = text("bunkerUri"),
                clientSecretKey = text("clientKey")?.hexToBytes()?.also { require(it.size == 32) },
                secretKey = text("secretKey")?.hexToBytes()?.also { require(it.size == 32) },
                displayName = text("displayName"),
                signedInAt = json["signedInAt"]?.jsonPrimitive?.long ?: 0,
            )
            when (account.method) {
                "nip55" -> require(account.signerPackage != null)
                "bunker" -> require(account.bunkerUri != null && account.clientSecretKey != null)
                "local" -> require(account.secretKey != null)
            }
            return account
        }
    }
}

/** The account on disk, in its own vault beside the rooms. */
class AccountStore(private val storage: RoomStorage) {
    @Synchronized fun load(): NostrAccount? = guarded {
        val bytes = storage.read() ?: return@guarded null
        val json = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        NostrAccount.fromJson(json)
    }

    @Synchronized fun save(account: NostrAccount) = guarded {
        storage.write(account.toJson().toString().encodeToByteArray())
    }

    @Synchronized fun clear() = guarded {
        storage.reset()
    }

    private inline fun <T> guarded(block: () -> T): T = try { block() } catch (e: Exception) {
        if (e is RoomStorageException) throw e
        throw RoomStorageException(e)
    }
}

/**
 * A signed-in account with its signer live: the signer app reachable, or the
 * bunker connected over its relays. Closing it drops the relay sockets; the
 * account itself stays saved.
 */
class AccountSession(val account: NostrAccount, val signer: ParticipantSigner) {
    fun close() = signer.close()
}

/** A bunker signer that presents itself to the signer once, on first use, so the app opens without waiting on a relay. */
private class ConnectingSigner(private val inner: BunkerSigner, private val connect: suspend () -> Unit) : ParticipantSigner by inner {
    private val gate = Mutex()
    private var connected = false
    private suspend fun ready() = gate.withLock { if (!connected) { connect(); connected = true } }
    override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): dev.forgesworn.kithmoot.protocol.NostrEvent { ready(); return inner.sign(kind, createdAt, tags, content) }
    override suspend fun nip44Encrypt(peer: String, plaintext: String): String { ready(); return inner.nip44Encrypt(peer, plaintext) }
    override suspend fun nip44Decrypt(peer: String, payload: String): String { ready(); return inner.nip44Decrypt(peer, payload) }
}

/** Brings a saved account's signer back. Pure for a pasted key, an intent away for a signer app, a relay away for a bunker. */
fun openAccount(
    account: NostrAccount,
    context: android.content.Context,
    bridge: Nip55Bridge,
    scope: kotlinx.coroutines.CoroutineScope,
): AccountSession {
    val signer: ParticipantSigner = when (account.method) {
        "local" -> LocalSigner(requireNotNull(account.secretKey))
        "nip55" -> Nip55Signer(account.pubkey, requireNotNull(account.signerPackage), context, bridge)
        "bunker" -> {
            val pointer = BunkerPointer.parse(requireNotNull(account.bunkerUri)) ?: throw SignerException("The saved bunker link is not readable.")
            val pool = dev.forgesworn.kithmoot.relay.RelayPool(pointer.relays, dev.forgesworn.kithmoot.relay.OkHttpRelaySockets(), scope)
            pool.start()
            val client = Nip46Client(pointer, requireNotNull(account.clientSecretKey), pool, scope)
            ConnectingSigner(BunkerSigner(account.pubkey, client, onClose = pool::stop)) {
                client.connect()
                val remote = client.getPublicKey()
                if (remote != account.pubkey) throw SignerException("The signer now holds a different key (${shortNpub(remote)}). Sign in again.")
            }
        }
        else -> throw SignerException("Unknown account type.")
    }
    return AccountSession(account, signer)
}
