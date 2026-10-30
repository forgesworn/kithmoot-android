package dev.forgesworn.kithmoot.service

import dev.forgesworn.kithmoot.protocol.CALL_BELL_TTL_SECONDS
import dev.forgesworn.kithmoot.protocol.KIND_CALL_BELL
import dev.forgesworn.kithmoot.protocol.KIND_ROOM_REKEY
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RelayPool
import dev.forgesworn.kithmoot.session.KIND_CHAT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/** What one watched room wants from the closed-app socket. An empty set asks for nothing of that kind. */
data class FeedInterest(
    val roomId: String,
    /** Day tags its bell could ring under now ([callBellTagsFor]); empty when it does not ring. */
    val bellTags: Set<String> = emptySet(),
    /** Its current and still-read past epoch ids; empty when it does not deliver chat. */
    val chatTags: Set<String> = emptySet(),
    /** The oldest chat it wants, unix seconds ([chatSince]). */
    val chatSince: Long = 0,
    /** Its authority and stable id, when it follows rekeys ([backgroundRekeyFilter]). */
    val rekeyAuthor: String? = null,
    val rekeyTag: String? = null,
)

enum class FeedKind { BELL, CHAT, REKEY }

/** Rooms per merged REQ: keeps each `#d` list modest, and 20 rooms to a REQ keeps even
 *  hundreds of saved rooms far below the 20 subscriptions a connection many relays allow. */
const val FEED_ROOMS_PER_REQ = 20

/**
 * One REQ's filters for [interests]: at most one bell, one chat and one rekey
 * filter, each covering every room that wants it. The chat filter asks from
 * the oldest room's [FeedInterest.chatSince]; [feedRoute] drops what is older
 * than each room's own.
 */
fun feedFilters(interests: List<FeedInterest>, now: Long): List<Filter> {
    val filters = mutableListOf<Filter>()
    val bell = interests.flatMap { it.bellTags }.distinct()
    // "#d", not "d": Filter's wire form for a tag filter (see relay/Filter.kt).
    if (bell.isNotEmpty()) filters += Filter(kinds = listOf(KIND_CALL_BELL), tags = mapOf("#d" to bell), since = now - CALL_BELL_TTL_SECONDS)
    val chat = interests.filter { it.chatTags.isNotEmpty() }
    if (chat.isNotEmpty()) filters += Filter(kinds = listOf(KIND_CHAT),
        tags = mapOf("#d" to chat.flatMap { it.chatTags }.distinct()), since = chat.minOf { it.chatSince })
    val rekey = interests.filter { it.rekeyAuthor != null && it.rekeyTag != null }
    if (rekey.isNotEmpty()) filters += Filter(kinds = listOf(KIND_ROOM_REKEY),
        authors = rekey.mapNotNull { it.rekeyAuthor }.distinct(), tags = mapOf("#d" to rekey.mapNotNull { it.rekeyTag }.distinct()))
    return filters
}

/**
 * Which rooms an event from a merged REQ belongs to, and as what. A rekey
 * matches only the room whose authority signed it: the merged filter crosses
 * every author with every room, so a relay could hand one room's authority's
 * event to another room's tag.
 */
fun feedRoute(event: NostrEvent, interests: List<FeedInterest>): List<Pair<String, FeedKind>> {
    val tag = event.tagValue("d") ?: return emptyList()
    return when (event.kind) {
        KIND_CALL_BELL -> interests.filter { tag in it.bellTags }.map { it.roomId to FeedKind.BELL }
        KIND_CHAT -> interests.filter { room ->
            room.chatTags.any { it.equals(tag, ignoreCase = true) } && event.createdAt >= room.chatSince
        }.map { it.roomId to FeedKind.CHAT }
        KIND_ROOM_REKEY -> interests.filter { it.rekeyTag == tag && it.rekeyAuthor == event.pubkey }.map { it.roomId to FeedKind.REKEY }
        else -> emptyList()
    }
}

/**
 * Every watched room's bell, chat and rekey subscriptions on one pool, merged
 * into as few REQs as there are chunks of [FEED_ROOMS_PER_REQ] rooms.
 *
 * Before, each room opened two or three REQs of its own on the shared socket,
 * so ten saved rooms were 20 to 30 subscriptions on one connection, past the
 * 20 that primal, ditto and offchain allow; the rest were refused. Rooms
 * joining or leaving, as a reconcile does them one by one, are settled for
 * [settleMs] and then resubscribed together.
 */
internal class BackgroundFeed(
    private val pool: RelayPool,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val settleMs: Long = 500,
) {
    class Member(val interest: () -> FeedInterest, val onEvent: (FeedKind, NostrEvent) -> Unit)

    private val members = linkedMapOf<Any, Member>()
    private var collectors: Job? = null
    private var pending: Job? = null

    @Synchronized fun join(member: Member): Any {
        val token = Any()
        members[token] = member
        resubscribeSoon()
        return token
    }

    /** True when no room is left on the feed. */
    @Synchronized fun leave(token: Any): Boolean {
        members.remove(token)
        if (members.isEmpty()) { pending?.cancel(); collectors?.cancel(); collectors = null; return true }
        resubscribeSoon()
        return false
    }

    @Synchronized fun end() { pending?.cancel(); collectors?.cancel(); members.clear() }

    private fun resubscribeSoon() {
        pending?.cancel()
        pending = scope.launch { delay(settleMs); resubscribe() }
    }

    @Synchronized private fun resubscribe() {
        collectors?.cancel()
        val chunks = members.values.toList().chunked(FEED_ROOMS_PER_REQ)
        collectors = scope.launch {
            for (chunk in chunks) launch {
                // What each room asked for when the REQ last went out, first send and every
                // reconnect alike, so an event is routed by what it was asked for.
                val asked = AtomicReference<List<Pair<Member, FeedInterest>>>(emptyList())
                pool.subscribe({
                    val snapshot = chunk.map { it to it.interest() }
                    asked.set(snapshot)
                    feedFilters(snapshot.map { it.second }, now())
                }).collect { event ->
                    val current = asked.get()
                    val routes = feedRoute(event, current.map { it.second }).toMap()
                    for ((member, interest) in current) routes[interest.roomId]?.let { kind -> member.onEvent(kind, event) }
                }
            }
        }
    }

    /** Subscriptions this feed holds on its pool's connection to each relay. */
    @Synchronized fun subscriptionCount(): Int = (members.size + FEED_ROOMS_PER_REQ - 1) / FEED_ROOMS_PER_REQ
}

/** How far past UTC midnight the closed-app listener rebuilds its rooms: spread, so every phone does not re-REQ in the same second. */
const val MIDNIGHT_SPREAD_MS = 10L * 60 * 1000

/**
 * The wait until the next rebuild of every watched room: midnight, plus a
 * random part of [MIDNIGHT_SPREAD_MS]. A bell's REQ already carries
 * tomorrow's day tag ([callBellTagsFor]), so a bell rung in the minutes after
 * midnight still arrives.
 */
fun midnightRebuildDelayMs(untilMidnightMs: Long, random: Random): Long = untilMidnightMs + random.nextLong(MIDNIGHT_SPREAD_MS)
