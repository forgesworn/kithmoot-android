package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.mls.AckRule
import dev.forgesworn.kithmoot.mls.CapabilitiesParser
import dev.forgesworn.kithmoot.mls.Destination
import dev.forgesworn.kithmoot.mls.DriverSession
import dev.forgesworn.kithmoot.mls.Effects
import dev.forgesworn.kithmoot.mls.Outgoing
import dev.forgesworn.kithmoot.mls.Phase
import dev.forgesworn.kithmoot.mls.Processed
import dev.forgesworn.kithmoot.mls.SlotOutcome
import dev.forgesworn.kithmoot.mls.Watched
import dev.forgesworn.vmls.ffi.VmlsAck
import dev.forgesworn.vmls.ffi.VmlsBoxInstallation
import dev.forgesworn.vmls.ffi.VmlsDestination
import dev.forgesworn.vmls.ffi.VmlsEvent
import dev.forgesworn.vmls.ffi.VmlsException
import dev.forgesworn.vmls.ffi.VmlsPhase
import dev.forgesworn.vmls.ffi.VmlsSlotStatus
import dev.forgesworn.vmls.ffi.VmlsWatchKind
import dev.forgesworn.vmls.ffi.parseCapabilities
import dev.forgesworn.vmls.ffi.VmlsPlatform
import dev.forgesworn.vmls.ffi.VmlsRandom
import dev.forgesworn.vmls.ffi.VmlsSession
import dev.forgesworn.vmls.ffi.VmlsStep
import dev.forgesworn.vmls.ffi.openSession
import java.security.SecureRandom

/**
 * The engine's sessions for [SessionHost] (P3-03b-3a), debug builds only.
 * [deviceKey] is the enrolled device's x-only public key and [rendezvousKey]
 * the persona's x-only `rz`; neither secret half crosses.
 */
class EngineSessions(deviceKey: ByteArray, rendezvousKey: ByteArray, random: SecureRandom = SecureRandom()) : SessionOpener<EngineSession> {
    val platform: VmlsPlatform = sessionCall { VmlsPlatform(deviceKey.copyOf(), rendezvousKey.copyOf(), Random(random)) }

    override fun open(session: ByteArray, plaintext: ByteArray, highWater: Long): EngineSession {
        require(highWater > 0)
        // Passed as is, so no unwiped heap copy is left: the adapter wipes its own, the host wipes [plaintext].
        return EngineSession(sessionCall { openSession(platform, session, plaintext, highWater.toULong()) })
    }

    private class Random(private val random: SecureRandom) : VmlsRandom {
        override fun fill(len: UInt): ByteArray = ByteArray(len.toInt()).also(random::nextBytes)
    }
}

/** One engine session under the host. [inner] is for engine calls inside [SessionHost.step] only. */
class EngineSession(val inner: VmlsSession) : DriverSession {
    override fun generation(): Long = sessionCall { inner.generation() }.let { check(it <= Long.MAX_VALUE.toULong()); it.toLong() }

    override fun commitAck(generation: Long, highWater: Long) = sessionCall { inner.commitAck(generation.toULong(), highWater.toULong()) }

    override fun close() = inner.close()

    // ---- the driver's calls (P3-03b-3a) ----

    override fun phase(): Phase = when (val p = sessionCall { inner.phase() }) {
        VmlsPhase.PendingJoin -> Phase.PendingJoin
        VmlsPhase.Active -> Phase.Active
        is VmlsPhase.NeedsRecovery -> Phase.NeedsRecovery(p.reason)
        VmlsPhase.Removed -> Phase.Removed
        VmlsPhase.Expired -> Phase.Expired
    }

    override fun epoch(): Long? = try {
        inner.epoch().also { check(it <= Long.MAX_VALUE.toULong()) }.toLong()
    } catch (_: VmlsException.Engine) {
        // No group yet (a pending join).
        null
    }

    override fun outbox(): List<Outgoing> = sessionCall { inner.outbox() }.map { out ->
        Outgoing(out.recordId, out.mailbox, when (val d = out.destination) {
            is VmlsDestination.Leaf -> Destination.Leaf(d.homeBox)
            is VmlsDestination.CommitSlot -> Destination.Slot(d.homeBox, d.epoch.also { check(it <= Long.MAX_VALUE.toULong()) }.toLong(), d.attempt.toLong())
            is VmlsDestination.Welcome -> Destination.Welcome(d.packageId)
            is VmlsDestination.Introduction -> Destination.Introduction
            is VmlsDestination.ForkEvidence -> Destination.Evidence(d.homeBox)
        }, out.envelope)
    }

