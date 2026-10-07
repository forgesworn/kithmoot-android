package dev.forgesworn.kithmoot.account

import dev.forgesworn.vmls.ffi.VmlsCoordEntry
import dev.forgesworn.vmls.ffi.VmlsCoordinator
import dev.forgesworn.vmls.ffi.VmlsDecision
import dev.forgesworn.vmls.ffi.VmlsException
import dev.forgesworn.vmls.ffi.VmlsPlatform
import dev.forgesworn.vmls.ffi.VmlsPromotion
import dev.forgesworn.vmls.ffi.VmlsRandom
import dev.forgesworn.vmls.ffi.VmlsSessionMark
import dev.forgesworn.vmls.ffi.VmlsStaged
import dev.forgesworn.vmls.ffi.VmlsWitnessAnswer
import dev.forgesworn.vmls.ffi.coordinatorGenesis
import dev.forgesworn.vmls.ffi.coordinatorObjectHash
import dev.forgesworn.vmls.ffi.openCoordinator
import java.security.SecureRandom

/**
 * [VaultWitness] over the VMLS engine's coordinator (vmls-ffi). The native library loads on first use, never when this object is made,
 * so JVM unit tests that compile the app's sources never need it.
 */
class EngineVaultWitness(private val random: SecureRandom = SecureRandom()) : VaultWitness {
    /** The coordinator only uses the platform's CSPRNG; the two keys are placeholders it never reads. */
    private val platform: VmlsPlatform by lazy {
        engine { VmlsPlatform(ByteArray(32), ByteArray(32), Random(random)) }
    }

    override fun objectHash(sealed: ByteArray): ByteArray = engine { coordinatorObjectHash(sealed) }

    override fun genesis(subject: ByteArray, installation: ByteArray, witnessKey: ByteArray, active: List<CoordEntry>): CoordGenesis =
        engine { coordinatorGenesis(subject, installation, witnessKey, active.map(::entry)).let { CoordGenesis(it.state, it.digest) } }

    override fun open(state: ByteArray, active: List<CoordEntry>, staged: List<CoordEntry>?): WitnessCoordinator =
        engine { EngineCoordinator(openCoordinator(platform, state, active.map(::entry), staged?.map(::entry))) }

    private class Random(private val random: SecureRandom) : VmlsRandom {
        override fun fill(len: UInt): ByteArray = ByteArray(len.toInt()).also(random::nextBytes)
    }
}

private class EngineStaged(val inner: VmlsStaged) : StagedCandidate {
    override val state: ByteArray = engine { inner.state() }
    override fun close() = inner.close()
}

private class EnginePromotion(val inner: VmlsPromotion) : PromotionCandidate {
    override val state: ByteArray = engine { inner.state() }
    override fun close() = inner.close()
}

private class EngineCoordinator(private val inner: VmlsCoordinator) : WitnessCoordinator {
    override fun state(): ByteArray = engine { inner.state() }
    override fun fenced(): String? = engine { inner.fenced() }
    override fun confirmed(): Boolean = engine { inner.confirmed() }
    override fun refused(): Boolean = engine { inner.refused() }
    override fun retiring(): Boolean = engine { inner.retiring() }
    override fun read(): ByteArray = engine { inner.read() }
    override fun onRead(answer: WitnessAnswer): WitnessDecision = engine { decision(inner.onRead(answer(answer))) }
    override fun stage(candidate: List<CoordEntry>): StagedCandidate = engine { EngineStaged(inner.stage(candidate.map(::entry))) }
    override fun staged(staged: StagedCandidate): ByteArray = engine { inner.staged((staged as EngineStaged).inner) }
    override fun resend(): ByteArray = engine { inner.resend() }
    override fun onAdvance(answer: WitnessAnswer): WitnessDecision = engine { decision(inner.onAdvance(answer(answer))) }
    override fun promote(): PromotionCandidate = engine { EnginePromotion(inner.promote()) }
    override fun promoted(promotion: PromotionCandidate): List<SessionMark> =
        engine { inner.promoted((promotion as EnginePromotion).inner).map(::mark) }
    override fun sessionMarks(): List<SessionMark> = engine { inner.sessionMarks().map(::mark) }
    override fun installationReplaced() = engine { inner.installationReplaced() }
    override fun retiringRead(): ByteArray? = engine { inner.retiringRead() }
    override fun onRetiringRead(answer: WitnessAnswer): WitnessDecision = engine { decision(inner.onRetiringRead(answer(answer))) }
    override fun retiringAdvance(): ByteArray? = engine { inner.retiringAdvance() }
    override fun onRetiring(answer: WitnessAnswer): WitnessDecision = engine { decision(inner.onRetiring(answer(answer))) }
    override fun close() = inner.close()
}

private fun entry(entry: CoordEntry): VmlsCoordEntry = when (entry) {
    is CoordEntry.Vault -> VmlsCoordEntry.Vault(entry.record.copyOf(), entry.sealedHash.copyOf())
    is CoordEntry.Session -> {
        require(entry.generation >= 0)
        VmlsCoordEntry.Session(entry.session.copyOf(), entry.generation.toULong(), entry.snapshotHash.copyOf())
    }
}

private fun mark(mark: VmlsSessionMark): SessionMark {
    // The engine's digest holds a generation as a signed 64-bit integer, so a larger one never reaches here.
    check(mark.generation <= Long.MAX_VALUE.toULong())
    return SessionMark(mark.session, mark.generation.toLong())
}

private fun answer(answer: WitnessAnswer): VmlsWitnessAnswer = when (answer) {
    is WitnessAnswer.Receipt -> VmlsWitnessAnswer.Receipt(answer.bytes.copyOf())
    WitnessAnswer.Unavailable -> VmlsWitnessAnswer.Unavailable
    WitnessAnswer.Refused -> VmlsWitnessAnswer.Refused
}

private fun decision(decision: VmlsDecision): WitnessDecision = when (decision) {
    VmlsDecision.Active -> WitnessDecision.Active
    VmlsDecision.Held -> WitnessDecision.Held
    VmlsDecision.Resend -> WitnessDecision.Resend
    is VmlsDecision.Promote -> WitnessDecision.Promote(decision.candidateDigest, decision.predecessorDigest)
    is VmlsDecision.Fenced -> WitnessDecision.Fenced(decision.reason)
    VmlsDecision.RetireDue -> WitnessDecision.RetireDue
    is VmlsDecision.Retired -> WitnessDecision.Retired(decision.dutyEnded)
}

/** The engine's refusals, as the vault's own exception: a stable code, never a native type. */
private inline fun <T> engine(call: () -> T): T = try {
    call()
} catch (error: VmlsException.Engine) {
    throw WitnessCoordinatorException(error.code, error)
} catch (error: VmlsException.Boundary) {
    throw WitnessCoordinatorException(error.code, error)
}
