package dev.forgesworn.kithmoot.ui

import android.os.Bundle
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.session.KIND_CHAT
import dev.forgesworn.kithmoot.storage.EncryptedRoomStorage
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The emulator driver kills a_prepare at its live checkpoint. Recovery opens
 * the actual encrypted saved room in a different process, without logo history. */
@RunWith(AndroidJUnit4::class)
class RoomLogoRestartTest {
    private val app get() = ApplicationProvider.getApplicationContext<KithMootApplication>()
    private val checkpoint get() = EncryptedRoomStorage(app, "kithmoot.lab.room-logo-restart", 64 * 1024)
    private fun mode() = InstrumentationRegistry.getArguments().getString("transitionMode").also {
        require(it == "image" || it == "removal")
    }!!
    private suspend fun await(label: String, condition: () -> Boolean) {
        try { withTimeout(15_000) { while (!condition()) delay(20) } }
        catch (error: TimeoutCancellationException) { throw AssertionError(label, error) }
    }
    private fun fixtureImage(): LogoImage {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val vectors = Json.parseToJsonElement(assets.open("logo-images-v1.json").bufferedReader().use { it.readText() })
            .jsonObject.getValue("vectors").jsonArray
        return requireNotNull(LogoImages.read(vectors.first { it.jsonObject["accepted"]?.jsonPrimitive?.boolean == true }
            .jsonObject.getValue("payload")))
    }

