package dev.forgesworn.kithmoot.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class GuestAdmissionPhase {
    PREVIEW, SIGNING, SENDING, WAITING, RECONNECTING, ADMITTED,
    DECLINED, EXPIRED, UNAVAILABLE, CANCELLED,
}

/** Public display state contains neither the invitation bearer nor a room secret. */
data class GuestAdmissionView(
    val id: Long,
    val roomName: String,
    val name: String,
    val phase: GuestAdmissionPhase = GuestAdmissionPhase.PREVIEW,
    val detail: String? = null,
)

internal class GuestAdmissionAttempt internal constructor(
    val id: Long,
    val url: String,
    val name: String,
)

/** One explicit action owns entry. Cancelling or retrying retires the old token. */
internal class GuestAdmissionGate {
    private val mutable = MutableStateFlow<GuestAdmissionView?>(null)
    val view: StateFlow<GuestAdmissionView?> = mutable.asStateFlow()
    private var nextId = 0L
    private var url: String? = null
    private var active: GuestAdmissionAttempt? = null

    @Synchronized fun prepare(link: String, roomName: String, name: String) {
        active = null
        url = link
        mutable.value = GuestAdmissionView(++nextId, roomName, name)
    }

    @Synchronized fun editName(name: String) {
        val current = mutable.value ?: return
        if (current.phase == GuestAdmissionPhase.PREVIEW) mutable.value = current.copy(name = name.take(80))
    }

    @Synchronized fun request(): GuestAdmissionAttempt? {
        val current = mutable.value ?: return null
        val link = url ?: return null
        if (current.phase != GuestAdmissionPhase.PREVIEW || active != null) return null
        return GuestAdmissionAttempt(current.id, link, current.name.trim()).also {
            active = it
            mutable.value = current.copy(phase = GuestAdmissionPhase.SENDING, detail = null)
        }
    }

    @Synchronized fun isCurrent(attempt: GuestAdmissionAttempt): Boolean = active === attempt

    @Synchronized fun phase(attempt: GuestAdmissionAttempt, phase: GuestAdmissionPhase, detail: String? = null): Boolean {
        if (active !== attempt) return false
        val current = mutable.value ?: return false
        // Re-offering an acknowledged immutable event is not a new send.
        if (current.phase == GuestAdmissionPhase.WAITING && phase == GuestAdmissionPhase.SENDING) return true
        mutable.value = current.copy(phase = phase, detail = detail)
        if (phase in terminal) active = null
        return true
    }

    @Synchronized fun cancel() {
        active = null
        mutable.value = mutable.value?.copy(phase = GuestAdmissionPhase.CANCELLED, detail = null)
    }

    @Synchronized fun retry(): Boolean {
        val current = mutable.value ?: return false
        if (current.phase !in terminal || url == null) return false
        active = null
        mutable.value = current.copy(id = ++nextId, phase = GuestAdmissionPhase.PREVIEW, detail = null)
        return true
    }

    @Synchronized fun finish(attempt: GuestAdmissionAttempt): Boolean {
        if (active !== attempt) return false
        clear()
        return true
    }

    @Synchronized fun clear() {
        active = null
        url = null
        mutable.value = null
    }

    private companion object {
        val terminal = setOf(GuestAdmissionPhase.DECLINED, GuestAdmissionPhase.EXPIRED,
            GuestAdmissionPhase.UNAVAILABLE, GuestAdmissionPhase.CANCELLED)
    }
}
