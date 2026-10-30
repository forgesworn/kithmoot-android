package dev.forgesworn.kithmoot.callrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class RelayControllerTest {
    private val london = ZoneId.of("Europe/London")
    private val secret = "d".repeat(64)
    private val request = RelayRequest(secret, "e".repeat(64), listOf("wss://relay.example.com/"), 23456)
    private val endpoint = "spry://${"f".repeat(64)}?relay=wss%3A%2F%2Frelay.example.com%2F"
    private val wifi = NetworkFacts(wifi = true, notMetered = true, vpn = false, bind = "192.168.1.20", gateway = "192.168.1.1")
    private val good = RelayConditions(wifi, BatteryFacts(charging = true, percent = 80, tenthsCelsius = 300))

    private inner class Clock : RelayClock {
        var time = ZonedDateTime.of(2026, 10, 30, 12, 0, 0, 0, london).toInstant().toEpochMilli()
        val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        override fun now() = time
        override val zone: ZoneId = london
        override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
            val entry = (time + delayMs) to task
            tasks += entry
            return { tasks.remove(entry) }
        }
        fun advance(ms: Long) {
            val until = time + ms
            while (true) {
                val next = tasks.filter { it.first <= until }.minByOrNull { it.first } ?: break
                tasks.remove(next)
                time = next.first
                next.second()
            }
            time = until
        }
    }

    private class Node(val args: List<String>, val environment: List<String>, val events: RelayNodeEvents) : RelayNode {
        var stops = 0
        override fun stop() { stops++ }
        fun ready() = events.line(RelayNodeLine.parse("""{"v":1,"event":"ready","endpoint":"spry://${"f".repeat(64)}?relay=wss%3A%2F%2Frelay.example.com%2F","pairing":"${"P".repeat(43)}","urls":["turn:198.51.100.7:51000?transport=udp","turn:198.51.100.7:51001?transport=tcp"],"relayPortMap":[[49200,51002]]}""")!!)
        fun status(allocations: Long, bytes: Long) = events.line(RelayNodeEvent.Status(allocations, bytes, null))
        fun end(code: Int, crashed: Boolean = false) = events.ended(code, crashed, false)
    }

    private class Usage(var saved: RelayDailyUsage = RelayDailyUsage()) : RelayUsageStore {
        override fun load() = saved
        override fun save(usage: RelayDailyUsage) { saved = usage }
    }

    private val clock = Clock()
    private val nodes = mutableListOf<Node>()
    private val states = mutableListOf<RelayState>()
    private val usage = Usage()
    private var library = true
    private var refuseStart = false

    private fun controller(dailyLimit: Long = RelayCaps.DAILY_LIMIT_BYTES) = RelayController(
        starter = { args, environment, events -> if (refuseStart) null else Node(args, environment, events).also { nodes += it } },
        clock = clock, usageStore = usage, stateFile = "/data/relay/node-state.json", publish = { states += it },
        dailyLimit = dailyLimit, libraryReady = { library })

    private fun RelayController.running(): Node {
        conditions(good)
        turnOn(request)
        val node = nodes.last()
        node.ready()
        assertEquals(RelayPhase.RUNNING, state().phase)
        return node
    }

    @Test fun `turning on starts one node with the request and the Wi-Fi network`() {
        val relay = controller()
        relay.conditions(good)
        assertTrue("nothing runs until the person turns it on", nodes.isEmpty())
        relay.turnOn(request)
        val node = nodes.single()
        assertEquals(listOf("SPRY_TURN_SECRET=$secret"), node.environment)
        assertEquals("192.168.1.1", node.args[node.args.indexOf("--gateway") + 1])
        assertEquals("192.168.1.20", node.args[node.args.indexOf("--bind") + 1])
        assertEquals("/data/relay/node-state.json", node.args[node.args.indexOf("--state-file") + 1])
        assertEquals(RelayPhase.STARTING, relay.state().phase)
        node.ready()
        node.status(2, 5000)
        val state = relay.state()
        assertEquals(RelayPhase.RUNNING, state.phase)
        assertEquals(endpoint, state.endpoint)
        assertEquals("192.168.1.20", state.bind)
        assertEquals(23456, state.listenPort)
        assertEquals(2, state.allocations)
        assertTrue(relay.allocationsLive())
        node.status(0, 6000)
        assertFalse("locks follow live allocations", relay.allocationsLive())
    }

    @Test fun `the state never carries a secret or the public address`() {
        val relay = controller()
        relay.running()
        for (state in states) {
            val json = state.toJson()
            assertFalse(json.contains(secret))
            assertFalse(json.contains("198.51.100.7"))
            assertEquals(state.copy(), RelayState.parse(json))
        }
    }

    @Test fun `the person's Stop asks the node to stop and ends idle`() {
        val relay = controller()
        val node = relay.running()
        relay.turnOff()
        assertEquals(1, node.stops)
        assertEquals(RelayPhase.STOPPING, relay.state().phase)
        assertFalse(relay.idle())
        node.end(0)
        assertEquals(RelayPhase.OFF, relay.state().phase)
        assertTrue(relay.idle())
        assertEquals(1, nodes.size)
    }

    @Test fun `exit 3 shows the node's reason and is never restarted as a crash`() {
        val relay = controller()
        val node = relay.running()
        node.events.line(RelayNodeEvent.PortmapEnded(lost = true, reason = "expired"))
        node.end(3)
        val state = relay.state()
        assertEquals(RelayPhase.CANNOT_HOST, state.phase)
        assertEquals(CANNOT_HOST_TEXT["expired"], state.message)
        assertTrue("left on, for another network", state.wanted)
        clock.advance(60_000)
        relay.conditions(good)
        assertEquals(1, nodes.size)
        // Another network is tried.
        relay.conditions(good.copy(network = wifi.copy(bind = "10.0.0.5", gateway = "10.0.0.1")))
        assertEquals(2, nodes.size)
        assertEquals("10.0.0.5", nodes[1].args[nodes[1].args.indexOf("--bind") + 1])
    }

    @Test fun `a router that refuses before ready is a network that cannot host`() {
        val relay = controller()
        relay.conditions(good)
        relay.turnOn(request)
        nodes.single().events.line(RelayNodeEvent.PortmapEnded(lost = false, reason = "carrier-grade-nat"))
        nodes.single().end(3)
        assertEquals(RelayPhase.CANNOT_HOST, relay.state().phase)
        assertEquals("carrier-grade-nat", relay.state().reason)
    }

    @Test fun `a crash after ready restarts once after ten seconds, then stays off and says so`() {
        val relay = controller()
        val first = relay.running()
        first.end(1)
        assertEquals(RelayFailure.CRASHED, relay.state().failure)
        clock.advance(9_999)
        assertEquals(1, nodes.size)
        clock.advance(1)
        assertEquals(2, nodes.size)
        nodes[1].ready()
        nodes[1].end(1, crashed = true)
        val state = relay.state()
        assertEquals(RelayPhase.FAILED, state.phase)
        assertEquals(RelayFailure.CRASHED_TWICE, state.failure)
        assertFalse(state.wanted)
        assertTrue(relay.idle())
        clock.advance(60_000)
        relay.conditions(good)
        assertEquals(2, nodes.size)
    }

    @Test fun `turning on again resets the one restart`() {
        val relay = controller()
        relay.running().end(1)
        clock.advance(10_000)
        nodes[1].ready(); nodes[1].end(1)
        relay.turnOn(request)
        nodes[2].ready(); nodes[2].end(1)
        assertEquals(RelayFailure.CRASHED, relay.state().failure)
    }

    @Test fun `a refusal before ready is a failed start, not retried on the same network`() {
        val relay = controller()
        relay.conditions(good)
        relay.turnOn(request)
        nodes.single().events.line(RelayNodeEvent.Stopped(1, "--bind must be the concrete local IPv4 address of the hosting interface"))
        nodes.single().end(1)
        val state = relay.state()
        assertEquals(RelayFailure.START_FAILED, state.failure)
        assertNotNull(state.refusal)
        clock.advance(60_000)
        relay.conditions(good)
        assertEquals(1, nodes.size)
    }

    @Test fun `a node that never becomes ready is stopped after two minutes`() {
        val relay = controller()
        relay.conditions(good)
        relay.turnOn(request)
        clock.advance(120_000)
        assertEquals(1, nodes.single().stops)
        nodes.single().end(0)
        assertEquals(RelayFailure.START_TIMEOUT, relay.state().failure)
    }

    @Test fun `a node that does not end when asked is abandoned`() {
        val relay = controller()
        relay.running()
        relay.turnOff()
        clock.advance(20_000)
        assertEquals(RelayFailure.STOP_TIMEOUT, relay.state().failure)
        assertTrue(relay.abandoned)
        assertTrue(relay.idle())
    }

    @Test fun `a gate stops the run and the relay resumes when it passes again`() {
        val relay = controller()
        val node = relay.running()
        relay.conditions(good.copy(battery = good.battery!!.copy(charging = false)))
        assertEquals(1, node.stops)
        node.end(0)
        assertEquals(RelayPhase.PAUSED, relay.state().phase)
        assertEquals(RelayGate.NOT_CHARGING, relay.state().gate)
        assertTrue(relay.state().wanted)
        relay.conditions(good)
        assertEquals(2, nodes.size)
    }

    @Test fun `nothing starts while a gate is shut`() {
        val relay = controller()
        relay.conditions(good.copy(network = wifi.copy(notMetered = false)))
        relay.turnOn(request)
        assertTrue(nodes.isEmpty())
        assertEquals(RelayGate.METERED, relay.state().gate)
        assertEquals(RelayGate.METERED.text, relay.state().message)
    }

    @Test fun `heat rests the relay for ten minutes of cool`() {
        val relay = controller()
        val node = relay.running()
        relay.conditions(good.copy(thermalStatus = 3))
        node.end(0)
        relay.conditions(good)
        assertEquals(RelayGate.HOT, relay.state().gate)
        clock.advance(10 * 60_000L + 1000)
        assertEquals(2, nodes.size)
    }

    @Test fun `a new network stops the run and starts on the new one`() {
        val relay = controller()
        val node = relay.running()
        relay.conditions(good.copy(network = wifi.copy(bind = "10.0.0.5", gateway = "10.0.0.1")))
        assertEquals(1, node.stops)
        assertEquals(1, nodes.size)
        node.end(0)
        assertEquals(2, nodes.size)
        assertEquals("10.0.0.1", nodes[1].args[nodes[1].args.indexOf("--gateway") + 1])
    }

    @Test fun `relayed bytes count across runs and the daily limit holds the relay until midnight`() {
        val relay = controller(dailyLimit = 10_000)
        val first = relay.running()
        first.status(1, 4000)
        first.status(1, 6000)
        assertEquals(6000, relay.state().todayBytes)
        relay.conditions(good.copy(battery = good.battery!!.copy(charging = false)))
        first.end(0)
        relay.conditions(good)
        val second = nodes[1]
        second.ready()
        // The second run counts from its own zero.
        second.status(1, 3000)
        assertEquals(9000, relay.state().todayBytes)
        second.status(1, 4500)
        assertEquals(1, second.stops)
        second.end(0)
        assertEquals(RelayGate.DAILY_LIMIT, relay.state().gate)
        assertEquals(10_500, usage.saved.bytes)
        val midnight = ZonedDateTime.of(2026, 10, 31, 0, 0, 0, 0, london).toInstant().toEpochMilli()
        clock.advance(midnight - clock.time - 1)
        assertEquals(2, nodes.size)
        clock.advance(2000)
        assertEquals(3, nodes.size)
        assertEquals(0, relay.state().todayBytes)
    }

    @Test fun `a missing library or a node that cannot start says so`() {
        library = false
        val relay = controller()
        relay.conditions(good)
        relay.turnOn(request)
        assertEquals(RelayFailure.LIBRARY, relay.state().failure)
        assertTrue(nodes.isEmpty())
        library = true
        refuseStart = true
        val other = controller()
        other.conditions(good)
        other.turnOn(request)
        assertEquals(RelayFailure.START_FAILED, other.state().failure)
    }

    @Test fun `stopping while paused leaves it off`() {
        val relay = controller()
        relay.conditions(good.copy(network = null))
        relay.turnOn(request)
        assertEquals(RelayPhase.PAUSED, relay.state().phase)
        relay.turnOff()
        assertEquals(RelayPhase.OFF, relay.state().phase)
        assertTrue(relay.idle())
        assertNull(relay.state().gate)
    }

    @Test fun `an unchanged state is not sent again`() {
        val relay = controller()
        relay.conditions(good.copy(network = null))
        relay.turnOn(request)
        val sent = states.size
        repeat(5) { relay.conditions(good.copy(network = null)) }
        assertEquals(sent, states.size)
        relay.conditions(good.copy(network = null, thermalStatus = 0, battery = good.battery!!.copy(percent = 79)))
        assertEquals(sent, states.size)
    }

    @Test fun `the request never shows its secret`() {
        assertFalse(request.toString().contains(secret))
    }
}
