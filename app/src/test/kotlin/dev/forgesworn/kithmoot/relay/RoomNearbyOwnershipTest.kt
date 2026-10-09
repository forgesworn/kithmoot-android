package dev.forgesworn.kithmoot.relay

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.util.UUID
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RoomNearbyOwnershipTest {
    private val config = RoomBleConfig("11".repeat(32), "22".repeat(32), UUID.fromString("446a88bc-5745-4a81-b2ec-10f7241afdae"))
    private class Radio(val order: MutableList<String>, val name: String, val failClose: Boolean) : RoomBleRadio {
        override fun start(config: RoomBleConfig) { order += "start $name" }
        override fun offer(bytes: ByteArray, to: String?) = 0
        override fun close() { order += "close $name"; if (failClose) error("teardown failed") }
    }
    private fun TestScope.factory(order: MutableList<String>, name: String, fail: Boolean = false): () -> NativeRoomMeshLink = {
        order += "construct $name"
        NativeRoomMeshLink({ Radio(order, name, fail) }, StandardTestDispatcher(testScheduler))
    }
    @Test fun `second owner waits for actual first teardown before construction`() = runTest {
        val ownership = RoomNearbyOwnership(); val order = mutableListOf<String>()
        val first = ownership.open(config, factory(order, "first"))
        val waiting = async { ownership.open(config, factory(order, "second")) }
        runCurrent(); assertFalse(waiting.isCompleted)
        assertEquals(listOf("construct first", "start first"), order)
        first.close(); first.close(); first.awaitClosed()
        val second = waiting.await()
        assertEquals(listOf("construct first", "start first", "close first", "construct second", "start second"), order)
        second.close(); second.awaitClosed()
    }
    @Test fun `failed physical teardown refuses every later owner without constructing a radio`() = runTest {
        val ownership = RoomNearbyOwnership(); val order = mutableListOf<String>()
        val first = ownership.open(config, factory(order, "first", true))
        first.close(); assertFailsWith<IllegalStateException> { first.awaitClosed() }
        repeat(2) { assertFailsWith<IllegalStateException> { ownership.open(config, factory(order, "forbidden")) } }
        assertFalse(order.any { "forbidden" in it })
    }
    @Test fun `cancelling a waiting entry leaves current owner intact and releases no foreign lease`() = runTest {
        val ownership = RoomNearbyOwnership(); val order = mutableListOf<String>()
        val first = ownership.open(config, factory(order, "first"))
        val waiting = async { ownership.open(config, factory(order, "cancelled")) }
        runCurrent(); waiting.cancelAndJoin()
        assertEquals(listOf("construct first", "start first"), order)
        first.close(); first.awaitClosed()
        val second = ownership.open(config, factory(order, "next"))
        second.close(); second.awaitClosed()
        assertFalse(order.any { "cancelled" in it })
    }
}
