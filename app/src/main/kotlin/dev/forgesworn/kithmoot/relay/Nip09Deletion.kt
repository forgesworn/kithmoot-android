package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.session.ChatStream
import dev.forgesworn.kithmoot.session.KIND_CHAT
import dev.forgesworn.kithmoot.session.ownEnvelope2Events
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Take back what one device signed in a room that self-destructs, as far as
 * NIP-09 allows: ask the room's relays what the device key wrote, and ask them
 * to delete it, in kind-5 requests signed by that same key. The web client's
 * `deleteSignedEvents` (src/self-destruct.ts) and the device step of its
 * tidy-up (app/src/room-tidy-up.ts), for a phone.
 *
 * A deletion request is a request. A relay may decline it or keep a copy, and
 * anything another member already copied stays with them. Each device can
 * delete only what it signed, which is why every member's device runs this.
 *
 * Chat in envelope 2 is signed by a key made for each message, so it is found
 * by the room's chat streams rather than by author, and asked for one message
 * at a time under that message's key (see [deleteOwnEvents]).
 */
object Nip09Deletion {
    const val KIND_DELETION = 5
    /** Event ids named in one request, as the web client sends them. */
    const val IDS_PER_REQUEST = 300
    /** A page of what a key signed, asked for in one go. */
    const val QUERY_LIMIT = 1_000
    /** Rounds of ask-then-delete, so a room with more than a page is emptied. */
    const val MAX_ROUNDS = 5
    const val CONTENT = "This KithMoot room self-destructed."
    /** Chat streams named in one filter's `#d`. */
    const val STREAMS_PER_FILTER = 20

    /** What a run could and could not do. */
    data class Report(
        /** Events found, other than deletion requests. */
        val found: Int = 0,
        /** Events named in a request a relay took. */
        val requested: Int = 0,
        /** Events named in a request no relay took, or that could not be sent. */
        val failed: Int = 0,
        /** False when the time ran out, or a question went unanswered. */
        val complete: Boolean = true,
        /** At least one relay answered what the key signed: false means no
         *  relay could be asked at all, and the run is worth trying again. */
        val reached: Boolean = false,
    )

