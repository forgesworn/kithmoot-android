package dev.forgesworn.kithmoot.notifications

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Glues [IncomingCallTracker]'s pure ring/stop decision to this device: the
 * per-room choice in [CallRingSettings], whether this room is the one on
 * screen right now, and posting or cancelling the notification through
 * [IncomingCallRinger].
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
     *  already on screen shows as [banner] instead of ringing. */
    @Volatile var foreground = false

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
        when (val change = tracker.update(call, self, joined)) {
            null -> Unit
            is IncomingCallChange.Stop -> {
                mutableBanner.value = null
                IncomingCallRinger.stop(context, roomId)
            }
            is IncomingCallChange.Ring -> {
                if (foreground) {
                    mutableBanner.value = change.call
                    return
                }
                mutableBanner.value = null
                when (settings.modeFor(roomId)) {
                    CallRingMode.NOTHING -> Unit
                    CallRingMode.QUIET -> IncomingCallRinger.ring(context, roomId, roomName, change.call.id, change.call.caller, quiet = true)
                    CallRingMode.RING -> IncomingCallRinger.ring(context, roomId, roomName, change.call.id, change.call.caller, quiet = false)
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
        if (change is IncomingCallChange.Stop && currentRoomId.isNotEmpty()) IncomingCallRinger.stop(context, currentRoomId)
    }
}
