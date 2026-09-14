package dev.forgesworn.kithmoot.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** An in-memory [VolumeStore], so persistence can be tested on a plain JVM. */
private class FakeVolumeStore : VolumeStore {
    val values = mutableMapOf<String, Float>()
    override fun getFloat(key: String, default: Float): Float = values[key] ?: default
    override fun putFloat(key: String, value: Float) { values[key] = value }
    override fun remove(key: String) { values.remove(key) }
}

class CallVolumeTest {

    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)

    // --- mapping -------------------------------------------------------------

    @Test
    fun `percent and gain convert both ways`() {
        assertEquals(0.0f, CallVolume.percentToGain(0))
        assertEquals(1.0f, CallVolume.percentToGain(100))
        assertEquals(2.0f, CallVolume.percentToGain(200))
        assertEquals(1.5f, CallVolume.percentToGain(150))

        assertEquals(0, CallVolume.gainToPercent(0.0f))
        assertEquals(100, CallVolume.gainToPercent(1.0f))
        assertEquals(200, CallVolume.gainToPercent(2.0f))
        assertEquals(150, CallVolume.gainToPercent(1.5f))
    }

    @Test
    fun `gain is clamped to 0 to 2`() {
        assertEquals(CallVolume.MIN_GAIN, CallVolume.clamp(-1.0f))
        assertEquals(CallVolume.MAX_GAIN, CallVolume.clamp(5.0f))
        assertEquals(1.3f, CallVolume.clamp(1.3f))
    }

    @Test
    fun `percent outside 0 to 200 is clamped before converting`() {
        assertEquals(CallVolume.MIN_GAIN, CallVolume.percentToGain(-50))
        assertEquals(CallVolume.MAX_GAIN, CallVolume.percentToGain(500))
    }

    // --- persistence -----------------------------------------------------------

    @Test
    fun `an untouched participant reads the default gain`() {
        val volume = CallVolume(FakeVolumeStore())
        assertEquals(CallVolume.DEFAULT_GAIN, volume.gainFor(alice))
    }

    @Test
    fun `a set level round-trips`() {
        val store = FakeVolumeStore()
        val volume = CallVolume(store)

        volume.setGain(alice, 1.75f)

        assertEquals(1.75f, volume.gainFor(alice))
    }

    @Test
    fun `setting the default level clears storage instead of writing it`() {
        val store = FakeVolumeStore()
        val volume = CallVolume(store)
        volume.setGain(alice, 0.4f)
        assertTrue(store.values.isNotEmpty())

        volume.setGain(alice, CallVolume.DEFAULT_GAIN)

        assertTrue(store.values.isEmpty())
        assertEquals(CallVolume.DEFAULT_GAIN, volume.gainFor(alice))
    }

    @Test
    fun `each participant is independent`() {
        val volume = CallVolume(FakeVolumeStore())

        volume.setGain(alice, 0.0f)
        volume.setGain(bob, 2.0f)

        assertEquals(0.0f, volume.gainFor(alice))
        assertEquals(2.0f, volume.gainFor(bob))
    }

    @Test
    fun `a level out of range is clamped on the way in`() {
        val volume = CallVolume(FakeVolumeStore())

        volume.setGain(alice, 9.0f)

        assertEquals(CallVolume.MAX_GAIN, volume.gainFor(alice))
    }

    @Test
    fun `a stored value out of range is clamped on the way out`() {
        val store = FakeVolumeStore()
        store.putFloat(CallVolume.keyFor(alice), 50.0f)
        val volume = CallVolume(store)

        assertEquals(CallVolume.MAX_GAIN, volume.gainFor(alice))
    }

    @Test
    fun `forgetting a participant clears their level`() {
        val store = FakeVolumeStore()
        val volume = CallVolume(store)
        volume.setGain(alice, 0.3f)

        volume.forget(alice)

        assertEquals(CallVolume.DEFAULT_GAIN, volume.gainFor(alice))
        assertTrue(store.values.isEmpty())
    }

    @Test
    fun `forgetting one participant never touches another`() {
        val store = FakeVolumeStore()
        val volume = CallVolume(store)
        volume.setGain(alice, 0.3f)
        volume.setGain(bob, 1.6f)

        volume.forget(alice)

        assertEquals(CallVolume.DEFAULT_GAIN, volume.gainFor(alice))
        assertEquals(1.6f, volume.gainFor(bob))
    }

    @Test
    fun `a non-pubkey key is never read, written or forgotten`() {
        val store = FakeVolumeStore()
        val volume = CallVolume(store)

        volume.setGain("alice", 0.2f)

        assertTrue(store.values.isEmpty())
        assertEquals(CallVolume.DEFAULT_GAIN, volume.gainFor("alice"))
        volume.forget("alice")
        assertFalse(store.values.containsKey(CallVolume.keyFor("alice")))
    }

    @Test
    fun `keys for different participants never collide`() {
        assertTrue(CallVolume.keyFor(alice) != CallVolume.keyFor(bob))
        assertTrue(CallVolume.keyFor(alice).endsWith(alice))
    }
}
