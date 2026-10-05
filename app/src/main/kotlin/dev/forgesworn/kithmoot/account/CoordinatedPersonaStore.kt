package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.relay.StoredLinkRoute
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.storage.SealKeyMissingException
import dev.forgesworn.kithmoot.storage.SealKeys
import android.security.keystore.KeyPermanentlyInvalidatedException
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
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
    /** The persona's sealed session snapshots (P3-03b-3a). */
    fun snapshots(name: String): SnapshotFiles
}

/**
 * A persona's MLS session snapshots: one immutable file per session and
 * generation, written atomically with fsync before the candidate naming it is
 * staged. The inner key seals them; the witnessed manifest holds each one's
 * generation and hash, so an older file is never opened as current.
 */
interface SnapshotFiles {
    /** The sealed bytes, or null when no such file exists. A failed read throws. */
    fun read(session: String, generation: Long): ByteArray?
    fun write(session: String, generation: Long, sealed: ByteArray)
    fun delete(session: String, generation: Long)
    /** Every snapshot file present, as (session hex, generation). */
    fun list(): List<Pair<String, Long>>
}

/** The largest marker an Android marker file holds. */
const val MARKER_MAX_BYTES: Int = Marker.MAX_BYTES

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
    /** The writer's Link seed, minted with [installation] before any pairing. */
    val writerSeed: ByteArray?,
    /** The persona's own witness route, from the keeper's witness-only pairing code. */
    val witnessRoute: StoredLinkRoute?,
    /** The coordinator's `state()` bytes; null until genesis. */
    val state: ByteArray?,
    /** Record id (hex) -> its sealed bytes, as last promoted. */
    val active: Map<String, ByteArray>,
    /** The staged candidate's objects, if one is pending. */
    val staged: Map<String, ByteArray>?,
    /** `clear()` replaced this installation; only the retiring duty remains. */
    val cleared: Boolean,
    /** Session id (hex) -> the generation of its snapshot file, as last promoted. */
    val sessions: Map<String, Long> = emptyMap(),
    /** The staged candidate's sessions; present exactly when [staged] is. */
    val stagedSessions: Map<String, Long>?,
) {
    init { require((staged == null) == (stagedSessions == null)) }

    fun next(
        state: ByteArray? = this.state,
        active: Map<String, ByteArray> = this.active,
        staged: Map<String, ByteArray>? = this.staged,
        cleared: Boolean = this.cleared,
        sessions: Map<String, Long> = this.sessions,
        // A new candidate names its sessions explicitly: a default here would stage "no sessions", and its promotion would sweep them all.
        stagedSessions: Map<String, Long>? = when {
            staged == null -> null
            staged === this.staged -> this.stagedSessions
            else -> throw IllegalArgumentException("A new candidate names its sessions")
        },
    ) = PersonaFile(persona, revision + 1, installation, writerSeed, witnessRoute, state, active, staged, cleared, sessions, stagedSessions)

    /** The same installation with its writer seed (a file prepared before seeds existed). */
    fun seeded(seed: ByteArray) = PersonaFile(persona, revision + 1, installation, seed, witnessRoute, state, active, staged, cleared, sessions, stagedSessions)

    /** The same installation, paired with its witness. */
    fun paired(route: StoredLinkRoute) = PersonaFile(persona, revision + 1, installation, writerSeed, route, state, active, staged, cleared, sessions, stagedSessions)

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
            generations(sessions)
            stagedSessions?.let { generations(it) }
        }
        return out.toByteArray()
    }

    private fun DataOutputStream.generations(map: Map<String, Long>) {
        writeInt(map.size)
        for ((session, generation) in map.toSortedMap()) { write(hex32(session)); writeLong(generation) }
    }

    private fun DataOutputStream.bytes(value: ByteArray) { writeInt(value.size); write(value) }
    private fun DataOutputStream.objects(map: Map<String, ByteArray>) {
        writeInt(map.size)
        for ((id, sealed) in map.toSortedMap()) { bytes(hexBytes(id)); bytes(sealed) }
    }

    companion object {
        /** 2 adds the session generations (P3-03b-3a); a format 1 file has none. */
        const val FORMAT: Byte = 2
        private const val FORMAT_1: Byte = 1
        private const val MAX_FIELD = 8 * 1024 * 1024
        /** Sessions one persona may hold; the engine's coordinator takes at most 4,096 entries in all. */
        const val MAX_SESSIONS = 1024

        /**
         * A new installation's file. Its revision starts at random, so a
         * coordinator cached for an earlier incarnation never matches it.
         * [writerSeed] and [witnessRoute] are carried only when an interrupted
         * genesis is retried: that writer was never enrolled at the box.
         */
        fun fresh(persona: String, installation: ByteArray, revision: Long, writerSeed: ByteArray?, witnessRoute: StoredLinkRoute? = null) =
            PersonaFile(persona, revision, installation, writerSeed, witnessRoute, null, emptyMap(), null, false, emptyMap(), null)

        fun decode(value: ByteArray, persona: String): PersonaFile {
            val input = DataInputStream(value.inputStream())
            val format = input.readByte()
            require(format == FORMAT || format == FORMAT_1)
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
            val sessions = if (format == FORMAT) input.generations() else emptyMap()
            val stagedSessions = if (staged == null) null else if (format == FORMAT) input.generations() else emptyMap()
            require(input.read() == -1)
            return PersonaFile(persona, revision, installation, seed, route, state, active, staged, flags and 16 != 0, sessions, stagedSessions)
        }

        private fun DataInputStream.generations(): Map<String, Long> {
            val count = readInt()
            require(count in 0..MAX_SESSIONS)
            return (0 until count).associate { ByteArray(32).also(::readFully).toHex() to readLong().also { g -> require(g > 0) } }
                .also { require(it.size == count) }
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

        internal fun hex32(hex: String): ByteArray = hexBytes(hex).also { require(it.size == 32) }
        private fun hexBytes(hex: String): ByteArray {
            require(hex.length % 2 == 0)
            return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
    }
}

/**
 * What the marker says: only what is public at the box (the subject, the
 * persona installation and the writer's node id). It never names the persona;
 * the vault's sealed coordination index maps files to personas.
 * A marker proves a coordinated file existed, so a missing file beside it is
 * never a fresh start; it also keeps the subject for the keeper's
 * `bothyd witness retire --subject` line after a fence or a clear, and lists
 * every retired subject and installation so neither is ever reused.
 */
internal data class Marker(
    val state: State,
    val reason: String?,
    val subject: String?,
    val writer: String?,
    val installation: String?,
    val retired: List<Tombstone> = emptyList(),
    /** The genesis digest, so the enrol line can be shown again until the keeper runs it. Public at the box. */
    val digest: String? = null,
) {
    enum class State(val wire: String) { Genesis("genesis"), Fenced("fenced"), Superseded("superseded") }
    data class Tombstone(val subject: String?, val installation: String?)

    val fenced: Boolean get() = state == State.Fenced

    /** At most [MAX_TOMBSTONES], the most recent kept: see [MAX_TOMBSTONES]. */
    fun encode(): ByteArray = buildJsonObject {
        put("v", 3)
        put("state", state.wire)
        put("reason", reason.json())
        put("subject", subject.json())
        put("writer", writer.json())
        put("installation", installation.json())
        put("digest", digest.json())
        put("retired", buildJsonArray {
            retired.takeLast(MAX_TOMBSTONES).forEach { add(buildJsonObject { put("subject", it.subject.json()); put("installation", it.installation.json()) }) }
        })
    }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        /**
         * Tombstones kept locally. The box itself never reuses a subject or
         * installation id (§4.1), and fresh ids are 32 random bytes, so the
         * local list is only a belt over that: the most recent 64 are kept,
         * bounding the marker well under [MAX_BYTES].
         */
        const val MAX_TOMBSTONES = 64
        /** The largest marker a store accepts. */
        const val MAX_BYTES = 64 * 1024

        private fun String?.json() = this?.let(::JsonPrimitive) ?: JsonNull

        fun decode(value: ByteArray): Marker {
            val json = Json.parseToJsonElement(value.toString(Charsets.UTF_8)).jsonObject
            require(json.getValue("v").jsonPrimitive.long == 3L)
            fun JsonObject.text(name: String) = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val state = State.entries.single { it.wire == json.text("state") }
            return Marker(
                state, json.text("reason"), json.text("subject"), json.text("writer"), json.text("installation"),
                json.getValue("retired").jsonArray.map { it.jsonObject.let { t -> Tombstone(t.text("subject"), t.text("installation")) } },
                json.text("digest"),
            )
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
    private val snapshots = stores.snapshots(name)
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

    /** Removes the file and its outer keys, then every session snapshot. */
    fun delete() {
        guarded { storage.reset() }
        // Orphaned snapshots are unreadable without the file and inner key; a failed sweep never blocks a retirement.
        runCatching { sweepSnapshots(emptySet()) }
    }

    // ---- session snapshots (P3-03b-3a) ----

    /** A snapshot's sealed bytes, or null when its file is definitively absent. */
    fun readSnapshot(session: String, generation: Long): ByteArray? = guarded { snapshots.read(session, generation) }

    fun writeSnapshot(session: String, generation: Long, sealed: ByteArray) = guarded { snapshots.write(session, generation, sealed) }

    /** Deletes every snapshot file not in [keep]: only ever ones no active or staged manifest names. */
    fun sweepSnapshots(keep: Set<Pair<String, Long>>) = guarded {
        for (file in snapshots.list()) if (file !in keep) snapshots.delete(file.first, file.second)
    }

    // ---- the marker ----

    fun marker(): Marker? = guarded { markerStore.read()?.let(Marker::decode) }
    fun writeMarker(marker: Marker) = guarded { markerStore.write(marker.encode()) }
    fun deleteMarker() = guarded { markerStore.delete() }

    /** Switches the marker to fenced, keeping its subject; an earlier fence's reason stands. */
    fun fenceMarker(reason: String) {
        val current = try { marker() } catch (_: MlsVaultUnavailableException) { null }
        if (current?.fenced == true) return
        writeMarker(current?.copy(state = Marker.State.Fenced, reason = reason) ?: Marker(Marker.State.Fenced, reason, null, null, null))
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

    fun seal(file: PersonaFile, recordId: ByteArray, plain: ByteArray): ByteArray = seal(innerAad(file, recordId), plain)

    /** Opens an inner object. A missing key or failed tag is [SealLostException]. */
    fun open(file: PersonaFile, recordId: ByteArray, sealed: ByteArray): ByteArray = open(innerAad(file, recordId), sealed)

    /** Seals a session snapshot, bound to the persona, installation, session and generation (§4.3 step 2). */
    fun sealSnapshot(file: PersonaFile, session: String, generation: Long, plain: ByteArray): ByteArray =
        seal(snapshotAad(file, session, generation), plain)

    /** Opens a session snapshot. A missing key or failed tag is [SealLostException]. */
    fun openSnapshot(file: PersonaFile, session: String, generation: Long, sealed: ByteArray): ByteArray =
        open(snapshotAad(file, session, generation), sealed)

    private fun seal(aad: ByteArray, plain: ByteArray): ByteArray = guarded {
        val key = stores.innerKeys.get(innerAlias) ?: throw SealKeyMissingException("The persona's inner key is unavailable")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        require(cipher.iv.size == 12)
        cipher.updateAAD(aad)
        byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)
    }

    private fun open(aad: ByteArray, sealed: ByteArray): ByteArray {
        try {
            require(sealed.size >= 29 && sealed[0] == 1.toByte())
            val key = stores.innerKeys.get(innerAlias) ?: throw SealKeyMissingException("The persona's inner key is unavailable")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed.copyOfRange(1, 13)))
            cipher.updateAAD(aad)
            return cipher.doFinal(sealed, 13, sealed.size - 13)
        } catch (error: Exception) {
            if (definitive(error)) throw SealLostException(error)
            throw MlsVaultUnavailableException(error)
        }
    }

    // The record AAD keeps format 1's number: records sealed before format 2 still open, and their bytes never change.
    private fun innerAad(file: PersonaFile, recordId: ByteArray): ByteArray =
        "$INNER_AAD|1|$persona|${file.installation.toHex()}|${recordId.toHex()}".toByteArray(Charsets.US_ASCII)

    private fun snapshotAad(file: PersonaFile, session: String, generation: Long): ByteArray {
        require(SESSION_HEX.matches(session) && generation > 0)
        return "$SESSION_AAD|1|$persona|${file.installation.toHex()}|$session|$generation".toByteArray(Charsets.US_ASCII)
    }

    private inline fun <T> guarded(block: () -> T): T = try { block() } catch (error: Exception) {
        if (error is MlsVaultUnavailableException) throw error
        throw MlsVaultUnavailableException(error)
    }

    companion object {
        private const val INNER_AAD = "kithmoot.mls-vault.v1|coord-object"
        private const val SESSION_AAD = "kithmoot.mls-vault.v1|coord-session"
        /** A session id as every map and file name holds it: 32 bytes in lowercase hex. */
        val SESSION_HEX = Regex("[0-9a-f]{64}")

        /** Fence only on definitive evidence; a transient Keystore or provider failure stays unavailable. */
        fun definitive(error: Throwable): Boolean {
            var cause: Throwable? = error
            var depth = 0
            while (cause != null && depth++ < 8) {
                // A key invalidated by the platform never comes back: as definitive as an absent alias.
                if (cause is SealKeyMissingException || cause is AEADBadTagException || cause is KeyPermanentlyInvalidatedException) return true
                cause = cause.cause
            }
            return false
        }

        fun random32(random: SecureRandom): ByteArray = ByteArray(32).also(random::nextBytes)
    }
}

/** An inner object's seal is definitively lost: the persona is fenced (`missing-seal-key`). */
internal class SealLostException(cause: Exception) : Exception("A sealed persona object can no longer be opened", cause)
