package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.account.ParticipantSigner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean

internal data class NodeSettingsView(val account: String? = null, val pair: NodePair? = null, val status: NodeStatus? = null,
    val operations: List<NodeOperationRef> = emptyList(), val pairingOrigin: String? = null, val busy: Boolean = false,
    val message: String? = null)

/** Pairing arrival is local data only. The owner explicitly confirms the
 * displayed host before any request; every late signer/network result checks
 * the captured account and pairing request again. */
internal class NodeSettingsController(
    private val store: NodeStore,
    private val actor: () -> ParticipantSigner?,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val client: (() -> Boolean) -> NodeClient = { permission -> NodeClient(now = now, allowed = permission) },
) {
    private val _state = MutableStateFlow(NodeSettingsView())
    val state = _state.asStateFlow()
    private var pending: NodePairLink? = null
    private val running = AtomicBoolean(false)
    fun offer(link: NodePairLink) { check(!_state.value.busy); pending = link; _state.update { it.copy(pairingOrigin = link.origin, message = null) } }
    fun cancelPairing() { if (!_state.value.busy) { pending = null; _state.update { it.copy(pairingOrigin = null) } } }
    fun refreshLocal() {
        val selected = actor(); val account = selected?.pubkey
        try {
            val pair = account?.let(store::pair); val operations = account?.let(store::operations).orEmpty()
            if (actor() !== selected) return
            _state.update { old -> NodeSettingsView(account, pair, old.status.takeIf { old.pair == pair && old.account == account }, operations,
                pending?.origin, old.busy, old.message.takeIf { old.account == account }) }
        } catch (_: Exception) {
            if (actor() === selected) _state.value = NodeSettingsView(account, pairingOrigin = pending?.origin, message = "The saved node settings are unavailable. Reconnect from your node.")
        }
    }
    suspend fun confirmPairing() {
        val link = pending ?: return; val selected = actor()
        if (selected == null) { _state.update { it.copy(message = "Sign in before pairing your KithMoot node.") }; return }
        if (!running.compareAndSet(false, true)) return
        _state.update { it.copy(busy = true, message = null) }
        fun current() = actor() === selected && pending === link
        try {
            val paired = client(::current).pair(link, selected)
            check(current()); store.savePair(paired); check(current())
            pending = null; refreshLocal()
            _state.update { it.copy(pairingOrigin = null, message = "Your KithMoot node is paired on this device.") }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (actor() === selected) _state.update { it.copy(message = "Pairing was not confirmed. Open your node and try a fresh pairing link.") } }
        finally { _state.update { it.copy(busy = false) }; running.set(false) }
    }
    suspend fun refreshStatus() {
        val selected = actor() ?: return
        if (!running.compareAndSet(false, true)) return
        _state.update { it.copy(busy = true, message = null) }
        try {
            val pair = requireNotNull(store.pair(selected.pubkey))
            val status = client { actor() === selected }.status(pair, selected)
            check(actor() === selected)
            _state.update { it.copy(account = selected.pubkey, pair = pair, status = status) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (actor() === selected) _state.update { it.copy(status = null, message = "Your node is unavailable. Check it and try again.") } }
        finally { _state.update { it.copy(busy = false) }; running.set(false) }
    }
    fun unpair() {
        val selected = actor() ?: return; check(!_state.value.busy)
        store.unpair(selected.pubkey); check(actor() === selected); refreshLocal()
        _state.update { it.copy(status = null, message = "Pairing removed from this device. Hosted rooms keep their existing access.") }
    }
    suspend fun checkOperation(reference: NodeOperationRef) {
        val selected = actor() ?: return
        if (selected.pubkey != reference.pair.owner) return
        if (!running.compareAndSet(false, true)) return
        _state.update { it.copy(busy = true, message = null) }
        try {
            val result = client { actor() === selected }.operation(reference.pair, reference.operation, selected)
            check(actor() === selected)
            val message = if (result.phase == "done") "Closure verified: calls ended and the room relay was purged." else "Closure is still in progress on your node."
            _state.update { it.copy(message = message) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (actor() === selected) _state.update { it.copy(message = "Closure could not be verified. Keep the operation reference and try again.") } }
        finally { _state.update { it.copy(busy = false) }; running.set(false) }
    }
    fun forgetOperation(reference: NodeOperationRef) {
        val selected = actor() ?: return; if (selected.pubkey != reference.pair.owner || _state.value.busy) return
        store.forgetOperation(selected.pubkey, reference.operation); refreshLocal()
    }
}
