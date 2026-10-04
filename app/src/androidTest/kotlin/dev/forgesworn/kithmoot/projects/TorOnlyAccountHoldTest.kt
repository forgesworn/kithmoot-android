package dev.forgesworn.kithmoot.projects

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.account.AccountWriteHold
import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.protocol.createRoomInvitation
import dev.forgesworn.kithmoot.protocol.deriveRoom
import dev.forgesworn.kithmoot.protocol.encodeInvitationUrl
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import dev.forgesworn.kithmoot.storage.RecoveryUi
import dev.forgesworn.kithmoot.storage.SavedRoom
import dev.forgesworn.kithmoot.ui.RoomViewModel
import dev.forgesworn.kithmoot.ui.Stage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.bouncycastle.crypto.digests.SHA3Digest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule

/**
 * P6-01, the owner's option 3 (kithmoot-android#145): while a Tor-only room is
 * open, and after it closes until the person next uses their account, the
 * account publishes nothing to its relays. Installed app, real view model,
 * real sockets: the account's relay is a loopback relay that records every
 * write. The Tor-only room's onion is random, so nothing hosts it, and no
 * Orbot is needed: the room opens with no relay reachable (P6-02 A).
 */
class TorOnlyAccountHoldTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val ui = RecoveryUi()
    private val app get() = ApplicationProvider.getApplicationContext<KithMootApplication>()
    private lateinit var model: RoomViewModel
    private var server: ProjectTestRelay? = null
    private val owner = LocalSigner(ByteArray(32) { 6 })
    private val member = LocalSigner(ByteArray(32) { 7 })

    @After fun cleanup() {
        if (::model.isInitialized) {
            if (model.stage.value == Stage.ROOM) { activity.scenario.onActivity { model.leave() }; ui.home() }
            activity.scenario.onActivity { model.signOut() }
            ui.await("account cleanup") { model.start.value.account == null }
        }
        AccountWriteHold.process.personActed()
        server?.close()
    }

    @Test fun account_publishes_wait_while_a_tor_only_room_is_open_and_until_the_person_next_acts() {
        val relay = ProjectTestRelay().also { server = it }
        activity.scenario.onActivity { model = ViewModelProvider(it)[RoomViewModel::class.java] }
        ui.home()
        if (app.accounts.load() != null) {
            ui.await("previous saved account") { model.start.value.account != null }
            activity.scenario.onActivity { model.signOut() }; ui.await("previous account closed") { model.start.value.account == null }
        }
        // A hold left by an earlier test in this process would make the first checks pass for the wrong reason.
        AccountWriteHold.process.personActed(); assertFalse(AccountWriteHold.process.isHeld)
        resetProjectTestVault(app, owner)
        app.savedRooms.reset()
        activity.scenario.onActivity {
            model.refreshSavedRooms()
            model.onRelaysChanged(relay.url)
            model.installLocalTestAccount(ByteArray(32) { 6 })
        }
        ui.await("account project history") { model.start.value.account != null && model.start.value.projects.ready }

        val roomId = seedTorOnlyRoom("Tor-only hold")
        activity.scenario.onActivity { model.refreshSavedRooms(); model.reopenRoom(roomId) }
        ui.await("Tor-only room open") { model.stage.value == Stage.ROOM && model.room.value.anonymous }
        assertTrue("opening a Tor-only room holds account publishes", AccountWriteHold.process.isHeld)

        // A project change saved while the room is open: kept on the phone, not sent.
        val writesBefore = relay.writes.size
        assertTrue(runBlocking { model.saveSharedProject(null, definition("Held in a Tor-only room")) })
        ui.await("change kept on the phone") { model.start.value.projects.pendingSends > 0 }
        // Longer than the 15 s publish timeout, so a send would have landed or failed by now.
        Thread.sleep(20_000)
        assertEquals("no account publish while the Tor-only room is open", writesBefore, relay.writes.size)
        assertTrue(model.start.value.projects.pendingSends > 0)

        activity.scenario.onActivity { model.leave() }
        ui.await("back home") { model.stage.value == Stage.START }
        Thread.sleep(10_000)
        assertTrue("still held just after the close", AccountWriteHold.process.isHeld)
        assertEquals("no account publish at the close", writesBefore, relay.writes.size)

        // The person's next account action sends what waited.
        activity.scenario.onActivity { model.retryProjectSends() }
        ui.await("held change sent") { relay.writes.size > writesBefore && model.start.value.projects.pendingSends == 0 }
        assertFalse(AccountWriteHold.process.isHeld)
    }

    private fun definition(name: String) = buildJsonObject {
        put("name", name); put("archived", false); put("authorityRevision", 1); put("rooms", JsonArray(emptyList()))
        put("members", JsonArray(listOf(owner, member).map { buildJsonObject { put("pubkey", it.pubkey); put("kind", "person"); put("epoch", 1) } }))
    }

    /** A saved Tor-only room on a random v3 onion that nothing hosts. */
    private fun seedTorOnlyRoom(name: String): String {
        val secret = Entropy.bytes(32)
        val now = System.currentTimeMillis() / 1000
        val relays = listOf("wss://${randomOnion()}/")
        val identity = PrimaryIdentity.create(deriveRoom(secret).roomId, now + 86400, now)
        val host = createRoomInvitation(true)
        val url = encodeInvitationUrl("https://kithmoot.forgesworn.dev/j/", host.invitation, relays)
        val room = SavedRoom.create(secret, identity, url, relays, name, now, host, host.invitation.canonicalInviter, anonymous = true)
        app.savedRooms.save(room)
        return room.id
    }

    private fun randomOnion(): String {
        val key = Entropy.bytes(32)
        val input = ".onion checksum".toByteArray(Charsets.US_ASCII) + key + byteArrayOf(3)
        val digest = ByteArray(32)
        SHA3Digest(256).apply { update(input, 0, input.size); doFinal(digest, 0) }
        val bytes = key + digest.copyOfRange(0, 2) + byteArrayOf(3)
        val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
        val out = StringBuilder()
        var buffer = 0; var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff); bits += 8
            while (bits >= 5) { bits -= 5; out.append(alphabet[(buffer ushr bits) and 31]) }
        }
        return "$out.onion"
    }
}