    /** An addressable event's address, `kind:pubkey:d`, or null for any other. */
    fun address(event: NostrEvent): String? {
        if (event.kind !in 30_000..39_999) return null
        val d = event.tags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1) ?: ""
        return "${event.kind}:${event.pubkey}:$d"
    }

    /**
     * The kind-5 requests that name [events], signed with [secretKey], at most
     * [IDS_PER_REQUEST] ids each: an `e` tag per event, an `a` tag per
     * addressable one (some relays honour only one of the two), then a `k` tag
     * per kind named. Deletion requests themselves are never named (a deletion
     * is the record that it was asked), nor anything another key signed.
     */
    fun requests(secretKey: ByteArray, events: Collection<NostrEvent>, now: Long): List<NostrEvent> {
        val author = Schnorr.publicKeyHex(secretKey)
        val mine = events.asSequence()
            .filter { it.kind != KIND_DELETION && it.pubkey.equals(author, ignoreCase = true) }
            .distinctBy { it.id }
            .sortedWith(compareBy<NostrEvent> { it.createdAt }.thenBy { it.id })
            .toList()
        return mine.chunked(IDS_PER_REQUEST).map { group ->
            val tags = buildList {
                group.forEach { add(listOf("e", it.id)) }
                group.mapNotNull(::address).distinct().forEach { add(listOf("a", it)) }
                group.map { it.kind }.distinct().sorted().forEach { add(listOf("k", it.toString())) }
            }
            Events.sign(secretKey, KIND_DELETION, now, tags, CONTENT)
        }
    }

    /**
     * Ask [transport]'s relays for everything [secretKey] signed and ask them
     * to delete it, round after round while a page comes back full, within
     * [timeoutMs] overall. Best effort: never throws (bar cancellation), and
     * says what it could and could not do.
     *
     * A chat message in envelope 2 is signed by an outer key made for it alone, which
     * no `authors` query names. With [streams] (see `savedRoomChatStreams`) the run then
     * reads those chat streams, keeps the messages this device wrote
     * (`ownEnvelope2Events`) and asks for each under its own outer key: one request per
     * message, zeroed after use. Without them only what [secretKey] signed is asked for.
     */
    suspend fun deleteOwnEvents(
        transport: RoomTransport,
        secretKey: ByteArray,
        now: () -> Long,
        timeoutMs: Long = 20_000,
        queryTimeoutMs: Long = 10_000,
        streams: List<ChatStream> = emptyList(),
    ): Report {
        val author = Schnorr.publicKeyHex(secretKey)
        var report = Report()
        val asked = HashSet<String>()
        val finished = withTimeoutOrNull(timeoutMs) {
            for (round in 0 until MAX_ROUNDS) {
                val found = try {
                    transport.queryAvailable(listOf(Filter(authors = listOf(author), limit = QUERY_LIMIT)), queryTimeoutMs)
                } catch (e: CancellationException) { throw e
                } catch (_: Exception) { report = report.copy(complete = false); break }
                report = report.copy(reached = true)
                val fresh = found.filter { it.kind != KIND_DELETION && it.pubkey.equals(author, ignoreCase = true) && it.id !in asked }
                    .distinctBy { it.id }
                report = report.copy(found = report.found + fresh.size)
                if (fresh.isEmpty()) break
                for (request in requests(secretKey, fresh, now())) {
                    val named = request.tags.count { it.firstOrNull() == "e" }
                    request.tags.filter { it.firstOrNull() == "e" }.forEach { asked.add(it[1]) }
                    val taken = try { transport.publishConfirmed(request, queryTimeoutMs) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { false }
                    report = if (taken) report.copy(requested = report.requested + named)
                        else report.copy(failed = report.failed + named, complete = false)
                }
                if (fresh.size < QUERY_LIMIT) break
            }
            if (streams.isNotEmpty()) report = deleteOwnEnvelope2(transport, secretKey, streams, now, queryTimeoutMs, report)
            true
        }
        return if (finished == null) report.copy(complete = false) else report
    }

    /**
     * The envelope-2 pass of [deleteOwnEvents]: [streams] read a page at a time, paging back by
     * time while a page is full (other members' messages fill pages too), and one request per
     * message this device wrote, signed by that message's outer key.
     */
    private suspend fun deleteOwnEnvelope2(
        transport: RoomTransport,
        deviceKey: ByteArray,
        streams: List<ChatStream>,
        now: () -> Long,
        queryTimeoutMs: Long,
        start: Report,
    ): Report {
        var report = start
        val asked = HashSet<String>()
        for (group in streams.chunked(STREAMS_PER_FILTER)) {
            var until: Long? = null
            for (round in 0 until MAX_ROUNDS) {
                val filter = Filter(kinds = listOf(KIND_CHAT), tags = mapOf("#d" to group.map { it.d }), until = until, limit = QUERY_LIMIT)
                val read = try {
                    transport.queryAvailable(listOf(filter), queryTimeoutMs)
                } catch (e: CancellationException) { throw e
                } catch (_: Exception) { report = report.copy(complete = false); break }
                report = report.copy(reached = true)
                val own = ownEnvelope2Events(read.filter { it.id !in asked }, group, deviceKey).distinctBy { it.event.id }
                report = report.copy(found = report.found + own.size)
                try {
                    for (message in own) {
                        asked.add(message.event.id)
                        val request = requests(message.secretKey, listOf(message.event), now()).single()
                        val taken = try { transport.publishConfirmed(request, queryTimeoutMs) }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { false }
                        report = if (taken) report.copy(requested = report.requested + 1)
                            else report.copy(failed = report.failed + 1, complete = false)
                    }
                } finally {
                    own.forEach { it.secretKey.fill(0) }
                }
                if (read.size < QUERY_LIMIT) break
                val oldest = read.minOf { it.createdAt }
                if (until != null && oldest >= until) break
                until = oldest
            }
        }
        return report
    }
}
