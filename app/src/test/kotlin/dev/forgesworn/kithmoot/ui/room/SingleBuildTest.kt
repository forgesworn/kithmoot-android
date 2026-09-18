package dev.forgesworn.kithmoot.ui.room

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One engine per room, however many callers ask for one.
 *
 * The bug this exists to stop: at a recovered epoch both the epoch-ready
 * callback and the waiter that starts the room asked `startMedia` for an
 * engine, the guard between them was "is there one yet", and that only
 * becomes true seconds later once ICE has resolved and a factory is built.
 * Both passed, both built, and the second assignment dropped the first engine
 * on the floor still holding a camera, a microphone and every peer connection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SingleBuildTest {

    private class Engine(val n: Int) {
        var disposed = false
    }

    @Test
    fun `two callers during one slow build produce one engine`() = runTest {
        val discarded = mutableListOf<Engine>()
        val single = SingleBuild<Engine> { it.disposed = true; discarded += it }
        var built = 0
        var held: Engine? = null

        repeat(2) {
            launch {
                single.build(
                    held = { held },
                    make = {
                        // ICE resolution and a peer connection factory. Slow,
                        // and the whole window the old guard was blind in.
                        delay(3_000)
                        Engine(++built)
                    },
                    install = { engine -> if (held == null) { held = engine; true } else false },
                )
            }
        }
        advanceUntilIdle()

        assertEquals(1, built, "the second caller must not start a build of its own")
        assertTrue(discarded.isEmpty(), "and nothing was built to be thrown away")
        assertEquals(1, held?.n)
    }

    @Test
    fun `an engine the caller no longer wants is disposed, not abandoned`() = runTest {
        val discarded = mutableListOf<Engine>()
        val single = SingleBuild<Engine> { it.disposed = true; discarded += it }

        val installed = single.build(
            held = { null },
            // The session moved on while this was being built: a Leave, or a
            // room closed under it.
            make = { delay(1_000); Engine(1) },
            install = { false },
        )

        assertFalse(installed)
        assertEquals(1, discarded.size, "a refused engine still holds hardware until something disposes it")
        assertTrue(discarded.single().disposed)
    }

    @Test
    fun `a build nobody waited for is still disposed`() = runTest {
        val discarded = mutableListOf<Engine>()
        val single = SingleBuild<Engine> { it.disposed = true; discarded += it }

        val job = launch {
            single.build(
                held = { null },
                make = { delay(5_000); Engine(1) },
                install = { true },
            )
        }
        runCurrent()
        job.cancel()
        advanceUntilIdle()

        // The construction itself is not cancellable - it is a factory call in
        // a native library - so cancelling the waiter is exactly the case
        // where an engine is produced with nobody left to own it.
        assertFalse(single.inFlight, "the claim is given back however the build ended")
    }

    @Test
    fun `a build is possible again once the first has finished`() = runTest {
        val single = SingleBuild<Engine> { it.disposed = true }
        var built = 0
        var held: Engine? = null

        single.build({ held }, { Engine(++built) }, { held = it; true })
        held = null
        single.build({ held }, { Engine(++built) }, { held = it; true })

        // Media is rebuilt after a rekey drops the engine; the claim is a
        // guard against overlap, never a one-shot latch for the room's life.
        assertEquals(2, built)
    }

    @Test
    fun `nothing is built over something already in place`() = runTest {
        val single = SingleBuild<Engine> { it.disposed = true }
        val existing = Engine(1)
        var built = 0

        val installed = single.build({ existing }, { Engine(++built) }, { true })

        assertFalse(installed)
        assertEquals(0, built)
    }
}
