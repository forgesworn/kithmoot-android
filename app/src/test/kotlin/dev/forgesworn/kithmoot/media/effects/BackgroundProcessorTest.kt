package dev.forgesworn.kithmoot.media.effects

import java.util.ArrayDeque
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/**
 * The guarantee, tested.
 *
 * Two things are being pinned down here and they pull in opposite directions.
 * **Off has to be free**: a person who never turns a background on must not pay
 * a copy, a bitmap or a thread hop for the existence of this feature, so the
 * frame that reaches libwebrtc's sink has to be the very object the capturer
 * handed over. **On has to be safe**: with a background chosen, there is no
 * condition under which the camera's own picture reaches the sink - not while
 * the model is loading, not while the compositor is behind, not while the app
 * is in somebody's pocket.
 *
 * None of this needs a device. `VideoFrame` is an ordinary Java object over a
 * `Buffer` interface, so a fake buffer is enough to put a frame through the
 * processor and see what came out the other side.
 */
class BackgroundProcessorTest {

    /** A frame buffer that counts its own references and refuses any work. */
    private class FakeBuffer(private val w: Int = 1280, private val h: Int = 720) : VideoFrame.Buffer {
        var references = 1
            private set
        override fun getWidth(): Int = w
        override fun getHeight(): Int = h
        override fun toI420(): VideoFrame.I420Buffer = throw UnsupportedOperationException("not on a JVM")
        override fun retain() { references += 1 }
        override fun release() { references -= 1 }
        override fun cropAndScale(x: Int, y: Int, cw: Int, ch: Int, sw: Int, sh: Int): VideoFrame.Buffer =
            throw UnsupportedOperationException("not on a JVM")
    }

    private fun frame(): VideoFrame = VideoFrame(FakeBuffer(), 90, 1L)

    private class Sink : VideoSink {
        val frames = mutableListOf<VideoFrame>()
        override fun onFrame(frame: VideoFrame) { frames += frame }
    }

    private class Composer(private val answer: () -> VideoFrame?) : FrameComposer {
        var calls = 0
            private set
        var paused = 0
            private set
        var closed = 0
            private set
        val choices = mutableListOf<BackgroundChoice>()
        val facings = mutableListOf<Boolean>()
        override fun compose(frame: VideoFrame, choice: BackgroundChoice, frontFacing: Boolean): VideoFrame? {
            calls += 1
            choices += choice
            facings += frontFacing
            return answer()
        }
        override fun pause() { paused += 1 }
        override fun close() { closed += 1 }
    }

    /** Runs submitted work only when told to, so "the worker is still busy" is
     *  a state a test can hold the processor in. */
    private class ManualExecutor : Executor {
        private val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { queue.add(command) }
        fun drain(): Int {
            var ran = 0
            while (queue.isNotEmpty()) { queue.poll()?.run(); ran += 1 }
            return ran
        }
        val pending: Int get() = queue.size
    }

    private val immediate = Executor { it.run() }

    // --- the rule ------------------------------------------------------------

    @Test
    fun `nothing chosen is the only road to pass-through`() {
        for (capturing in listOf(true, false)) {
            for (visible in listOf(true, false)) {
                for (busy in listOf(true, false)) {
                    assertEquals(
                        FrameRoute.PASS_THROUGH,
                        routeFor(chosen = false, capturing = capturing, visible = visible, busy = busy),
                        "off must pass through whatever else is going on",
                    )
                }
            }
        }
    }

    @Test
    fun `with a background chosen nothing ever passes through`() {
        for (capturing in listOf(true, false)) {
            for (visible in listOf(true, false)) {
                for (busy in listOf(true, false)) {
                    val route = routeFor(chosen = true, capturing = capturing, visible = visible, busy = busy)
                    assertTrue(
                        route != FrameRoute.PASS_THROUGH,
                        "capturing=$capturing visible=$visible busy=$busy let the room out",
                    )
                }
            }
        }
    }

    @Test
    fun `a chosen background composes when everything is well and drops when it is not`() {
        assertEquals(FrameRoute.COMPOSE, routeFor(chosen = true, capturing = true, visible = true, busy = false))
        assertEquals(FrameRoute.DROP, routeFor(chosen = true, capturing = false, visible = true, busy = false))
        assertEquals(FrameRoute.DROP, routeFor(chosen = true, capturing = true, visible = false, busy = false))
        assertEquals(FrameRoute.DROP, routeFor(chosen = true, capturing = true, visible = true, busy = true))
    }

    // --- off is free ---------------------------------------------------------

