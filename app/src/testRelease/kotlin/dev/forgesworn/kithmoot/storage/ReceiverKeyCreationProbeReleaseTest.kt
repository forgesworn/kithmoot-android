package dev.forgesworn.kithmoot.storage

import dev.forgesworn.kithmoot.BuildConfig
import org.junit.Assert.*
import org.junit.Test

class ReceiverKeyCreationProbeReleaseTest {
    @Test fun registration_is_disabled_in_the_actual_release_variant() {
        assertFalse(BuildConfig.DEBUG)
        for (phase in ReceiverKeyCreationProbe.Phase.entries) {
            val context = ReceiverKeyCreationProbe.Context(
                "committed-source-before-offer", "RECEIVER", "MISSING", phase)
            val error = assertThrows(IllegalStateException::class.java) {
                ReceiverKeyCreationProbe.open(context)
            }
            assertEquals("Receiver observation is debug-only", error.message)
            assertNull(ReceiverKeyCreationProbe.requested())
            ReceiverKeyCreationProbe.completed(null, ReceiverKeyCreationProbe.Outcome.CREATED)
            assertNull(ReceiverKeyCreationProbe.requested())
        }
    }

    @Test fun actual_seal_failure_keeps_its_original_exception_without_observation() {
        assertFalse(BuildConfig.DEBUG)
        assertNull(java.security.Security.getProvider("AndroidKeyStore"))
        assertNull(ReceiverKeyCreationProbe.requested())
        assertThrows(java.security.NoSuchProviderException::class.java) {
            AndroidKeyStoreSealKeys.create("kithmoot.epoch.v1.entry." + "0".repeat(32))
        }
        assertNull(ReceiverKeyCreationProbe.requested())
    }
}
