package dev.forgesworn.kithmoot.media

import org.junit.Assert.assertEquals
import org.junit.Test

class SpryRelayLibraryTest {
    @Test fun `the library this app was written for is ready`() {
        assertEquals(SpryRelayLibrary.Status.Ready, SpryRelayLibrary.probe { SpryRelayLibrary.ABI_VERSION })
    }

    @Test fun `a library speaking another ABI is reported, not used`() {
        assertEquals(SpryRelayLibrary.Status.WrongAbi(2), SpryRelayLibrary.probe { 2 })
    }

    @Test fun `a missing or broken library is reported, never thrown`() {
        assertEquals(SpryRelayLibrary.Status.Missing, SpryRelayLibrary.probe { throw UnsatisfiedLinkError("no spry_relay") })
        assertEquals(SpryRelayLibrary.Status.Missing, SpryRelayLibrary.probe { throw ExceptionInInitializerError("init") })
        assertEquals(SpryRelayLibrary.Status.Missing, SpryRelayLibrary.probe { throw NoClassDefFoundError("SpryRelayNative") })
    }
}