    override fun watchList(): List<Watched> = sessionCall { inner.watchList() }.map { w ->
        Watched(w.mailbox, when (val k = w.kind) {
            is VmlsWatchKind.OwnLeaf, is VmlsWatchKind.ForkEvidence -> Watched.Kind.Mailbox(retained = false)
            is VmlsWatchKind.RetainedLeaf -> Watched.Kind.Mailbox(retained = true)
            VmlsWatchKind.Welcome -> Watched.Kind.Welcome
            is VmlsWatchKind.CommitSlot -> Watched.Kind.Slot(k.attempt.toLong())
        }, w.homeBox)
    }

    override fun tick(now: Long) = effects { inner.tick(now.u()) }
    override fun delivered(recordIds: List<ByteArray>) = effects { inner.outboundDelivered(recordIds) }
    override fun depositResult(now: Long, attempt: Long, signedReceipt: ByteArray) = effects { inner.depositResult(now.u(), attempt.u32(), signedReceipt) }
    override fun slotStatus(now: Long, attempt: Long, outcome: SlotOutcome, signedReceipt: ByteArray) = effects {
        inner.slotStatus(now.u(), attempt.u32(), when (outcome) {
            SlotOutcome.Filled -> VmlsSlotStatus.FILLED
            SlotOutcome.Expired -> VmlsSlotStatus.EXPIRED
            SlotOutcome.Void -> VmlsSlotStatus.VOID
        }, signedReceipt)
    }
    override fun observeReceipt(now: Long, signedReceipt: ByteArray) = effects { inner.observeReceipt(now.u(), signedReceipt) }
    override fun observeInstallation(now: Long, installation: ByteArray) = effects { inner.observeInstallation(now.u(), installation) }
    override fun mailboxDrained(mailbox: ByteArray) = effects { inner.mailboxDrained(mailbox) }
    override fun confirmMember(packageId: ByteArray) = effects { inner.confirmMember(packageId) }

    override fun process(now: Long, mailbox: ByteArray, envelope: ByteArray, signedReceipt: ByteArray?, installation: Pair<ByteArray, ByteArray>?): EngineStep<Processed> {
        val processed = try {
            inner.process(now.u(), mailbox, envelope, signedReceipt, installation?.let { VmlsBoxInstallation(it.first, it.second) })
        } catch (refused: VmlsException.Engine) {
            // A refused call changes nothing: the record stays at the box.
            return EngineStep(null, Processed(Effects(refused = refused.code), AckRule.Keep))
        }
        val ack = when (processed.ack) {
            VmlsAck.Now -> AckRule.Now
            is VmlsAck.AfterCommitAck -> AckRule.AfterStep
            VmlsAck.Keep -> AckRule.Keep
        }
        val step = hostedStep(processed.step)
        return EngineStep(step.snapshot, Processed(effectsOf(processed.step), ack))
    }

    /** An engine refusal changes nothing (the engine restores its state), so it is answered, not thrown. Boundary faults still throw. */
    private inline fun effects(call: () -> VmlsStep): EngineStep<Effects> {
        val step = try { call() } catch (refused: VmlsException.Engine) { return EngineStep(null, Effects(refused = refused.code)) }
        return EngineStep(hostedStep(step).snapshot, effectsOf(step))
    }

    private fun effectsOf(step: VmlsStep) = Effects(
        events = step.events,
        unconfirmed = step.events.filterIsInstance<VmlsEvent.OrderingUnconfirmed>().map { it.slot to it.attempt.toLong() },
    )

    private fun Long.u(): ULong { require(this >= 0); return toULong() }
    private fun Long.u32(): UInt { require(this in 0..0xFFFF_FFFFL); return toUInt() }
}

/** The engine's strict reading of a capabilities body (`parse_capabilities`). */
object EngineCapabilities : CapabilitiesParser {
    override fun installation(body: ByteArray): ByteArray? = try {
        parseCapabilities(body)
    } catch (_: VmlsException) {
        null
    }
}

/** An engine step as the host takes it: its snapshot to seal, and the step itself for the caller. */
fun hostedStep(step: VmlsStep): EngineStep<VmlsStep> = EngineStep(
    step.snapshot?.let { StepSnapshot(it.session, it.generation.also { g -> check(g <= Long.MAX_VALUE.toULong()) }.toLong(), it.plaintext) },
    step,
)

/** The engine's refusal, as a stable code. */
class EngineSessionException(val code: String, cause: Throwable) : Exception("The MLS engine refused: $code", cause)

internal inline fun <T> sessionCall(call: () -> T): T = try {
    call()
} catch (error: VmlsException.Engine) {
    throw EngineSessionException(error.code, error)
} catch (error: VmlsException.Boundary) {
    throw EngineSessionException(error.code, error)
}
