package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.storage.SealKeyMissingException
import dev.forgesworn.kithmoot.storage.SealKeys
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.ProviderException
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/*
 * JVM stand-ins for the restore-witness coordinator, the witness and the
 * Android stores. The coordinator is a line-by-line Kotlin model of vennel's
 * `vmls_mls::coordinator` (decisions, fences, the retiring duty), with
 * receipts "signed" by a hash of the pinned key: the real engine and real
 * Ed25519 receipts are exercised by the instrumented `CoordinatedVaultEngineTest`.
 */

private fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run { parts.forEach(::update); digest() }

/** The fake receipt layout matches the real one: version, status, subject, seq, digest, challenge, signature. */
internal object FakeReceipts {
    fun signedBytes(status: Int, subject: ByteArray, seq: Long, digest: ByteArray, challenge: ByteArray): ByteArray =
        ByteBuffer.allocate(106).put(1).put(status.toByte()).put(subject).putLong(seq).put(digest).put(challenge).array()

    fun sign(key: ByteArray, signed: ByteArray): ByteArray = signed + sha256(key, signed) + sha256(signed, key)

    class Receipt(val status: Int, val subject: ByteArray, val seq: Long, val digest: ByteArray, val challenge: ByteArray)

    fun verified(bytes: ByteArray, key: ByteArray, challenge: ByteArray): Receipt? {
        if (bytes.size != 170 || bytes[0] != 1.toByte()) return null
        val signed = bytes.copyOfRange(0, 106)
        if (!sign(key, signed).contentEquals(bytes)) return null
        val buffer = ByteBuffer.wrap(bytes)
        buffer.get(); val status = buffer.get().toInt()
        val subject = ByteArray(32).also(buffer::get)
        val seq = buffer.getLong()
        val digest = ByteArray(32).also(buffer::get)
        val got = ByteArray(32).also(buffer::get)
        if (!got.contentEquals(challenge) || status !in 0..2) return null
        return Receipt(status, subject, seq, digest, got)
    }
}

/** The fake coordinator's requests: `1 | subject | challenge` and `2 | subject | seq | expected | next | challenge`. */
internal object FakeRequests {
    fun read(subject: ByteArray, challenge: ByteArray) = byteArrayOf(1) + subject + challenge
    fun advance(subject: ByteArray, seq: Long, expected: ByteArray, next: ByteArray, challenge: ByteArray) =
        ByteBuffer.allocate(1 + 32 + 8 + 32 + 32 + 32).put(2).put(subject).putLong(seq).put(expected).put(next).put(challenge).array()
}

/** A [VaultWitness] over the Kotlin model of the core. */
internal class FakeVaultWitness(private val random: SecureRandom = SecureRandom()) : VaultWitness {
    val opened = mutableListOf<FakeCoordinator>()

    override fun objectHash(sealed: ByteArray): ByteArray = sha256("object".toByteArray(), sealed)

    override fun genesis(subject: ByteArray, installation: ByteArray, witnessKey: ByteArray, active: List<CoordEntry>): CoordGenesis {
        val entries = active.map(::pair)
        val digest = digest(subject, installation, entries)
        val state = FakeState(subject, installation, witnessKey, 0, digest, entries, ByteArray(0), null, null, null, refused = false, replaced = false)
        return CoordGenesis(state.encode(), digest)
    }

    override fun open(state: ByteArray, active: List<CoordEntry>, staged: List<CoordEntry>?): WitnessCoordinator =
        FakeCoordinator.open(FakeState.decode(state), active.map(::pair), staged?.map(::pair), random).also { opened += it }

    companion object {
        fun pair(entry: CoordEntry): Pair<String, String> = when (entry) {
            is CoordEntry.Vault -> entry.record.toHex() to entry.sealedHash.toHex()
        }

        fun digest(subject: ByteArray, installation: ByteArray, entries: List<Pair<String, String>>): ByteArray {
            require(entries.map { it.first }.toSet().size == entries.size) { "duplicate object" }
            val canonical = entries.sortedBy { it.first }.joinToString(";") { "${it.first}=${it.second}" }
            return sha256("manifest".toByteArray(), subject, installation, canonical.toByteArray())
        }
    }
}