    @Test fun a_prepare() = runBlocking<Unit> {
        checkpoint.reset()
        val transition = mode()
        val f = GuestAdmissionEntryTest.Fixture(ack = true, hostRoom = true, retainControls = true)
        try {
            f.start()
            val image = fixtureImage()
            val acceptedImage = f.rootWork!!.replaceRoomLogo(image)
            val oldImage = f.events.last { it.kind == KIND_CHAT }
            val queries = f.controlQueries()
            f.main { f.model.joinFromUrl(f.url); f.model.onGuestAdmissionNameChanged("Restart logo guest"); f.model.requestGuestAdmission() }
            await("guest waits outside room") { f.model.guestAdmission.value?.phase == GuestAdmissionPhase.WAITING }
            assertEquals(queries, f.controlQueries())
            assertNull(f.app.savedRooms.get(f.room.roomId)); assertNull(f.model.room.value.logo)
            val accepted = if (transition == "removal") f.rootWork!!.replaceRoomLogo(null) else acceptedImage
            val request = decodeInvitationRequest(f.requests.single(), f.host.invitation, f.now())!!
            f.reply(encodeInvitationGrant(f.host, request.device, request.requestId, f.secret, f.now()))
            await("accepted artwork committed before process death") {
                f.model.stage.value == Stage.ROOM && f.app.savedRooms.get(f.room.roomId)?.sharedLogo?.record?.id == accepted.id
            }
            val saved = f.app.savedRooms.get(f.room.roomId)!!
            assertEquals(if (transition == "image") image.sha256 else null, f.model.room.value.logo?.sha256)
            assertTrue(f.model.room.value.chat.isEmpty())
            assertFalse(f.model.room.value.onCall); assertFalse(f.model.room.value.micOn); assertFalse(f.model.room.value.cameraOn)
            val expected = buildJsonObject {
                put("v", 1); put("pid", Process.myPid()); put("mode", transition); put("port", f.server.port)
                put("secret", f.secret.toHex()); put("inviterKey", f.host.inviterSecretKey.toHex())
                put("bearer", f.host.invitation.bearer.toHex()); put("room", saved.id)
                put("participant", saved.participant); put("device", saved.devicePubkey)
                put("record", accepted.id); put("image", image.sha256); put("oldImage", oldImage.toJson())
            }.toString().toByteArray(Charsets.UTF_8)
            try { checkpoint.write(expected) } finally { expected.fill(0) }
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                putString("room_logo_restart_checkpoint", "ready")
                putString("room_logo_restart_pid", Process.myPid().toString())
                putString("room_logo_restart_mode", transition)
            })
            awaitCancellation() // Real SIGKILL prevents graceful cleanup.
        } finally { f.close(); checkpoint.reset() }
    }

    @Test fun b_recover() = runBlocking {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("requireRestart"))
        val plain = checkpoint.read() ?: throw AssertionError("Preparing checkpoint is missing")
        val expected = try { Json.parseToJsonElement(plain.toString(Charsets.UTF_8)).jsonObject } finally { plain.fill(0) }
        assertEquals(1, expected.getValue("v").jsonPrimitive.int)
        assertNotEquals(expected.getValue("pid").jsonPrimitive.int, Process.myPid())
        val transition = mode()
        assertEquals(transition, expected.getValue("mode").jsonPrimitive.content)
        val buffers = mutableListOf<ByteArray>()
        fun secret(key: String) = expected.getValue(key).jsonPrimitive.content.hexToBytes().also { require(it.size == 32); buffers += it }
        val roomSecret = secret("secret"); val inviterKey = secret("inviterKey"); val bearer = secret("bearer")
        val host = RoomInvitationHost(RoomInvitation(bearer, Schnorr.publicKeyHex(inviterKey)), inviterKey)
        val port = expected.getValue("port").jsonPrimitive.int; require(port in 1024..65535)
        // This fresh relay serves epoch replies, but has no artwork history.
        val f = GuestAdmissionEntryTest.Fixture(ack = true, hostRoom = true,
            secretOverride = roomSecret, hostOverride = host, relayPort = port)
        try {
            val room = expected.getValue("room").jsonPrimitive.content
            assertEquals(room, f.room.roomId)
            val saved = f.app.savedRooms.get(room) ?: throw AssertionError("Encrypted saved room missing after process death")
            val record = expected.getValue("record").jsonPrimitive.content
            val hash = if (transition == "image") expected.getValue("image").jsonPrimitive.content else null
            assertEquals(record, saved.sharedLogo?.record?.id)
            assertEquals(hash, saved.sharedLogo?.record?.image?.sha256)
            // No seed is injected into the new process: production storage and
            // reopen restore the accepted record and its tombstone precedence.
            f.start(); f.main { f.model.reopenRoom(room) }
            await("same guest room opens after SIGKILL") { f.model.stage.value == Stage.ROOM || f.model.start.value.error != null }
            assertNull(f.model.start.value.error)
            assertEquals(expected.getValue("participant").jsonPrimitive.content, f.model.room.value.selfParticipant)
            assertEquals(expected.getValue("device").jsonPrimitive.content, f.model.room.value.selfDevice)
            await("artwork state restored from encrypted cache") {
                f.app.roomLogos.seed(saved, f.now())?.record?.id == record && f.model.room.value.logo?.sha256 == hash
            }
            await("recovered private artwork subscription is active") { f.controlQueries() > 0 }
            assertFalse(f.events.any { it.kind == KIND_CHAT })
            // A stale encrypted logo arriving after reopening cannot resurrect
            // an accepted removal; image mode also keeps its exact record.
            assertTrue("Stale encrypted artwork must reach a recovered subscription",
                f.reply(NostrEvent.fromJson(expected.getValue("oldImage"))) > 0)
            delay(350)
            assertEquals(record, f.app.roomLogos.seed(saved, f.now())?.record?.id)
            assertEquals(hash, f.model.room.value.logo?.sha256)
            assertTrue(f.model.room.value.chat.isEmpty())
            assertFalse(f.model.room.value.onCall); assertFalse(f.model.room.value.micOn); assertFalse(f.model.room.value.cameraOn)
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                putString("room_logo_recovery_pid", Process.myPid().toString())
                putString("room_logo_recovery_mode", transition)
            })
        } finally { f.close(); checkpoint.reset(); buffers.forEach { it.fill(0) } }
    }
}
