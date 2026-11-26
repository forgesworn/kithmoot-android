package dev.forgesworn.kithmoot.board

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The board's exports between the board and the system's save screen: each is kept or refused in words, never left waiting. */
class RoomBoardSavesTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val written = CopyOnWriteArrayList<Pair<String, String>>()
    private val discarded = CopyOnWriteArrayList<String>()
    private var writes = true
    private val saves = RoomBoardSaves<String>(scope,
        write = { place, bytes -> if (writes) { written += place to String(bytes); true } else false },
        discard = { place -> discarded += place })

    @AfterTest fun stop() = scope.cancel()

    private fun waiting() = runBlocking { withTimeout(5_000) { while (saves.pending.value == null) kotlinx.coroutines.delay(5) }; saves.pending.value!! }

    @Test fun `waits for the person, then writes the export where they chose and says it is kept`() = runBlocking {
        val keeping = scope.async { saves.keep("girnel-board.json", "{\"cards\":[]}".toByteArray()) }
        assertEquals("girnel-board.json", waiting().name)
        saves.chosen("content://downloads/7")
        assertNull(withTimeout(5_000) { keeping.await() })
        assertEquals(listOf("content://downloads/7" to "{\"cards\":[]}"), written.toList())
        assertNull(saves.pending.value)
    }

    @Test fun `says so when the person chose nowhere, the file could not be written, or there is no save screen`() = runBlocking {
        val nowhere = scope.async { saves.keep("a.json", "{}".toByteArray()) }
        waiting(); saves.chosen(null)
        assertEquals(RoomBoardSaves.NOT_CHOSEN, withTimeout(5_000) { nowhere.await() })

        writes = false
        val unwritable = scope.async { saves.keep("b.json", "{}".toByteArray()) }
        waiting(); saves.chosen("content://downloads/8")
        assertEquals(RoomBoardSaves.NOT_WRITTEN, withTimeout(5_000) { unwritable.await() })
        assertEquals(listOf("content://downloads/8"), discarded.toList(), "the empty file the save screen made is taken away")

        val noScreen = scope.async { saves.keep("c.json", "{}".toByteArray()) }
        waiting(); saves.unavailable()
        assertEquals(RoomBoardSaves.NO_SCREEN, withTimeout(5_000) { noScreen.await() })
    }

    @Test fun `keeps one export waiting at a time, and answers the one waiting when the board goes`() = runBlocking {
        val first = scope.async { saves.keep("a.json", "{}".toByteArray()) }
        waiting()
        assertEquals("Another export is waiting to be saved.", saves.keep("b.json", "{}".toByteArray()))
        saves.drop()
        assertEquals(RoomBoardSaves.DROPPED, withTimeout(5_000) { first.await() })
        assertNull(saves.pending.value)
    }

    @Test fun `takes away a place chosen for an export that has gone, and writes nothing twice`() = runBlocking {
        saves.chosen("content://downloads/9")
        withTimeout(5_000) { while (discarded.isEmpty()) kotlinx.coroutines.delay(5) }
        assertEquals(listOf("content://downloads/9"), discarded.toList())

        val keeping = scope.async { saves.keep("a.json", "{}".toByteArray()) }
        waiting()
        saves.chosen("content://downloads/10")
        saves.chosen("content://downloads/11")
        assertNull(withTimeout(5_000) { keeping.await() })
        withTimeout(5_000) { while (discarded.size < 2) kotlinx.coroutines.delay(5) }
        assertEquals(listOf("content://downloads/10"), written.map { it.first })
        assertTrue("content://downloads/11" in discarded)
    }

    @Test fun `forgets an export whose board stopped waiting for it`() = runBlocking {
        val keeping = scope.async { saves.keep("a.json", "{}".toByteArray()) }
        waiting()
        keeping.cancel()
        withTimeout(5_000) { while (saves.pending.value != null) kotlinx.coroutines.delay(5) }
        assertNull(saves.pending.value)
    }
}
