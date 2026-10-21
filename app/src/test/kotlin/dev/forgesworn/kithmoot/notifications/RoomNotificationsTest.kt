package dev.forgesworn.kithmoot.notifications

import dev.forgesworn.kithmoot.session.ChatMessage
import dev.forgesworn.kithmoot.session.MessageRef
import dev.forgesworn.kithmoot.ui.StartState
import dev.forgesworn.kithmoot.ui.start.Destination
import dev.forgesworn.kithmoot.ui.start.shellBadges
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Calm shell stage 7 on Android: the web's room notifications and timed mutes (kithmoot #496). */
class RoomNotificationsTest {
    private val me = "a1".repeat(32)
    private val them = "b2".repeat(32)
    private val zone = ZoneId.of("Europe/London")
    private fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toEpochSecond()
    private fun message(body: String = "hello", reply: MessageRef? = null, mentions: List<String>? = null) =
        ChatMessage("m1", them, them, body, 100, reply = reply, mentions = mentions)

    @Test fun `the menu's words and the store's values are the web's`() {
        assertEquals(listOf("Every message", "Only mentions and replies", "Nothing", "Use my default"), RoomNotifyMode.entries.map { it.label })
        assertEquals(listOf("all", "mentions", "off", "inherit"), RoomNotifyMode.entries.map { it.storeValue })
        assertEquals(listOf("Mute for 1 hour", "Mute for 8 hours", "Mute for 1 day", "Mute for 1 week"), MuteLength.entries.map { it.label })
        assertEquals(listOf(3_600L, 28_800L, 86_400L, 604_800L), MuteLength.entries.map { it.seconds })
        assertEquals(RoomNotifyMode.DEFAULT, RoomNotifyMode.from(null))
        assertEquals(RoomNotifyMode.DEFAULT, RoomNotifyMode.from("nonsense"))
        // The default itself is never Use my default.
        assertEquals(RoomNotifyMode.ALL, RoomNotifyMode.defaultFrom("inherit"))
        assertEquals(RoomNotifyMode.MENTIONS, RoomNotifyMode.defaultFrom("mentions"))
    }

    @Test fun `a timed mute beats the room's choice, which beats the default`() {
        val now = 1_000L
        assertEquals(NotifyLevel.ALL, notifyLevel(RoomNotifyMode.DEFAULT, null, RoomNotifyMode.ALL, now))
        assertEquals(NotifyLevel.MENTIONS, notifyLevel(RoomNotifyMode.DEFAULT, null, RoomNotifyMode.MENTIONS, now))
        assertEquals(NotifyLevel.ALL, notifyLevel(RoomNotifyMode.ALL, null, RoomNotifyMode.NOTHING, now))
        assertEquals(NotifyLevel.NOTHING, notifyLevel(RoomNotifyMode.ALL, now + 1, RoomNotifyMode.ALL, now))
        // An ended mute is no mute: the room is back to what it chose.
        assertEquals(NotifyLevel.MENTIONS, notifyLevel(RoomNotifyMode.MENTIONS, now, RoomNotifyMode.ALL, now))
    }

    @Test fun `Nothing is a mute with no end, a default of Nothing is quiet but not muted`() {
        assertEquals(RoomMute.Always, roomMute(RoomNotifyMode.NOTHING, null, 10))
        assertEquals(RoomMute.Always, roomMute(RoomNotifyMode.NOTHING, 99, 10))
        assertEquals(RoomMute.Until(99), roomMute(RoomNotifyMode.ALL, 99, 10))
        assertNull(roomMute(RoomNotifyMode.DEFAULT, 10, 10))
        assertNull(roomMute(RoomNotifyMode.DEFAULT, null, 10))
        assertEquals(NotifyLevel.NOTHING, notifyLevel(RoomNotifyMode.DEFAULT, null, RoomNotifyMode.NOTHING, 10))
    }

    @Test fun `only mentions and replies lets through what is aimed at the reader`() {
        val level = NotifyLevel.MENTIONS
        assertFalse(wantsNotice(level, message(), me, privateChat = false))
        assertTrue(wantsNotice(level, message(reply = MessageRef("x", me.uppercase())), me, privateChat = false))
        assertFalse(wantsNotice(level, message(reply = MessageRef("x", them)), me, privateChat = false))
        assertTrue(wantsNotice(level, message(mentions = listOf(me)), me, privateChat = false))
        assertTrue(wantsNotice(level, message(mentions = listOf("everyone")), me, privateChat = false))
        assertTrue(wantsNotice(level, message("@everyone are we on?"), me, privateChat = false))
        // Everything in a private chat is aimed at the reader.
        assertTrue(wantsNotice(level, message(), me, privateChat = true))
        assertTrue(wantsNotice(NotifyLevel.ALL, message(), me, privateChat = false))
        assertFalse(wantsNotice(NotifyLevel.NOTHING, message(mentions = listOf(me)), me, privateChat = true))
    }

    @Test fun `the header says until when, as the row writes its times`() {
        val now = at("2026-10-21T09:30:00")
        assertEquals("Muted", mutedUntilText(RoomMute.Always, now, zone))
        assertEquals("Muted until 11:00", mutedUntilText(RoomMute.Until(at("2026-10-21T11:00:00")), now, zone))
        assertEquals("Muted until tomorrow, 09:00", mutedUntilText(RoomMute.Until(at("2026-10-22T09:00:00")), now, zone))
        assertEquals("Muted until Fri, 10:00", mutedUntilText(RoomMute.Until(at("2026-10-23T10:00:00")), now, zone))
        assertEquals("Muted until 28 Oct, 10:00", mutedUntilText(RoomMute.Until(at("2026-10-28T10:00:00")), now, zone))
        // A 12-hour phone keeps its clock.
        assertEquals("Muted until 5:30 pm", mutedUntilText(RoomMute.Until(at("2026-10-21T17:30:00")), now, zone, Locale.UK, is24Hour = false))
    }

    @Test fun `the store's entries give the muted rooms and the next end`() {
        val now = 1_000L
        val entries = mapOf(
            "mode.quiet" to "off", "until.quiet" to 5_000L,
            "mode.busy" to "mentions", "until.busy" to 2_000L,
            "until.done" to 900L, "until.broken" to "soon",
            "mode.plain" to "all",
        )
        assertEquals(mapOf("quiet" to RoomMute.Always, "busy" to RoomMute.Until(2_000)),
            RoomNotificationSettings.mutesIn(entries, now))
        assertEquals(2_000L, RoomNotificationSettings.nextMuteEnd(entries, now))
        assertEquals(setOf("until.done", "until.broken"), RoomNotificationSettings.endedMutes(entries, now))
        assertNull(RoomNotificationSettings.nextMuteEnd(mapOf("mode.quiet" to "off"), now))
    }

    @Test fun `muted rooms leave the Chats count but keep their unread`() {
        val state = StartState(unreadCounts = mapOf("a" to 3, "b" to 1, "c" to 2),
            mutedRooms = mapOf("b" to RoomMute.Always, "c" to RoomMute.Until(99)))
        assertEquals(1, shellBadges(state)[Destination.CHATS])
    }
}