    @Test
    fun `off forwards the very same frame, with no copy and no compositor`() {
        val composer = Composer { null }
        val sink = Sink()
        val worker = ManualExecutor()
        val processor = BackgroundProcessor(composer, worker)
        processor.setSink(sink)
        processor.onCapturerStarted(true)

        val one = frame()
        processor.onFrameCaptured(one)

        assertSame(one, sink.frames.single(), "the frame handed on must be the object we were given")
        assertEquals(0, composer.calls)
        assertEquals(0, worker.pending, "off must not even touch the worker thread")
        assertEquals(1, (one.buffer as FakeBuffer).references, "off must not retain the frame")
    }

    @Test
    fun `off keeps passing through while the app is away and the camera is stopped`() {
        val composer = Composer { null }
        val sink = Sink()
        val processor = BackgroundProcessor(composer, immediate)
        processor.setSink(sink)
        processor.onCapturerStarted(true)
        processor.setVisible(false)

        val one = frame()
        processor.onFrameCaptured(one)
        assertSame(one, sink.frames.single())
        assertEquals(0, composer.calls)
    }

    @Test
    fun `no sink yet means nothing goes anywhere`() {
        val composer = Composer { null }
        val processor = BackgroundProcessor(composer, immediate)
        processor.onCapturerStarted(true)
        processor.onFrameCaptured(frame())
        assertEquals(0, composer.calls)
    }

    // --- on is safe ----------------------------------------------------------

    @Test
    fun `a chosen background sends the composited frame and not the camera's`() {
        val made = frame()
        val composer = Composer { made }
        val sink = Sink()
        val processor = BackgroundProcessor(composer, immediate)
        processor.setSink(sink)
        processor.onCapturerStarted(true)
        processor.setChoice(BackgroundChoice(SeaScene.LAGOON, fish = true))

        val one = frame()
        processor.onFrameCaptured(one)

        assertSame(made, sink.frames.single())
        assertEquals(1, composer.calls)
        assertEquals(SeaScene.LAGOON, composer.choices.single().scene)
        // Retained for the worker, released when it finished.
        assertEquals(1, (one.buffer as FakeBuffer).references)
    }

    @Test
    fun `a compositor that cannot compose publishes nothing at all`() {
        val composer = Composer { null }
        val sink = Sink()
        val processor = BackgroundProcessor(composer, immediate)
        processor.setSink(sink)
        processor.onCapturerStarted(true)
        processor.setChoice(BackgroundChoice(SeaScene.DEEP))

        processor.onFrameCaptured(frame())

        assertTrue(sink.frames.isEmpty(), "a failed composite must never fall back to the room")
    }

    @Test
    fun `a compositor that throws publishes nothing at all`() {
        val composer = Composer { throw IllegalStateException("no GPU today") }
        val sink = Sink()
        val processor = BackgroundProcessor(composer, immediate)
        processor.setSink(sink)
        processor.onCapturerStarted(true)
        processor.setChoice(BackgroundChoice(SeaScene.SAND))

        val one = frame()
        processor.onFrameCaptured(one)

        assertTrue(sink.frames.isEmpty())
        assertEquals(1, (one.buffer as FakeBuffer).references, "a throwing composite must still let the frame go")
    }

    @Test
    fun `the person is told after a run of failures, not after the first`() {
        val said = mutableListOf<String>()
        val composer = Composer { null }
        val processor = BackgroundProcessor(composer, immediate, onTrouble = { said += it })
        processor.setSink(Sink())
        processor.onCapturerStarted(true)
        processor.setChoice(BackgroundChoice(SeaScene.CORAL))

        repeat(4) { processor.onFrameCaptured(frame()) }
        assertTrue(said.isEmpty(), "a hiccup is not worth a warning")
        processor.onFrameCaptured(frame())
        assertEquals(1, said.size)
        processor.onFrameCaptured(frame())
        assertEquals(1, said.size, "it should be said once, not once a frame")
    }

    @Test
    fun `the app going away stops the model and publishes nothing`() {
        val composer = Composer { frame() }
        val sink = Sink()
        val processor = BackgroundProcessor(composer, immediate)
        processor.setSink(sink)
        processor.onCapturerStarted(true)
        processor.setChoice(BackgroundChoice(SeaScene.LAGOON))

        processor.setVisible(false)
        assertEquals(1, composer.paused, "the model should have been let go")
        processor.onFrameCaptured(frame())
        assertTrue(sink.frames.isEmpty())

        processor.setVisible(true)
        processor.onFrameCaptured(frame())
        assertEquals(1, sink.frames.size, "coming back should start composing again")
    }

    @Test
    fun `a stopped capturer publishes nothing and lets the model go`() {
        val composer = Composer { frame() }
        val sink = Sink()
        val processor = BackgroundProcessor(composer, immediate)
        processor.setSink(sink)
        processor.onCapturerStarted(true)
        processor.setChoice(BackgroundChoice(SeaScene.LAGOON))
        processor.onCapturerStopped()

        processor.onFrameCaptured(frame())
        assertTrue(sink.frames.isEmpty())
        assertTrue(composer.paused >= 1)
    }

    // --- falling behind ------------------------------------------------------

