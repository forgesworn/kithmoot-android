package dev.forgesworn.kithmoot.ui.settings

import dev.forgesworn.kithmoot.relay.RelayHealth
import dev.forgesworn.kithmoot.ui.AccountView
import dev.forgesworn.kithmoot.update.AppUpdates
import dev.forgesworn.kithmoot.update.InstalledFrom

/**
 * The one-line summaries on Settings' root list (settings-ux-spec C-ROOT-*).
 * Pure, so every wording is tested without a screen.
 */

/** How many relays are refusing, failing or retrying: the count the Connections row and the room menu report. */
fun relayIssueCount(health: Collection<RelayHealth>): Int = health.count {
    it.connection.contains("failed", true) || it.connection.contains("retry", true) || it.connection == "Reconnecting" ||
        it.read.contains("refused", true) || it.read.contains("authentication", true) ||
        it.write.contains("refused", true) || it.write == "No acknowledgement"
}

fun relaysNeedAttention(count: Int): String = "$count ${if (count == 1) "relay needs" else "relays need"} attention"

fun connectionsSummary(issues: Int): String = if (issues > 0) relaysNeedAttention(issues) else "Relays and site address"

fun changesWaiting(count: Int): String = "$count ${if (count == 1) "change" else "changes"} waiting to sync"

/** The line under a signed-in account: a sync problem first, then waiting changes, then who signs. */
fun accountSummary(account: AccountView, syncError: String?, pending: Int): String = when {
    syncError != null -> "Room sync failed. Open to retry"
    pending > 0 -> changesWaiting(pending)
    else -> signerLine(account)
}

fun signerLine(account: AccountView): String = when (account.method) {
    "nip55" -> "Signed in with ${account.signerLabel ?: "a signer app"} (signer app)"
    "bunker" -> "Signed in through your remote signer (bunker)"
    else -> "Signed in with a key kept on this phone (local)"
}

const val SIGNED_OUT_SUMMARY = "Find your rooms on every device. You don't need an account to join a room."
const val PREVIEW_ACCOUNT_SUMMARY = "Sign in with the same account to keep its rooms"
const val NOTIFICATIONS_SUMMARY = "Messages, sound and incoming calls"

fun privacySummary(publicProfiles: Boolean): String =
    if (publicProfiles) "Profile names and pictures shown" else "Profile names and pictures hidden"

/** The Updates row: the version, then what the last check found. */
fun updatesSummary(versionName: String, state: AppUpdates.State, installedFrom: InstalledFrom): String {
    val version = "Version $versionName"
    return when (state) {
        AppUpdates.State.Current -> "$version · Up to date"
        is AppUpdates.State.Available -> "$version · ${state.versionName} available"
        is AppUpdates.State.Downloading -> "$version · Downloading ${state.versionName}"
        is AppUpdates.State.Ready -> "$version · ${state.versionName} ready to install"
        is AppUpdates.State.Installing -> "$version · Installing ${state.versionName}"
        is AppUpdates.State.Failed -> "$version · Update failed"
        AppUpdates.State.Idle, AppUpdates.State.Checking ->
            if (installedFrom == InstalledFrom.ZAPSTORE) "$version · Updates come through Zapstore" else version
    }
}
