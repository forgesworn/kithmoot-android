package dev.forgesworn.kithmoot.ui.start

import dev.forgesworn.kithmoot.account.AccountRoom
import dev.forgesworn.kithmoot.account.shortNpub
import dev.forgesworn.kithmoot.storage.SavedRoomSummary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Pure logic for the merged home rooms list: no DOM, no Compose. It is cheap
 * to test against real dates and real room shapes, and pass 2 (live
 * previews, unread counts and presence, see design-home-rooms.md Q1) only
 * has to supply a non-null [RoomActivity] for these functions to use.
 */

internal enum class RoomSource { PHONE, ACCOUNT }

internal data class HomeRoom(
    val id: String, val label: String, val source: RoomSource, val openedAt: Long,
    val project: String?, val account: String?, val anonymous: Boolean,
    val secondary: Boolean, val ended: Boolean, val canShareInvite: Boolean,
)

/** Pass 2 fills this; null for every row in this pass. */
internal data class RoomActivity(
    val latestAt: Long?, val latest: PreviewMessage?, val readsChat: Boolean,
    val unreadPeople: Int, val unreadAgents: Int, val present: List<Presence>,
)

internal data class PreviewMessage(val participant: String, val text: String, val hasFiles: Boolean)
internal data class Presence(val participant: String, val agent: Boolean)

internal data class RoomRowState(val status: String?, val time: String, val timeSpoken: String)

internal enum class HomeLayout { COMPACT, SHORT, MEDIUM, EXPANDED }

/** Blank, or the stored fallback name a room gets before anybody names it,
 *  both read the same on screen: "Untitled room" (F8). Anything else is
 *  the name unchanged. */
internal fun roomLabel(name: String?, id: String): String {
    val value = name.orEmpty()
    if (value.isBlank() || value == "Room ${id.take(8)}") return "Untitled room"
    return value
}

/** One list of [HomeRoom]: every room saved on this phone, plus, when signed
 *  in, every account bookmark not already saved here. No second list. */
internal fun mergeRooms(saved: List<SavedRoomSummary>, bookmarks: List<AccountRoom>, signedIn: Boolean): List<HomeRoom> {
    val savedIds = saved.map { it.id }.toSet()
    val fromSaved = saved.map { room ->
        HomeRoom(
            id = room.id, label = roomLabel(room.name, room.id), source = RoomSource.PHONE,
            openedAt = room.openedAt, project = room.project, account = room.account,
            anonymous = room.anonymous, secondary = room.secondary, ended = room.ended,
            canShareInvite = room.canShareInvite,
        )
    }
    if (!signedIn) return fromSaved
    val fromBookmarks = bookmarks.filter { it.roomId !in savedIds }.map { bookmark ->
        HomeRoom(
            id = bookmark.roomId, label = roomLabel(bookmark.name, bookmark.roomId), source = RoomSource.ACCOUNT,
            openedAt = bookmark.openedAt, project = null, account = null, anonymous = false,
            secondary = false, ended = false, canShareInvite = false,
        )
    }
    return fromSaved + fromBookmarks
}

/** The moment a room last had something happen in it: the newest message
 *  this device can read (pass 2), or the last time this device opened it. */
internal fun activityAt(room: HomeRoom, activity: RoomActivity?): Long = maxOf(room.openedAt, activity?.latestAt ?: 0L)

/** Newest activity first, like the web. Ties break by label, then id, so
 *  the order is stable and never depends on list insertion order. */
internal fun sortByActivity(rooms: List<HomeRoom>, activity: (HomeRoom) -> RoomActivity?): List<HomeRoom> =
    rooms.sortedWith(
        compareByDescending<HomeRoom> { activityAt(it, activity(it)) }
            .thenBy { it.label.lowercase(Locale.ROOT) }
            .thenBy { it.id },
    )

/** Rows hold their order while the list has focus, is scrolling, has a menu
 *  open, or TalkBack is on; new rooms still join at the end. */
internal fun holdOrder(previous: List<String>?, sorted: List<String>, held: Boolean): List<String> {
    if (previous == null || !held) return sorted
    val sortedIds = sorted.toSet()
    val previousIds = previous.toSet()
    val kept = previous.filter { it in sortedIds }
    val appended = sorted.filter { it !in previousIds }
    return kept + appended
}

