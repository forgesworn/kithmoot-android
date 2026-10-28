package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.NostrEvent

/*
 * Renewing this device's credential inside an open room.
 *
 * A device credential lasts a day (a Ring me room's lasts seven), and a room
 * can be left open for longer than that: behind other apps, with the screen
 * off, or across a night. Every message, roster entry and call signal carries
 * the credential, and readers refuse anything signed under one that has
 * expired, so a room that never renews stops being heard. The web client met
 * this when a laptop slept through its renewal timer; this client did not
 * renew inside a session at all.
 *
 * So the deadline is the wall clock (`System.currentTimeMillis`, through the
 * session's `now`), never an uptime timer, which stands still while the phone
 * is in deep sleep. It is looked at on every heartbeat, when the app comes back
 * to the front, when the network returns, and before every send
 * (`RoomSession.checkCredential` and `RoomSession.ensureCredential`). One
 * renewal is in flight per room at a time, and every caller shares it.
 *
 * A key held on this phone renews without anyone noticing. A signer app is
 * asked only quietly (its content provider, which never shows a screen) on a
 * heartbeat or a reconnection; it may show itself only when the person is in
 * front of the room, on returning to it with little time left, or when they
 * press Send on a credential that has run out.
 */

/** The moment at which the wall clock is checked against this device's credential. */
enum class CredentialMoment {
    /** A heartbeat or a reconnection: nobody asked, so a signer is asked only quietly. */
    TICK,
    /** The room is back in front of the person. */
    RESUME,
}

/**
 * Mints a new credential for this device in this room, and keeps it where the
 * next opening will find it. [visible] says the signer may show itself; when
 * it is false, or when this is not a moment it may show itself after all,
 * a signer that would have to ask returns null rather than asking. Throws when
 * the signer refused, did not answer or the result could not be kept.
 */
fun interface RoomCredentialRenewer {
    suspend fun renew(visible: Boolean): NostrEvent?
}

/** Why a credential could not be renewed for a send, each with something different for the person to do. */
enum class CredentialRenewalReason {
    /** The signer said no, or answered with something unusable. */
    SIGNER_REFUSED,
    /** The signer did not answer in time. */
    SIGNER_NO_ANSWER,
    /** Only the signer can renew it, and it may not be shown now: the app is not in front. */
    SIGNER_NEEDED,
    /** The account this room was joined as is not signed in on this phone. */
    NOT_CONNECTED,
    /** The new credential could not be kept on this phone. */
    STORAGE,
    /** This device cannot sign for its participant: a paired device, or a room open nearby only. */
    CANNOT_RENEW,
    /** The room was closed while it waited. */
    LEFT,
    /** Something tried to sign without renewing first. The last line of defence; a send renews before it gets here. */
    EXPIRED,
}

/** A send could not have a credential that is good now. The message is the person's words for it. */
class CredentialRenewalException(val reason: CredentialRenewalReason, message: String = CredentialWords.failure(reason), cause: Throwable? = null) :
    IllegalStateException(message, cause)

/** What the person is told about renewing this device's access, beside the message box. Mirrors the web client's wording. */
object CredentialWords {
    /** Shown while a send waits on a signer that has not answered yet. */
    const val WAITING_FOR_SIGNER = "Your signer did not answer, so this message is waiting. Open your signer and it will send."

    fun failure(reason: CredentialRenewalReason): String = when (reason) {
        CredentialRenewalReason.SIGNER_REFUSED ->
            "Your signer did not renew this device's access to the room, so this message has not been sent. Open your signer, then press Send to try again."
        CredentialRenewalReason.SIGNER_NO_ANSWER ->
            "Your signer did not answer, so this message has not been sent. Open your signer, then press Send to try again."
        CredentialRenewalReason.SIGNER_NEEDED ->
            "Your signer needs to renew this device's access to the room, so this message has not been sent. Open KithMoot, then press Send to try again."
        CredentialRenewalReason.NOT_CONNECTED ->
            "Your Nostr account is not connected on this phone, so this message has not been sent. Sign in again, then press Send."
        CredentialRenewalReason.STORAGE ->
            "This phone could not save its renewed access to the room, so this message has not been sent. Press Send to try again."
        CredentialRenewalReason.CANNOT_RENEW ->
            "This device's access to the room has run out, and only the device that holds your identity can renew it. Pair this device again from there to keep sending."
        CredentialRenewalReason.LEFT -> "This room was closed before the message could be sent."
        CredentialRenewalReason.EXPIRED ->
            "This device's access to the room ran out before it could be renewed, so this message has not been sent. Press Send to try again."
    }

    /** For a room open nearby only, whose account signer is never asked while offline. */
    const val NEARBY_ONLY =
        "This device's access to the room has run out, and it cannot be renewed while the room is connected nearby only. Reopen the room using Internet to keep sending."
}

/** When [credential] stops authorising its device; zero when it does not say. */
internal fun credentialExpiresAt(credential: NostrEvent): Long = credential.tagValue("expiration")?.toLongOrNull() ?: 0L

/** The wall-clock second at which [credential] is due to be renewed: halfway through its life, as on the web. */
internal fun credentialRenewAt(credential: NostrEvent): Long {
    val expires = credentialExpiresAt(credential)
    return credential.createdAt + (expires - credential.createdAt) / 2
}

/**
 * The least life a credential must have left for a message to be signed under
 * it without renewing first: a message kept on the phone is published only
 * while the credential it carries lasts, so one signed in its last minute could
 * be stranded by a slow relay.
 */
internal const val SEND_CREDENTIAL_MARGIN_SECONDS = 5L * 60

/**
 * Coming back to a room whose credential has less than this left may show the
 * signer: soon enough that the next message would otherwise wait on it, late
 * enough that a person is not asked twice a day. Matches the reachability
 * banner's two hours (`service/CredentialRenewal.kt`, `AT_RISK_SECONDS`).
 */
internal const val VISIBLE_RENEWAL_BELOW_SECONDS = 2L * 60 * 60

/** After a renewal fails, how long before a heartbeat tries again. Short, because the clock is running. */
internal const val RENEWAL_RETRY_SECONDS = 60L

/** After a signer said it would have to show itself to renew, how long before it is asked quietly again. */
internal const val QUIET_SIGNER_RETRY_SECONDS = 15L * 60

/** After the signer was shown and did not renew, how long before coming back to the room shows it again. A send always may. */
internal const val VISIBLE_RENEWAL_RETRY_SECONDS = 30L * 60
