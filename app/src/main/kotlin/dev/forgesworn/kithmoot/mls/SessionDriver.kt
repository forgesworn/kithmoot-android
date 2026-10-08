package dev.forgesworn.kithmoot.mls

import dev.forgesworn.kithmoot.account.EngineStep
import dev.forgesworn.kithmoot.account.Hosted
import dev.forgesworn.kithmoot.account.HostedSession
import dev.forgesworn.kithmoot.account.SessionHost
import dev.forgesworn.kithmoot.crypto.toHex

/** Where an outbound record goes (the engine's `Destination`). */
sealed class Destination {
    /** A member leaf's mailbox, on [box]; [leaf] is the recipient's leaf, the same in every epoch. */
    class Leaf(val box: ByteArray, val leaf: ByteArray) : Destination()
    /** A commit slot of [epoch] at [attempt], on the group's home [box]. */
    class Slot(val box: ByteArray, val epoch: Long, val attempt: Long) : Destination()
    /** A capability's single-use Welcome mailbox; the engine names no box. */
    class Welcome(val packageId: ByteArray) : Destination()
    /** An introduction mailbox for an adding person; the engine names no box. */
    data object Introduction : Destination()
    /** A member leaf's fork-evidence mailbox, on [box]. */
    class Evidence(val box: ByteArray) : Destination()
}

class Outgoing(val recordId: ByteArray, val mailbox: ByteArray, val destination: Destination, val envelope: ByteArray)

/** A mailbox to fetch (the engine's `Watch`). */
class Watched(val mailbox: ByteArray, val kind: Kind, val box: ByteArray?) {
    sealed class Kind {
        /** This leaf's mailbox, fork evidence, or (with [retained]) a departed epoch's leaf mailbox, kept to the engine's bounds. */
        class Mailbox(val retained: Boolean) : Kind()
        /** A pending join's Welcome mailbox. */
        data object Welcome : Kind()
        /** A commit slot at [attempt]: read through its status, never fetched. */
        class Slot(val attempt: Long) : Kind()
    }
}

/** What a step asks of the driver besides its events, which are the room's (3b). */
class Effects(
    val events: List<Any> = emptyList(),
    /** `OrderingUnconfirmed`: the slots whose receipts to query. */
    val unconfirmed: List<Pair<ByteArray, Long>> = emptyList(),
    /**
     * The engine refused the call with this stable code (`UnexpectedSlot`,
     * `ReceiptUnverified`, `NotActive`, ...). A refused call changes nothing,
     * so the driver decides whether to go on.
     */
    val refused: String? = null,
)

/** The session's phase (the engine's `Phase`). */
sealed class Phase {
    data object PendingJoin : Phase()
    data object Active : Phase()
    /** [reason] is the engine's code: `Gap`, `Fork`, `RestoreFenced`, `Rollback`, `ConfirmationTag`. */
    data class NeedsRecovery(val reason: String) : Phase()
    data object Removed : Phase()
    data object Expired : Phase()
}

/** When a fetched record may be acknowledged at its box (the engine's `Ack`). */
enum class AckRule {
    /** Nothing changed: at once. */
    Now,
    /** Once its step is witnessed and acknowledged, which a released step is. */
    AfterStep,
    /** Never: leave it at the box and fetch it again. */
    Keep,
}

class Processed(val effects: Effects, val ack: AckRule)

/** A slot status the engine is told about (the engine's `SlotStatus`). */
enum class SlotOutcome { Filled, Expired, Void }

/**
 * The engine calls the driver makes on one hosted session (vmls-ffi's
 * `VmlsSession`). Each answers an [EngineStep] for [SessionHost],
 * which witnesses any snapshot before the step is released.
 */
