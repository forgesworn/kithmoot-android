package dev.forgesworn.kithmoot.telecom

import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.PhoneAccountHandle

/**
 * The self-managed ConnectionService Telecom binds for KithMoot calls. It
 * holds no state of its own; everything goes through [CallTelecom], which
 * also decides whether Telecom is asked at all.
 *
 * Telecom calls all four of these on the main thread.
 */
class KithMootConnectionService : ConnectionService() {
    override fun onCreateIncomingConnection(account: PhoneAccountHandle?, request: ConnectionRequest?): Connection =
        CallTelecom.createIncoming(applicationContext, request)

    override fun onCreateIncomingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest?) =
        CallTelecom.incomingFailed(applicationContext, request)

    override fun onCreateOutgoingConnection(account: PhoneAccountHandle?, request: ConnectionRequest?): Connection =
        CallTelecom.createOutgoing(applicationContext)

    override fun onCreateOutgoingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest?) =
        CallTelecom.outgoingFailed()
}

/**
 * One KithMoot call as Telecom sees it: VoIP audio, holdable, named by the
 * caller label or room name the notifications already show and nothing more.
 * Each callback is handed straight to [CallTelecom].
 */
internal class KithMootConnection(val roomId: String, val ring: CallTelecom.Ring?) : Connection() {
    var phase: TelecomPhase = TelecomPhase.RINGING
    /** The route last asked for through [CallTelecom]'s audio hand-off. */
    var wantedRoute: Int? = null

    init {
        connectionProperties = PROPERTY_SELF_MANAGED
        connectionCapabilities = CAPABILITY_HOLD or CAPABILITY_SUPPORT_HOLD
        audioModeIsVoip = true
    }

    override fun onShowIncomingCallUi() = CallTelecom.showIncomingUi(this)
    override fun onAnswer() = CallTelecom.answeredByTelecom(this)
    override fun onReject() = CallTelecom.hungUpByTelecom(this)
    override fun onDisconnect() = CallTelecom.hungUpByTelecom(this)
    override fun onAbort() = CallTelecom.hungUpByTelecom(this)
    override fun onHold() = CallTelecom.held(this)
    override fun onUnhold() = CallTelecom.resumed(this)

    @Deprecated("Deprecated in Java")
    override fun onCallAudioStateChanged(state: CallAudioState?) {
        if (state == null || phase != TelecomPhase.ACTIVE) return
        routeCorrection(state.route, wantedRoute, state.supportedRouteMask)?.let {
            @Suppress("DEPRECATION")
            setAudioRoute(it)
        }
    }
}
