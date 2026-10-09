package dev.forgesworn.kithmoot.ui

import android.os.Bundle
import android.os.Process
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.*
import dev.forgesworn.kithmoot.session.*
import dev.forgesworn.kithmoot.storage.EncryptedRoomStorage
import dev.forgesworn.kithmoot.storage.RoomSharingVault
import dev.forgesworn.kithmoot.ui.room.RoomSharingSheet
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** a_prepare remains active at its checkpoint; the emulator-only driver SIGKILLs
 * that PID. b_recover runs in a different process with production stores/model. */
class RoomSharingRestartTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<KithMootApplication>()
    private val checkpoint get() = EncryptedRoomStorage(app, "kithmoot.lab.sharing-restart", 64 * 1024)
    private suspend fun await(label: String, predicate: () -> Boolean) {
        try { withTimeout(90_000) { while (!predicate()) delay(25) } }
        catch (error: TimeoutCancellationException) { throw AssertionError(label, error) }
    }
    private fun show(f: FreshNearbyEntryTest.Fixture) = compose.setContent { KithMootTheme {
        val room by f.model.room.collectAsState()
        room.sharing?.let { RoomSharingSheet(it, f.model::selectSharingParticipant,
            f.model::startRoomSharing, f.model::stopRoomSharing, {}) }
    } }

    private fun readJournal(room: String, participant: String, device: String): JsonObject {
        val owner = "$room:$participant:$device"
        val alias = "kithmoot.room-forwarding." + Digests.sha256(owner.toByteArray()).toHex()
        val plain = EncryptedRoomStorage(app, alias, RoomForwardingLedger.MAX_FILE_BYTES).read()!!
        return try { Json.parseToJsonElement(plain.toString(Charsets.UTF_8)).jsonObject } finally { plain.fill(0) }
    }
    private fun internetDebt(journal: JsonObject) = journal.getValue("spends").jsonArray.map { it.jsonObject }
        .filter { it.getValue("lane").jsonPrimitive.content == "INTERNET" }.sumOf { it.getValue("bytes").jsonPrimitive.int }

    @Test fun a_prepare() = runBlocking<Unit> {
        checkpoint.reset()
        val f = FreshNearbyEntryTest.Fixture(mixed = true, rootInternetOnly = true)
        var peer: RoomSession? = null
        try {
            f.start(); f.main { f.model.joinNearbyFromUrl(f.url, f.descriptor, RoomRoute.MIXED) }
            await("mixed room opens") { f.model.stage.value == Stage.ROOM || f.model.start.value.error != null }
            assertNull(f.model.start.value.error)
            val saved = f.app.savedRooms.get(f.room.roomId)!!
            val now = System.currentTimeMillis() / 1000
            val member = RoomSession(f.room, PrimaryIdentity.create(f.room.roomId, now + 3600, now), f.transport,
                f.scope, authority = f.host.invitation.inviter, timing = SessionTiming(announceJitterMs = 0))
            peer = member; member.join()
            await("nearby participant can be approved") { f.model.room.value.sharing?.candidates?.contains(member.identity.participant) == true }
            show(f)
            compose.onNodeWithContentDescription("Approve messages from ${member.identity.participant}").performScrollTo().performClick()
            await("approval committed") { f.model.room.value.sharing?.busy == false }
            assertNull(f.model.room.value.sharing!!.error)
            compose.onNodeWithText("Start sharing").performClick()
            await("sharing owner active") { f.model.room.value.sharing?.enabled == true }
            f.relayEnabled = false
            member.sendChat("original across active process kill")
            await("uncertain handoff reached the selected relay") {
                f.relayWrites.any { it.kind == KIND_CHAT && it.pubkey == member.identity.devicePubkey }
            }
            val original = f.relayWrites.first { it.kind == KIND_CHAT && it.pubkey == member.identity.devicePubkey }
            assertTrue(Events.verify(original)); assertTrue(f.root.chat.value.isEmpty())
            // Read the real atomic encrypted journal after reservation preceded TX.
            // No second ledger/writer or in-memory owner is opened or stopped.
            val journal = readJournal(saved.id, saved.participant, saved.devicePubkey)
            val row = journal.getValue("entries").jsonArray.single().jsonObject
            assertEquals(original, NostrEvent.fromJson(row.getValue("event")))
            assertEquals("UNKNOWN", row.getValue("INTERNET").jsonObject.getValue("state").jsonPrimitive.content)
            val attempts = row.getValue("INTERNET").jsonObject.getValue("attempts").jsonPrimitive.int
            assertTrue(attempts in 1..RoomForwardingLedger.MAX_ATTEMPTS)
            assertTrue(internetDebt(journal) > 0)
            assertEquals(0, internetDebt(journal) % attempts)
            val expected = buildJsonObject {
                put("v", 1); put("pid", Process.myPid()); put("port", f.server.port)
                put("secret", f.secret.toHex()); put("inviterKey", f.host.inviterSecretKey.toHex())
                put("bearer", f.host.invitation.bearer.toHex())
                put("rootParticipantKey", f.root.identity.let { it as PrimaryIdentity }.participantKeyForStorage()!!.toHex())
                put("rootDeviceKey", f.root.identity.deviceSecretKey.toHex()); put("rootCredential", f.root.identity.credential.toJson())
                put("room", saved.id); put("participant", saved.participant); put("device", saved.devicePubkey)
                put("approved", member.identity.participant); put("relays", JsonArray(f.relays.map(::JsonPrimitive)))
                put("journal", journal)
            }.toString().toByteArray(Charsets.UTF_8)
            try { checkpoint.write(expected) } finally { expected.fill(0) }
            assertTrue(f.model.room.value.sharing!!.enabled)
            assertFalse(f.radios.any { it.closed })
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                putString("sharing_restart_checkpoint", "ready")
                putString("sharing_restart_pid", Process.myPid().toString())
            })
            awaitCancellation() // External SIGKILL: no graceful cleanup runs.
        } finally { peer?.leave(); f.close(); checkpoint.reset() }
    }

    @Test fun b_recover() = runBlocking {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("requireRestart"))
        val plain = checkpoint.read() ?: throw AssertionError("Preparing checkpoint is missing")
        val expected = try { Json.parseToJsonElement(plain.toString(Charsets.UTF_8)).jsonObject } finally { plain.fill(0) }
        assertEquals(1, expected.getValue("v").jsonPrimitive.int)
        assertNotEquals(expected.getValue("pid").jsonPrimitive.int, Process.myPid())
        val buffers = mutableListOf<ByteArray>()
        fun secret(key: String) = expected.getValue(key).jsonPrimitive.content.hexToBytes().also { require(it.size == 32); buffers += it }
        val roomSecret = secret("secret"); val inviterKey = secret("inviterKey")
        val bearer = secret("bearer"); val rootParticipant = secret("rootParticipantKey"); val rootDevice = secret("rootDeviceKey")
        val host = RoomInvitationHost(RoomInvitation(bearer, Schnorr.publicKeyHex(inviterKey), persistent = true), inviterKey)
        val rootIdentity = PrimaryIdentity(dev.forgesworn.kithmoot.account.LocalSigner(rootParticipant), rootDevice,
            NostrEvent.fromJson(expected.getValue("rootCredential")))
        val port = expected.getValue("port").jsonPrimitive.int; require(port in 1024..65535)
        val f = FreshNearbyEntryTest.Fixture(mixed = true, rootInternetOnly = true,
            secretOverride = roomSecret, hostOverride = host, rootIdentityOverride = rootIdentity, relayPort = port)
        try {
            val room = expected.getValue("room").jsonPrimitive.content
            val participant = expected.getValue("participant").jsonPrimitive.content
            val device = expected.getValue("device").jsonPrimitive.content
            assertEquals(room, f.room.roomId)
            assertEquals(expected.getValue("relays").jsonArray.map { it.jsonPrimitive.content }, f.relays)
            val binding = RoomForwardingBinding(room, participant, device, RoomNearbyDiscovery.scope(room), f.relays,
                setOf(expected.getValue("approved").jsonPrimitive.content))
            val checkpointJournal = expected.getValue("journal").jsonObject
            val checkpointRow = checkpointJournal.getValue("entries").jsonArray.single().jsonObject
            val original = NostrEvent.fromJson(checkpointRow.getValue("event"))
            val checkpointAttempts = checkpointRow.getValue("INTERNET").jsonObject.getValue("attempts").jsonPrimitive.int
            val checkpointDebt = internetDebt(checkpointJournal)
            // The owner stays live between readiness and external SIGKILL: it
            // may charge more reservations. Compare the exact journal left by
            // death, and require every additional charge to conserve byte debt.
            val journal = readJournal(room, participant, device)
            val old = journal.getValue("entries").jsonArray.single().jsonObject
            assertEquals(original, NostrEvent.fromJson(old.getValue("event")))
            assertEquals(checkpointRow.getValue("expires"), old.getValue("expires"))
            assertEquals(checkpointJournal.getValue("pin"), journal.getValue("pin"))
            val attempts = old.getValue("INTERNET").jsonObject.getValue("attempts").jsonPrimitive.int
            val debt = internetDebt(journal)
            assertTrue(attempts in checkpointAttempts..RoomForwardingLedger.MAX_ATTEMPTS)
            assertEquals(checkpointDebt / checkpointAttempts * attempts, debt)
            val vault = RoomSharingVault(app, room, participant, device)
            val held = vault.prepare(binding).use { it.status() }
            assertTrue(held.suspended)
            assertEquals(original, held.entries.single().event)
            assertEquals(old.getValue("expires").jsonPrimitive.long, held.entries.single().expires)
            assertEquals(attempts, held.entries.single().internet.attempts)
            assertEquals(ForwardingLaneState.UNKNOWN, held.entries.single().internet.state)
            assertEquals(debt, held.internetBytes)
            assertTrue(held.high >= journal.getValue("high").jsonPrimitive.long)
            f.start(); f.main { f.model.reopenRoom(room) }
            await("same owner reopens after process kill") { f.model.stage.value == Stage.ROOM || f.model.start.value.error != null }
            assertNull(f.model.start.value.error)
            await("sharing preferences loaded") { f.model.room.value.sharing != null }
            assertEquals(participant, f.model.room.value.selfParticipant)
            assertEquals(device, f.model.room.value.selfDevice)
            assertEquals(binding.senders, f.model.room.value.sharing!!.selected)
            assertFalse(f.model.room.value.sharing!!.enabled)
            assertTrue("Reopen requires fresh live epoch admission", f.epochRequests.isNotEmpty())
            delay(5_250)
            assertTrue(f.root.chat.value.isEmpty()); assertFalse(f.relayWrites.any { it.id == original.id })
            val disabled = vault.prepare(binding).use { it.status() }
            assertEquals(held.entries, disabled.entries)
            assertEquals(held.internetBytes, disabled.internetBytes)
            assertEquals(held.nearbyBytes, disabled.nearbyBytes)
            show(f); compose.onNodeWithText("Resume sharing").performClick()
            await("explicit resume carries original ciphertext") { f.root.chat.value.count { it.body == "original across active process kill" } == 1 }
            f.main { f.model.stopRoomSharing() }
            val after = vault.prepare(binding).use { it.status() }
            assertEquals(listOf(original), f.relayWrites.filter { it.id == original.id })
            assertEquals(original, after.entries.single().event)
            assertEquals(held.entries.single().expires, after.entries.single().expires)
            val resumedAttempts = after.entries.single().internet.attempts
            assertTrue(resumedAttempts > attempts && resumedAttempts <= RoomForwardingLedger.MAX_ATTEMPTS)
            assertEquals(debt / attempts * resumedAttempts, after.internetBytes)
            assertTrue(after.internetBytes > debt); assertTrue(after.high >= held.high)
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                putString("sharing_recovery_pid", Process.myPid().toString())
                putString("sharing_recovery_attempts_at_death", attempts.toString())
                putString("sharing_recovery_attempts_after_resume", resumedAttempts.toString())
                putString("sharing_recovery_debt_bytes_at_death", debt.toString())
                putString("sharing_recovery_debt_bytes_after_resume", after.internetBytes.toString())
            })
        } finally { f.close(); checkpoint.reset(); buffers.forEach { it.fill(0) } }
        assertNull(checkpoint.read())
    }
}
