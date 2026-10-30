package dev.forgesworn.kithmoot.callrelay

import android.content.Context
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.storage.EncryptedRoomStorage
import dev.forgesworn.kithmoot.storage.RoomStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * The built-in call relay's lasting identity (design, 1.3 and 4.1).
 * `SPRY_TURN_SECRET` derives the node's key, its pairing id, its grant tokens
 * and so the `spry:` endpoint rooms carry; the endpoint also names the
 * control relays, and clients compare endpoints whole. So the secret, the
 * app's pairing secret, the account, the control relays and the listener port
 * are made once, kept together, and thrown away only when the person removes
 * the relay. The desktop's desktop/relay-identity.mjs.
 *
 * [toString] never shows a secret.
 */
internal class RelayIdentity(
    val turnSecret: String,
    val pairingSecret: String,
    val participant: String,
    val controlRelays: List<String>,
    val listenPort: Int,
) {
    /** The node's `--pairing-key`: the pairing secret's x-only public key. */
    val pairingPublicKey: String get() = publicKey(pairingSecret) ?: error("Invalid call relay settings.")

    override fun toString() = "RelayIdentity(redacted)"

    fun encode(): ByteArray = buildJsonObject {
        put("v", 1); put("turnSecret", turnSecret); put("pairingSecret", pairingSecret); put("participant", participant)
        put("controlRelays", JsonArray(controlRelays.map(::JsonPrimitive))); put("listenPort", listenPort)
    }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")
        private val KEYS = setOf("v", "turnSecret", "pairingSecret", "participant", "controlRelays", "listenPort")

        /** The x-only public key of a secp256k1 secret, or null for one that is not valid (zero, or not below the order). */
        fun publicKey(secret: String): String? {
            if (!HEX64.matches(secret)) return null
            val bytes = secret.hexToBytes()
            return try { Schnorr.publicKeyHex(bytes) } catch (_: Exception) { null } finally { bytes.fill(0) }
        }

        /** A saved identity, checked field by field; throws for anything else, never naming a value. */
        fun decode(bytes: ByteArray): RelayIdentity = try {
            val raw = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            require(raw.keys == KEYS && raw.getValue("v").jsonPrimitive.int == 1)
            fun hex(name: String) = raw.getValue(name).jsonPrimitive.also { require(it.isString) }.content.also { require(HEX64.matches(it)) }
            val relays = raw.getValue("controlRelays").jsonArray.map { it.jsonPrimitive.also { p -> require(p.isString) }.content }
            val port = raw.getValue("listenPort").jsonPrimitive.int
            require(port in RelayCaps.LISTEN_PORTS && RelayConfig.controlRelayList(relays) == relays)
            RelayIdentity(hex("turnSecret"), hex("pairingSecret").also { require(publicKey(it) != null) }, hex("participant"), relays, port)
        } catch (_: Exception) { throw IllegalStateException("The call relay's saved keys could not be read.") }

        /** A new identity for [participant], with control relays already picked. */
        fun create(participant: String, controlRelays: List<String>, random: (Int) -> ByteArray = Entropy::bytes): RelayIdentity {
            require(HEX64.matches(participant)) { "Invalid call relay settings." }
            val relays = RelayConfig.controlRelayList(controlRelays)
            var pairing: String
            do { pairing = random(32).toHex() } while (publicKey(pairing) == null)
            val span = RelayCaps.LISTEN_PORTS.last - RelayCaps.LISTEN_PORTS.first + 1
            val draw = random(4).fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xff) }
            return RelayIdentity(random(32).toHex(), pairing, participant, relays, RelayCaps.LISTEN_PORTS.first + (draw % span).toInt())
        }
    }
}

/**
 * Keeps the [RelayIdentity] with the app's Keystore-wrapped storage, in
 * `noBackupFilesDir`, never in clear and never synced. Read and written in the
 * app's main process only; the relay process is handed what it needs over the
 * binder.
 */
internal class RelayIdentityStore(private val storage: RoomStorage, private val stateDirectory: File) {
    constructor(context: Context) : this(EncryptedRoomStorage(context, ALIAS, 4096, AAD), stateDirectory(context))

    /** The saved identity, null if none was made; throws if one exists but cannot be read. */
    fun read(): RelayIdentity? = storage.read()?.let { bytes -> try { RelayIdentity.decode(bytes) } finally { bytes.fill(0) } }

    fun save(identity: RelayIdentity) {
        val bytes = identity.encode()
        try { storage.write(bytes) } finally { bytes.fill(0) }
    }

    /**
     * Forget the identity and the node's own state (its grants and pairing).
     * The router-mapping record beside the state is kept, so the next run can
     * still clean up after a crash. Only while the relay is stopped.
     */
    fun remove() {
        storage.reset()
        File(stateDirectory, STATE_FILE).delete()
    }

    companion object {
        const val ALIAS = "kithmoot.spry-relay.v1"
        private val AAD = "KithMoot built-in call relay v1".toByteArray(Charsets.UTF_8)
        const val STATE_FILE = "node-state.json"

        /** Where the node keeps its state file and mapping record, private to the app and outside backup. */
        fun stateDirectory(context: Context) = File(context.applicationContext.noBackupFilesDir, "spry-relay")
    }
}
