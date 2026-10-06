package dev.forgesworn.kithmoot.mls

import dev.forgesworn.kithmoot.mls.VmlsConsentGate.Reason
import dev.forgesworn.kithmoot.mls.VmlsConsentGate.Verdict
import dev.forgesworn.kithmoot.storage.MemoryStorage
import kotlin.test.Test
import kotlin.test.assertEquals

/** Which join requests reach the keeper (P3-03b-3 decision 18). */
class VmlsConsentGateTest {
    private val now = 1_900_000_000L
    private val link = "bb".repeat(32)
    private fun device(i: Int) = "%064x".format(i)
    private var room = VmlsRoom("aa".repeat(32), "cc".repeat(32), "Kitchen", "dd".repeat(32), VmlsRole.KEEPER, joined = true).invited(link)

    /** Offers as the platform will: the room is stored after every Ask. */
    private fun VmlsConsentGate.ask(request: String, device: String, at: Long, over: String = link): Verdict {
        val (verdict, next) = offer(room, over, request, device, at)
        room = next
        return verdict
    }

    @Test fun `one prompt is open per room until answered or lapsed`() {
        val gate = VmlsConsentGate()
        assertEquals(Verdict.Ask, gate.ask("r1", device(1), now))
        assertEquals(Verdict.Drop(Reason.BUSY), gate.ask("r2", device(2), now + 1))
        gate.answered(room.session, "r1")
        assertEquals(Verdict.Ask, gate.ask("r3", device(2), now + 2))
        // Unanswered, a prompt lapses with the guest's wait.
        assertEquals(Verdict.Ask, gate.ask("r4", device(3), now + 2 + VmlsConsentGate.PROMPT_SECONDS))
    }

    @Test fun `a request id is answered once, and a device is asked about once per link`() {
        val gate = VmlsConsentGate()
        assertEquals(Verdict.Ask, gate.ask("r1", device(1), now))
        gate.answered(room.session, "r1")
        assertEquals(Verdict.Drop(Reason.DUPLICATE), gate.ask("r1", device(1), now + 1))
        assertEquals(Verdict.Drop(Reason.ASKED), gate.ask("r2", device(1), now + 1))
        // Answering another id leaves the open prompt open.
        assertEquals(Verdict.Ask, gate.ask("r3", device(2), now + 2))
        gate.answered(room.session, "r1")
        assertEquals(Verdict.Drop(Reason.BUSY), gate.ask("r4", device(4), now + 3))
        // A new link asks again.
        gate.answered(room.session, "r3")
        room = room.invited("ee".repeat(32))
        assertEquals(Verdict.Ask, gate.ask("r5", device(1), now + 4, over = "ee".repeat(32)))
    }

    @Test fun `a retired link, or a stopped room, asks nobody`() {
        val gate = VmlsConsentGate()
        assertEquals(Verdict.Drop(Reason.RETIRED), gate.ask("r1", device(1), now, over = "ee".repeat(32)))
        room = room.invited(null)
        assertEquals(Verdict.Drop(Reason.RETIRED), gate.ask("r2", device(1), now))
        room = room.invited(link).apply(listOf(RoomSignal.NeedsRecovery("Fork")), now).room
        assertEquals(Verdict.Drop(Reason.RETIRED), gate.ask("r3", device(1), now))
    }

    @Test fun `at most five prompts per room in any hour, the rest dropped unseen`() {
        val gate = VmlsConsentGate()
        for (i in 1..VmlsConsentGate.MAX_PER_HOUR) {
            assertEquals(Verdict.Ask, gate.ask("r$i", device(i), now + i * 60L))
            gate.answered(room.session, "r$i")
        }
        assertEquals(Verdict.Drop(Reason.RATE), gate.ask("r6", device(6), now + 3599))
        // The id is spent even so: the guest asks again with a new one.
        assertEquals(Verdict.Drop(Reason.DUPLICATE), gate.ask("r6", device(6), now + 3700))
        // An hour after the first prompt, one more may be asked.
        assertEquals(Verdict.Ask, gate.ask("r7", device(6), now + 60 + 3600))
    }

    @Test fun `the limits outlast the app, through the store`() {
        val storage = MemoryStorage()
        val first = VmlsConsentGate()
        for (i in 1..VmlsConsentGate.MAX_PER_HOUR) {
            assertEquals(Verdict.Ask, first.ask("r$i", device(i), now + i))
            first.answered(room.session, "r$i")
            VmlsRoomStore(storage).put(room)
        }
        // The system ends the app; a new process reads the room back.
        room = VmlsRoomStore(storage).room(room.persona, room.session)!!
        val second = VmlsConsentGate()
        assertEquals(Verdict.Drop(Reason.ASKED), second.ask("s1", device(1), now + 100))
        assertEquals(Verdict.Drop(Reason.RATE), second.ask("s2", device(9), now + 100))
        // A clock set back still counts the prompts already shown.
        assertEquals(Verdict.Drop(Reason.RATE), second.ask("s3", device(9), now - 600))
    }

    @Test fun `a malformed request is dropped, and a guest's room asks nobody`() {
        val gate = VmlsConsentGate()
        assertEquals(Verdict.Drop(Reason.MALFORMED), gate.ask("r1", "a/b", now))
        assertEquals(Verdict.Drop(Reason.MALFORMED), gate.ask("r2", "AB".repeat(32), now))
        assertEquals(Verdict.Drop(Reason.MALFORMED), gate.ask("r.3", device(1), now))
        assertEquals(Verdict.Drop(Reason.MALFORMED), gate.ask("r4", device(1), now, over = "link"))
        room = room.copy(role = VmlsRole.GUEST, invite = null, asked = emptySet(), prompted = emptyList())
        assertEquals(Verdict.Drop(Reason.RETIRED), gate.ask("r5", device(1), now))
    }

    @Test fun `offering the same link again keeps the devices asked`() {
        val gate = VmlsConsentGate()
        assertEquals(Verdict.Ask, gate.ask("r1", device(1), now))
        gate.answered(room.session, "r1")
        room = room.invited(link)
        assertEquals(Verdict.Drop(Reason.ASKED), gate.ask("r2", device(1), now + 1))
    }

    @Test fun `the gate and the driver write the stored room without undoing each other`() {
        val store = VmlsRoomStore(MemoryStorage())
        store.put(room)
        val leaf = "11".repeat(32)
        // The driver read the room before the request came in.
        val driven = store.room(room.persona, room.session)!!
        assertEquals(Verdict.Ask, VmlsConsentGate().offer(store, room.persona, room.session, link, "r1", device(1), now))
        // The driver then records a once-only proposal and saves its copy.
        store.saveDriven(driven.apply(listOf(RoomSignal.PendingMemberExpired(leaf)), now).room)
        val stored = store.room(room.persona, room.session)!!
        assertEquals(setOf(device(1)), stored.asked)
        assertEquals(mapOf(leaf to now), stored.grace)
        // A room forgotten meanwhile is not brought back by either.
        store.forget(room.persona, room.session)
        assertEquals(null, store.saveDriven(stored))
        assertEquals(Verdict.Drop(Reason.RETIRED), VmlsConsentGate().offer(store, room.persona, room.session, link, "r2", device(2), now))
        assertEquals(emptyList(), store.rooms())
    }
}
