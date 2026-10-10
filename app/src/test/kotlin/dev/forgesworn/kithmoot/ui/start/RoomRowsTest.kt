package dev.forgesworn.kithmoot.ui.start

import dev.forgesworn.kithmoot.account.AccountRoom
import dev.forgesworn.kithmoot.account.shortNpub
import dev.forgesworn.kithmoot.storage.SavedRoomSummary
import java.time.ZoneId
import java.util.Locale
import kotlin.test.*

class RoomRowsTest {
    private val id = "1a2b3c4d5e6f7890" + "0".repeat(48)

    private fun room(
        roomId: String = "room-1", label: String = "Room", source: RoomSource = RoomSource.PHONE,
        openedAt: Long = 0, project: String? = null, account: String? = null, anonymous: Boolean = false,
        secondary: Boolean = false, ended: Boolean = false, canShareInvite: Boolean = false,
    ) = HomeRoom(roomId, label, source, openedAt, project, account, anonymous, secondary, ended, canShareInvite)

    @Test fun `roomLabel falls back to Untitled room`() {
        assertEquals("Untitled room", roomLabel(null, id))
        assertEquals("Untitled room", roomLabel("", id))
        assertEquals("Untitled room", roomLabel("Room ${id.take(8)}", id))
        val other = "1a2b3c4d" + "0".repeat(56)
        assertEquals("Room 00000000", roomLabel("Room 00000000", other))
        assertEquals("Garden group", roomLabel("Garden group", id))
    }

    @Test fun `mergeRooms adds account bookmarks not already saved`() {
        val saved = listOf(SavedRoomSummary("a", "A", false, 10))
        val bookmarks = listOf(AccountRoom("a", "link-a", "A", 999), AccountRoom("b", "link-b", "B", 20))
        val signedIn = mergeRooms(saved, bookmarks, signedIn = true)
        assertEquals(2, signedIn.size)
        val a = signedIn.first { it.id == "a" }
        val b = signedIn.first { it.id == "b" }
        assertEquals(RoomSource.PHONE, a.source)
        assertEquals(RoomSource.ACCOUNT, b.source)
        assertEquals(20L, b.openedAt)

        val signedOut = mergeRooms(saved, bookmarks, signedIn = false)
        assertEquals(listOf("a"), signedOut.map { it.id })
    }

    @Test fun `private pictures are scoped to the saved account and exclude anonymous rooms`() {
        val account = "1".repeat(64)
        val peer = "2".repeat(64)
        val saved = listOf(
            SavedRoomSummary("local", "Peer", false, 10, privatePeer = peer),
            SavedRoomSummary("account", "Peer", false, 10, account = account, privatePeer = peer),
            SavedRoomSummary("anonymous", "Peer", false, 10, anonymous = true, privatePeer = peer),
        )
        val signedOut = mergeRooms(saved, emptyList(), false)
        assertEquals(listOf(peer, null, null), signedOut.map { it.privatePeer })
        assertEquals(listOf(peer, peer, null), mergeRooms(saved, emptyList(), true, account).map { it.privatePeer })
        assertEquals(listOf(peer, null, null), mergeRooms(saved, emptyList(), true, "3".repeat(64)).map { it.privatePeer })
        val state = dev.forgesworn.kithmoot.ui.StartState(savedRooms = saved)
        assertEquals(listOf(peer), dev.forgesworn.kithmoot.ui.privateChatProfileScope(state).second)
        assertTrue(dev.forgesworn.kithmoot.ui.privateChatProfileScope(state.copy(publicProfiles = false)).second.isEmpty())
        assertTrue(dev.forgesworn.kithmoot.ui.privateChatProfileScope(state.copy(savedRooms = saved.map {
            it.copy(route = dev.forgesworn.kithmoot.relay.RoomRoute.NEARBY)
        })).second.isEmpty())
    }

