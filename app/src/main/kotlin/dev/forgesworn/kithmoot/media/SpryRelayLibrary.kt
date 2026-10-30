package dev.forgesworn.kithmoot.media

import dev.forgesworn.spry.SpryRelayNative

/**
 * Loads the built-in call relay's library (stage A3 of kithmoot
 * docs/design/2026-10-28-built-in-relay.md) and checks it speaks the ABI this
 * app was written for. Nothing here starts a relay; the foreground service
 * (A4) will, through [SpryRelayNative], only once [load] says [Status.Ready].
 * A missing or mismatched library is reported, never thrown, so a phone
 * without it simply cannot host.
 */
object SpryRelayLibrary {
    /** The library ABI this app's JNI class matches (spry-turn 0.4.0). */
    const val ABI_VERSION = 1

    sealed interface Status {
        data object Ready : Status
        data class WrongAbi(val found: Int) : Status
        data object Missing : Status
    }

    @Volatile private var status: Status? = null

    /** Loads the library once and reports whether it can be used. */
    fun load(): Status = status ?: synchronized(this) {
        status ?: probe { SpryRelayNative.abiVersion() }.also { status = it }
    }

    /** What [load] decides from the library's answer, apart from the loading. */
    internal fun probe(abiVersion: () -> Int): Status = try {
        val found = abiVersion()
        if (found == ABI_VERSION) Status.Ready else Status.WrongAbi(found)
    } catch (_: UnsatisfiedLinkError) {
        Status.Missing
    } catch (_: ExceptionInInitializerError) {
        Status.Missing
    } catch (_: NoClassDefFoundError) {
        // The class failed to initialise earlier in this process.
        Status.Missing
    }
}
