package dev.forgesworn.kithmoot.account

import kotlin.test.*

class EngineSnapshotFormatTest {
    private val magic = "VMLS/1 snapshot".toByteArray(Charsets.US_ASCII)
    @Test fun `Admin2 snapshot selects only Admin2 opener`() {
        assertEquals(EngineSnapshotFormat.Admin2, EngineSnapshotFormat.read(magic + byteArrayOf(5)))
        for (version in listOf(3, 4)) assertEquals(EngineSnapshotFormat.Invitation1, EngineSnapshotFormat.read(magic + byteArrayOf(version.toByte())))
        for (version in listOf(1, 2)) assertEquals(EngineSnapshotFormat.Legacy, EngineSnapshotFormat.read(magic + byteArrayOf(version.toByte())))
    }
    @Test fun `unknown versions truncated or wrong magic never fall back`() {
        for (version in listOf(0, 6, 255)) assertFailsWith<IllegalStateException> { EngineSnapshotFormat.read(magic + byteArrayOf(version.toByte())) }
        assertFailsWith<IllegalArgumentException> { EngineSnapshotFormat.read(magic) }
        assertFailsWith<IllegalArgumentException> { EngineSnapshotFormat.read("unknown snapshot".toByteArray() + byteArrayOf(5)) }
    }
}
