package dev.forgesworn.kithmoot.notifications

import dev.forgesworn.kithmoot.session.MAX_CHAT_TEXT_LENGTH
import org.junit.Assert.*
import org.junit.Test

class NoticeReplyTest {
    private val now = 1_800_000_000L
    private val room = ReplyRoom(anonymous = false, quiet = false, ended = false, hasEpoch = true, needsProof = false,
        account = ReplyAccount.NONE, credentialExpiresAt = now + 86_400)

    @Test fun aRoomWhoseKeyIsHeldHereOffersReply() {
        assertTrue(canReplyFromNotice(room, now))
        assertTrue(canReplyFromNotice(room.copy(account = ReplyAccount.SIGNED_IN), now))
    }

    @Test fun roomsThatCannotSendWithNobodyToAskDoNot() {
        assertFalse("Tor only", canReplyFromNotice(room.copy(anonymous = true), now))
        assertFalse("its own schedule", canReplyFromNotice(room.copy(quiet = true), now))
        assertFalse("ended", canReplyFromNotice(room.copy(ended = true), now))
        assertFalse("removed from the room", canReplyFromNotice(room.copy(hasEpoch = false), now))
        assertFalse("a Kindred proof", canReplyFromNotice(room.copy(needsProof = true), now))
        assertFalse("a bunker", canReplyFromNotice(room.copy(account = ReplyAccount.BUNKER), now))
        assertFalse("another account", canReplyFromNotice(room.copy(account = ReplyAccount.SIGNED_OUT), now))
    }

    @Test fun aCredentialMustOutlastTheReply() {
        assertFalse("none to carry", canReplyFromNotice(room.copy(credentialExpiresAt = null), now))
        assertFalse("expired", canReplyFromNotice(room.copy(credentialExpiresAt = now - 1), now))
        assertFalse("about to", canReplyFromNotice(room.copy(credentialExpiresAt = now + REPLY_CREDENTIAL_MARGIN_SECONDS - 1), now))
        assertTrue(canReplyFromNotice(room.copy(credentialExpiresAt = now + REPLY_CREDENTIAL_MARGIN_SECONDS), now))
    }

    @Test fun theTextIsTrimmedCappedAndNeverBlank() {
        assertEquals("On my way", noticeReplyText("  On my way \n"))
        assertNull(noticeReplyText(null))
        assertNull(noticeReplyText(""))
        assertNull(noticeReplyText(" \n\t "))
        val long = noticeReplyText("x".repeat(MAX_CHAT_TEXT_LENGTH + 50))!!
        assertEquals(MAX_CHAT_TEXT_LENGTH, long.length)
        assertEquals("a", noticeReplyText("a" + " ".repeat(MAX_CHAT_TEXT_LENGTH)))
    }

    @Test fun aSentReplyFollowsThePreviewSetting() {
        assertEquals("On my way", replyNoticeBody("On my way", ReplyOutcome.SENT, previews = true))
        assertFalse("On my way" in replyNoticeBody("On my way", ReplyOutcome.SENT, previews = false))
        assertEquals(MAX_NOTICE_BODY, replyNoticeBody("x".repeat(1_000), ReplyOutcome.SENT, previews = true).length)
    }

    @Test fun aKeptReplySaysSoAndHidesItsTextWithoutPreviews() {
        val shown = replyNoticeBody("On my way", ReplyOutcome.KEPT, previews = true)
        assertTrue(shown.startsWith("On my way\n"))
        assertTrue("Not sent yet" in shown)
        val hidden = replyNoticeBody("On my way", ReplyOutcome.KEPT, previews = false)
        assertFalse("On my way" in hidden)
        assertTrue("Not sent yet" in hidden)
    }

    @Test fun aFailedReplyAlwaysShowsAllItsTextBecauseNothingElseHoldsIt() {
        val text = "y".repeat(1_000)
        for (previews in listOf(true, false)) {
            val body = replyNoticeBody(text, ReplyOutcome.FAILED, previews)
            assertTrue(body.startsWith(text + "\n"))
            assertTrue("Not sent." in body)
        }
    }

    @Test fun anOpenRoomsSenderIsRemovedOnlyByItself() {
        val first: suspend (String) -> ReplyOutcome = { ReplyOutcome.SENT }
        val second: suspend (String) -> ReplyOutcome = { ReplyOutcome.KEPT }
        OpenRoomReplies.register("room", first)
        OpenRoomReplies.register("room", second)
        OpenRoomReplies.unregister("room", first)
        assertSame(second, OpenRoomReplies.sender("room"))
        OpenRoomReplies.unregister("room", second)
        assertNull(OpenRoomReplies.sender("room"))
    }
}