    @Test fun `account bookmark picture identifies the other member and rejects unrelated policies`() {
        val account = "1".repeat(64)
        val peer = "2".repeat(64)
        val secret = ByteArray(32) { 1 }
        val link = dev.forgesworn.kithmoot.protocol.encodeJoinUrl("https://example.test/j/", secret, emptyList(),
            dev.forgesworn.kithmoot.session.dmPolicy(account, peer))
        val bookmarks = listOf(AccountRoom("private", link, "Peer", 10), AccountRoom("broken", "bad", "Group", 10))
        assertEquals(listOf(peer, null), mergeRooms(emptyList(), bookmarks, true, account).map { it.privatePeer })
        assertTrue(mergeRooms(emptyList(), bookmarks, true, "3".repeat(64)).all { it.privatePeer == null })
    }

    @Test fun `sortByActivity orders by time then label then id`() {
        val rooms = listOf(room(roomId = "z", label = "Zebra", openedAt = 100), room(roomId = "a", label = "apple", openedAt = 100),
            room(roomId = "b", label = "Apple", openedAt = 200))
        val sorted = sortByActivity(rooms) { null }
        assertEquals(listOf("a", "b", "z"), sorted.map { it.id })
    }

    @Test fun `viewing an old conversation changes neither last message time nor order`() {
        val old = room(roomId = "old", openedAt = 50)
        val newer = room(roomId = "new", openedAt = 60)
        val activity = mutableMapOf("old" to RoomActivity(100, null, true, 0, 0, emptyList()),
            "new" to RoomActivity(200, null, true, 0, 0, emptyList()))
        val viewed = old.copy(openedAt = 300)
        assertEquals(100L, activityAt(viewed, activity["old"]))
        assertEquals(listOf("new", "old"), sortByActivity(listOf(viewed, newer)) { activity[it.id] }.map { it.id })
        val before = roomRowState(old, activity["old"], null, null, 400, ZoneId.of("UTC"), Locale.UK, true)
        val after = roomRowState(viewed, activity["old"], null, null, 400, ZoneId.of("UTC"), Locale.UK, true)
        assertEquals(before.time, after.time)
        activity["old"] = activity.getValue("old").copy(latestAt = 500)
        assertEquals(listOf("old", "new"), sortByActivity(listOf(viewed, newer)) { activity[it.id] }.map { it.id })
    }

    @Test fun `unavailable history has no fabricated activity time`() {
        assertEquals(0L, activityAt(room(openedAt = 300), null))
        assertEquals("" to "", formatActivityTime(0, 400, ZoneId.of("UTC"), Locale.UK, true))
    }

    @Test fun `sortByActivity prefers newest activity over openedAt`() {
        val older = room(roomId = "old", openedAt = 100)
        val newer = room(roomId = "new", openedAt = 200)
        val activity = mapOf("old" to RoomActivity(250, null, true, 0, 0, emptyList()))
        val sorted = sortByActivity(listOf(older, newer)) { activity[it.id] }
        assertEquals(listOf("old", "new"), sorted.map { it.id })
    }

    @Test fun `holdOrder keeps previous order while held`() {
        assertEquals(listOf("a", "b", "c", "d"), holdOrder(listOf("a", "b", "c"), listOf("c", "a", "b", "d"), held = true))
        assertEquals(listOf("c", "a", "b", "d"), holdOrder(listOf("a", "b", "c"), listOf("c", "a", "b", "d"), held = false))
        assertEquals(listOf("c", "a", "b", "d"), holdOrder(null, listOf("c", "a", "b", "d"), held = true))
        assertEquals(listOf("a", "c"), holdOrder(listOf("a", "b", "c"), listOf("a", "c"), held = true))
    }

