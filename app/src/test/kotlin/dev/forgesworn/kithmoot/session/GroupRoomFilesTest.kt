package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.GrantReceiver
import dev.forgesworn.kithmoot.protocol.buildGrant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** One open room's grants, and when it asks its keeper for them (S5b). */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupRoomFilesTest {
    private val room = "2848bdd5c60a79e2dfe667ae9616c1107f61709032b66e556e28f4a96e69450b"
    private val authoritySk = ByteArray(32) { 6 }
    private val authority = Schnorr.publicKeyHex(authoritySk)
    private val deviceSk = ByteArray(32) { 4 }
    private val device = Schnorr.publicKeyHex(deviceSk)
    private var saved = 0
    private val store = object : GroupRoomFiles.GrantStore {
        override fun load() = GrantReceiver(authority, room, device)
        override fun save(receiver: GrantReceiver): Boolean { saved++; return true }
    }

    @Test fun `with no grant at a reachable node it asks the keeper, at most once in ten minutes`() = runTest {
        var asks = 0
        var ms = 0L
        val files = GroupRoomFiles(room, deviceSk, store, backgroundScope, nodes = { listOf("https://grove.example.net") },
            askKeeper = { asks++; true }, say = {}, now = { 1_800_000_000 }, nowMs = { ms }, io = StandardTestDispatcher(testScheduler))
        files.checkGrantsSoon(delayMs = 1_000); advanceTimeBy(1_001); runCurrent()
        assertEquals(1, asks)
        files.ask(); runCurrent()
        assertEquals(1, asks)
        ms += STORAGE_GRANT_ASK_MS
        files.ask(); runCurrent()
        assertEquals(2, asks)
    }

    @Test fun `a room whose nodes this app cannot reach never asks`() = runTest {
        var asks = 0
        val files = GroupRoomFiles(room, deviceSk, store, backgroundScope, nodes = { listOf("http://127.0.0.1:8080") },
            askKeeper = { asks++; true }, say = {}, now = { 1_800_000_000 }, io = StandardTestDispatcher(testScheduler))
        files.checkGrantsSoon(delayMs = 1_000); advanceTimeBy(1_001); runCurrent()
        assertEquals(0, asks)
    }

    @Test fun `a grant for this device is kept, saved and wakes waiting files, and one for another device is not`() = runTest {
        val files = GroupRoomFiles(room, deviceSk, store, backgroundScope, nodes = { listOf("https://grove.example.net") },
            askKeeper = { true }, say = {}, now = { 1_800_000_000 }, io = StandardTestDispatcher(testScheduler))
        val before = files.wake.value
        val mine = buildGrant(authoritySk, room, device, "grove.example.net", listOf("get"), null, "ab".repeat(16), 1_800_000_000, 1_800_086_400)
        assertTrue(files.offerGrant(mine.toJson()))
        assertEquals(before + 1, files.wake.value)
        advanceTimeBy(300); runCurrent()
        assertEquals(1, saved)
        val theirs = buildGrant(authoritySk, room, "cd".repeat(32), "grove.example.net", listOf("get"), null, "ef".repeat(16), 1_800_000_000, 1_800_086_400)
        assertFalse(files.offerGrant(theirs.toJson()))
        files.close(keep = true)
        assertEquals(2, saved)
    }
}
