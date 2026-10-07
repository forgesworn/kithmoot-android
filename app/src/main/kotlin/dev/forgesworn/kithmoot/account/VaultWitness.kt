package dev.forgesworn.kithmoot.account

/**
 * The restore-witness coordinator (vennel `vmls_mls::coordinator`, contract
 * §4.1-§4.5) as the MLS vault sees it, in Kotlin types only (P3-03b-2).
 *
 * Every build implements it over the VMLS engine (`EngineVaultWitness`), made
 * by [vaultWitness]. There is no unwitnessed mode.
 *
 * Every decision is the core's: this interface only carries bytes across.
 */
interface VaultWitness {
    /** `coordinator_object_hash` of a sealed object's exact bytes. */
    fun objectHash(sealed: ByteArray): ByteArray

    /** A new persona installation's genesis over [active], for the keeper's enrolment. */
    fun genesis(subject: ByteArray, installation: ByteArray, witnessKey: ByteArray, active: List<CoordEntry>): CoordGenesis

    /**
     * Opens the coordinator over its persisted [state] and the objects really
     * stored: [active], and [staged] when a candidate is staged.
     */
    fun open(state: ByteArray, active: List<CoordEntry>, staged: List<CoordEntry>?): WitnessCoordinator
}

/** One covered object, as the vault finds it in durable storage. */
sealed class CoordEntry {
    /** A sealed vault record: its short record id and the hash of its sealed bytes. */
    class Vault(val record: ByteArray, val sealedHash: ByteArray) : CoordEntry()

    /**
     * An MLS session (P3-03b-3a): its 32-byte id, the generation of its sealed
     * snapshot and the hash of that snapshot's sealed bytes.
     */
    class Session(val session: ByteArray, val generation: Long, val snapshotHash: ByteArray) : CoordEntry()
}

/** The generation a session's snapshot must be opened at: the one in the witnessed manifest (§4.3). */
class SessionMark(val session: ByteArray, val generation: Long)

class CoordGenesis(val state: ByteArray, val digest: ByteArray)

/** The witness's answer to one request. */
sealed class WitnessAnswer {
    class Receipt(val bytes: ByteArray) : WitnessAnswer() {
        override fun toString() = "Receipt(${bytes.size} bytes)"
    }
    /** Unreachable, timed out, or not a receipt. */
    data object Unavailable : WitnessAnswer()
    /** The witness's own marked `403`: unknown subject, or another writer's. */
    data object Refused : WitnessAnswer()
}

/** What the vault does next (`vmls_mls::coordinator::Decision`). */
sealed class WitnessDecision {
    data object Active : WitnessDecision()
    data object Held : WitnessDecision()
    data object Resend : WitnessDecision()
    class Promote(val candidateDigest: ByteArray, val predecessorDigest: ByteArray) : WitnessDecision()
    data class Fenced(val reason: String) : WitnessDecision()
    data object RetireDue : WitnessDecision()
    data class Retired(val dutyEnded: Boolean) : WitnessDecision()
}

/** A staged candidate: persist [state] with its objects, then pass it to [WitnessCoordinator.staged]. */
interface StagedCandidate : AutoCloseable {
    val state: ByteArray
}

/** A promotion: persist [state] with the promoted objects, then pass it to [WitnessCoordinator.promoted]. */
interface PromotionCandidate : AutoCloseable {
    val state: ByteArray
}

/**
 * One persona installation's coordinator, open under the persona lock. A call
 * the core refuses throws [WitnessCoordinatorException].
 */
interface WitnessCoordinator : AutoCloseable {
    fun state(): ByteArray
    fun fenced(): String?
    fun confirmed(): Boolean
    fun refused(): Boolean
    fun retiring(): Boolean
    fun read(): ByteArray
    fun onRead(answer: WitnessAnswer): WitnessDecision
    fun stage(candidate: List<CoordEntry>): StagedCandidate
    fun staged(staged: StagedCandidate): ByteArray
    fun resend(): ByteArray
    fun onAdvance(answer: WitnessAnswer): WitnessDecision
    fun promote(): PromotionCandidate
    /** The promotion is persisted: each session's witnessed generation, to `commit_ack` before anything is released. */
    fun promoted(promotion: PromotionCandidate): List<SessionMark>
    /** Each session's witnessed generation; refused unless the witness confirms the active state. */
    fun sessionMarks(): List<SessionMark>
    fun installationReplaced()
    fun retiringRead(): ByteArray?
    fun onRetiringRead(answer: WitnessAnswer): WitnessDecision
    fun retiringAdvance(): ByteArray?
    fun onRetiring(answer: WitnessAnswer): WitnessDecision
}

/** The core refused a call: its stable code (`Stale`, `CoordinatorFenced`, ...). */
class WitnessCoordinatorException(val code: String, cause: Throwable? = null) : Exception("The witness coordinator refused: $code", cause)

/** This build carries no witness engine: nothing covered may be written. */
class VaultWitnessUnavailableException : Exception("The restore-witness engine is not in this build")

/** Carries witness requests to the persona's pinned witness (see [WitnessLink]). */
interface WitnessChannel {
    suspend fun read(request: ByteArray): WitnessAnswer
    suspend fun advance(request: ByteArray): WitnessAnswer
}
