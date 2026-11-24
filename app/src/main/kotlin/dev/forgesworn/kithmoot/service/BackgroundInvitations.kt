package dev.forgesworn.kithmoot.service

import android.content.Context
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.account.ContactInbox
import dev.forgesworn.kithmoot.account.ContactsRelay
import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.account.parseDeviceBlocks
import dev.forgesworn.kithmoot.account.readSavedSignetBlocks
import dev.forgesworn.kithmoot.notifications.PrivateInvitationAlerts
import dev.forgesworn.kithmoot.protocol.KIND_DM_RELAYS
import dev.forgesworn.kithmoot.protocol.latestDmRelayList
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.OkHttpRelaySockets
import dev.forgesworn.kithmoot.relay.RelayPool
import dev.forgesworn.kithmoot.storage.ContactAccountVault
import dev.forgesworn.kithmoot.ui.coldStartRelays
import dev.forgesworn.kithmoot.ui.relayChoicesFor
import dev.forgesworn.kithmoot.ui.savedRelayChoices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

internal fun backgroundInvitationAccount(app: KithMootApplication): String? {
    if (!app.accountNetwork.allowsNostr) return null
    val account = runCatching { app.accounts.load() }.getOrNull() ?: return null
    return account.pubkey.takeIf { account.method == "nip55" && PrivateInvitationAlerts(app).enabledSince(it) > 0 }
}

/** Receive into the same encrypted inbox the foreground app opens after a tap. */
internal suspend fun watchPrivateInvitations(app: KithMootApplication, signer: ParticipantSigner, scope: CoroutineScope,
    sockets: OkHttpRelaySockets, current: () -> Boolean) {
    val account = signer.pubkey
    val prefs = app.getSharedPreferences("kithmoot.display", Context.MODE_PRIVATE)
    fun pref(key: String) = prefs.getString(key, null)
    val (configured, defaults) = coldStartRelays(::pref)
    val reads = relayChoicesFor(configured, savedRelayChoices(::pref, account) ?: defaults).filter { it.read }.map { it.url }
    if (reads.isEmpty() || !current()) return
    fun receive(urls: List<String>): RelayPool = RelayPool(urls, app.accountNetwork.ordinarySockets(sockets), scope,
        writeRelays = emptySet()).also { it.start() }
    val discover = receive(reads)
    val own = try { latestDmRelayList(discover.queryAvailable(listOf(Filter(kinds = listOf(KIND_DM_RELAYS), authors = listOf(account), limit = 4)), 4_000), account) }
        finally { discover.stop() }
    if (!current()) return
    val blocked = runCatching {
        parseDeviceBlocks(ContactAccountVault.peek(app, account, "blocks"), account).map { it.participant }.toSet() +
            readSavedSignetBlocks(ContactAccountVault.peek(app, account, "directory"), account)
    }.getOrNull() ?: return
    val incoming = receive((reads + own).distinct().take(12))
    val alerts = PrivateInvitationAlerts(app)
    val inbox = ContactInbox(signer, ContactAccountVault(app, account, "inbox"), scope,
        ContactsRelay(incoming, incoming::stop), { error("The background inbox only reads") }, current,
        allowed = { false }, blocked = { it in blocked }, onIncoming = { invitation ->
            if (current() && runCatching { app.savedRooms.get(invitation.room) == null }.getOrDefault(false)) {
                alerts.post(account, invitation)
            }
        })
    try { inbox.open(); if (inbox.state.value.ready) awaitCancellation() }
    finally { withContext(NonCancellable) { inbox.close() } }
}
