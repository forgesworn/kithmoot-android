package dev.forgesworn.kithmoot.media

import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.PublicCallRelayAdvert
import dev.forgesworn.kithmoot.protocol.PublicCallRelayDisplay
import dev.forgesworn.kithmoot.protocol.PublicRelayAdvert
import dev.forgesworn.kithmoot.protocol.PublicRelayAdvertBook
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RelayChoice
import dev.forgesworn.kithmoot.relay.RelayHealth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Finding public call relays over Nostr, on this device only
 * (docs/2026-10-25-public-call-relays-android.md, and the web design it
 * extends). Off until the person turns it on. KithMoot ships no list of call
 * relays and contacts no host of its own for this.
 */
internal object PublicRelayDiscovery {
    /** Client rule 5: at most 64 adverts considered. */
    const val QUERY_LIMIT = 64
    /** The one query closes at the end of stored events or after this long. */
    const val QUERY_TIMEOUT_MS = 5000L
    /** Client rule 5: at most three credential endpoints for one call, counting the check. */
    const val CALL_OPERATORS = 3

    fun filter(now: Long): Filter = Filter(
        kinds = listOf(PublicCallRelayAdvert.KIND),
        tags = mapOf("#d" to listOf(PublicCallRelayAdvert.IDENTIFIER)),
        since = now - PublicCallRelayAdvert.MAX_LIFETIME,
        limit = QUERY_LIMIT,
    )

    /**
     * The relays the one query goes to: this account's Connections entries
     * marked read, less any whose health this session says is failing (a
     * connection that "failed", a read that was "refused"). No history means
     * it is asked. Never a room's relays, an invitation's hints or a relay
     * added for this.
     */
    fun queryRelays(choices: List<RelayChoice>, health: Map<String, RelayHealth>): List<String> {
        val byUrl = health.mapKeys { it.key.removeSuffix("/") }
        return choices.filter { choice ->
            val known = byUrl[choice.url.removeSuffix("/")]
            choice.read && (known == null || !(known.connection.contains("failed", true) || known.read.contains("refused", true)))
        }.map { it.url }.distinct()
    }

    /** At most 64 events, the newest, oldest first so a newer one supersedes in order. */
    fun newest(events: List<NostrEvent>): List<NostrEvent> = events.distinctBy { it.id }
        .sortedWith(compareByDescending<NostrEvent> { it.createdAt }.thenBy { it.id })
        .take(QUERY_LIMIT).reversed()
}

/** One relay as the picker shows it. Hostname and region are as advertised; the note is cleaned. */
internal data class PublicRelayListing(
    val key: String,
    val shortKey: String,
    /** ASCII; an internationalised name is never shown decoded. */
    val host: String,
    /** The operator's own claim, shown as a code. */
    val region: String?,
    /** The operator's own claim, cleaned for display. */
    val note: String?,
    val lastGood: Boolean,
    /** The last good relay's hostname, now advertised under another key. */
    val knownHostNewKey: Boolean,
)

/** Whether finding public call relays is on, as far as this run can tell. */
internal enum class PublicCallRelayMode { OFF, ON, UNAVAILABLE }

/**
 * The switch, the adverts found this run, the person's selection and the
 * logins held for one call. Adverts and logins stay in memory; the store
 * keeps only the last good relay and the replay watermarks.
 */
