package dev.forgesworn.kithmoot.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.epoch.NativeHostingStatus
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.RoomRoute
import dev.forgesworn.kithmoot.session.SecondaryIdentity
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Production rendered controls, actual encrypted source/index and loopback
 * custody. These fixtures do not assert physical RF or participant receipts. */
class NativeInvitationReplacementTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nearby_two_replacements_keep_approved_chat_and_an_offline_device_at_the_current_epoch() = runBlocking {
        journey(RoomRoute.NEARBY)
    }

    @Test fun mixed_two_replacements_keep_approved_chat_and_an_offline_device_at_the_current_epoch() = runBlocking {
        journey(RoomRoute.MIXED)
    }

    private suspend fun journey(route: RoomRoute) = coroutineScope {
        val f = NativeHostFixture()
        val offlineKey = ByteArray(32).apply { this[31] = 6 }
        try {
            f.startModel(); compose.showNativeHost(f)
            compose.onNodeWithText(if (route.internet) "Start nearby + Internet chat" else "Start nearby chat")
                .performScrollTo().performClick()
            val saved = f.opened(); val at = System.currentTimeMillis() / 1000
            val who = f.identity(saved, at)
            val joining = async { f.join(saved, at, who = who) }
            NativeHostFixture.await("replacement member approval") { f.model.room.value.letInAsks.any { it.participant == who.participant } }
            compose.onNodeWithText("Let in").performClick(); val peer = joining.await()
            val secondary = requireNotNull(SecondaryIdentity.adopt(who.enrol(Schnorr.publicKeyHex(offlineKey), saved.id, at + 3600, at),
                offlineKey, saved.id, now = at))
            f.join(saved, at, who = secondary).leave()
            f.awaitHost("replacement observes the offline approved device") {
                f.source(saved).getValue("devices").jsonArray.size == 3 && f.model.room.value.nativeHosting?.canChangeMembers == true
            }
            compose.onNodeWithContentDescription("Room details").performClick()
            compose.onNodeWithText("Change room key").performScrollTo().performClick()
            compose.onNodeWithText("Change key").performClick()
            f.awaitHost("traffic epoch changes before link replacement") {
                !f.model.room.value.nativeHostingBusy && f.model.room.value.nativeHosting?.let {
                    it.status == NativeHostingStatus.READY && it.epoch == 1
                } == true
            }
            compose.onNodeWithText("Replace invitation").performScrollTo().performClick()
            val cancelled = f.source(saved)
            val cancelledWrites = (f.phoneEvents + f.relayWrites).count { it.kind == KIND_INVITATION_RETIREMENT }
            compose.onNodeWithText("Cancel").performClick()
            assertEquals(cancelled, f.source(saved))
            assertEquals(cancelledWrites, (f.phoneEvents + f.relayWrites).count { it.kind == KIND_INVITATION_RETIREMENT })
            compose.onNodeWithText("Replace invitation").performScrollTo().performClick()
            val stale = requireNotNull(f.model.room.value.nativeHosting)
            // A real approved-device request advances the source observation.
            f.join(saved, at, who = secondary).leave()
            f.awaitHost("actual source refresh invalidates replacement confirmation") {
                f.model.room.value.nativeHosting?.revision != stale.revision
            }
            compose.onNodeWithTag("native-confirm-replacement").assertIsNotEnabled()
            compose.onNodeWithText("Room hosting changed. Close this confirmation and try again.").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
            val before = f.source(saved)
            compose.onNodeWithText("Replace invitation").performScrollTo().performClick()
            compose.onNodeWithTag("native-confirm-replacement").performClick()
            f.awaitHost("completed generation one exposes its actual saved invitation") {
                f.model.room.value.nativeHosting?.invitationGeneration == 1 &&
                    !f.model.room.value.nativeHostingBusy && f.model.room.value.canShareInvitation
            }
            val first = requireNotNull(f.app.savedRooms.get(saved.id))
            assertNotEquals(saved.joinUrl, first.joinUrl)
            assertEquals(first.joinUrl.substringAfter('#'), f.model.room.value.joinUrl.substringAfter('#'))
            assertEquals(first.joinUrl.substringAfter('#'), requireNotNull(f.model.inviteLinkFor(saved.id)).substringAfter('#'))
            assertTrafficUnchanged(before, f.source(saved))
            compose.onNodeWithText("Retire invitation").performScrollTo().performClick()
            compose.onNodeWithText("Retire link").performClick()
            f.awaitHost("generation one notice archives before its replacement") {
                !f.model.room.value.nativeHostingBusy && f.model.room.value.nativeHosting?.canResendRetirement == true &&
                    !f.model.room.value.canShareInvitation
            }
            val retired = f.source(saved)
            val notices = retired.getValue("retirements")
            val noticeOffers = (f.phoneEvents + f.relayWrites).count { it.kind == KIND_INVITATION_RETIREMENT }
            compose.onNodeWithText("Replace invitation").performScrollTo().performClick()
            compose.onNodeWithTag("native-confirm-replacement").performClick()
            f.awaitHost("retired generation completes with a new actual link") {
                f.model.room.value.nativeHosting?.invitationGeneration == 2 &&
                    !f.model.room.value.nativeHostingBusy && f.model.room.value.canShareInvitation
            }
            val completed = f.source(saved)
            assertEquals(notices, completed.getValue("retirements"))
            assertEquals(noticeOffers, (f.phoneEvents + f.relayWrites).count { it.kind == KIND_INVITATION_RETIREMENT })
            assertTrafficUnchanged(before, completed)
            assertEquals(2, completed.getValue("invitationHistory").jsonArray.size)
            val next = requireNotNull(f.app.savedRooms.get(saved.id))
            assertNotEquals(first.joinUrl, next.joinUrl)
            assertEquals(next.joinUrl.substringAfter('#'), f.model.room.value.joinUrl.substringAfter('#'))
            assertEquals(next.joinUrl.substringAfter('#'), requireNotNull(f.model.inviteLinkFor(saved.id)).substringAfter('#'))
            f.main { f.model.replaceNativeInvitation(stale) }
            f.awaitHost("stale owner command refuses after two generations") {
                f.model.room.value.notice == "Room hosting changed. Open the confirmation again."
            }
            assertEquals(completed, f.source(saved))
            f.main { f.model.sendChat("host after two replacements") }; peer.sendChat("member after two replacements")
            f.awaitHost("approved chat survives both link changes") {
                peer.chat.value.any { it.body == "host after two replacements" } &&
                    f.model.room.value.chat.any { it.body == "member after two replacements" }
            }
            f.main { f.model.leave() }
            NativeHostFixture.await("replacement paths close before encrypted reopen") {
                f.model.stage.value == Stage.START && !f.model.start.value.busy && f.radios.all { it.closed }
            }
            f.main { f.model.reopenRoom(saved.id) }; f.opened()
            f.awaitHost("cold completed generation displays the same verified invitation") {
                f.model.room.value.nativeHosting?.invitationGeneration == 2 && f.model.room.value.canShareInvitation
            }
            assertEquals(next.joinUrl.substringAfter('#'), f.model.room.value.joinUrl.substringAfter('#'))
            assertTrafficUnchanged(before, f.source(saved))
            if (!route.internet) assertEquals(0, f.server.requestCount)
            println("NATIVE_REPLACEMENT_UI route=${route.stored} generations=2 trafficEpoch=1 approvedDevices=3 cancelUnchanged=true staleConfirmationRefused=true retiredNoticeReused=true bothWayChat=true encryptedReopen=true participantReceipt=false")
        } finally { offlineKey.fill(0); f.close() }
    }

    @Test fun mixed_pending_replacement_reopens_exact_originals_and_hides_both_links_until_custody() = runBlocking {
        val f = NativeHostFixture()
        try {
            f.startModel(); compose.showNativeHost(f)
            compose.onNodeWithText("Start nearby + Internet chat").performScrollTo().performClick()
            val saved = f.opened()
            f.awaitHost("mixed source is ready for replacement") { f.model.room.value.nativeHosting?.canReplaceInvitation == true }
            f.main { f.setNearbyAvailable(false); f.retirementAccepted = false }
            f.awaitHost("nearby lane unavailable before replacement") { f.model.room.value.nearby?.writablePeers == 0 }
            compose.onNodeWithContentDescription("Room details").performClick()
            compose.onNodeWithText("Replace invitation").performScrollTo().performClick()
            compose.onNodeWithTag("native-confirm-replacement").performClick()
            f.awaitHost("retained replacement is pending local custody") {
                !f.model.room.value.nativeHostingBusy && f.model.room.value.nativeHosting?.replacementGeneration == 1
            }
            val pending = f.source(saved).getValue("replacement").jsonObject
            val proposed = pending.getValue("proposed").jsonObject.getValue("welcome")
            val notice = pending.getValue("retirement")
            assertFalse(f.model.room.value.canShareInvitation)
            assertTrue(f.model.room.value.joinUrl.isEmpty())
            assertNull(f.model.inviteLinkFor(saved.id))
            assertEquals(saved.joinUrl, f.app.savedRooms.get(saved.id)!!.joinUrl)
            val stale = requireNotNull(f.model.room.value.nativeHosting)
            f.main { f.model.leave() }
            NativeHostFixture.await("pending source releases its encrypted owner") {
                f.model.stage.value == Stage.START && !f.model.start.value.busy && f.radios.all { it.closed }
            }
            f.main { f.model.reopenRoom(saved.id) }; f.opened()
            f.awaitHost("same originals reopen pending with no share capability") {
                f.model.room.value.nativeHosting?.replacementGeneration == 1 && !f.model.room.value.canShareInvitation
            }
            val reopened = f.source(saved).getValue("replacement").jsonObject
            assertEquals(proposed, reopened.getValue("proposed").jsonObject.getValue("welcome"))
            assertEquals(notice, reopened.getValue("retirement"))
            assertNotEquals(stale.ownerGeneration, f.model.room.value.nativeHosting!!.ownerGeneration)
            assertTrue(f.model.room.value.joinUrl.isEmpty())
            f.main { f.retirementAccepted = true; f.setNearbyAvailable(true) }
            f.awaitHost("original replacement completes after selected lanes return") {
                f.model.room.value.nativeHosting?.invitationGeneration == 1 && f.model.room.value.canShareInvitation
            }
            val completed = f.source(saved)
            assertEquals(proposed, completed.getValue("activeInvitation").jsonObject.getValue("welcome"))
            assertEquals(notice, completed.getValue("retirements").jsonArray.single().jsonObject.getValue("event"))
            assertEquals(JsonNull, completed.getValue("replacement"))
            assertTrue(f.relayWrites.filter { it.kind == KIND_INVITATION_RETIREMENT }.all { it.toJson() == notice })
            println("NATIVE_REPLACEMENT_UI route=mixed generation=1 pendingEncryptedReopen=true sameOriginals=true sharingWithdrawnUntilCustody=true participantReceipt=false")
        } finally { f.close() }
    }

    private fun assertTrafficUnchanged(before: JsonObject, after: JsonObject) {
        for (field in listOf("base", "signer", "epoch", "secret", "epochCause", "devices", "members", "removed"))
            assertEquals("Replacement changed $field", before.getValue(field), after.getValue(field))
    }
}