internal class FakePending(val predecessorSeq: Long, val predecessorDigest: ByteArray, val candidateDigest: ByteArray, val entries: List<Pair<String, String>>)

internal data class FakeState(
    val subject: ByteArray,
    val installation: ByteArray,
    val witness: ByteArray,
    val activeSeq: Long,
    val activeDigest: ByteArray,
    val activeEntries: List<Pair<String, String>>,
    val activeReceipt: ByteArray,
    val pending: FakePending?,
    val fence: String?,
    val retiring: Pair<Long, ByteArray>?,
    val refused: Boolean,
    val replaced: Boolean,
) {
    fun encode(): ByteArray = buildJsonObject {
        put("subject", subject.toHex()); put("installation", installation.toHex()); put("witness", witness.toHex())
        put("active_seq", activeSeq); put("active_digest", activeDigest.toHex()); put("active_entries", entries(activeEntries))
        put("active_receipt", activeReceipt.toHex())
        put("pending", pending?.let { p ->
            buildJsonObject {
                put("seq", p.predecessorSeq); put("pred", p.predecessorDigest.toHex()); put("cand", p.candidateDigest.toHex())
                put("entries", entries(p.entries))
            }
        } ?: JsonNull)
        put("fence", fence?.let(::JsonPrimitive) ?: JsonNull)
        put("retiring", retiring?.let { buildJsonArray { add(JsonPrimitive(it.first)); add(JsonPrimitive(it.second.toHex())) } } ?: JsonNull)
        put("refused", refused); put("replaced", replaced)
    }.toString().toByteArray()

    companion object {
        private fun entries(list: List<Pair<String, String>>) = buildJsonArray { list.forEach { add(buildJsonArray { add(JsonPrimitive(it.first)); add(JsonPrimitive(it.second)) }) } }
        private fun entries(json: JsonArray) = json.map { it.jsonArray[0].jsonPrimitive.content to it.jsonArray[1].jsonPrimitive.content }
        private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        fun decode(bytes: ByteArray): FakeState {
            val j = Json.parseToJsonElement(String(bytes)).jsonObject
            fun t(n: String) = j.getValue(n).jsonPrimitive.content
            return FakeState(
                hex(t("subject")), hex(t("installation")), hex(t("witness")), j.getValue("active_seq").jsonPrimitive.long, hex(t("active_digest")),
                entries(j.getValue("active_entries").jsonArray), hex(t("active_receipt")),
                (j["pending"] as? JsonObject)?.let { p ->
                    FakePending(p.getValue("seq").jsonPrimitive.long, hex(p.getValue("pred").jsonPrimitive.content), hex(p.getValue("cand").jsonPrimitive.content), entries(p.getValue("entries").jsonArray))
                },
                (j["fence"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                (j["retiring"] as? JsonArray)?.let { it[0].jsonPrimitive.long to hex(it[1].jsonPrimitive.content) },
                j.getValue("refused").jsonPrimitive.content == "true", j.getValue("replaced").jsonPrimitive.content == "true",
            )
        }
    }
}

private enum class Asked { Read, Advance, RetiringRead, Retiring }

private class FakeStaged(override val state: ByteArray, val next: FakeState, val challenge: ByteArray) : StagedCandidate { override fun close() {} }
private class FakePromotion(override val state: ByteArray, val next: FakeState) : PromotionCandidate { override fun close() {} }

/** A Kotlin model of `vmls_mls::coordinator::Coordinator`. */
internal class FakeCoordinator private constructor(private var state: FakeState, private val random: SecureRandom) : WitnessCoordinator {
    private var confirmed = false
    private var outstanding: Pair<Asked, ByteArray>? = null
    private var promotable: FakeReceipts.Receipt? = null
    private var retireDue = false
    var closed = false

    companion object {
        fun open(state: FakeState, active: List<Pair<String, String>>, staged: List<Pair<String, String>>?, random: SecureRandom): FakeCoordinator {
            val c = FakeCoordinator(state, random)
            if (state.fence != null) return c
            val local = runCatching { FakeVaultWitness.digest(state.subject, state.installation, active) }.getOrNull()
            if (local == null || !local.contentEquals(state.activeDigest) || active.toSet() != state.activeEntries.toSet()) {
                c.fence("local-state-mismatch"); return c
            }
            state.pending?.let { p ->
                val intact = staged != null &&
                    runCatching { FakeVaultWitness.digest(state.subject, state.installation, staged) }.getOrNull()?.contentEquals(p.candidateDigest) == true &&
                    staged.toSet() == p.entries.toSet()
                if (!intact || p.predecessorSeq != state.activeSeq || !p.predecessorDigest.contentEquals(state.activeDigest)) c.fence("stage-corrupt")
            }
            return c
        }
    }

    override fun state(): ByteArray = state.encode()
    override fun fenced(): String? = state.fence
    override fun confirmed(): Boolean = confirmed && state.fence == null
    override fun refused(): Boolean = state.refused
    override fun retiring(): Boolean = state.retiring != null
    override fun close() { closed = true }

    private fun challenge() = ByteArray(32).also(random::nextBytes)
    private fun unfenced() { if (state.fence != null) throw WitnessCoordinatorException("CoordinatorFenced") }

    override fun read(): ByteArray {
        unfenced()
        val ch = challenge()
        outstanding = Asked.Read to ch; promotable = null; retireDue = false
        return FakeRequests.read(state.subject, ch)
    }

    override fun onRead(answer: WitnessAnswer): WitnessDecision {
        val ch = answering(Asked.Read)
        val r = verified(answer, ch) ?: run { confirmed = false; return WitnessDecision.Held }
        when (r.status) {
            2 -> return if (r.seq < state.activeSeq) fenceBehind() else fence("witness-retired")
            1 -> { confirmed = false; return WitnessDecision.Held }
        }
        if (r.seq < state.activeSeq) return fenceBehind()
        if (r.seq == state.activeSeq && r.digest.contentEquals(state.activeDigest)) {
            if (state.pending != null) return WitnessDecision.Resend
            confirmed = true
            return WitnessDecision.Active
        }
        val p = state.pending
        if (p != null && r.seq == state.activeSeq + 1 && r.digest.contentEquals(p.candidateDigest)) return promotion(r)
        return fence("witness-mismatch")
    }

    override fun stage(candidate: List<CoordEntry>): StagedCandidate {
        unfenced()
        if (state.pending != null) throw WitnessCoordinatorException("CandidatePending")
        if (!confirmed) throw WitnessCoordinatorException("NotConfirmed")
        if (state.activeSeq >= MAX_SEQ) { fence("sequence-exhausted"); throw WitnessCoordinatorException("SequenceExhausted") }
        val entries = candidate.map(FakeVaultWitness::pair)
        val digest = FakeVaultWitness.digest(state.subject, state.installation, entries)
        if (digest.contentEquals(state.activeDigest)) throw WitnessCoordinatorException("Unchanged")
        val next = state.copy(pending = FakePending(state.activeSeq, state.activeDigest, digest, entries))
        return FakeStaged(next.encode(), next, challenge())
    }

    override fun staged(staged: StagedCandidate): ByteArray {
        unfenced()
        val s = staged as FakeStaged
        val p = s.next.pending ?: throw WitnessCoordinatorException("Stale")
        if (state.pending != null || s.next.copy(pending = null).encode().contentEquals(state.encode()).not()) throw WitnessCoordinatorException("Stale")
        state = s.next
        outstanding = Asked.Advance to s.challenge; promotable = null; confirmed = false
        return FakeRequests.advance(state.subject, p.predecessorSeq, p.predecessorDigest, p.candidateDigest, s.challenge)
    }

    override fun resend(): ByteArray {
        unfenced()
        val p = state.pending ?: throw WitnessCoordinatorException("NoCandidate")
        val ch = challenge()
        outstanding = Asked.Advance to ch; promotable = null
        return FakeRequests.advance(state.subject, p.predecessorSeq, p.predecessorDigest, p.candidateDigest, ch)
    }

    override fun onAdvance(answer: WitnessAnswer): WitnessDecision {
        val ch = answering(Asked.Advance)
        val p = state.pending ?: throw WitnessCoordinatorException("NoCandidate")
        val r = verified(answer, ch) ?: run { confirmed = false; return WitnessDecision.Held }
        return when {
            r.status == 2 && r.seq < p.predecessorSeq -> fenceBehind()
            r.status == 2 -> fence("witness-retired")
            r.status == 1 -> fence("witness-conflict")
            r.seq == p.predecessorSeq + 1 && r.digest.contentEquals(p.candidateDigest) -> promotion(r)
            else -> fence("witness-mismatch")
        }
    }

    override fun promote(): PromotionCandidate {
        unfenced()
        val p = state.pending ?: throw WitnessCoordinatorException("NoCandidate")
        val r = promotable?.takeIf { it.seq == p.predecessorSeq + 1 && it.digest.contentEquals(p.candidateDigest) } ?: throw WitnessCoordinatorException("NotPromotable")
        val next = state.copy(pending = null, activeSeq = r.seq, activeDigest = p.candidateDigest, activeEntries = p.entries, activeReceipt = r.challenge)
        return FakePromotion(next.encode(), next)
    }

    override fun promoted(promotion: PromotionCandidate) {
        unfenced()
        val expected = promote() as FakePromotion
        if (!expected.state.contentEquals(promotion.state)) throw WitnessCoordinatorException("Stale")
        state = expected.next; promotable = null; confirmed = true
    }

    override fun installationReplaced() { state = state.copy(replaced = true) }

    override fun retiringRead(): ByteArray? {
        if (state.retiring == null) return null
        val ch = challenge()
        outstanding = Asked.RetiringRead to ch; promotable = null; retireDue = false
        return FakeRequests.read(state.subject, ch)
    }

    override fun onRetiringRead(answer: WitnessAnswer): WitnessDecision {
        val ch = answering(Asked.RetiringRead)
        val duty = state.retiring ?: return WitnessDecision.Held
        val r = verified(answer, ch)
        return when {
            r != null && r.status == 2 -> retired()
            r != null && r.status == 0 && r.seq < duty.first -> { retireDue = true; WitnessDecision.RetireDue }
            else -> WitnessDecision.Held
        }
    }

    override fun retiringAdvance(): ByteArray? {
        val duty = state.retiring?.takeIf { retireDue } ?: return null
        retireDue = false
        val ch = challenge()
        outstanding = Asked.Retiring to ch; promotable = null
        return FakeRequests.advance(state.subject, duty.first, duty.second, ByteArray(32), ch)
    }

    override fun onRetiring(answer: WitnessAnswer): WitnessDecision {
        val ch = answering(Asked.Retiring)
        val r = verified(answer, ch)
        return if (r != null && r.status == 2) retired() else WitnessDecision.Held
    }

    private fun answering(asked: Asked): ByteArray {
        val o = outstanding
        if (o == null || o.first != asked) throw WitnessCoordinatorException("NoRequest")
        outstanding = null
        return o.second
    }

    private fun verified(answer: WitnessAnswer, challenge: ByteArray): FakeReceipts.Receipt? {
        val bytes = when (answer) {
            is WitnessAnswer.Receipt -> answer.bytes
            WitnessAnswer.Refused -> { state = state.copy(refused = true); return null }
            WitnessAnswer.Unavailable -> return null
        }
        val r = FakeReceipts.verified(bytes, state.witness, challenge) ?: return null
        if (!r.subject.contentEquals(state.subject)) return null
        state = state.copy(refused = false)
        return r
    }

    private fun promotion(r: FakeReceipts.Receipt): WitnessDecision {
        val p = state.pending!!
        promotable = r
        return WitnessDecision.Promote(p.candidateDigest, p.predecessorDigest)
    }

    private fun retired(): WitnessDecision {
        val ended = state.replaced
        if (ended) state = state.copy(retiring = null)
        return WitnessDecision.Retired(ended)
    }

    private fun fence(reason: String): WitnessDecision {
        confirmed = false; outstanding = null; promotable = null
        if (state.fence == null) state = state.copy(fence = reason)
        return WitnessDecision.Fenced(state.fence!!)
    }

    private fun fenceBehind(): WitnessDecision {
        if (state.fence == null) state = state.copy(retiring = state.activeSeq to state.activeDigest)
        return fence("witness-behind")
    }

    private val MAX_SEQ = 9_007_199_254_740_991L
}

/** The witness: bothy's `read` and `advance` semantics, answered with fake-signed receipts. */
internal class FakeWitnessServer(val key: ByteArray) {
    enum class Mode { Up, Down, Refuse, LoseAnswer, WrongKey }

    class Subject(var seq: Long, var digest: ByteArray, var retired: Boolean = false)

    val subjects = mutableMapOf<String, Subject>()
    @Volatile var mode = Mode.Up
    var requests = 0
    var advances = 0
    /** Runs inside each request, before it is answered (to observe or hold the lock). */
    var during: suspend () -> Unit = {}

    fun enrol(subject: String, digest: String) {
        subjects[subject] = Subject(0, ByteArray(digest.length / 2) { digest.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
    }

    val channel = object : WitnessChannel {
        override suspend fun read(request: ByteArray): WitnessAnswer = answer(request)
        override suspend fun advance(request: ByteArray): WitnessAnswer = answer(request)
    }

    private suspend fun answer(request: ByteArray): WitnessAnswer {
        requests++
        during()
        if (mode == Mode.Down) return WitnessAnswer.Unavailable
        val buffer = ByteBuffer.wrap(request)
        val kind = buffer.get().toInt()
        val subject = ByteArray(32).also(buffer::get)
        val sub = subjects[subject.toHex()]
        if (mode == Mode.Refuse || sub == null) return WitnessAnswer.Refused
        val status: Int
        if (kind == 1) {
            status = if (sub.retired) 2 else 0
        } else {
            advances++
            val expectedSeq = buffer.getLong()
            val expected = ByteArray(32).also(buffer::get)
            val next = ByteArray(32).also(buffer::get)
            status = when {
                sub.retired -> 2
                expectedSeq > sub.seq -> { sub.retired = true; 2 }
                sub.seq == expectedSeq + 1 && sub.digest.contentEquals(next) -> 0
                sub.seq != expectedSeq || !sub.digest.contentEquals(expected) -> 1
                else -> { sub.seq = expectedSeq + 1; sub.digest = next; 0 }
            }
        }
        val challenge = ByteArray(32).also { buffer.position(request.size - 32); buffer.get(it) }
        val signer = if (mode == Mode.WrongKey) ByteArray(32) { 7 } else key
        val receipt = FakeReceipts.sign(signer, FakeReceipts.signedBytes(status, subject, sub.seq, sub.digest, challenge))
        if (mode == Mode.LoseAnswer) return WitnessAnswer.Unavailable
        return WitnessAnswer.Receipt(receipt)
    }
}

/**
 * The coordinated vault's stores in memory. Outer files behave like the
 * Android ones: a value opens only with its AAD and with the live key it was
 * sealed under, so restoring older bytes is a missing seal key. [copy] clones
 * the whole profile, keys included.
 */
internal class MemoryCoordinatedStores private constructor(
    override val lockName: String,
    private val values: MutableMap<String, Sealed>,
    private val liveKeys: MutableMap<String, String>,
    private val markers: MutableMap<String, ByteArray>,
    private val keys: MutableMap<String, SecretKey>,
) : CoordinatedVaultStores {
    constructor() : this("coord-test-" + UUID.randomUUID(), mutableMapOf(), mutableMapOf(), mutableMapOf(), mutableMapOf())

    class Sealed(val aad: ByteArray, val value: ByteArray, val key: String)

    /** Every outer read fails as a transient Keystore failure would. */
    @Volatile var transient = false
    /** The next outer read of a coordinated file fails its tag. */
    @Volatile var badTag = false
    /** The next coordinated write fails, standing in for a kill before its commit. */
    @Volatile var failNextCoordinatedWrite = false
    /** Coordinated writes made so far. */
    var coordinatedWrites = 0
    private val locks = mutableMapOf<String, Mutex>()

    fun copy(): MemoryCoordinatedStores = synchronized(values) {
        MemoryCoordinatedStores(
            "coord-test-" + UUID.randomUUID(),
            values.toMutableMap(), liveKeys.toMutableMap(),
            markers.mapValues { it.value.copyOf() }.toMutableMap(), keys.toMutableMap(),
        )
    }

    fun names() = synchronized(values) { values.keys.toList() }
    fun aads() = synchronized(values) { values.values.map { String(it.aad, Charsets.US_ASCII) } }
    fun sealed(name: String): Sealed? = synchronized(values) { values[name] }
    fun restore(name: String, older: Sealed) = synchronized(values) { values[name] = older }
    fun remove(name: String) = synchronized(values) { values.remove(name); liveKeys.remove(name) }
    fun corrupt(name: String) = synchronized(values) {
        val v = values.getValue(name)
        values[name] = Sealed(v.aad, v.value.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }, "corrupt")
    }
    fun marker(): ByteArray? = synchronized(values) { markers.values.singleOrNull() }
    fun coordinatedName(): String = coordinatedNames().single()
    fun deleteInner() = synchronized(values) { keys.keys.filter { it.endsWith(".inner") }.forEach(keys::remove) }

    override fun open(name: String, aad: ByteArray): RoomStorage = storage(name, aad, coordinated = false)
    override fun coordinated(name: String, aad: ByteArray): RoomStorage = storage(name, aad, coordinated = true)

    private fun storage(name: String, aad: ByteArray, coordinated: Boolean) = object : RoomStorage {
        override fun read(): ByteArray? = synchronized(values) {
            if (transient) throw ProviderException("Keystore is busy")
            val sealed = values[name] ?: return null
            if (liveKeys[name] != sealed.key) throw SealKeyMissingException("The key is unavailable")
            if (coordinated && badTag) { badTag = false; throw IllegalStateException(AEADBadTagException("mac check failed")) }
            check(sealed.aad.contentEquals(aad)) { "AEAD tag mismatch" }
            if (!coordinated && sealed.value.isNotEmpty() && sealed.value.last() != TAG) throw IllegalStateException("corrupt")
            if (coordinated) sealed.value.copyOf() else sealed.value.copyOf(sealed.value.size - 1)
        }
        override fun write(value: ByteArray) = synchronized(values) {
            if (transient) throw ProviderException("Keystore is busy")
            if (coordinated && failNextCoordinatedWrite) { failNextCoordinatedWrite = false; throw java.io.IOException("killed before the commit") }
            val key = UUID.randomUUID().toString()
            values[name] = Sealed(aad.copyOf(), if (coordinated) value.copyOf() else value + TAG, key)
            liveKeys[name] = key
            if (coordinated) coordinatedWrites++
        }
        override fun reset() { synchronized(values) { values.remove(name); liveKeys.remove(name) } }
    }

    override fun marker(name: String): MarkerStore = object : MarkerStore {
        override fun read(): ByteArray? = synchronized(values) { markers[name]?.copyOf() }
        override fun write(value: ByteArray) = synchronized(values) { markers[name] = value.copyOf() }
        override fun delete() { synchronized(values) { markers.remove(name) } }
    }

    override val innerKeys: SealKeys = object : SealKeys {
        override fun get(alias: String): SecretKey? = synchronized(values) {
            if (transient) throw ProviderException("Keystore is busy")
            keys[alias]
        }
        override fun create(alias: String): SecretKey = synchronized(values) {
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { keys[alias] = it }
        }
        override fun delete(alias: String) { synchronized(values) { keys.remove(alias) } }
        override fun aliases(): List<String> = synchronized(values) { keys.keys.toList() }
    }

    override fun innerAlias(name: String): String = "$name.inner"

    override fun coordinatedNames(): List<String> = synchronized(values) {
        (values.keys.filter { it.startsWith("coord.") } + markers.keys).distinct().sorted()
    }

    override fun personaLock(name: String): PersonaLock {
        val mutex = synchronized(locks) { locks.getOrPut(name) { Mutex() } }
        return object : PersonaLock {
            override suspend fun <T> withLock(work: suspend () -> T): T = mutex.withLock { work() }
        }
    }

    private companion object { const val TAG: Byte = 0x5a }
}