    @Test fun `roomRowState precedence`() {
        val zone = ZoneId.of("Europe/London")
        val now = 1_000_000L
        fun state(r: HomeRoom, activity: RoomActivity? = null, callRoomId: String? = null, signedInAs: String? = null) =
            roomRowState(r, activity, callRoomId, signedInAs, now, zone, Locale.UK, true)

        val callAndEnded = room(roomId = "x", ended = true)
        assertEquals("On a call now.", state(callAndEnded, callRoomId = "x").status)

        val ended = room(roomId = "y", ended = true)
        assertEquals("Ended. Its invite link no longer works.", state(ended).status)

        val pubkey = "0".repeat(64)
        val other = "1".repeat(64)
        val joinedAsOther = room(roomId = "j", account = pubkey)
        assertEquals("Joined as ${shortNpub(pubkey)}. Sign in with that account to open it.", state(joinedAsOther, signedInAs = other).status)
        assertNull(state(joinedAsOther, signedInAs = pubkey).status)

        val accountOnly = room(roomId = "b", source = RoomSource.ACCOUNT)
        assertEquals("From your other devices.", state(accountOnly).status)

        val anon = room(roomId = "t", anonymous = true)
        assertEquals("Tor-only room.", state(anon).status)

        val plain = room(roomId = "p")
        assertNull(state(plain).status)
    }

    @Test fun `roomRowState pass 2 precedence`() {
        val zone = ZoneId.of("Europe/London")
        val now = 1_000_000L
        val quiet = RoomActivity(now, null, readsChat = false, 0, 0, emptyList())
        assertEquals("Quiet room. Open it to read.",
            roomRowState(room(), quiet, null, null, now, zone, Locale.UK, true).status)

        val nameOf: (String) -> String = { "Rowan" }
        val own = RoomActivity(now, PreviewMessage("me", "hi", false), true, 0, 0, emptyList())
        assertEquals("You: hi", roomRowState(room(), own, null, null, now, zone, Locale.UK, true, "me", nameOf).status)

        val theirs = RoomActivity(now, PreviewMessage("them", "hi", false), true, 0, 0, emptyList())
        assertEquals("Rowan: hi", roomRowState(room(), theirs, null, null, now, zone, Locale.UK, true, "me", nameOf).status)

        val fileOnly = RoomActivity(now, PreviewMessage("them", "", true), true, 0, 0, emptyList())
        assertEquals("Rowan sent a file", roomRowState(room(), fileOnly, null, null, now, zone, Locale.UK, true, "me", nameOf).status)

        val nothing = RoomActivity(now, null, true, 0, 0, emptyList())
        assertEquals("No messages yet", roomRowState(room(), nothing, null, null, now, zone, Locale.UK, true, "me", nameOf).status)
    }

    @Test fun `formatActivityTime follows the day gap and the clock setting`() {
        val zone = ZoneId.of("Europe/London")
        val locale = Locale.UK
        fun at(s: String) = java.time.LocalDateTime.parse(s).atZone(zone).toEpochSecond()
        val now = at("2026-09-27T15:00:00")

        assertEquals("14:02" to "14:02", formatActivityTime(at("2026-09-27T14:02:00"), now, zone, locale, true))
        val (visible12, spoken12) = formatActivityTime(at("2026-09-27T14:02:00"), now, zone, locale, false)
        assertEquals(visible12, spoken12)
        assertTrue(visible12.lowercase().contains("2:02"), visible12)

        assertEquals("Yesterday" to "Yesterday", formatActivityTime(at("2026-09-26T09:00:00"), now, zone, locale, true))

        val (visibleMon, spokenMon) = formatActivityTime(at("2026-09-21T09:00:00"), now, zone, locale, true)
        assertEquals("Mon", visibleMon)
        assertEquals("Monday", spokenMon)

        val (visibleSept, spokenSept) = formatActivityTime(at("2026-09-19T09:00:00"), now, zone, locale, true)
        assertTrue(visibleSept.startsWith("19 Sep"), visibleSept)
        assertTrue(spokenSept.startsWith("19 Sep") && spokenSept.contains("September"), spokenSept)

        val (visibleOldYear, _) = formatActivityTime(at("2025-09-19T09:00:00"), now, zone, locale, true)
        assertTrue(visibleOldYear.contains("2025"), visibleOldYear)
    }

