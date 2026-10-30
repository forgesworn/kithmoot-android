package dev.forgesworn.kithmoot.ui

import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.ui.room.PublicProfile
import dev.forgesworn.kithmoot.ui.room.decodePublicProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeoutOrNull

/** A slow connection is not a permanent absence of a profile. Retry missing
 * authors twice, within the caller's account/room and networking lifetime. */
internal suspend fun collectPublicProfiles(
    authors: List<String>,
    current: () -> Boolean,
    subscribe: (List<String>) -> Flow<NostrEvent>,
    now: () -> Long,
    accept: (String, PublicProfile) -> Unit,
) {
    val missing = authors.toMutableSet()
    val pauses = listOf(1_000L, 5_000L)
    repeat(3) { attempt ->
        if (!current() || missing.isEmpty()) return
        val requested = missing.toSet()
        try {
            withTimeoutOrNull(15_000L) {
                subscribe(requested.toList()).collect { event ->
                    if (!current()) return@collect
                    val profile = decodePublicProfile(event, requested, now()) ?: return@collect
                    missing.remove(event.pubkey)
                    accept(event.pubkey, profile)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* A transient read failure gets the same bounded retry. */ }
        if (missing.isEmpty() || !current() || attempt == 2) return
        delay(pauses[attempt])
    }
}

/** How long the room's participant set has to settle before its profiles are asked for:
 *  a call that ten people join in a few seconds is one request, not ten. */
internal const val PROFILE_BATCH_MS = 1_500L

/**
 * The authors still worth asking for: not already known, and not already
 * asked for in full this session without an answer. The room's lookup pool
 * lives for the room and asks only for these, rather than opening a new pool
 * and asking for every participant again each time somebody joins.
 */
internal fun profileAuthorsToAsk(authors: Collection<String>, known: Set<String>, givenUp: Set<String>): List<String> =
    authors.filter { it !in known && it !in givenUp }.distinct().take(500)

/** One entry per relay, however it is spelt: `wss://relay.primal.net` and `wss://relay.primal.net/` are one socket. */
internal fun distinctRelays(urls: List<String>): List<String> =
    urls.distinctBy { url -> runCatching { dev.forgesworn.kithmoot.protocol.canonicalRelayUrl(url) }.getOrDefault(url) }

/** Account metadata also names the self tile when directory lookups for
 * other people are off, or when the room's separate lookup has failed. */
internal fun RoomState.withAccountProfile(account: String, profile: PublicProfile): RoomState {
    if (selfParticipant != account || anonymous || !route.internet) return this
    val old = profiles[account]
    if (old != null && (old.createdAt > profile.createdAt || old.createdAt == profile.createdAt && old.eventId <= profile.eventId)) return this
    return copy(profiles = profiles + (account to profile)).withAccountName(account, profile.name)
}

/** A name already learned for this account remains local account state when
 * public networking is paused; that does not retain or load its picture. */
internal fun RoomState.withAccountName(account: String, name: String?): RoomState {
    if (selfParticipant != account || anonymous || name.isNullOrBlank()) return this
    return copy(tiles = tiles.map { if (it.isSelf && it.participant == account) it.copy(name = name) else it })
}