internal class PublicCallRelays(
    private val store: PublicCallRelayStore,
    private val fetchLogin: suspend (PublicRelayAdvert) -> PublicRelayLoginResult,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val gate = Mutex()
    private val searching = Mutex()
    @Volatile private var loaded = false
    @Volatile var mode: PublicCallRelayMode = PublicCallRelayMode.OFF
        private set
    private var state: PublicCallRelayState? = null
    private var book = PublicRelayAdvertBook()
    @Volatile var selected: String? = null
        private set
    private val logins = HashMap<String, PublicRelayLogin>()
    /** Moves whenever logins are forgotten, so one fetched across a leave is not kept. */
    private var loginGeneration = 0L
    private val contacted = LinkedHashSet<String>()
    @Volatile var searchedAt = 0L
        private set
    private var generation = 0L

    val enabled: Boolean get() = mode == PublicCallRelayMode.ON
    val lastGood: PublicCallRelayLastGood? get() = synchronized(this) { state?.lastGood }

    /** Reads the file once. An unreadable file is treated as off and never written. */
    suspend fun load(): PublicCallRelayMode = gate.withLock {
        if (!loaded) {
            when (val read = withContext(io) { store.read() }) {
                PublicCallRelayRead.Off -> { mode = PublicCallRelayMode.OFF; synchronized(this) { state = null } }
                is PublicCallRelayRead.On -> synchronized(this) {
                    state = read.state
                    book = PublicRelayAdvertBook(restored = read.state.watermarks, restoredAt = clock())
                    mode = PublicCallRelayMode.ON
                }
                PublicCallRelayRead.Unavailable -> { mode = PublicCallRelayMode.UNAVAILABLE; synchronized(this) { state = null } }
            }
            loaded = true
        }
        mode
    }

    /**
     * Turning it on starts an empty file; turning it off forgets everything
     * it kept: watermarks, the last good relay, adverts, the selection and
     * every login. Off is allowed from an unreadable file; on is not.
     * Throws if the file could not be written or cleared.
     */
    suspend fun setEnabled(on: Boolean) {
        load()
        gate.withLock {
            synchronized(this) {
                generation++
                selected = null
                logins.clear()
                contacted.clear()
                searchedAt = 0
                book = PublicRelayAdvertBook()
            }
            if (on) {
                check(mode != PublicCallRelayMode.UNAVAILABLE) { "The public call relay file could not be read." }
                val fresh = PublicCallRelayState()
                withContext(io) { store.write(fresh) }
                synchronized(this) { state = fresh }
                mode = PublicCallRelayMode.ON
            } else {
                synchronized(this) { state = null }
                mode = PublicCallRelayMode.OFF
                withContext(io) { store.clear() }
            }
        }
    }

    private suspend fun persist() {
        val save = synchronized(this) {
            val current = state ?: return
            current.copy(watermarks = book.watermarks(clock())).also { state = it }
        }
        // Kept for this run only if it cannot be saved.
        try { withContext(io) { if (mode == PublicCallRelayMode.ON) store.write(save) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { }
    }

    /**
     * One discovery query through [search], which throws when no relay could
     * be asked. A second caller waits for the first and gets its result.
     */
    suspend fun discover(search: suspend (Long) -> List<NostrEvent>): List<PublicRelayListing> {
        load()
        check(enabled) { "Public call relays are off." }
        val started = searchedAt
        searching.withLock {
            if (searchedAt != started) return listing()
            val asked = synchronized(this) { generation }
            val events = PublicRelayDiscovery.newest(search(clock()))
            synchronized(this) {
                if (asked != generation) return emptyList()
                for (event in events) runCatching { book.accept(event, clock()) }
                searchedAt = System.currentTimeMillis().coerceAtLeast(started + 1)
            }
            persist()
        }
        return listing()
    }

    private fun allowed(): List<PublicRelayAdvert> = synchronized(this) {
        book.open(clock()).filter { advert -> advert.host?.let(PublicCallRelayAdvert::hostAllowed) == true }
    }

    /** Sorted on this device only: the last good relay first, then by hostname. */
    fun listing(): List<PublicRelayListing> {
        if (!enabled) return emptyList()
        val good = lastGood
        return allowed().map { advert ->
            val note = advert.offer?.note?.let(PublicCallRelayDisplay::cleanOperatorNote)?.takeIf { it.isNotEmpty() }
            PublicRelayListing(
                key = advert.publicKey,
                shortKey = PublicCallRelayDisplay.shortTransportKey(advert.publicKey),
                host = advert.host!!,
                region = advert.region,
                note = note,
                lastGood = good?.key == advert.publicKey,
                knownHostNewKey = good != null && good.key != advert.publicKey && good.host == advert.host,
            )
        }.sortedWith(compareByDescending<PublicRelayListing> { it.lastGood }.thenBy { it.host }.thenBy { it.key })
            .take(PublicRelayDiscovery.QUERY_LIMIT)
    }

    fun select(key: String?) {
        selected = key?.takeIf { wanted -> allowed().any { it.publicKey == wanted } }
    }

    fun advert(key: String): PublicRelayAdvert? = synchronized(this) {
        book.openFrom(key, clock())?.takeIf { it.host?.let(PublicCallRelayAdvert::hostAllowed) == true }
    }

    /** The relays this person chose that a call may use, in order, at most three. */
    fun callCandidates(): List<String> =
        if (!enabled) emptyList() else listOfNotNull(selected, lastGood?.key).distinct().take(PublicRelayDiscovery.CALL_OPERATORS)

    /**
     * A login from the chosen operator: the one already held (from the check)
     * while it has more than two minutes left, otherwise a fresh one. A fourth
     * operator in one call's budget is refused without being contacted.
     */
    suspend fun login(key: String, fresh: Boolean = false): PublicRelayLoginResult {
        val asked: Long
        val askedLogins: Long
        val advert: PublicRelayAdvert
        synchronized(this) {
            if (!enabled) return PublicRelayLoginResult.Failed
            val held = logins[key]
            if (!fresh && held != null && held.expiresAt - clock() > LOGIN_REUSE_MARGIN_SECONDS) return PublicRelayLoginResult.Issued(held)
            if (key !in contacted && contacted.size >= PublicRelayDiscovery.CALL_OPERATORS) return PublicRelayLoginResult.Failed
            advert = advert(key) ?: return PublicRelayLoginResult.Failed
            asked = generation
            askedLogins = loginGeneration
            contacted += key
        }
        val result = fetchLogin(advert)
        synchronized(this) {
            if (asked != generation || askedLogins != loginGeneration) return PublicRelayLoginResult.Failed
            if (result is PublicRelayLoginResult.Issued) logins[key] = result.login
        }
        return result
    }

    /** How many operators this budget has contacted. */
    fun contactedCount(): Int = synchronized(this) { contacted.size }

    /**
     * A new budget of three operators: when a call ends, and when a check
     * starts with no call on (the next call counts that check).
     */
    fun newBudget() { synchronized(this) { contacted.clear() } }

    fun dropLogin(key: String) { synchronized(this) { logins.remove(key) } }

    /** The call ended: every login is forgotten. */
    fun dropLogins() { synchronized(this) { logins.clear(); loginGeneration++ } }

    /** The relay worked: remember it, on this device only, as the last good one. */
    suspend fun markGood(key: String) {
        val advert = advert(key) ?: return
        val host = advert.host ?: return
        synchronized(this) { state = state?.copy(lastGood = PublicCallRelayLastGood(key, host)) ?: return }
        persist()
    }

    /** Stop using a public relay, keeping the switch on. */
    suspend fun forgetChoice() {
        synchronized(this) {
            selected = null
            logins.clear()
            loginGeneration++
            state = state?.copy(lastGood = null) ?: return
        }
        persist()
    }

    companion object {
        /** A login with less than this left is not reused. */
        const val LOGIN_REUSE_MARGIN_SECONDS = 120L
    }
}

/** One set of public call relay state and one login client for the whole process, so a check in Settings and a call in any room share them. */
internal object PublicCallRelaysProcess {
    @Volatile private var held: PublicCallRelays? = null
    private val loginClient = lazy { PublicRelayLoginClient() }
    val logins: PublicRelayLoginClient by loginClient

    fun relays(context: android.content.Context): PublicCallRelays = held ?: synchronized(this) {
        held ?: PublicCallRelays(PublicCallRelayStore(context.applicationContext), { advert -> logins.fetch(advert) }).also { held = it }
    }

    /** A call ended: logins forgotten, a new budget of operators, idle connections dropped. */
    fun callEnded() {
        held?.let { it.dropLogins(); it.newBudget() }
        if (loginClient.isInitialized()) logins.evictAll()
    }
}
