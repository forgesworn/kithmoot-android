package dev.forgesworn.kithmoot.notifications

import android.content.Context
import dev.forgesworn.kithmoot.telecom.CallTelecom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Glues [IncomingCallTracker]'s pure ring/stop decision to this device: the
 * per-room choice in [CallRingSettings], whether this room is the one on
 * screen right now, and ringing through [CallTelecom] (which posts
 * [IncomingCallRinger]'s notification either way) or cancelling it - or,
 * for this person's own call on another device, [OwnCallElsewhereNotice].
 *
 * One instance per open room (see `RoomViewModel`), same as
 * [ChatNotifications] - a call starting in a visited chat-only room rings
 * independently of a call already under way in the primary one.
 */
class IncomingCallRingCoordinator(private val context: Context) {
    private val tracker = IncomingCallTracker()
    private val settings = CallRingSettings(context)

    /** Set by whichever screen is actually showing this room; see
     *  `RoomViewModel.setCallRingForeground`. A call starting in a room
     *  already on screen shows as [banner] instead of ringing. Coming on
     *  screen also takes down an [OwnCallElsewhereNotice]: the room's own
     *  call UI now shows that call. */
    @Volatile var foreground = false
        set(value) {
            field = value
            if (value && currentRoomId.isNotEmpty()) OwnCallElsewhereNotice.cancel(context, currentRoomId)
        }

    private var currentRoomId = ""

    private val mutableBanner = MutableStateFlow<IncomingCall?>(null)
    /** The call to show as an in-app banner, because this room is already
     *  what the person is looking at and a ring would be redundant. */
    val banner: StateFlow<IncomingCall?> = mutableBanner.asStateFlow()

    fun modeFor(roomId: String): CallRingMode = settings.modeFor(roomId)
    fun setMode(roomId: String, mode: CallRingMode) = settings.setMode(roomId, mode)

    /** Called on every presence update for this room. `caller` and `self`
     *  are participant identities; `joined` is this device's own call
     *  membership. See [IncomingCallTracker.update] for the ring rule. */
    fun update(roomId: String, roomName: String, callId: String?, caller: String?, self: String, joined: Boolean) {
        currentRoomId = roomId
        val call = if (callId != null && caller != null) IncomingCall(callId, caller) else null
        if (call != null && joined) HandledCalls.add(roomId, call.id)
        // A call this device already answered or declined counts as joined:
        // it stops any ring and never starts one, whichever tracker sees it.
        val handled = call != null && HandledCalls.contains(roomId, call.id)
        when (val change = tracker.update(call, self, joined || handled)) {
            null -> Unit
            is IncomingCallChange.Stop -> {
                mutableBanner.value = null
                IncomingCallRinger.stop(context, roomId)
            }
            is IncomingCallChange.OwnCallElsewhereStop -> OwnCallElsewhereNotice.cancel(context, roomId)
            is IncomingCallChange.OwnCallElsewhere -> {
                // Replaces a ring for someone else's call, should one be up.
                mutableBanner.value = null
                IncomingCallRinger.stop(context, roomId)
                // On screen, the room's own call UI already shows it. Rooms
                // set to Nothing stay silent about calls altogether; Ring me
                // and Notify quietly both get the notice, which is quiet either way.
                if (foreground || settings.modeFor(roomId) == CallRingMode.NOTHING) return
                OwnCallElsewhereNotice.post(context, roomId, roomName, change.call.id)
            }
            is IncomingCallChange.Ring -> {
                OwnCallElsewhereNotice.cancel(context, roomId)
                if (foreground) {
                    mutableBanner.value = change.call
                    return
                }
                mutableBanner.value = null
                when (settings.modeFor(roomId)) {
                    CallRingMode.NOTHING -> Unit
                    // Through Telecom where it can, the notification alone where it cannot.
                    CallRingMode.QUIET -> CallTelecom.ring(context, roomId, roomName, change.call.id, CallerNames.label(context, change.call.caller), quiet = true)
                    CallRingMode.RING -> CallTelecom.ring(context, roomId, roomName, change.call.id, CallerNames.label(context, change.call.caller), quiet = false)
                }
            }
        }
    }

    fun dismissBanner() {
        mutableBanner.value = null
    }

    /** The room has closed on this device: stop any ring and forget what was seen. */
    fun end() {
        mutableBanner.value = null
        val change = tracker.reset()
        if (currentRoomId.isEmpty()) return
        if (change is IncomingCallChange.Stop) IncomingCallRinger.stop(context, currentRoomId)
        if (change is IncomingCallChange.OwnCallElsewhereStop) OwnCallElsewhereNotice.cancel(context, currentRoomId)
    }
}
