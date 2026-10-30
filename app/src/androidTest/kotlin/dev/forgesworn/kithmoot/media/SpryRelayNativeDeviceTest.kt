package dev.forgesworn.kithmoot.media

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.forgesworn.spry.SpryRelayNative
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bundled relay library loads on this device and its JNI entry points
 * answer as spry-turn documents them (docs/embedding.md). No relay is started
 * and nothing touches the network: the node is refused before it binds, as in
 * spry-turn's own C ABI test.
 */
@RunWith(AndroidJUnit4::class)
class SpryRelayNativeDeviceTest {
    @Test fun the_bundled_library_loads_and_speaks_the_expected_abi() {
        assertEquals(SpryRelayLibrary.Status.Ready, SpryRelayLibrary.load())
        assertEquals(SpryRelayLibrary.ABI_VERSION, SpryRelayNative.abiVersion())
    }

    @Test fun a_malformed_setting_starts_nothing() {
        assertEquals(0L, SpryRelayNative.start(arrayOf("--map-ports"), arrayOf("NOT_A_SETTING")))
        assertEquals(0, SpryRelayNative.free(0L))
    }

    @Test fun a_refused_setting_ends_the_node_with_a_stopped_line() {
        val handle = SpryRelayNative.start(arrayOf("--map-ports"), emptyArray())
        assertNotEquals(0L, handle)
        try {
            val line = SpryRelayNative.nextLine(handle, 5_000)
            val text = String(requireNotNull(line) { "no line within 5 s" }, Charsets.UTF_8)
            assertTrue(text, text.contains("\"event\":\"stopped\"") && text.contains("\"code\":1"))
            assertTrue(text, text.contains("SPRY_TURN_SECRET must contain at least 32 bytes"))
            val end = SpryRelayNative.nextLine(handle, 1_000)
            assertTrue("an empty array once stopped was read", end != null && end.isEmpty())
            assertEquals(1, SpryRelayNative.exitCode(handle))
            assertEquals(0, SpryRelayNative.stop(handle))
        } finally {
            assertEquals(0, SpryRelayNative.free(handle))
        }
    }
}
