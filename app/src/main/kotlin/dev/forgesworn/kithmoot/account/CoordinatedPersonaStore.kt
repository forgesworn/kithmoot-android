package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.relay.StoredLinkRoute
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.storage.SealKeyMissingException
import dev.forgesworn.kithmoot.storage.SealKeys
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * The coordinated MLS vault's durable places (P3-03b-2). Beside the
 * [MlsVaultStores] the vault already has (the vault-wide installation store,
 * and here the session epoch store, both delete-before-commit):
 * - one rollback-resistant file per persona, which deletes a superseded outer
 *   key only after its commit;
 * - an unsealed marker beside it;
 * - the persona's never-rotated inner key;
 * - a lock per persona (a process-wide mutex plus a file lock).
 */
interface CoordinatedVaultStores : MlsVaultStores {
    /** The persona's coordinated file under its fixed [name]; deletes old keys after the commit. */
    fun coordinated(name: String, aad: ByteArray): RoomStorage
    fun marker(name: String): MarkerStore
    /** Where inner keys live, and each persona's alias in it. */
    val innerKeys: SealKeys
    fun innerAlias(name: String): String
    /** Names of every coordinated persona with a file or a marker present, readable or not. */
    fun coordinatedNames(): List<String>
    fun personaLock(name: String): PersonaLock
}

/** A small unsealed file written atomically with fsync. */
interface MarkerStore {
    fun read(): ByteArray?
    fun write(value: ByteArray)
    fun delete()
}

/** Held across the witness round trip, so one persona's trip never blocks another. */
interface PersonaLock {
    suspend fun <T> withLock(work: suspend () -> T): T
}

/** A persona's coordinated file, decoded. Secret bytes are wiped by [wipe]. */
internal class PersonaFile(
    val persona: String,
    val revision: Long,
    /** The persona's own 32-byte installation id, minted before pairing. */
    val installation: ByteArray,
    /** The writer's Link seed (PR 5 fills it). */
    val writerSeed: ByteArray?,
    /** The persona's own witness route (PR 5 fills it). */
    val witnessRoute: StoredLinkRoute?,
    /** The coordinator's `state()` bytes; null until genesis. */
    val state: ByteArray?,
    /** Record id (hex) -> its sealed bytes, as last promoted. */
    val active: Map<String, ByteArray>,
    /** The staged candidate's objects, if one is pending. */
    val staged: Map<String, ByteArray>?,
    /** `clear()` replaced this installation; only the retiring duty remains. */
    val cleared: Boolean,
) {
    fun next(
        state: ByteArray? = this.state,
        active: Map<String, ByteArray> = this.active,
        staged: Map<String, ByteArray>? = this.staged,
        cleared: Boolean = this.cleared,
    ) = PersonaFile(persona, revision + 1, installation, writerSeed, witnessRoute, state, active, staged, cleared)

    fun wipe() { writerSeed?.fill(0) }

    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).run {
            writeByte(FORMAT.toInt())
            write(hex32(persona))
            writeLong(revision)
            write(installation)
            var flags = 0
            if (writerSeed != null) flags = flags or 1
            if (witnessRoute != null) flags = flags or 2
            if (state != null) flags = flags or 4
            if (staged != null) flags = flags or 8
            if (cleared) flags = flags or 16
            writeByte(flags)
            writerSeed?.let { write(it) }
            witnessRoute?.let { route ->
                bytes(route.routeId.toByteArray(Charsets.US_ASCII))
                bytes(route.card)
                write(route.pairedRouteSecret)
                writeLong(route.cardSerial.toLong())
                writeLong(route.cardVerifiedAt.toLong())
            }
            state?.let { bytes(it) }
            objects(active)
            staged?.let { objects(it) }
        }
        return out.toByteArray()
    }

    private fun DataOutputStream.bytes(value: ByteArray) { writeInt(value.size); write(value) }
    private fun DataOutputStream.objects(map: Map<String, ByteArray>) {
        writeInt(map.size)
        for ((id, sealed) in map.toSortedMap()) { bytes(hexBytes(id)); bytes(sealed) }
    }

    companion object {
        const val FORMAT: Byte = 1
        private const val MAX_FIELD = 8 * 1024 * 1024

        fun fresh(persona: String, installation: ByteArray) =
            PersonaFile(persona, 1, installation, null, null, null, emptyMap(), null, false)

        fun decode(value: ByteArray, persona: String): PersonaFile {
            val input = DataInputStream(value.inputStream())
            require(input.readByte() == FORMAT)
            require(ByteArray(32).also(input::readFully).toHex() == persona)
            val revision = input.readLong()
            val installation = ByteArray(32).also(input::readFully)
            val flags = input.readUnsignedByte()
            require(flags and 0xe0 == 0)
            val seed = if (flags and 1 != 0) ByteArray(32).also(input::readFully) else null
            val route = if (flags and 2 != 0) StoredLinkRoute(
                input.bytes().toString(Charsets.US_ASCII), input.bytes(),
                ByteArray(32).also(input::readFully), input.readLong().toULong(), input.readLong().toULong(),
            ) else null
            val state = if (flags and 4 != 0) input.bytes() else null
            val active = input.objects()
            val staged = if (flags and 8 != 0) input.objects() else null
            require(input.read() == -1)
            return PersonaFile(persona, revision, installation, seed, route, state, active, staged, flags and 16 != 0)
        }

        private fun DataInputStream.bytes(): ByteArray {
            val size = readInt()
            require(size in 0..MAX_FIELD)
            return ByteArray(size).also(::readFully)
        }

        private fun DataInputStream.objects(): Map<String, ByteArray> {
            val count = readInt()
            require(count in 0..64)
            return (0 until count).associate { bytes().toHex() to bytes() }.also { require(it.size == count) }
        }

        private fun hex32(hex: String): ByteArray = hexBytes(hex).also { require(it.size == 32) }
        private fun hexBytes(hex: String): ByteArray {
            require(hex.length % 2 == 0)
            return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
    }
}

