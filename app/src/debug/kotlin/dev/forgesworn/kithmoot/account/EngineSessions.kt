package dev.forgesworn.kithmoot.account

import dev.forgesworn.vmls.ffi.VmlsException
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
class EngineSession(val inner: VmlsSession) : HostedSession {
    override fun generation(): Long = sessionCall { inner.generation() }.let { check(it <= Long.MAX_VALUE.toULong()); it.toLong() }

    override fun commitAck(generation: Long, highWater: Long) = sessionCall { inner.commitAck(generation.toULong(), highWater.toULong()) }

    override fun close() = inner.close()
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
