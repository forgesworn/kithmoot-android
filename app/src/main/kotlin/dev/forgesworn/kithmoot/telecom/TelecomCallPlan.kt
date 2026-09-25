package dev.forgesworn.kithmoot.telecom

import android.media.AudioDeviceInfo
import android.telecom.CallAudioState
import android.telecom.DisconnectCause

/*
 * The decisions behind [CallTelecom], kept free of Android state so they can
 * be unit-tested. Every Telecom call site asks one of these first; anything
 * Telecom refuses lands on the notification path the app had before.
 */

/** How an incoming call is announced on this device. */
internal enum class RingPath {
    /** A self-managed Telecom call, which then posts the usual CallStyle
     *  notification from `onShowIncomingCallUi`. */
    TELECOM,
    /** Today's ringing notification with no Telecom call behind it. */
    NOTIFICATION,
    /** The notification with Answer and Decline, but no sound, vibration or
     *  full-screen intent. */
    QUIET_NOTIFICATION,
}

/**
 * @param quiet the room is set to ring quietly, which never involves Telecom:
 *   a car or headset announcing the call is not quiet.
 * @param telecomOn the [TelecomSettings] kill switch.
 * @param incomingPermitted `TelecomManager.isIncomingCallPermitted`, or null
 *   when asking threw (no account, SecurityException, an OEM quirk).
 *
 * Telecom refusing a call it was asked about is nearly always an emergency
 * call in progress, or the phone already at its limit of ringing calls. The
 * call is still shown, quietly, rather than dropped or rung over the top.
 */
internal fun ringPath(quiet: Boolean, telecomOn: Boolean, incomingPermitted: Boolean?): RingPath = when {
    quiet -> RingPath.QUIET_NOTIFICATION
    !telecomOn || incomingPermitted == null -> RingPath.NOTIFICATION
    incomingPermitted -> RingPath.TELECOM
    else -> RingPath.QUIET_NOTIFICATION
}

/** The path `onCreateIncomingConnectionFailed` falls back to: same reasoning
 *  as a refused [ringPath], since that is what the failure nearly always is. */
internal val FAILED_INCOMING_PATH = RingPath.QUIET_NOTIFICATION

/** What joining a call does on the Telecom side. */
internal enum class JoinStep {
    /** The call was answered through a ringing Telecom call: that one carries on. */
    ADOPT_ANSWERED,
    /** Ask Telecom for an outgoing self-managed call. */
    PLACE,
    /** Leave Telecom out: already covered, switched off, or refused. */
    NOTHING,
}

internal fun joinStep(hasLive: Boolean, hasAnswered: Boolean, telecomOn: Boolean, outgoingPermitted: () -> Boolean?): JoinStep = when {
    hasLive -> JoinStep.NOTHING
    hasAnswered -> JoinStep.ADOPT_ANSWERED
    !telecomOn -> JoinStep.NOTHING
    outgoingPermitted() == true -> JoinStep.PLACE
    else -> JoinStep.NOTHING
}

/** Where a KithMoot Telecom call is in its life. */
internal enum class TelecomPhase { RINGING, ANSWERED, ACTIVE, HELD }

/** A headset, car or Telecom itself hanging a call up. */
internal enum class HangUpStep {
    /** Still ringing: the same as Decline on the notification. */
    DECLINE,
    /** Answered or under way: leave the call in the app. */
    LEAVE,
}

internal fun hangUpStep(phase: TelecomPhase): HangUpStep =
    if (phase == TelecomPhase.RINGING) HangUpStep.DECLINE else HangUpStep.LEAVE

/** Why a KithMoot Telecom call ended, from the app's side. */
internal enum class EndReason { DECLINED, MISSED, LOCAL, CANCELLED }

internal fun disconnectCode(reason: EndReason): Int = when (reason) {
    EndReason.DECLINED -> DisconnectCause.REJECTED
    EndReason.MISSED -> DisconnectCause.MISSED
    EndReason.LOCAL -> DisconnectCause.LOCAL
    EndReason.CANCELLED -> DisconnectCause.CANCELED
}

/** The Telecom route for a communication device [dev.forgesworn.kithmoot.media.CallAudioRouting]
 *  has chosen, or null for one Telecom has no route for. */
internal fun telecomRouteFor(deviceType: Int): Int? = when (deviceType) {
    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET ->
        CallAudioState.ROUTE_WIRED_HEADSET
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID ->
        CallAudioState.ROUTE_BLUETOOTH
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> CallAudioState.ROUTE_SPEAKER
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> CallAudioState.ROUTE_EARPIECE
    else -> null
}

/**
 * Telecom starts an audio-only call on the earpiece and falls back to it when
 * a headset goes, where KithMoot has always used the speaker. When Telecom
 * lands on the earpiece after the app asked for something else it still
 * supports, this is the route to ask for again; otherwise null.
 */
internal fun routeCorrection(current: Int, wanted: Int?, supportedMask: Int): Int? {
    if (wanted == null || current != CallAudioState.ROUTE_EARPIECE || wanted == current) return null
    return wanted.takeIf { supportedMask and it != 0 }
}

/** Holding mutes a live, unmuted microphone, and only that one is unmuted on
 *  resume: a microphone the person muted themselves stays muted. */
internal fun holdMutesMic(micOn: Boolean, micMuted: Boolean): Boolean = micOn && !micMuted