    @Test
    fun `a frame that arrives while the last one is still going is dropped, not queued`() {
        val composer = Composer { frame() }
        val sink = Sink()
        val worker = ManualExecutor()
        val processor = BackgroundProcessor(composer, worker)
        processor.setSink(sink)
        processor.onCapturerStarted(true)
        processor.setChoice(BackgroundChoice(SeaScene.LAGOON))
        worker.drain()

        val first = frame()
        processor.onFrameCaptured(first)
        assertEquals(1, worker.pending)

        // Ten more arrive before the worker has got to the first.
        val late = List(10) { frame() }
        for (one in late) processor.onFrameCaptured(one)
        assertEquals(1, worker.pending, "the queue must not grow")
        for (one in late) assertEquals(1, (one.buffer as FakeBuffer).references, "a dropped frame must not be retained")

        worker.drain()
        assertEquals(1, composer.calls)
        assertEquals(1, sink.frames.size)

        // And the next one after that is composited, rather than the processor
        // being stuck thinking it is busy.
        processor.onFrameCaptured(frame())
        worker.drain()
        assertEquals(2, composer.calls)
    }

    // --- the mirror and the choice reach the compositor -----------------------

    @Test
    fun `which way the camera faces is handed to the compositor`() {
        val composer = Composer { frame() }
        val processor = BackgroundProcessor(composer, immediate)
        processor.setSink(Sink())
        processor.onCapturerStarted(true)
        processor.setChoice(BackgroundChoice(SeaScene.LAGOON))

        processor.onFrameCaptured(frame())
        processor.setFrontFacing(false)
        processor.onFrameCaptured(frame())

        assertEquals(listOf(true, false), composer.facings)
    }

    @Test
    fun `turning it off lets the model go and turns the next frame into a pass-through`() {
        val composer = Composer { frame() }
        val sink = Sink()
        val processor = BackgroundProcessor(composer, immediate)
        processor.setSink(sink)
        processor.onCapturerStarted(true)
        processor.setChoice(BackgroundChoice(SeaScene.LAGOON))
        processor.onFrameCaptured(frame())
        assertEquals(1, composer.calls)

        processor.setChoice(BackgroundChoice(scene = null))
        assertEquals(1, composer.paused)
        val after = frame()
        processor.onFrameCaptured(after)
        assertSame(after, sink.frames.last())
        assertEquals(1, composer.calls)
    }

    @Test
    fun `closing gives the compositor back`() {
        val composer = Composer { frame() }
        val processor = BackgroundProcessor(composer, immediate)
        processor.setSink(Sink())
        processor.close()
        assertEquals(1, composer.closed)
        processor.onFrameCaptured(frame())
        assertEquals(0, composer.calls)
    }

    // --- what is remembered --------------------------------------------------

    @Test
    fun `the choice survives a round trip through the store`() {
        val store = FakeBackgroundStore()
        val preference = BackgroundPreference(store)

        assertEquals(BackgroundChoice(null, fish = true), preference.load())

        preference.save(BackgroundChoice(SeaScene.CORAL, fish = false))
        assertEquals(BackgroundChoice(SeaScene.CORAL, fish = false), preference.load())

        preference.save(BackgroundChoice(null, fish = true))
        assertNull(preference.load().scene)
        assertTrue(preference.load().fish)
    }

    @Test
    fun `a scene this version does not know leaves the background off`() {
        val store = FakeBackgroundStore()
        store.strings["background:scene"] = "KELP_FOREST"
        assertNull(BackgroundPreference(store).load().scene)
    }

    @Test
    fun `off is the default, so a camera is never quietly handed to a model`() {
        assertEquals(false, BackgroundChoice().on)
        assertNull(BackgroundChoice().scene)
    }

    @Test
    fun `every scene has a bundled picture and a label`() {
        for (scene in SeaScene.entries) {
            assertTrue(scene.asset.startsWith("backgrounds/"), scene.asset)
            assertTrue(scene.asset.endsWith(".webp"), scene.asset)
            assertTrue(scene.label.isNotBlank())
        }
        assertEquals(
            listOf("Lagoon", "Coral garden", "Deep blue", "White sand"),
            SeaScene.entries.map { it.label },
            "the web client's wording, in the web client's order",
        )
        assertEquals(6, FISH_SPRITES.size)
    }

    private class FakeBackgroundStore : BackgroundStore {
        val strings = mutableMapOf<String, String>()
        val flags = mutableMapOf<String, Boolean>()
        override fun getString(key: String, default: String?): String? = strings[key] ?: default
        override fun getBoolean(key: String, default: Boolean): Boolean = flags[key] ?: default
        override fun putString(key: String, value: String) { strings[key] = value }
        override fun putBoolean(key: String, value: Boolean) { flags[key] = value }
        override fun remove(key: String) { strings.remove(key); flags.remove(key) }
    }
}
