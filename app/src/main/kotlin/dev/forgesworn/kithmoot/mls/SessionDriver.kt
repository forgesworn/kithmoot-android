package dev.forgesworn.kithmoot.mls

import dev.forgesworn.kithmoot.account.EngineStep
import dev.forgesworn.kithmoot.account.Hosted
import dev.forgesworn.kithmoot.account.HostedSession
import dev.forgesworn.kithmoot.account.SessionHost
import dev.forgesworn.kithmoot.crypto.toHex

/** Where an outbound record goes (the engine's `Destination`). */
sealed class Destination {
    /** A member leaf's mailbox, on [box]. */
    class Leaf(val box: ByteArray) : Destination()
    /** A commit slot at [attempt], on the group's home [box]. */
    class Slot(val box: ByteArray, val attempt: Long) : Destination()
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
        /** This leaf's mailbox, fork evidence, or (with [retained]) a departed epoch's leaf mailbox. */
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
)

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
 * The engine calls the driver makes on one hosted session (debug builds:
 * vmls-ffi's `VmlsSession`). Each answers an [EngineStep] for [SessionHost],
 * which witnesses any snapshot before the step is released.
 */
interface DriverSession : HostedSession {
    fun outbox(): List<Outgoing>
    fun watchList(): List<Watched>
    fun tick(now: Long): EngineStep<Effects>
    fun delivered(recordIds: List<ByteArray>): EngineStep<Effects>
    fun depositResult(now: Long, attempt: Long, signedReceipt: ByteArray): EngineStep<Effects>
    fun slotStatus(now: Long, attempt: Long, outcome: SlotOutcome, signedReceipt: ByteArray): EngineStep<Effects>
    fun observeReceipt(now: Long, signedReceipt: ByteArray): EngineStep<Effects>
    fun observeInstallation(now: Long, installation: ByteArray): EngineStep<Effects>
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
    /** Everything this round could do is done. [held] counts records left for another box (P3-03b-3b). */
    data class Done(val delivered: Int, val processed: Int, val held: Int) : Round()
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
 * 3. deposits the outbox, then records what was delivered, each commit
 *    slot's receipt, and each acknowledged Welcome;
 * 4. fetches every watched mailbox and processes each record, acknowledging
 *    it at the box only as the engine's `Ack` allows; reads each watched
 *    commit slot through its status; reports a drained departed epoch;
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

    suspend fun round(persona: String, session: ByteArray, now: Long): Round {
        // 1. Capabilities first: no reply holds, never fences (engine `observe_installation`).
        val installation = when (val answer = client.capabilities()) {
            is BoxAnswer.Ok -> capabilities.installation(answer.value) ?: return Round.Offline(BoxAnswer.Malformed)
            is BoxAnswer.Refused -> return Round.Offline(answer)
            is BoxAnswer.NotSigned -> return Round.Offline(answer)
            BoxAnswer.Unreachable -> return Round.Offline(BoxAnswer.Unreachable)
            BoxAnswer.Malformed -> return Round.Offline(BoxAnswer.Malformed)
        }
        val unconfirmed = mutableListOf<Pair<ByteArray, Long>>()
        fun take(effects: Effects) { if (effects.events.isNotEmpty()) events(effects.events); unconfirmed += effects.unconfirmed }

        stepped(persona, session) { it.observeInstallation(now, installation) }.let { r -> r.stop?.let { return it }; take(r.value!!) }
        // 2. Time-driven rules.
        stepped(persona, session) { it.tick(now) }.let { r -> r.stop?.let { return it }; take(r.value!!) }

        // 3. The outbox, to the home box only.
        val outgoing = read(persona, session) { it.outbox() }.let { r -> r.stop?.let { return it }; r.value!! }
        var held = 0
        var delivered = 0
        val sent = mutableListOf<ByteArray>()
        for (out in outgoing) {
            val box = when (val d = out.destination) {
                is Destination.Leaf -> d.box
                is Destination.Slot -> d.box
                is Destination.Evidence -> d.box
                // The engine names no box: in this slice a group lives on one box, its home box.
                is Destination.Welcome, Destination.Introduction -> homeBox
            }
            if (!box.contentEquals(homeBox)) { held++; continue }
            when (val d = out.destination) {
                is Destination.Slot -> {
                    val answer = client.depositSlot(out.mailbox, d.attempt, out.envelope) as? BoxAnswer.Ok ?: break
                    sent += out.recordId
                    // No signed receipt yet (not durable): the slot's status is read below instead.
                    answer.value.signedReceipt?.let { signed ->
                        stepped(persona, session) { it.depositResult(now, d.attempt, signed) }.let { r -> r.stop?.let { return it }; take(r.value!!) }
                    }
                }
                is Destination.Welcome -> {
                    val answer = client.deposit(out.mailbox, out.envelope) as? BoxAnswer.Ok ?: break
                    // Kept in the outbox, and deposited again each round, until the joiner's
                    // acknowledgement shows: the deposit's answer is the only way the adder learns it.
                    if (answer.value.welcomeAcknowledged == true) {
                        stepped(persona, session) { it.confirmMember(d.packageId) }.let { r -> r.stop?.let { return it }; take(r.value!!) }
                        sent += out.recordId
                    }
                }
                else -> {
                    client.deposit(out.mailbox, out.envelope) as? BoxAnswer.Ok ?: break
                    sent += out.recordId
                }
            }
        }
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
            val seen = mutableSetOf<String>()
            var after: String? = null
            var complete = false
            for (page in 0 until maxPages) {
                val answer = client.fetch(batch.map { it.mailbox }, after) as? BoxAnswer.Ok ?: break
                val acks = mutableListOf<AckItem>()
                for (record in answer.value.records) {
                    val kind = kinds[record.mailbox.toHex()]
                    seen += record.mailbox.toHex()
                    val welcome = if (kind == Watched.Kind.Welcome) homeBox to installation else null
                    val r = stepped(persona, session) { it.process(now, record.mailbox, record.envelope, null, welcome) }
                    r.stop?.let { stop -> ack(acks); return stop }
                    val result = r.value!!
                    take(result.effects)
                    processed++
                    if (result.ack != AckRule.Keep) acks += AckItem(record.mailbox, record.receipt)
                }
                ack(acks)
                after = answer.value.next
                if (after == null) { complete = true; break }
            }
            // A departed epoch's mailbox the box answered empty, after acknowledgement, is deleted (D6).
            if (complete) {
                for (w in batch) {
                    val kind = w.kind
                    if (kind is Watched.Kind.Mailbox && kind.retained && w.mailbox.toHex() !in seen) {
                        stepped(persona, session) { it.mailboxDrained(w.mailbox) }.let { r -> r.stop?.let { return it }; take(r.value!!) }
                    }
                }
            }
        }
        for (w in watched) {
            val kind = w.kind as? Watched.Kind.Slot ?: continue
            val state = (client.slotStatus(w.mailbox, kind.attempt) as? BoxAnswer.Ok)?.value ?: continue
            val signed = state.signedReceipt ?: continue
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

        // 5. Receipts an OrderingUnconfirmed asked for.
        for ((slot, attempt) in unconfirmed.distinctBy { it.first.toHex() to it.second }) {
            val signed = ((client.slotStatus(slot, attempt) as? BoxAnswer.Ok)?.value?.signedReceipt) ?: continue
            stepped(persona, session) { it.observeReceipt(now, signed) }.let { r -> r.stop?.let { return it }; r.value?.let { e -> if (e.events.isNotEmpty()) events(e.events) } }
        }
        return Round.Done(delivered, processed, held)
    }

    private suspend fun ack(items: List<AckItem>) {
        // Best effort: a record left unacknowledged is fetched again and answered as a duplicate.
        for (batch in items.chunked(VmlsBoxClient.MAX_ACKS)) client.ack(batch)
    }

    private class Stepped<T>(val value: T?, val stop: Round?)

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
