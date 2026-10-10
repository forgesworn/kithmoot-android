package dev.forgesworn.kithmoot.ui.start

import dev.forgesworn.kithmoot.account.AccountRoom
import dev.forgesworn.kithmoot.account.shortNpub
import dev.forgesworn.kithmoot.storage.SavedRoomSummary
import dev.forgesworn.kithmoot.protocol.conferenceEnded
import dev.forgesworn.kithmoot.session.conferenceEndedMessage
import dev.forgesworn.kithmoot.session.conferenceEndsLine
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
    /** A conference room's end, unix seconds; null for a room that does not end. */
    val endsAt: Long? = null,
    /** Pinned on this device; account bookmarks are never pinned. */
    val pinned: Boolean = false,
    /** The room self-destructs when it ends: its pill is green, amber, red. */
    val destruct: Boolean = false,
    /** When this device first knew the room, for scaling the countdown. */
    val startsAt: Long? = null,
    val privatePeer: String? = null,
)

/** Pass 2 fills this; null for every row in this pass. */
internal data class RoomActivity(
    val latestAt: Long?, val latest: PreviewMessage?, val readsChat: Boolean,
    val unreadPeople: Int, val unreadAgents: Int, val present: List<Presence>,
)

internal data class PreviewMessage(val participant: String, val text: String, val hasFiles: Boolean)
internal data class Presence(val participant: String, val agent: Boolean)

internal data class RoomRowState(val status: String?, val time: String, val timeSpoken: String)

internal const val NO_MESSAGES_YET = "No messages yet"

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
internal fun mergeRooms(saved: List<SavedRoomSummary>, bookmarks: List<AccountRoom>, signedIn: Boolean, account: String? = null): List<HomeRoom> {
    val savedIds = saved.map { it.id }.toSet()
    val fromSaved = saved.map { room ->
        HomeRoom(
            id = room.id, label = roomLabel(room.name, room.id), source = RoomSource.PHONE,
            openedAt = room.openedAt, project = room.project, account = room.account,
            anonymous = room.anonymous, secondary = room.secondary, ended = room.ended,
            canShareInvite = room.canShareInvite, endsAt = room.endsAt, pinned = room.pinned,
            destruct = room.destruct, startsAt = room.startsAt,
            privatePeer = room.privatePeer.takeIf { !room.anonymous && (room.account == null || room.account == account) },
        )
    }
    if (!signedIn) return fromSaved
    val fromBookmarks = bookmarks.filter { it.roomId !in savedIds }.map { bookmark ->
        HomeRoom(
            id = bookmark.roomId, label = roomLabel(bookmark.name, bookmark.roomId), source = RoomSource.ACCOUNT,
            openedAt = bookmark.openedAt, project = null, account = null, anonymous = false,
            secondary = false, ended = false, canShareInvite = false,
            endsAt = bookmark.endsAt, destruct = bookmark.destruct, startsAt = bookmark.startsAt,
            privatePeer = account?.let { self -> runCatching {
                val policy = dev.forgesworn.kithmoot.protocol.decodeInvitationUrl(bookmark.link)?.policy
                    ?: dev.forgesworn.kithmoot.protocol.decodeJoinUrl(bookmark.link).policy
                dev.forgesworn.kithmoot.session.dmPeer(policy, self)
            }.getOrNull() },
        )
    }
    return fromSaved + fromBookmarks
}

/** Latest readable message time. Opening a conversation is a read action,
 * not new activity. Zero means no readable message time is known. */
internal fun activityAt(@Suppress("UNUSED_PARAMETER") room: HomeRoom, activity: RoomActivity?): Long =
    activity?.latestAt?.coerceAtLeast(0L) ?: 0L

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

internal enum class HomeSection(val label: String, val foldable: Boolean) {
    PINNED("Pinned", false), UNREAD("Unread", false), RECENT("Recent", false), OLDER("Older", true),
    OTHER("Other rooms", false), ENDED("Ended", true),
}

/** A run of rooms under one heading; a null [section] is the flat list, with no heading. */
internal data class RoomSectionGroup(val section: HomeSection?, val rooms: List<HomeRoom>)

internal const val SECTIONING_MAX_FLAT_ROOMS = 8
internal const val RECENT_WINDOW_SECONDS = 7L * 24 * 60 * 60

/** The one section a room belongs to (room-list-sections.md section 1): pinned
 *  beats everything, ended beats unread, and an unpinned read room is Recent
 *  until it has been quiet for seven days. */
internal fun sectionOf(room: HomeRoom, activity: RoomActivity?, now: Long): HomeSection = when {
    room.pinned -> HomeSection.PINNED
    room.ended || conferenceEnded(room.endsAt, now) -> HomeSection.ENDED
    (activity?.unreadPeople ?: 0) > 0 -> HomeSection.UNREAD
    activityAt(room, activity) <= 0L -> HomeSection.OTHER
    activityAt(room, activity) >= now - RECENT_WINDOW_SECONDS -> HomeSection.RECENT
    else -> HomeSection.OLDER
}

/** Like [holdOrder], for sections: while the list is held a room keeps the
 *  section it was in, so it does not jump under the pointer. */
