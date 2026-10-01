package dev.forgesworn.kithmoot.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.R

/**
 * Stopping and restarting ringing in the background, from the listener's
 * notification and from the app. The action used to turn off both ringing and
 * message delivery with one silent tap and no way back short of Settings; now
 * it stops ringing only, says so, and offers "Turn back on" right where it was
 * pressed.
 */

/** The notification action's label. Plain about what it does, and about nothing else. */
const val STOP_RINGING_LABEL = "Stop ringing"

/** What [BackgroundRingActionReceiver] did to the switches, as plain data for tests. */
data class RingActionOutcome(
    val ringOn: Boolean,
    val deliveryOn: Boolean,
    /** The background service has nothing left to do and should stop. */
    val stopService: Boolean,
    /** The notification offering "Turn back on" is posted. */
    val offerTurnOn: Boolean,
    /** The service is started, if it is not already running. */
    val startService: Boolean,
    /** The notification offering "Turn back on" is taken away. */
    val clearOffer: Boolean,
)

/** "Stop ringing" was pressed: ringing goes off, message delivery is left as the person had it. */
fun afterStopRinging(ringWasOn: Boolean, deliveryOn: Boolean) = RingActionOutcome(
    ringOn = false, deliveryOn = deliveryOn,
    stopService = !deliveryOn, offerTurnOn = ringWasOn, startService = false, clearOffer = false,
)

/** "Turn back on" was pressed, here or in the app. */
fun afterTurnOn(deliveryOn: Boolean) = RingActionOutcome(
    ringOn = true, deliveryOn = deliveryOn,
    stopService = false, offerTurnOn = false, startService = true, clearOffer = true,
)

/** The follow-up posted after "Stop ringing": what changed and the way back. */
fun ringingOffNoticeText() = ReachabilityNoticeText(
    title = "Calls won't ring while KithMoot is closed",
    text = "You stopped ringing from the notification. Turn it back on to hear calls when KithMoot is closed.",
)

/** The banner shown in the app, on the rooms list and in a room, while ringing is off. */
fun ringingOffBanner() = ReachabilityBanner(
    message = "Calls won't ring while KithMoot is closed.",
    detail = "Ringing in the background is turned off.",
    action = "Turn on",
)

/** What the receiver needs from the phone. A fake in tests. */
internal interface RingActionHost {
    var ringOn: Boolean
    var deliveryOn: Boolean
    fun startService()
    fun stopService()
    fun offerTurnOn()
    fun clearOffer()
}

internal fun handleRingAction(action: String?, host: RingActionHost) {
    val outcome = when (action) {
        BackgroundRingActionReceiver.ACTION_TURN_OFF -> afterStopRinging(host.ringOn, host.deliveryOn)
        BackgroundRingActionReceiver.ACTION_TURN_ON -> afterTurnOn(host.deliveryOn)
        else -> return
    }
    host.ringOn = outcome.ringOn
    host.deliveryOn = outcome.deliveryOn
    if (outcome.stopService) host.stopService()
    if (outcome.startService) host.startService()
    if (outcome.offerTurnOn) host.offerTurnOn()
    if (outcome.clearOffer) host.clearOffer()
}

/** Turns ringing back on, from the app: the same as the notification's "Turn back on". */
fun turnRingingOn(context: Context) = handleRingAction(BackgroundRingActionReceiver.ACTION_TURN_ON, AndroidRingHost(context))

internal class AndroidRingHost(private val context: Context) : RingActionHost {
    private val ring = BackgroundRingSettings(context)
    private val delivery = BackgroundDeliverySettings(context)
    override var ringOn: Boolean
        get() = ring.enabled()
        set(value) = ring.setEnabled(value)
    override var deliveryOn: Boolean
        get() = delivery.enabled()
        set(value) = delivery.setEnabled(value)

    // The OS can refuse a foreground start from the background; the switch stays on and the next launch starts it.
    override fun startService() { runCatching { BackgroundCallListenerService.start(context) } }
    override fun stopService() = BackgroundCallListenerService.stop(context)
    override fun offerTurnOn() = postTurnOnOffer(context)
    override fun clearOffer() = cancelTurnOnOffer(context)
}

private const val OFFER_ID = 4630

/** Says calls will not ring and offers the way back, until it is used or swiped away. */
internal fun postTurnOnOffer(context: Context) {
    val manager = NotificationManagerCompat.from(context)
    if (!manager.areNotificationsEnabled()) return
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(CredentialRenewal.reachabilityChannel())
    val turnOn = PendingIntent.getBroadcast(
        context, OFFER_ID,
        Intent(context, BackgroundRingActionReceiver::class.java).setAction(BackgroundRingActionReceiver.ACTION_TURN_ON),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val open = PendingIntent.getActivity(context, OFFER_ID, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    val words = ringingOffNoticeText()
    val notification = NotificationCompat.Builder(context, CredentialRenewal.CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_chat_notice)
        .setContentTitle(words.title)
        .setContentText(words.text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(words.text))
        .setContentIntent(open)
        .addAction(0, "Turn back on", turnOn)
        .setAutoCancel(false)
        .setOnlyAlertOnce(true)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .build()
    try { manager.notify(OFFER_ID, notification) } catch (_: SecurityException) { }
}

internal fun cancelTurnOnOffer(context: Context) {
    NotificationManagerCompat.from(context).cancel(OFFER_ID)
}
