package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.storage.RoomStorageException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * The Nostr account this phone is signed in as, and how to reach its key.
 *
 * Every room started or joined while this account is active is joined as
 * this person. What is kept is the least that
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
    /** App-owned transport/decryption key. Also used for Cambium's encrypted child handoff; never sent to a signer. */
    val clientSecretKey: ByteArray? = null,
    val secretKey: ByteArray? = null,
    val displayName: String? = null,
    val signedInAt: Long = 0,
    val personaLabel: String? = null,
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
        personaLabel?.let { put("personaLabel", it) }
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
                personaLabel = text("personaLabel"),
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

/** The active account and approved connections share one encrypted, atomic record. */
class AccountStore(private val storage: RoomStorage) {
    private data class Accounts(val active: String?, val connections: List<NostrAccount>)
    private val _activePubkey = MutableStateFlow<String?>(null)
    val activePubkey = _activePubkey.asStateFlow()

    @Synchronized fun load(): NostrAccount? {
        return try {
            guarded {
                val saved = read()
                _activePubkey.value = saved.active
                saved.connections.firstOrNull { it.pubkey == saved.active }
            }
        } catch (e: RoomStorageException) {
            _activePubkey.value = null
            throw e
        }
    }

    @Synchronized fun list(): List<NostrAccount> = guarded { read().connections }

    @Synchronized fun save(account: NostrAccount) = guarded {
        val remaining = read().connections.filterNot { it.pubkey == account.pubkey }
        write(Accounts(account.pubkey, listOf(account) + remaining))
        _activePubkey.value = account.pubkey
    }

    @Synchronized fun clear() = guarded {
        val saved = read()
        val remaining = saved.connections.filterNot { it.pubkey == saved.active }
        if (remaining.isEmpty()) storage.reset()
        else write(Accounts(null, remaining))
        _activePubkey.value = null
    }

    private fun read(): Accounts {
        val bytes = storage.read() ?: return Accounts(null, emptyList())
        val json = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        return when (json["v"]?.jsonPrimitive?.content) {
            "2" -> {
                val active = json["active"]?.jsonPrimitive?.contentOrNull
                val connections = requireNotNull(json["accounts"]).jsonArray.map { NostrAccount.fromJson(it.jsonObject) }
                val keys = connections.map { it.pubkey }.toSet()
                require(keys.size == connections.size && (active == null || active in keys))
                Accounts(active, connections)
            }
            "1" -> NostrAccount.fromJson(json).let { Accounts(it.pubkey, listOf(it)) }
            else -> error("Unsupported account record.")
        }
    }

    private fun write(saved: Accounts) {
        val only = saved.connections.singleOrNull()?.takeIf { it.pubkey == saved.active }
        val json = only?.toJson() ?: buildJsonObject {
            put("v", 2)
            saved.active?.let { put("active", it) }
            put("accounts", JsonArray(saved.connections.map(NostrAccount::toJson)))
        }
        storage.write(json.toString().encodeToByteArray())
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

/** A preview local-key account may only be replaced by the same public identity. */
internal fun requireSameRetainedAccount(retained: NostrAccount?, replacement: NostrAccount) {
    if (retained?.method == "local" && retained.pubkey != replacement.pubkey) {
        throw SignerException(
            "That signer is ${shortNpub(replacement.pubkey)}, but this preview kept ${shortNpub(retained.pubkey)}. " +
                "Sign in with the same account. Nothing was changed on this phone.",
        )
    }
}

/** A bunker signer that presents itself to the signer once, on first use, so the app opens without waiting on a relay. */
private class ConnectingSigner(private val inner: BunkerSigner, private val connect: suspend ((() -> Boolean)?) -> Unit) : ParticipantSigner by inner, RendezvousProvisionSigner {
    private val gate = Mutex()
    private var connected = false
    private suspend fun ready(current: (() -> Boolean)? = null) = gate.withLock {
        check(current?.invoke() != false)
        if (!connected) { connect(current); connected = true }
    }
    override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent { ready(); return inner.sign(kind, createdAt, tags, content) }
    override suspend fun nip44Encrypt(peer: String, plaintext: String): String { ready(); return inner.nip44Encrypt(peer, plaintext) }
    override suspend fun nip44Decrypt(peer: String, payload: String): String { ready(); return inner.nip44Decrypt(peer, payload) }
    override suspend fun openInboxCiphertext(peer: String, payload: String, current: () -> Boolean): String {
        ready(current); return inner.openInboxCiphertext(peer, payload, current)
    }
    override suspend fun sealInboxText(peer: String, plaintext: String, current: () -> Boolean): String {
        ready(current); return inner.sealInboxText(peer, plaintext, current)
    }
    override suspend fun signInboxEvent(kind: Int, at: Long, tags: List<List<String>>, content: String, current: () -> Boolean): NostrEvent {
        ready(current); return inner.signInboxEvent(kind, at, tags, content, current)
    }
    override suspend fun provisionRendezvous(targetDevice: String, index: Long, nonce: ByteArray, expiresAt: Long, current: () -> Boolean): String {
        if (!current()) throw SignerException("The account changed before provisioning.")
        ready()
        if (!current()) throw SignerException("The account changed while connecting.")
        return inner.provisionRendezvous(targetDevice, index, nonce, expiresAt, current)
    }
}

/** Brings a saved account's signer back through a signer app, a bunker, or a debug-preview legacy record. */
fun openAccount(
    account: NostrAccount,
    context: android.content.Context,
    bridge: Nip55Bridge,
    scope: kotlinx.coroutines.CoroutineScope,
): AccountSession {
    val signer: ParticipantSigner = when (account.method) {
        "local" -> {
            if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) {
                throw SignerException("This account came from a debug preview's local-key option. Sign in again with a signer app or bunker.")
            }
            LocalSigner(requireNotNull(account.secretKey))
        }
        "nip55" -> Nip55Signer(account.pubkey, requireNotNull(account.signerPackage), context, bridge)
        "bunker" -> {
            val pointer = BunkerPointer.parse(requireNotNull(account.bunkerUri)) ?: throw SignerException("The saved bunker link is not readable.")
            val pool = dev.forgesworn.kithmoot.relay.RelayPool(pointer.relays, dev.forgesworn.kithmoot.relay.OkHttpRelaySockets(), scope)
            pool.start()
            val client = Nip46Client(pointer, requireNotNull(account.clientSecretKey), pool, scope)
            ConnectingSigner(BunkerSigner(account.pubkey, client, onClose = pool::stop)) { current ->
                client.connect(current)
                val remote = client.getPublicKey(current)
                if (remote != account.pubkey) throw SignerException("The signer now holds a different key (${shortNpub(remote)}). Sign in again.")
            }
        }
        else -> throw SignerException("Unknown account type.")
    }
    return AccountSession(account, signer)
}

/** Cold-start migration happens before adoption, never during a pending ceremony.
 * Reuses the account's existing encrypted record and preserves a retained target. */
internal fun NostrAccount.withProvisionTransportKey(): NostrAccount {
    if (method != "nip55" || signerPackage != "dev.forgesworn.cambium" || clientSecretKey != null) return this
    repeat(128) {
        val key = Entropy.bytes(32)
        if (runCatching { Schnorr.publicKey(key) }.isSuccess) return copy(clientSecretKey = key)
        key.fill(0)
    }
    throw SignerException("A protected provision target could not be created.")
}
