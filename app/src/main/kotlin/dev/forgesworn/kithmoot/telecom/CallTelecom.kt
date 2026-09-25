package dev.forgesworn.kithmoot.telecom

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.media.CallAudioRouting
import dev.forgesworn.kithmoot.notifications.IncomingCallActionReceiver
import dev.forgesworn.kithmoot.notifications.IncomingCallRinger
import dev.forgesworn.kithmoot.ui.room.callerLabel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Something a headset, car or phone call asked of the call under way. */
enum class CallTelecomEvent { HANG_UP, HOLD, RESUME }

/**
 * KithMoot calls as self-managed Telecom calls, like Signal or WhatsApp:
 * Bluetooth, car and wired-headset buttons answer and hang up, and Android
 * knows a call is live, so a phone call arriving holds this one.
 *
 * Only the platform framework (`android.telecom`), so it runs as-is on
 * GrapheneOS and needs nothing from Google.
 *
 * Every Telecom call here is guarded: if Telecom refuses anything, throws, or
 * [TelecomSettings] is off, the call rings and runs exactly as it did before
 * this existed - [IncomingCallRinger]'s notification and
 * [CallAudioRouting]'s own audio mode and focus.
 *
 * Privacy: the account never sets `EXTRA_LOG_SELF_MANAGED_CALLS`, so no call
 * reaches the system call log, and nothing here leaves the device. A paired
 * car or headset sees the caller label or room name, as the lock screen's
 * notification already shows.
 *
 * All state lives on the main thread, where Telecom delivers its callbacks;
 * calls from anywhere else are posted there.
 */
object CallTelecom {
    private const val TAG = "KithMootTelecom"
    private const val ACCOUNT_ID = "kithmoot"
    private const val URI_SCHEME = "kithmoot"
    private const val EXTRA_ROOM_ID = "dev.forgesworn.kithmoot.telecom.room_id"
    /** Telecom calls back within milliseconds; after this, assume it never will. */
    private const val CREATE_WATCHDOG_MS = 3_000L
    /** Same as [IncomingCallRinger]'s own ring timeout. */
    private const val RING_TIMEOUT_MS = 45_000L
    /** An answered call the app never joined is let go after this. */
    private const val ANSWER_TIMEOUT_MS = 60_000L

    data class Ring(val roomId: String, val roomName: String, val callId: String, val caller: String)

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null

    /** Rings Telecom has been asked for and not yet created a connection for. */
    private val pending = mutableMapOf<String, Ring>()
    private val ringing = mutableMapOf<String, KithMootConnection>()
    /** Answered on a headset or the notification; the app is still joining. */
    private var answered: KithMootConnection? = null
    private var answerTimeout: Runnable? = null
    /** The one call under way. */
    private var live: KithMootConnection? = null
    /** A placeCall awaiting `onCreateOutgoingConnection`: the room's name. */
    private var outgoing: String? = null

    private val mutableEvents = MutableSharedFlow<CallTelecomEvent>(extraBufferCapacity = 8)
    /** For the call's `RoomViewModel`: hang-up, hold and resume from outside the app. */
    val events: SharedFlow<CallTelecomEvent> = mutableEvents.asSharedFlow()

    private val mutableAudio = MutableStateFlow<CallAudioRouting.TelecomRoute?>(null)
    /** Non-null while a Telecom call owns this call's audio; handed to
     *  [CallAudioRouting.handToTelecom]. */
    val audio: StateFlow<CallAudioRouting.TelecomRoute?> = mutableAudio.asStateFlow()

    // --- incoming ------------------------------------------------------------

    /**
     * Announces an incoming call: through Telecom where it can, otherwise the
     * notification as before. See [ringPath].
     */
    fun ring(context: Context, roomId: String, roomName: String, callId: String, caller: String, quiet: Boolean) {
        val app = context.applicationContext
        onMain {
            appContext = app
            val ring = Ring(roomId, roomName, callId, caller)
            // No notifications, no ring - the same as before, Telecom or not.
            if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return@onMain
            val telecomOn = TelecomSettings(app).enabled()
            val permitted = if (!quiet && telecomOn) guarded("isIncomingCallPermitted") {
                val handle = register(app) ?: return@guarded null
                manager(app)?.isIncomingCallPermitted(handle)
            } else null
            when (ringPath(quiet, telecomOn, permitted)) {
                RingPath.TELECOM -> if (!askTelecomToRing(app, ring)) notify(app, ring, quiet = false)
                RingPath.NOTIFICATION -> notify(app, ring, quiet = false)
                RingPath.QUIET_NOTIFICATION -> notify(app, ring, quiet = true)
            }
        }
    }