interface DriverSession : HostedSession {
    fun phase(): Phase
    /** The current epoch, or null before the group is joined. */
    fun epoch(): Long?
    fun outbox(): List<Outgoing>
    fun watchList(): List<Watched>
    fun tick(now: Long): EngineStep<Effects>
    fun delivered(recordIds: List<ByteArray>): EngineStep<Effects>
    fun depositResult(now: Long, attempt: Long, signedReceipt: ByteArray): EngineStep<Effects>
    fun slotStatus(now: Long, attempt: Long, outcome: SlotOutcome, signedReceipt: ByteArray): EngineStep<Effects>
    fun observeReceipt(now: Long, signedReceipt: ByteArray): EngineStep<Effects>
    fun observeInstallation(now: Long, installation: ByteArray): EngineStep<Effects>
    /**
     * A departed epoch's mailbox the box answered empty. The engine deletes it only once every remaining member
     * has been heard past that epoch (vennel P3-07); until then it refuses, changing nothing.
     */
    fun mailboxDrained(mailbox: ByteArray): EngineStep<Effects>
    fun confirmMember(packageId: ByteArray): EngineStep<Effects>
    /** One fetched record, with the box's signed receipt for a slot and, for a Welcome, its box's installation. */
    fun process(now: Long, mailbox: ByteArray, envelope: ByteArray, signedReceipt: ByteArray?, installation: Pair<ByteArray, ByteArray>?): EngineStep<Processed>
}

/** The engine's strict reading of a capabilities body: the box's current installation. */
fun interface CapabilitiesParser {
    /** The 32-byte installation, or null when the body is not a capabilities reply the engine accepts. */
    fun installation(body: ByteArray): ByteArray?
}

/** How one round ended. */
sealed class Round {
    /**
     * The round ran to its end. [held] counts records left for another box
     * (P3-03b-3b); [stalled] is true when a deposit had no answer, so the
     * rest of the outbox waits for the next round. [leafHeld] counts records
     * left behind an earlier record to the same leaf the box did not take.
     * [limited] is true when the box refused a commit as `rate-limited`: it
     * takes a few new commits an hour from one grant, and this one waits.
     */
    data class Done(
        val delivered: Int, val processed: Int, val held: Int, val stalled: Boolean = false, val leafHeld: Int = 0,
        val limited: Boolean = false,
    ) : Round()
    /** The session is removed, expired or in a recovery other than a gap: nothing is driven (the room shows why). */
    data class Stopped(val phase: Phase) : Round()
    /** The box gave no capabilities reply: nothing was sent or fetched (no reply holds, P2-R-02). */
    data class Offline(val answer: BoxAnswer<Nothing>?) : Round()
    /** The witness has not confirmed a step: the round stopped; nothing unwitnessed was released. */
    data object Held : Round()
    data class Fenced(val reason: String) : Round()
    data object Unknown : Round()
}

/**
 * One MLS session's driver loop over its home box (P3-03b-3a; the steps are
 * the vennel driver note's). A [round]:
 * 1. reads the box's capabilities and gives the engine its installation; no
 *    reply holds the round;
 * 2. runs the engine's `tick`;
 * 3. deposits the outbox, then records what was delivered (a Welcome once
 *    its package took it), each commit slot's receipt, and each
 *    acknowledged Welcome;
 * 4. fetches every watched mailbox and processes each record, acknowledging
 *    it at the box only as the engine's `Ack` allows; reads each watched
 *    commit slot through its status. A departed epoch's mailbox answered
 *    empty on every page of its fetch is reported drained, which the
 *    engine refuses until every remaining member has moved past the epoch;
 * 5. queries the receipts an `OrderingUnconfirmed` asked for.
 *
 * Every engine call runs in [SessionHost.step], which witnesses its snapshot
 * before releasing it. No box request is made inside a step: the vault's
 * signer takes the persona lock the step holds. Only the home box is driven
 * here; a record for another box is left in the outbox and counted.
 */
