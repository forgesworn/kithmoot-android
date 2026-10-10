package dev.forgesworn.kithmoot.ui

import android.os.Bundle
import android.os.Process
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.relay.RoomRoute
import dev.forgesworn.kithmoot.storage.EncryptedRoomStorage
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** The external driver kills a_prepare while the actual native source owns
 * its journals. b_recover must run in a new process using those actual stores. */
class NativeHostRestartTest {
    @get:Rule val compose = createComposeRule()
    private fun checkpoint(f: NativeHostFixture) = EncryptedRoomStorage(f.app, "kithmoot.lab.native-host-restart", 64 * 1024)
    private fun welcome(source: JsonObject) = NostrEvent.fromJson(source.getValue("welcomeDelivery").jsonObject.getValue("event"))
    private fun attempts(source: JsonObject) = source.getValue("welcomeDelivery").jsonObject.getValue("attempts").jsonPrimitive.int
    private fun debt(source: JsonObject): Int = source.getValue("spends").jsonArray.sumOf { entry ->
        val spend = entry.jsonObject
        if (spend["lane"]?.jsonPrimitive?.content == "INTERNET") spend.getValue("bytes").jsonPrimitive.int else 0
    }
    private fun bytes(event: NostrEvent) = event.toJson().toString().toByteArray(Charsets.UTF_8).size