internal fun holdSections(previous: Map<String, HomeSection>?, current: Map<String, HomeSection>, held: Boolean): Map<String, HomeSection> =
    if (previous == null || !held) current else current.mapValues { (id, section) -> previous[id] ?: section }

internal fun matchesQuery(room: HomeRoom, query: String): Boolean =
    query.isBlank() || room.label.contains(query.trim(), true) || room.id.contains(query.trim(), true)

/** Groups [rooms] (already in activity order and already project-filtered).
 *  A search, or eight rooms or fewer, gives one flat list with pinned rooms
 *  first and no headings, so nothing is ever hidden in a closed fold. */
internal fun groupRooms(rooms: List<HomeRoom>, query: String, sections: Map<String, HomeSection>): List<RoomSectionGroup> {
    if (query.isNotBlank() || rooms.size <= SECTIONING_MAX_FLAT_ROOMS) {
        val (pinned, rest) = rooms.filter { matchesQuery(it, query) }.partition { it.pinned }
        return listOf(RoomSectionGroup(null, pinned + rest))
    }
    return HomeSection.entries.mapNotNull { section ->
        rooms.filter { sections[it.id] == section }.takeIf { it.isNotEmpty() }?.let { RoomSectionGroup(section, it) }
    }
}

/** The heading's text: the label, plus the count while the section is folded. */
internal fun sectionHeading(section: HomeSection, count: Int, folded: Boolean): String =
    if (folded) "${section.label} · $count" else section.label

/** Avatar palette (room-list-sections.md section 2): light, dark. White text passes 4.5:1 on every one. */
private val AVATAR_PALETTE = listOf(
    0xFF0B6B8A to 0xFF0E7FA3, 0xFF6A4FB3 to 0xFF7A5FC4, 0xFFA2431F to 0xFFB54C25, 0xFF2F6F3E to 0xFF357D46,
    0xFF8A3A6B to 0xFF9C4479, 0xFF5B5F1C to 0xFF6B7020, 0xFF1F5F9E to 0xFF2A6DB0, 0xFF7A4A12 to 0xFF8C5616,
)

/** `parseInt(roomId.slice(0, 2), 16) % 8`, as ARGB: the light or the dark colour. */
internal fun avatarColour(roomId: String, dark: Boolean): Long {
    val index = (roomId.take(2).toIntOrNull(16) ?: 0) % AVATAR_PALETTE.size
    return AVATAR_PALETTE[index].let { if (dark) it.second else it.first }
}

/** The room's first letter or digit, uppercased, else `#`. */
internal fun avatarLetter(label: String): String =
    label.firstOrNull { it.isLetterOrDigit() }?.toString()?.uppercase(Locale.ROOT) ?: "#"

/** The row's second line, in the precedence order of design-home-rooms.md
 *  section 6. [selfParticipant] and [nameOf] are pass 2 only: this pass
 *  always calls with a null [activity], so every row falls to the
 *  Tor-only-or-nothing branch. */
internal fun roomRowState(
    room: HomeRoom, activity: RoomActivity?, callRoomId: String?, signedInAs: String?,
    now: Long, zone: ZoneId, locale: Locale, is24Hour: Boolean,
    selfParticipant: String? = null, nameOf: (String) -> String = { it },
    latestMessageAt: Long? = null,
): RoomRowState {
    val conferenceOver = conferenceEnded(room.endsAt, now)
    val status = when {
        room.id == callRoomId && !conferenceOver -> "On a call now."
        conferenceOver -> conferenceEndedMessage(room.endsAt!!, zone, locale)
        room.ended -> "Ended. Its invite link no longer works."
        room.account != null && room.account != signedInAs ->
            "Joined as ${shortNpub(room.account)}. Sign in with that account to open it."
        room.source == RoomSource.ACCOUNT -> "From your other devices."
        // A room that self-destructs says so in its countdown pill instead.
        room.endsAt != null && room.destruct && activity == null -> null
        room.endsAt != null && activity == null -> "Conference room. ${conferenceEndsLine(room.endsAt, zone, locale)}."
        activity == null -> if (room.anonymous) "Tor-only room." else null
        !activity.readsChat -> "Quiet room. Open it to read."
        else -> previewLine(activity.latest, selfParticipant.orEmpty(), nameOf) ?: NO_MESSAGES_YET
    }
    val (time, timeSpoken) = formatActivityTime(latestMessageAt ?: activityAt(room, activity), now, zone, locale, is24Hour)
    return RoomRowState(status, time, timeSpoken)
}

/** en-GB-shaped time for a row's second line, following the device's
 *  12/24-hour setting and adding the year outside the current year. */
internal fun formatActivityTime(at: Long, now: Long, zone: ZoneId, locale: Locale, is24Hour: Boolean): Pair<String, String> {
    if (at <= 0L) return "" to ""
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
internal fun isReturning(rooms: List<HomeRoom>, signedIn: Boolean, tombstones: Int = 0): Boolean = rooms.isNotEmpty() || signedIn || tombstones > 0

/** Whether a row shows the countdown pill: a room with an end still to come. */
internal fun showsCountdown(room: HomeRoom, now: Long): Boolean = room.endsAt != null && !room.ended && !conferenceEnded(room.endsAt, now)