    @Test fun `homeLayout classifies by width then height`() {
        assertEquals(HomeLayout.COMPACT, homeLayout(360, 640))
        assertEquals(HomeLayout.SHORT, homeLayout(760, 360))
        assertEquals(HomeLayout.MEDIUM, homeLayout(700, 1000))
        assertEquals(HomeLayout.EXPANDED, homeLayout(1280, 800))
        assertEquals(HomeLayout.SHORT, homeLayout(900, 400))
        assertEquals(HomeLayout.COMPACT, homeLayout(599, 900))
        assertEquals(HomeLayout.EXPANDED, homeLayout(840, 480))
    }

    @Test fun `isReturning needs a room or an account`() {
        assertFalse(isReturning(emptyList(), signedIn = false))
        assertTrue(isReturning(emptyList(), signedIn = true))
        assertTrue(isReturning(listOf(room()), signedIn = false))
    }

    @Test fun `previewLine and presenceText match the web`() {
        val nameOf: (String) -> String = { "Rowan" }
        assertNull(previewLine(null, "me", nameOf))
        assertEquals("You: hi", previewLine(PreviewMessage("me", "hi", false), "me", nameOf))
        assertEquals("Rowan sent a file", previewLine(PreviewMessage("them", "", true), "me", nameOf))
        assertNull(previewLine(PreviewMessage("them", "", false), "me", nameOf))

        assertEquals("1 here" to "1 person here", presenceText(listOf(Presence("a", false))))
        assertEquals("3 here" to "2 people and 1 agent here",
            presenceText(listOf(Presence("a", false), Presence("b", false), Presence("c", true))))
        assertEquals("nobody here", presenceText(emptyList()).second)
    }

    @Test fun `a conference room says when it ends, and that it has ended`() {
        val london = ZoneId.of("Europe/London")
        val ends = 1_759_597_200L // Sat 4 Oct 2025, 18:00 in London
        val conference = room(canShareInvite = true).copy(endsAt = ends)
        assertEquals("Conference room. Ends Sat 4 Oct, 18:00.",
            roomRowState(conference, null, null, null, ends - 60, london, Locale.UK, true).status)
        assertEquals("This conference room ended on Sat 4 Oct, 18:00.",
            roomRowState(conference, null, "room-1", null, ends, london, Locale.UK, true).status)
        assertEquals(ends, mergeRooms(listOf(SavedRoomSummary("a", "A", false, 10, endsAt = ends)), emptyList(), false).single().endsAt)
    }

    @Test fun `a self-destructing room's row leaves the end to its pill, and its bookmark carries the flag`() {
        val london = ZoneId.of("Europe/London")
        val ends = 1_759_597_200L
        val doomed = room(canShareInvite = true).copy(endsAt = ends, destruct = true, startsAt = ends - 86_400)
        assertNull(roomRowState(doomed, null, null, null, ends - 60, london, Locale.UK, true).status)
        assertTrue(showsCountdown(doomed, ends - 60))
        assertFalse(showsCountdown(doomed, ends))
        assertFalse(showsCountdown(room(), ends))
        assertEquals("This conference room ended on Sat 4 Oct, 18:00.",
            roomRowState(doomed, null, null, null, ends, london, Locale.UK, true).status)
        val merged = mergeRooms(listOf(SavedRoomSummary("a", "A", false, 10, endsAt = ends, destruct = true, startsAt = 5)),
            listOf(AccountRoom("b", "link-b", "B", 20, endsAt = ends, destruct = true, startsAt = 7)), signedIn = true)
        assertEquals(listOf(true, true), merged.map { it.destruct })
        assertEquals(listOf<Long?>(5, 7), merged.map { it.startsAt })
        assertEquals(ends, merged.single { it.id == "b" }.endsAt)
        // Only tombstones left still shows the rooms list, so the rows can be seen and dismissed.
        assertTrue(isReturning(emptyList(), signedIn = false, tombstones = 1))
        assertFalse(isReturning(emptyList(), signedIn = false))
    }
}