    @Test fun a_prepare(): Unit = runBlocking {
        val f = NativeHostFixture()
        val checkpoint = checkpoint(f)
        var ready = false
        try {
            assertNull("An old lab checkpoint cannot become a fresh preparation", checkpoint.read())
            f.welcomeAccepted = false
            f.startModel(); compose.showNativeHost(f)
            compose.onNodeWithText("Start nearby + Internet chat").performScrollTo().performClick()
            val saved = f.opened()
            val at = System.currentTimeMillis() / 1000
            val who = f.identity(saved, at)
            val joining = async { f.join(saved, at) }
            NativeHostFixture.await("unknown member waits for the rendered approval") {
                f.model.room.value.letInAsks.any { it.participant == who.participant }
            }
            assertFalse(joining.isCompleted)
            compose.onNodeWithText("Let in").performClick()
            val peer = joining.await()
            f.main { f.model.sendChat("host before active kill") }
            peer.sendChat("member before active kill")
            NativeHostFixture.await("both directions work before active host death") {
                peer.chat.value.count { it.body == "host before active kill" } == 1 &&
                    f.model.room.value.chat.count { it.body == "member before active kill" } == 1
            }
            NativeHostFixture.await("original welcome has a durable ambiguous handoff") {
                f.source(saved)["welcomeDelivery"] != JsonNull && f.relayWrites.any { it.kind == 1463 }
            }
            val source = f.source(saved)
            val original = welcome(source)
            assertTrue(Events.verify(original))
            assertEquals(NostrEvent.fromJson(source.getValue("welcome")), original)
            assertFalse(source.getValue("welcomeDelivery").jsonObject.getValue("accepted").jsonPrimitive.boolean)
            assertTrue(attempts(source) in 1..7)
            assertEquals(bytes(original) * attempts(source), debt(source))
            assertTrue(source.getValue("members").jsonArray.any { it.jsonPrimitive.content == who.participant })
            assertTrue(source.getValue("courierReady").jsonPrimitive.boolean)
            assertNull(saved.host(at))
            assertEquals(RoomRoute.MIXED, saved.route)
            // Only public expected metadata and the original signed ciphertext.
            // Never copy source signer/base/bearer into a second recovery store.
            val expected = buildJsonObject {
                put("pid", Process.myPid()); put("port", f.server.port); put("room", saved.id)
                put("pin", saved.nativeAuthority!!.pin); put("participant", saved.participant); put("device", saved.devicePubkey)
                put("route", saved.route.stored); put("relays", JsonArray(saved.relays.map(::JsonPrimitive)))
                put("welcome", original.toJson()); put("peerCreatedAt", at); put("peer", who.participant)
                put("attempts", attempts(source)); put("debt", debt(source)); put("high", source.getValue("high"))
            }
            val checkpointBytes = expected.toString().toByteArray(Charsets.UTF_8)
            try { checkpoint.write(checkpointBytes) } finally { checkpointBytes.fill(0) }
            assertEquals(1, f.radios.count { !it.closed })
            assertEquals(Stage.ROOM, f.model.stage.value)
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                putString("native_host_restart_pid", Process.myPid().toString())
                putString("native_host_restart_checkpoint", "ready")
            })
            ready = true
            awaitCancellation() // No leave, source close or graceful cleanup before SIGKILL.
        } finally {
            if (!ready) { try { f.close() } finally { checkpoint.reset() } }
        }
    }

    @Test fun b_recover() = runBlocking {
        check(InstrumentationRegistry.getArguments().getString("requireRestart") == "true") {
            "Use the guarded external active-process driver"
        }
        // Read metadata before binding the retained exact relay endpoint.
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<dev.forgesworn.kithmoot.KithMootApplication>()
        val checkpoint = EncryptedRoomStorage(app, "kithmoot.lab.native-host-restart", 64 * 1024)
        val checkpointBytes = requireNotNull(checkpoint.read()) { "No active native host checkpoint" }
        val expected = try { Json.parseToJsonElement(checkpointBytes.toString(Charsets.UTF_8)).jsonObject }
            finally { checkpointBytes.fill(0) }
        require(expected.keys == setOf("pid", "port", "room", "pin", "participant", "device", "route", "relays",
            "welcome", "peerCreatedAt", "peer", "attempts", "debt", "high"))
        assertNotEquals(expected.getValue("pid").jsonPrimitive.int, Process.myPid())
        val f = NativeHostFixture(relayPort = expected.getValue("port").jsonPrimitive.int)
        try {
            val saved = requireNotNull(f.app.savedRooms.get(expected.getValue("room").jsonPrimitive.content))
            f.savedRoom = saved
            assertEquals(expected.getValue("pin").jsonPrimitive.content, saved.nativeAuthority!!.pin)
            assertEquals(expected.getValue("participant").jsonPrimitive.content, saved.participant)
            assertEquals(expected.getValue("device").jsonPrimitive.content, saved.devicePubkey)
            assertEquals(expected.getValue("route").jsonPrimitive.content, saved.route.stored)
            assertEquals(expected.getValue("relays").jsonArray.map { it.jsonPrimitive.content }, f.relays)
            assertEquals(f.relays, saved.relays)
            val original = NostrEvent.fromJson(expected.getValue("welcome"))
            val held = f.source(saved) // No application owner, routes or source reconstruction yet.
            assertEquals(original, welcome(held))
            assertEquals(original, NostrEvent.fromJson(held.getValue("welcome")))
            assertEquals(saved.nativeAuthority!!.pin, held.getValue("pin").jsonPrimitive.content)
            assertEquals(0, held.getValue("epoch").jsonPrimitive.int)
            assertEquals("ACTIVE", held.getValue("phase").jsonPrimitive.content)
            assertEquals(JsonNull, held.getValue("pending"))
            assertTrue(held.getValue("courierReady").jsonPrimitive.boolean)
            assertFalse(held.getValue("welcomeDelivery").jsonObject.getValue("accepted").jsonPrimitive.boolean)
            val deathAttempts = attempts(held)
            assertTrue(deathAttempts in expected.getValue("attempts").jsonPrimitive.int..7)
            assertEquals(bytes(original) * deathAttempts, debt(held))
            assertTrue(held.getValue("high").jsonPrimitive.long >= expected.getValue("high").jsonPrimitive.long)
            assertTrue(held.getValue("members").jsonArray.any { it.jsonPrimitive.content == expected.getValue("peer").jsonPrimitive.content })
            f.startModel(); compose.showNativeHost(f)
            f.main { f.model.reopenRoom(saved.id) }; f.opened()
            assertEquals(saved.participant, f.model.room.value.selfParticipant)
            assertEquals(saved.devicePubkey, f.model.room.value.selfDevice)
            NativeHostFixture.await("the same welcome retries at its original endpoint and is accepted") {
                f.relayWrites.any { it.id == original.id } &&
                    f.source(saved).getValue("welcomeDelivery").jsonObject.getValue("accepted").jsonPrimitive.boolean
            }
            assertEquals(listOf(original), f.relayWrites.filter { it.id == original.id }.distinct())
            val after = f.source(saved)
            assertEquals(original, welcome(after))
            assertTrue(attempts(after) > deathAttempts)
            assertEquals(bytes(original) * attempts(after), debt(after))
            assertTrue(after.getValue("high").jsonPrimitive.long >= held.getValue("high").jsonPrimitive.long)
            val at = expected.getValue("peerCreatedAt").jsonPrimitive.long
            val peer = withTimeout(30_000) { f.join(saved, at) }
            assertTrue("Prior approval survives actual process death", f.model.room.value.letInAsks.isEmpty())
            f.main { f.model.sendChat("host after active kill") }
            peer.sendChat("member after active kill")
            NativeHostFixture.await("fresh native chat works in both directions after process death") {
                peer.chat.value.count { it.body == "host after active kill" } == 1 &&
                    f.model.room.value.chat.count { it.body == "member after active kill" } == 1
            }
            val guest = f.identity(saved, at, guest = true)
            val joining = async { f.join(saved, at, guest = true) }
            NativeHostFixture.await("new unknown member still needs a rendered approval") {
                f.model.room.value.letInAsks.any { it.participant == guest.participant }
            }
            assertFalse(joining.isCompleted)
            compose.onNodeWithText("Let in").performClick()
            val newcomer = joining.await()
            newcomer.sendChat("new member after explicit approval")
            NativeHostFixture.await("new member reaches the recovered host once") {
                f.model.room.value.chat.count { it.body == "new member after explicit approval" } == 1
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                putString("native_host_recovery_pid", Process.myPid().toString())
                putString("native_host_recovery_attempts_at_death", deathAttempts.toString())
                putString("native_host_recovery_attempts_after_reopen", attempts(after).toString())
                putString("native_host_recovery_debt_at_death", debt(held).toString())
                putString("native_host_recovery_debt_after_reopen", debt(after).toString())
            })
        } finally { try { f.close() } finally { checkpoint.reset() } }
        assertNull(checkpoint.read())
    }
}