/** What the marker says. Public at the box: the subject and the writer's node id. */
internal data class Marker(val fenced: Boolean, val reason: String?, val subject: String?, val writer: String?) {
    fun encode(): ByteArray = buildJsonObject {
        put("v", 1)
        put("state", if (fenced) "fenced" else "genesis")
        put("reason", reason?.let(::JsonPrimitive) ?: JsonNull)
        put("subject", subject?.let(::JsonPrimitive) ?: JsonNull)
        put("writer", writer?.let(::JsonPrimitive) ?: JsonNull)
    }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        fun decode(value: ByteArray): Marker {
            val json = Json.parseToJsonElement(value.toString(Charsets.UTF_8)).jsonObject
            require(json.getValue("v").jsonPrimitive.long == 1L)
            val state = json.getValue("state").jsonPrimitive.content
            require(state == "fenced" || state == "genesis")
            fun text(name: String) = (json[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
            return Marker(state == "fenced", text("reason"), text("subject"), text("writer"))
        }
    }
}

/** How reading the coordinated file went. */
internal sealed class FileRead {
    data object Missing : FileRead()
    class Present(val file: PersonaFile) : FileRead()
    /** Definitive evidence the outer seal key is gone: the alias is absent, or the GCM tag fails. */
    data object SealLost : FileRead()
}

/**
 * One persona's coordinated file (P3-03b-2, decision C3): the coordinator
 * state, the persona's installation id, the writer slots and the sealed
 * objects, active and staged. Stage, promote and every state change are each
 * one atomic write with fsync, made in order under the persona lock.
 *
 * Each inner object is sealed once under the persona's never-rotated inner
 * key, with an AAD binding the format version, the persona, the persona's
 * installation id and the record id, so an unchanged object keeps its exact
 * bytes and its `coordinator_object_hash`.
 */
internal class CoordinatedPersonaStore(
    val persona: String,
    private val name: String,
    private val stores: CoordinatedVaultStores,
    outerAad: ByteArray,
) {
    private val storage = stores.coordinated(name, outerAad)
    private val markerStore = stores.marker(name)
    private val innerAlias = stores.innerAlias(name)
    val lock: PersonaLock = stores.personaLock(name)

    fun read(): FileRead {
        val bytes = try {
            storage.read()
        } catch (error: Exception) {
            if (definitive(error)) return FileRead.SealLost
            throw MlsVaultUnavailableException(error)
        } ?: return FileRead.Missing
        try {
            return FileRead.Present(guarded { PersonaFile.decode(bytes, persona) })
        } finally {
            bytes.fill(0)
        }
    }

    fun write(file: PersonaFile) {
        val bytes = file.encode()
        try { guarded { storage.write(bytes) } } finally { bytes.fill(0) }
    }

    /** Removes the file and its outer keys. */
    fun delete() = guarded { storage.reset() }

    // ---- the marker ----

    fun marker(): Marker? = guarded { markerStore.read()?.let(Marker::decode) }
    fun writeMarker(marker: Marker) = guarded { markerStore.write(marker.encode()) }
    fun deleteMarker() = guarded { markerStore.delete() }

    /** Switches the marker to fenced; only its state changes. */
    fun fenceMarker(reason: String) {
        val current = try { marker() } catch (_: MlsVaultUnavailableException) { null }
        if (current?.fenced == true) return
        writeMarker(Marker(true, reason, current?.subject, current?.writer))
    }

    // ---- the inner key ----

    /** True when the inner key's alias is present; a transient Keystore failure throws. */
    fun innerKeyPresent(): Boolean = guarded { stores.innerKeys.get(innerAlias) != null }

    /** A fresh inner key for a new installation; any older one under the alias goes. */
    fun newInnerKey() = guarded {
        if (stores.innerKeys.get(innerAlias) != null) stores.innerKeys.delete(innerAlias)
        stores.innerKeys.create(innerAlias)
    }

    fun deleteInnerKey() = guarded { stores.innerKeys.delete(innerAlias) }

    fun seal(file: PersonaFile, recordId: ByteArray, plain: ByteArray): ByteArray = guarded {
        val key = stores.innerKeys.get(innerAlias) ?: throw SealKeyMissingException("The persona's inner key is unavailable")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        require(cipher.iv.size == 12)
        cipher.updateAAD(innerAad(file, recordId))
        byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)
    }

