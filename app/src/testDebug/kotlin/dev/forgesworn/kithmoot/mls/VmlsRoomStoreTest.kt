package dev.forgesworn.kithmoot.mls

import dev.forgesworn.kithmoot.storage.MemoryStorage
import dev.forgesworn.kithmoot.storage.RoomStorageException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The VMLS room store (P3-03b-3 decision 21) and its box routes (decision 16). */
class VmlsRoomStoreTest {
    private val persona = "aa".repeat(32)
    private val box = "cc".repeat(32)
    private val leaf = "11".repeat(32)
    private val room = VmlsRoom(persona, "bb".repeat(32), "Kitchen", box, VmlsRole.KEEPER, joined = true)

    @Test fun `a room keeps its stored fields and never its members, epoch or commit flags`() {
        val storage = MemoryStorage()
        val stored = room.copy(
            stop = RoomStop.Recovery("Gap"), invite = "dd".repeat(32),
            grace = mapOf(leaf to 1_900_000_000L), removing = setOf(leaf),
            members = mapOf(leaf to VmlsRoomMember(leaf, "33".repeat(32), "44".repeat(32), true)),
            epoch = 9, sending = true, retrying = true, checking = true,
        )
        VmlsRoomStore(storage).put(stored)
        val read = VmlsRoomStore(storage).room(persona, room.session)!!
        assertEquals(stored.copy(members = emptyMap(), epoch = null, sending = false, retrying = false, checking = false), read)
        // Plaintext never reaches the store.
        assertTrue("Kitchen" in storage.value!!.decodeToString() && "33".repeat(32) !in storage.value!!.decodeToString())
    }

    @Test fun `every stop is read back`() {
        val storage = MemoryStorage()
        for (stop in listOf(RoomStop.Recovery("Fork"), RoomStop.Unknown("NewEvent"), RoomStop.KeyCompromise, RoomStop.Removed, RoomStop.JoinLapsed)) {
            VmlsRoomStore(storage).put(room.copy(stop = stop))
            assertEquals(stop, VmlsRoomStore(storage).room(persona, room.session)!!.stop)
        }
    }

    @Test fun `a room is replaced by persona and session, and forgotten`() {
        val store = VmlsRoomStore(MemoryStorage())
        store.put(room)
        store.put(room.copy(name = "Garden"))
        store.put(room.copy(session = "ee".repeat(32)))
        assertEquals(2, store.rooms().size)
        assertEquals("Garden", store.room(persona, room.session)!!.name)
        store.forget(persona, room.session)
        assertNull(store.room(persona, room.session))
        assertEquals(1, store.rooms().size)
    }

    @Test fun `one route per persona per box, kept while a room uses it`() {
        val store = VmlsRoomStore(MemoryStorage())
        store.putRoute(VmlsBoxRoute(persona, box, "route-1", "Home box"))
        store.putRoute(VmlsBoxRoute(persona, box, "route-2", "Home box"))
        assertEquals("route-2", store.route(persona, box)!!.routeId)
        assertEquals(1, store.routes().size)
        store.put(room)
        assertFailsWith<IllegalArgumentException> { store.forgetRoute(persona, box) }
        store.forget(persona, room.session)
        store.forgetRoute(persona, box)
        assertNull(store.route(persona, box))
        assertFailsWith<IllegalArgumentException> { VmlsBoxRoute(persona, box, "bad route", "Home box") }
    }

    @Test fun `a damaged or foreign store is refused, not read past`() {
        for (bad in listOf("{", """{"version":"2","rooms":[],"routes":[]}""",
            """{"version":"1","rooms":[{"persona":"${persona}","session":"${room.session}","name":"K","box":"$box","role":"GUEST","joined":true,"grace":{"$leaf":1},"removing":[]}],"routes":[]}""",
            """{"version":"1","rooms":[{"persona":"${persona}","session":"${room.session}","name":"K","box":"$box","role":"KEEPER","joined":true,"stop":"other","grace":{},"removing":[]}],"routes":[]}""")) {
            val storage = MemoryStorage().apply { value = bad.encodeToByteArray() }
            assertFailsWith<RoomStorageException> { VmlsRoomStore(storage).rooms() }
        }
        val storage = MemoryStorage().apply { failWrites = true }
        assertFailsWith<RoomStorageException> { VmlsRoomStore(storage).put(room) }
    }
}
