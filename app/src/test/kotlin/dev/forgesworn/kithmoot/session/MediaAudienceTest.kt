package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RosterEntry
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Who this device's media may be sent to.
 *
 * The interop vectors prove this client reads the `agent` flag off the wire.
 * They cannot prove it ACTS on it, because a vector is a decode and the
 * switch is about what gets sent - so the rule is checked here, directly,
 * and `WebRtcEngine` is the one line that hands its result to the links.
 */
class MediaAudienceTest {

    private val credential = NostrEvent(20460, 0, emptyList(), "", "00".repeat(32), "00".repeat(32), "00".repeat(64))

    private fun entry(participant: String, device: String, agent: Boolean = false) =
        RosterEntry(participant, device, credential, agent = agent, updatedAt = 0)

    @Test
    fun `an agent is refused while the switch is off and admitted while it is on`() {
        val agents = setOf("ada-laptop")

        val off = mediaAudience(agents, agentsMayHear = false)
        assertFalse(off("ada-laptop"), "an agent must be sent nothing while the switch is off")
        assertTrue(off("alice-phone"), "a person must be sent media whatever the switch says")

        val on = mediaAudience(agents, agentsMayHear = true)
        assertTrue(on("ada-laptop"), "an agent must be sent media once somebody allows it")
    }

    @Test
    fun `every device of a person who says they are an agent is refused`() {
        // The switch is about a person, not a socket. Somebody who brought an
        // agent process and a phone under one participant key is one member,
        // and refusing only the device that carried the flag would send the
        // media to the same person by another route.
        val roster = listOf(
            entry("ada", "ada-laptop", agent = true),
            entry("ada", "ada-phone"),
            entry("alice", "alice-phone"),
        )
        val people = groupByParticipant(roster)
        val agentDevices = people.filter { it.agent }.flatMap { p -> p.devices.map { it.device } }.toSet()

        val rule = mediaAudience(agentDevices, agentsMayHear = false)
        assertFalse(rule("ada-laptop"))
        assertFalse(rule("ada-phone"))
        assertTrue(rule("alice-phone"))
    }

    @Test
    fun `a room with no agents refuses nobody`() {
        val rule = mediaAudience(emptySet(), agentsMayHear = false)
        assertTrue(rule("alice-phone"))
        assertTrue(rule("bob-desktop"))
    }

    @Test
    fun `an agent flag is only an honest true`() {
        // A looser client's `1` or `"yes"` is a person: the flag decides what
        // this device sends, so it is read as strictly as a farewell is.
        val entry = RosterEntry.fromJson(
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"participant":"${"a".repeat(64)}","device":"${"b".repeat(64)}",""" +
                    """"credential":${credential.toJson()},"tracks":[],"claims":{},"updatedAt":0,"agent":"yes"}""",
            ) as kotlinx.serialization.json.JsonObject,
        )
        assertFalse(entry.agent, "only a JSON true declares an agent")
    }
}