    /** Opens an inner object. A missing key or failed tag is [SealLostException]. */
    fun open(file: PersonaFile, recordId: ByteArray, sealed: ByteArray): ByteArray {
        try {
            require(sealed.size >= 29 && sealed[0] == 1.toByte())
            val key = stores.innerKeys.get(innerAlias) ?: throw SealKeyMissingException("The persona's inner key is unavailable")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed.copyOfRange(1, 13)))
            cipher.updateAAD(innerAad(file, recordId))
            return cipher.doFinal(sealed, 13, sealed.size - 13)
        } catch (error: Exception) {
            if (definitive(error)) throw SealLostException(error)
            throw MlsVaultUnavailableException(error)
        }
    }

    private fun innerAad(file: PersonaFile, recordId: ByteArray): ByteArray =
        "$INNER_AAD|${PersonaFile.FORMAT}|$persona|${file.installation.toHex()}|${recordId.toHex()}".toByteArray(Charsets.US_ASCII)

    private inline fun <T> guarded(block: () -> T): T = try { block() } catch (error: Exception) {
        if (error is MlsVaultUnavailableException) throw error
        throw MlsVaultUnavailableException(error)
    }

    companion object {
        private const val INNER_AAD = "kithmoot.mls-vault.v1|coord-object"

        /** Fence only on definitive evidence; a transient Keystore or provider failure stays unavailable. */
        fun definitive(error: Throwable): Boolean {
            var cause: Throwable? = error
            var depth = 0
            while (cause != null && depth++ < 8) {
                if (cause is SealKeyMissingException || cause is AEADBadTagException) return true
                cause = cause.cause
            }
            return false
        }

        fun random32(random: SecureRandom): ByteArray = ByteArray(32).also(random::nextBytes)
    }
}

/** An inner object's seal is definitively lost: the persona is fenced (`missing-seal-key`). */
internal class SealLostException(cause: Exception) : Exception("A sealed persona object can no longer be opened", cause)