class SessionDriver<S : DriverSession>(
    private val host: SessionHost<S>,
    private val client: VmlsBoxClient,
    /** The home box's Link node id: the box [client] speaks to. */
    private val homeBox: ByteArray,
    private val capabilities: CapabilitiesParser,
    private val events: (List<Any>) -> Unit = {},
    private val maxPages: Int = 8,
) {
    init { require(homeBox.size == 32 && maxPages > 0) }

    /** `OrderingUnconfirmed` queries not yet answered, by session: the engine raises each once. */
    private val pendingQueries = HashMap<String, MutableSet<Pair<String, Long>>>()

    suspend fun round(persona: String, session: ByteArray, now: Long): Round {
        // 1. Capabilities first: no reply holds, never fences (engine `observe_installation`).
        val installation = when (val answer = client.capabilities()) {
            is BoxAnswer.Ok -> capabilities.installation(answer.value) ?: return Round.Offline(BoxAnswer.Malformed)
            is BoxAnswer.Refused -> return Round.Offline(answer)
            is BoxAnswer.NotSigned -> return Round.Offline(answer)
            BoxAnswer.Unreachable -> return Round.Offline(BoxAnswer.Unreachable)
            BoxAnswer.Malformed -> return Round.Offline(BoxAnswer.Malformed)
        }
        val queries = synchronized(pendingQueries) { pendingQueries.getOrPut(session.toHex()) { mutableSetOf() } }
        fun take(effects: Effects) {
            if (effects.events.isNotEmpty()) events(effects.events)
            synchronized(pendingQueries) { effects.unconfirmed.forEach { (slot, attempt) -> queries += slot.toHex() to attempt } }
        }

        // The group's installation exists only once it is joined: a pending join is never compared.
        var phase = read(persona, session) { it.phase() }.let { r -> r.stop?.let { return it }; r.value!! }
        if (phase == Phase.Active || phase is Phase.NeedsRecovery) {
            stepped(persona, session) { it.observeInstallation(now, installation) }.let { r -> r.stop?.let { return it }; take(r.value!!) }
        }
        // 2. Time-driven rules (a pending join's expiry among them).
        stepped(persona, session) { it.tick(now) }.let { r -> r.stop?.let { return it }; take(r.value!!) }
        phase = read(persona, session) { it.phase() }.let { r -> r.stop?.let { return it }; r.value!! }
        // A gap still watches its evidence mailbox (P2-R-05); any other recovery, removal or expiry drives nothing.
        when (phase) {
            Phase.Active, Phase.PendingJoin, Phase.NeedsRecovery("Gap") -> Unit
            else -> return Round.Stopped(phase)
        }
        val active = phase == Phase.Active
        val epoch = read(persona, session) { it.epoch() }.let { r -> r.stop?.let { return it }; r.value }

        // 3. The outbox, to the home box only.
        val outgoing = read(persona, session) { it.outbox() }.let { r -> r.stop?.let { return it }; r.value!! }
        var held = 0
        var stalled = false
        var limited = false
        val sent = mutableListOf<ByteArray>()
        // A leaf's records keep the engine's order (vennel contract §5.2): one not taken holds the later ones to the
        // same recipient leaf (a different mailbox each epoch) until the next round, so a send under a departed epoch
        // is never overtaken by a later one.
        var leafHeld = 0
        val heldLeaves = mutableSetOf<String>()
        for (out in outgoing) {
            val d = out.destination
            val leaf = (d as? Destination.Leaf)?.leaf?.toHex()
            if (leaf != null && leaf in heldLeaves) { leafHeld++; continue }
            if (d is Destination.Slot && epoch != null && d.epoch < epoch) {
                // An earlier epoch's commit: decided already, and never deposited again.
                sent += out.recordId
                continue
            }
            // Commits wait while the session is not active (a gap): the engine refuses their results.
            if (d is Destination.Slot && !active) continue
            val box = when (d) {
                is Destination.Leaf -> d.box
                is Destination.Slot -> d.box
                is Destination.Evidence -> d.box
                // The engine names no box: in this slice a group lives on one box, its home box.
                is Destination.Welcome, Destination.Introduction -> homeBox
            }
            if (!box.contentEquals(homeBox)) { held++; leaf?.let(heldLeaves::add); continue }
            val answer: BoxAnswer<*> = when (d) {
                is Destination.Slot -> client.depositSlot(out.mailbox, d.attempt, out.envelope)
                else -> client.deposit(out.mailbox, out.envelope)
            }
            when (answer) {
                // No answer, or nothing signed: the box may have it; the rest waits for the next round.
                BoxAnswer.Unreachable, is BoxAnswer.NotSigned -> { stalled = true; break }
                // Refused or not trusted: this record waits, the others go on. A Welcome the box will
                // never take (its package expired, gone or consumed) leaves the outbox.
                is BoxAnswer.Refused -> {
                    if (d is Destination.Welcome && answer.code in WELCOME_GONE) sent += out.recordId
                    if (d is Destination.Slot && answer.code == RATE_LIMITED) limited = true
                    leaf?.let(heldLeaves::add)
                    continue
                }
                BoxAnswer.Malformed -> { leaf?.let(heldLeaves::add); continue }
                is BoxAnswer.Ok -> Unit
            }
            when (val value = (answer as BoxAnswer.Ok).value) {
                is SlotDeposited -> {
                    sent += out.recordId
                    // No signed receipt yet (not durable): the slot's status is read below instead.
                    value.signedReceipt?.let { signed ->
                        // A refusal (`UnexpectedSlot`, `ReceiptUnverified`) changes nothing; the slot is read back below.
                        stepped(persona, session) { it.depositResult(now, d.let { s -> (s as Destination.Slot).attempt }, signed) }.let { r -> r.stop?.let { return it }; take(r.value!!) }
                    }
                }
                is Deposited -> when (d) {
                    is Destination.Welcome -> when (value.welcomeAcknowledged) {
                        // No package took it: stored as a plain record, which the joiner's box may evict
                        // and which blocks a later registration. It waits, deposited again, never delivered.
                        null -> Unit
                        // Delivered once its package took it (P3-03b-3 decision 12). A keeper joiner's
                        // acknowledgement confirms the member here; a hosted guest's never shows (D5), and
                        // its first Update, which the engine requires before it sends, confirms it instead.
                        true -> {
                            stepped(persona, session) { it.confirmMember(d.packageId) }.let { r -> r.stop?.let { return it }; take(r.value!!) }
                            sent += out.recordId
                        }
                        false -> sent += out.recordId
                    }
                    else -> sent += out.recordId
                }
            }
        }
        var delivered = 0
        if (sent.isNotEmpty()) {
            stepped(persona, session) { it.delivered(sent) }.let { r -> r.stop?.let { return it }; take(r.value!!) }
            delivered = sent.size
        }

        // 4. Watched mailboxes and commit slots.
        val watched = read(persona, session) { it.watchList() }.let { r -> r.stop?.let { return it }; r.value!! }
            .filter { w -> w.box == null || w.box.contentEquals(homeBox) }
        var processed = 0
        val mailboxes = watched.filter { it.kind !is Watched.Kind.Slot }.distinctBy { it.mailbox.toHex() }
        for (batch in mailboxes.chunked(VmlsBoxClient.MAX_FETCH_MAILBOXES)) {
            val kinds = batch.associate { it.mailbox.toHex() to it.kind }
            var after: String? = null
            var complete = false
            val answered = mutableSetOf<String>()
            for (page in 0 until maxPages) {
                val answer = client.fetch(batch.map { it.mailbox }, after) as? BoxAnswer.Ok ?: break
                val acks = mutableListOf<AckItem>()
                for (record in answer.value.records) {
                    answered += record.mailbox.toHex()
                    val kind = kinds[record.mailbox.toHex()]
                    val welcome = if (kind == Watched.Kind.Welcome) homeBox to installation else null
                    val r = stepped(persona, session) { it.process(now, record.mailbox, record.envelope, null, welcome) }
                    r.stop?.let { stop -> ack(acks); return stop }
                    val result = r.value!!
                    take(result.effects)
                    processed++
                    // A refused record is left at the box.
                    if (result.effects.refused == null && result.ack != AckRule.Keep) acks += AckItem(record.mailbox, record.receipt)
                }
                ack(acks)
                after = answer.value.next
                if (after == null) { complete = true; break }
            }
            // A departed epoch's mailbox with no record on any page was empty as of the last page's request, which
            // began after every earlier record here was processed (the box pages by an increasing cursor). Reported
            // drained, the engine deletes it only once every remaining member has been heard past the epoch (vennel
            // P3-07); before that it refuses and nothing changes. An early report used to lose a peer's stale send.
            if (complete) for (w in batch) {
                if ((w.kind as? Watched.Kind.Mailbox)?.retained != true || w.mailbox.toHex() in answered) continue
                stepped(persona, session) { it.mailboxDrained(w.mailbox) }.let { r -> r.stop?.let { return it }; take(r.value!!) }
            }
        }
        for (w in watched) {
            val kind = w.kind as? Watched.Kind.Slot ?: continue
            val state = (client.slotStatus(w.mailbox, kind.attempt) as? BoxAnswer.Ok)?.value ?: continue
            val signed = state.signedReceipt ?: continue
            // A slot of an epoch a commit read back in this loop has left is refused, changing nothing.
            val r = when (state.state) {
                SlotState.State.Empty -> continue
                SlotState.State.Filled -> stepped(persona, session) { s -> s.process(now, w.mailbox, state.envelope!!, signed, null).let { EngineStep(it.snapshot, it.value.effects) } }
                SlotState.State.Expired -> stepped(persona, session) { it.slotStatus(now, kind.attempt, SlotOutcome.Expired, signed) }
                SlotState.State.Void -> stepped(persona, session) { it.slotStatus(now, kind.attempt, SlotOutcome.Void, signed) }
            }
            r.stop?.let { return it }
            take(r.value!!)
            processed++
        }

        // 5. Receipts an OrderingUnconfirmed asked for, kept until the engine takes one.
        for ((slot, attempt) in synchronized(pendingQueries) { queries.toList() }) {
            val signed = ((client.slotStatus(slot.hexToBytesOrNull() ?: continue, attempt) as? BoxAnswer.Ok)?.value?.signedReceipt) ?: continue
            val r = stepped(persona, session) { it.observeReceipt(now, signed) }
            r.stop?.let { return it }
            val effects = r.value!!
            if (effects.events.isNotEmpty()) events(effects.events)
            // Taken, or refused as unrelated or departed for good: either way the query is answered.
            synchronized(pendingQueries) { queries -= slot to attempt }
        }
        return Round.Done(delivered, processed, held, stalled, leafHeld, limited)
    }

    private suspend fun ack(items: List<AckItem>) {
        // Best effort: a record left unacknowledged is fetched again and answered as a duplicate.
        for (batch in items.chunked(VmlsBoxClient.MAX_ACKS)) client.ack(batch)
    }

    private class Stepped<T>(val value: T?, val stop: Round?)

    private companion object {
        /** The box's refusal of a new commit slot past its hourly limit for the grant. */
        const val RATE_LIMITED = "rate-limited"
        /** Bothy's refusals for a Welcome mailbox whose package can never take it. */
        val WELCOME_GONE = setOf("expired", "consumed", "withdrawn", "not-found")

        fun String.hexToBytesOrNull(): ByteArray? =
            if (length == 64 && all { it in '0'..'9' || it in 'a'..'f' }) ByteArray(32) { substring(it * 2, it * 2 + 2).toInt(16).toByte() } else null
    }

    private suspend fun <T> stepped(persona: String, session: ByteArray, call: (S) -> EngineStep<T>): Stepped<T> =
        when (val hosted = host.step(persona, session, call)) {
            is Hosted.Released -> Stepped(hosted.value, null)
            Hosted.Held -> Stepped(null, Round.Held)
            is Hosted.Fenced -> Stepped(null, Round.Fenced(hosted.reason))
            Hosted.Unknown -> Stepped(null, Round.Unknown)
        }

    /** A read that changes nothing: released without a witness round trip. */
    private suspend fun <T> read(persona: String, session: ByteArray, call: (S) -> T): Stepped<T> =
        stepped(persona, session) { EngineStep(null, call(it)) }
}