/** The row's second line, in the precedence order of design-home-rooms.md
 *  section 6. [selfParticipant] and [nameOf] are pass 2 only: this pass
 *  always calls with a null [activity], so every row falls to the
 *  Tor-only-or-nothing branch. */
internal fun roomRowState(
    room: HomeRoom, activity: RoomActivity?, callRoomId: String?, signedInAs: String?,
    now: Long, zone: ZoneId, locale: Locale, is24Hour: Boolean,
    selfParticipant: String? = null, nameOf: (String) -> String = { it },
): RoomRowState {
    val status = when {
        room.id == callRoomId -> "On a call now."
        room.ended -> "Ended. Its invite link no longer works."
        room.account != null && room.account != signedInAs ->
            "Joined as ${shortNpub(room.account)}. Sign in with that account to open it."
        room.source == RoomSource.ACCOUNT -> "From your other devices."
        activity == null -> if (room.anonymous) "Tor-only room." else null
        !activity.readsChat -> "Quiet room. Open it to read."
        else -> previewLine(activity.latest, selfParticipant.orEmpty(), nameOf) ?: "No messages yet"
    }
    val (time, timeSpoken) = formatActivityTime(activityAt(room, activity), now, zone, locale, is24Hour)
    return RoomRowState(status, time, timeSpoken)
}

/** en-GB-shaped time for a row's second line, following the device's
 *  12/24-hour setting and adding the year outside the current year. */
internal fun formatActivityTime(at: Long, now: Long, zone: ZoneId, locale: Locale, is24Hour: Boolean): Pair<String, String> {
    val atDate = Instant.ofEpochSecond(at).atZone(zone)
    val nowDate = Instant.ofEpochSecond(now).atZone(zone)
    val days = ChronoUnit.DAYS.between(atDate.toLocalDate(), nowDate.toLocalDate())
    return when {
        days <= 0 -> {
            val text = DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm a", locale).format(atDate)
            text to text
        }
        days == 1L -> "Yesterday" to "Yesterday"
        days in 2..6 -> DateTimeFormatter.ofPattern("EEE", locale).format(atDate) to
            DateTimeFormatter.ofPattern("EEEE", locale).format(atDate)
        else -> {
            val yearSuffix = if (atDate.year != nowDate.year) " yyyy" else ""
            DateTimeFormatter.ofPattern("d MMM$yearSuffix", locale).format(atDate) to
                DateTimeFormatter.ofPattern("d MMMM$yearSuffix", locale).format(atDate)
        }
    }
}

/** The rooms list's second line for its newest message (pass 2): "You: …"
 *  for the person's own, "{Name}: …" for anybody else's, "{Name} sent a
 *  file" with no caption, or null when there is nothing to preview. */
internal fun previewLine(message: PreviewMessage?, selfParticipant: String, nameOf: (String) -> String): String? {
    if (message == null) return null
    val who = if (message.participant == selfParticipant) "You" else nameOf(message.participant)
    return when {
        message.text.isNotEmpty() -> "$who: ${message.text}"
        message.hasFiles -> "$who sent a file"
        else -> null
    }
}

/** Who is here, spoken and visible (pass 2): "1 here" / "1 person here",
 *  "3 here" / "2 people and 1 agent here". */
internal fun presenceText(present: List<Presence>): Pair<String, String> {
    val agents = present.count { it.agent }
    val people = present.size - agents
    val visible = "${present.size} here"
    val parts = buildList {
        if (people > 0) add("$people ${if (people == 1) "person" else "people"}")
        if (agents > 0) add("$agents agent${if (agents == 1) "" else "s"}")
    }
    val spoken = if (parts.isNotEmpty()) "${parts.joinToString(" and ")} here" else "nobody here"
    return visible to spoken
}

/** Classifies the space available to home, measured, not guessed from the
 *  device (design-home-rooms.md section 5). */
internal fun homeLayout(widthDp: Int, heightDp: Int): HomeLayout = when {
    widthDp < 600 -> HomeLayout.COMPACT
    heightDp < 480 -> HomeLayout.SHORT
    widthDp < 840 -> HomeLayout.MEDIUM
    else -> HomeLayout.EXPANDED
}

/** The web's rule: returning once there is a room to show, or an account to
 *  fetch rooms for. Otherwise the cold, first-run state. */
internal fun isReturning(rooms: List<HomeRoom>, signedIn: Boolean): Boolean = rooms.isNotEmpty() || signedIn
