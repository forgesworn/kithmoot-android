package dev.forgesworn.kithmoot.mls

import dev.forgesworn.kithmoot.mls.VmlsConsentGate.Reason
import dev.forgesworn.kithmoot.mls.VmlsConsentGate.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals

/** Which join requests reach the keeper (P3-03b-3 decision 18). */
class VmlsConsentGateTest {
    private val now = 1_900_000_000L
    private val room = "aa".repeat(32)
    private val link = "bb".repeat(32)

    @Test fun `one prompt is open per room until answered or lapsed`() {
        val gate = VmlsConsentGate()
        assertEquals(Verdict.Ask, gate.offer(room, link, "r1", "d1", now))
        assertEquals(Verdict.Drop(Reason.BUSY), gate.offer(room, link, "r2", "d2", now + 1))
        // Another room is asked meanwhile.
        assertEquals(Verdict.Ask, gate.offer("cc".repeat(32), link, "r3", "d2", now + 1))
        gate.answered(room, "r1")
        assertEquals(Verdict.Ask, gate.offer(room, link, "r4", "d2", now + 2))
        // Unanswered, a prompt lapses with the guest's wait.
        assertEquals(Verdict.Ask, gate.offer(room, link, "r5", "d3", now + 2 + VmlsConsentGate.PROMPT_SECONDS))
    }

    @Test fun `a request id is answered once, and a device is asked about once per link`() {
        val gate = VmlsConsentGate()
        assertEquals(Verdict.Ask, gate.offer(room, link, "r1", "d1", now))
        gate.answered(room, "r1")
        assertEquals(Verdict.Drop(Reason.DUPLICATE), gate.offer(room, link, "r1", "d1", now + 1))
        assertEquals(Verdict.Drop(Reason.ASKED), gate.offer(room, link, "r2", "d1", now + 1))
        // A new link asks again.
        assertEquals(Verdict.Ask, gate.offer(room, "dd".repeat(32), "r3", "d1", now + 1))
        // Answering another id leaves the open prompt open.
        gate.answered(room, "r1")
        assertEquals(Verdict.Drop(Reason.BUSY), gate.offer(room, link, "r4", "d4", now + 2))
    }

    @Test fun `at most five prompts per room in any hour, the rest dropped unseen`() {
        val gate = VmlsConsentGate()
        for (i in 1..VmlsConsentGate.MAX_PER_HOUR) {
            assertEquals(Verdict.Ask, gate.offer(room, link, "r$i", "d$i", now + i * 60L))
            gate.answered(room, "r$i")
        }
        assertEquals(Verdict.Drop(Reason.RATE), gate.offer(room, link, "r6", "d6", now + 3599))
        // The id is spent even so: the guest asks again with a new one.
        assertEquals(Verdict.Drop(Reason.DUPLICATE), gate.offer(room, link, "r6", "d6", now + 3700))
        // An hour after the first prompt, one more may be asked.
        assertEquals(Verdict.Ask, gate.offer(room, link, "r7", "d6", now + 60 + 3600))
    }
}