    private fun askTelecomToRing(app: Context, ring: Ring): Boolean {
        val handle = register(app) ?: return false
        val roomExtras = Bundle().apply { putString(EXTRA_ROOM_ID, ring.roomId) }
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, address())
            putBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS, roomExtras)
            putString(EXTRA_ROOM_ID, ring.roomId)
        }
        pending[ring.roomId] = ring
        val asked = guarded("addNewIncomingCall") { manager(app)?.let { it.addNewIncomingCall(handle, extras); true } } == true
        if (!asked) {
            pending.remove(ring.roomId)
            return false
        }
        // Telecom never answering at all is today's ring, late.
        main.postDelayed({
            if (pending[ring.roomId] == ring) {
                pending.remove(ring.roomId)
                Log.w(TAG, "no connection from Telecom; ringing without it")
                notify(app, ring, quiet = false)
            }
        }, CREATE_WATCHDOG_MS)
        main.postDelayed({
            val stillRinging = pending[ring.roomId] == ring || ringing[ring.roomId]?.ring == ring
            if (stillRinging) IncomingCallRinger.stop(app, ring.roomId)
        }, RING_TIMEOUT_MS)
        return true
    }

    internal fun createIncoming(context: Context, request: ConnectionRequest?): Connection {
        appContext = appContext ?: context
        val ring = takePending(request)
            ?: return Connection.createFailedConnection(DisconnectCause(DisconnectCause.CANCELED))
        val connection = KithMootConnection(ring.roomId, ring)
        connection.setAddress(address(), TelecomManager.PRESENTATION_ALLOWED)
        connection.setCallerDisplayName(callerLabel(ring.caller), TelecomManager.PRESENTATION_ALLOWED)
        connection.setRinging()
        ringing.put(ring.roomId, connection)?.let { end(it, EndReason.MISSED) }
        return connection
    }

    internal fun incomingFailed(context: Context, request: ConnectionRequest?) {
        val app = appContext ?: context
        val ring = takePending(request) ?: return
        Log.w(TAG, "Telecom refused the incoming call; ringing quietly without it")
        notify(app, ring, quiet = FAILED_INCOMING_PATH == RingPath.QUIET_NOTIFICATION)
    }

    /** Self-managed apps show their own incoming UI: the existing CallStyle
     *  notification and full-screen intent. */
    internal fun showIncomingUi(connection: KithMootConnection) {
        val app = appContext ?: return
        val ring = connection.ring ?: return
        if (ringing[connection.roomId] === connection) notify(app, ring, quiet = false)
    }

    /** Answer off the notification or [dev.forgesworn.kithmoot.ui.incoming.IncomingCallActivity].
     *  Called before the ring is stopped, so the Telecom call survives it. */
    fun answeredInApp(roomId: String) = onMain {
        val connection = ringing.remove(roomId) ?: return@onMain
        markAnswered(connection)
    }

    /** Decline off the notification or the incoming-call screen. */
    fun declinedInApp(roomId: String) = onMain {
        pending.remove(roomId)
        ringing.remove(roomId)?.let { end(it, EndReason.DECLINED) }
    }

    /** The ring stopped for any reason - timed out, the call ended, this
     *  device joined elsewhere. Called from [IncomingCallRinger.stop]. */
    fun ringStopped(roomId: String) = onMain {
        pending.remove(roomId)
        ringing.remove(roomId)?.let { end(it, EndReason.MISSED) }
    }

    /** A headset, car or Telecom itself answered: the notification's Answer. */
    internal fun answeredByTelecom(connection: KithMootConnection) {
        if (ringing[connection.roomId] !== connection) return
        ringing.remove(connection.roomId)
        markAnswered(connection)
        val app = appContext ?: return
        val ring = connection.ring ?: return
        IncomingCallRinger.stop(app, ring.roomId)
        val answer = Intent(app, MainActivity::class.java).setAction(IncomingCallActionReceiver.ACTION_ANSWER)
            .putExtra(IncomingCallRinger.EXTRA_ROOM_ID, ring.roomId)
            .putExtra(IncomingCallRinger.EXTRA_ROOM_NAME, ring.roomName)
            .putExtra(IncomingCallRinger.EXTRA_CALL_ID, ring.callId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try {
            app.startActivity(answer)
        } catch (e: Exception) {
            Log.w(TAG, "could not open KithMoot for a headset answer", e)
            hungUpByTelecom(connection)
        }
    }

    private fun markAnswered(connection: KithMootConnection) {
        answered?.takeIf { it !== connection }?.let { end(it, EndReason.LOCAL) }
        connection.phase = TelecomPhase.ANSWERED
        connection.setActive()
        answered = connection
        answerTimeout?.let(main::removeCallbacks)
        answerTimeout = Runnable {
            if (answered === connection) {
                answered = null
                end(connection, EndReason.LOCAL)
            }
        }.also { main.postDelayed(it, ANSWER_TIMEOUT_MS) }
    }

    // --- the call under way ----------------------------------------------------

    /** This device joined a call, by any path. Adopts an answered Telecom
     *  call, or asks Telecom for an outgoing one. */
    fun callJoined(context: Context, roomName: String) {
        val app = context.applicationContext
        onMain {
            appContext = app
            val step = joinStep(
                hasLive = live != null || outgoing != null,
                hasAnswered = answered != null,
                telecomOn = TelecomSettings(app).enabled(),
                outgoingPermitted = {
                    guarded("isOutgoingCallPermitted") {
                        val handle = register(app) ?: return@guarded null
                        manager(app)?.isOutgoingCallPermitted(handle)
                    }
                },
            )
            when (step) {
                JoinStep.NOTHING -> Unit
                JoinStep.ADOPT_ANSWERED -> {
                    val connection = answered ?: return@onMain
                    answered = null
                    answerTimeout?.let(main::removeCallbacks)
                    answerTimeout = null
                    goLive(connection)
                }
                JoinStep.PLACE -> {
                    val handle = register(app) ?: return@onMain
                    outgoing = roomName
                    val extras = Bundle().apply {
                        putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                    }
                    val placed = guarded("placeCall") { manager(app)?.let { it.placeCall(address(), extras); true } } == true
                    if (!placed) outgoing = null
                }
            }
        }
    }

    internal fun createOutgoing(context: Context): Connection {
        appContext = appContext ?: context
        val roomName = outgoing
        outgoing = null
        if (roomName == null || live != null) {
            return Connection.createFailedConnection(DisconnectCause(DisconnectCause.CANCELED))
        }
        val connection = KithMootConnection(roomId = "", ring = null)
        connection.setAddress(address(), TelecomManager.PRESENTATION_ALLOWED)
        connection.setCallerDisplayName(roomName.ifBlank { "KithMoot" }.take(120), TelecomManager.PRESENTATION_ALLOWED)
        goLive(connection)
        return connection
    }

    internal fun outgoingFailed() {
        outgoing = null
        Log.w(TAG, "Telecom refused the outgoing call; the call runs without it")
    }

    private fun goLive(connection: KithMootConnection) {
        connection.phase = TelecomPhase.ACTIVE
        connection.setActive()
        live = connection
        mutableAudio.value = routeFor(connection)
    }

    /** This device left the call in the app, or the room closed. */
    fun callLeft() = onMain {
        outgoing = null
        val connection = live ?: return@onMain
        live = null
        mutableAudio.value = null
        end(connection, EndReason.LOCAL)
    }

    internal fun hungUpByTelecom(connection: KithMootConnection) {
        when (hangUpStep(connection.phase)) {
            HangUpStep.DECLINE -> {
                if (ringing[connection.roomId] === connection) ringing.remove(connection.roomId)
                end(connection, EndReason.DECLINED)
                appContext?.let { app -> IncomingCallRinger.stop(app, connection.roomId) }
            }
            HangUpStep.LEAVE -> {
                if (answered === connection) answered = null
                if (live === connection) {
                    live = null
                    mutableAudio.value = null
                }
                end(connection, EndReason.LOCAL)
                mutableEvents.tryEmit(CallTelecomEvent.HANG_UP)
            }
        }
    }

    /** A phone call (or anything else Telecom puts first) holds this one. */
    internal fun held(connection: KithMootConnection) {
        if (live !== connection || connection.phase != TelecomPhase.ACTIVE) return
        connection.phase = TelecomPhase.HELD
        connection.setOnHold()
        mutableEvents.tryEmit(CallTelecomEvent.HOLD)
    }

    internal fun resumed(connection: KithMootConnection) {
        if (live !== connection || connection.phase != TelecomPhase.HELD) return
        connection.phase = TelecomPhase.ACTIVE
        connection.setActive()
        mutableEvents.tryEmit(CallTelecomEvent.RESUME)
        // Telecom puts a resumed call back on its default route.
        connection.wantedRoute?.let {
            @Suppress("DEPRECATION")
            connection.setAudioRoute(it)
        }
    }

    // setAudioRoute and callAudioState are deprecated from API 34 in favour
    // of CallEndpoint, but still work there, and are the only ones on 33,
    // the floor.
    @Suppress("DEPRECATION")
    private fun routeFor(connection: KithMootConnection) = CallAudioRouting.TelecomRoute { deviceType ->
        val route = telecomRouteFor(deviceType) ?: return@TelecomRoute
        onMain {
            if (live !== connection || connection.phase != TelecomPhase.ACTIVE) return@onMain
            connection.wantedRoute = route
            if (connection.callAudioState?.route != route) guarded("setAudioRoute") { connection.setAudioRoute(route) }
        }
    }

    // --- plumbing --------------------------------------------------------------

    private fun end(connection: KithMootConnection, reason: EndReason) {
        guarded("setDisconnected") {
            connection.setDisconnected(DisconnectCause(disconnectCode(reason)))
            connection.destroy()
        }
    }

    private fun notify(app: Context, ring: Ring, quiet: Boolean) =
        IncomingCallRinger.ring(app, ring.roomId, ring.roomName, ring.callId, ring.caller, quiet)

    private fun takePending(request: ConnectionRequest?): Ring? {
        val extras = request?.extras
        val roomId = extras?.getString(EXTRA_ROOM_ID)
            ?: extras?.getBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS)?.getString(EXTRA_ROOM_ID)
            // Some Telecom builds pass the extras on differently; one ring
            // pending is unambiguous regardless.
            ?: pending.keys.singleOrNull()
            ?: return null
        return pending.remove(roomId)
    }

    private fun address(): Uri = Uri.fromParts(URI_SCHEME, "KithMoot", null)

    private fun manager(context: Context): TelecomManager? = context.getSystemService(TelecomManager::class.java)

    private fun handle(context: Context) =
        PhoneAccountHandle(ComponentName(context, KithMootConnectionService::class.java), ACCOUNT_ID)

    /** Registers the self-managed account; cheap and idempotent, so done
     *  lazily before each use rather than once at start-up. */
    private fun register(context: Context): PhoneAccountHandle? = guarded("registerPhoneAccount") {
        val handle = handle(context)
        val account = PhoneAccount.builder(handle, "KithMoot")
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            .addSupportedUriScheme(URI_SCHEME)
            .build()
        manager(context)?.registerPhoneAccount(account) ?: return@guarded null
        handle
    }

    /** The kill switch going off also takes the account away, unless a call
     *  is using it: unregistering would disconnect that call, and the switch
     *  only promises to apply from the next one. */
    fun unregister(context: Context) {
        val app = context.applicationContext
        onMain {
            if (live != null || answered != null || outgoing != null || ringing.isNotEmpty() || pending.isNotEmpty()) return@onMain
            guarded("unregisterPhoneAccount") { manager(app)?.unregisterPhoneAccount(handle(app)) }
        }
    }

    private inline fun <T> guarded(what: String, block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        Log.w(TAG, "$what failed; carrying on without Telecom", e)
        null
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
