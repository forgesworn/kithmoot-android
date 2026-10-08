package dev.forgesworn.kithmoot.mls

import android.content.Context
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.account.EngineCore
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.storage.EncryptedRoomStorage

/**
 * Every build carries the VMLS engine, so every build has VMLS rooms (P3-03b-3):
 * the shared coordinated vault, the app's Link engine, and their own stores
 * (decision 21), apart from saved rooms.
 */
fun vmlsBoxes(context: Context): VmlsBoxes? {
    val app = context.applicationContext as KithMootApplication
    val core = EngineCore.of(app)
    return VmlsRuntime(
        vault = core.vault,
        link = app.linkEngine,
        store = VmlsRoomStore(EncryptedRoomStorage(app, "kithmoot.vmls-rooms.v1", 1024 * 1024)),
        ledger = VmlsGrantLedger(EncryptedRoomStorage(app, "kithmoot.vmls-grants.v1", 1024 * 1024)),
        invites = VmlsInviteStore(EncryptedRoomStorage(app, "kithmoot.vmls-links.v1", 256 * 1024)),
        carriers = { relays -> RelayCarrier(relays, core.scope) },
        requestCarriers = { relays, signer -> RelayCarrier(relays, core.scope, signer) },
        // The rendezvous child is keyed by the signed-in account's NIP-46 client key.
        rendezvous = { persona ->
            app.accounts.load()?.takeIf { it.pubkey == persona }?.clientSecretKey
                ?.let { key -> app.rendezvous.active(persona, Schnorr.publicKeyHex(key)) }
        },
        quiet = core.quiet,
        scope = core.scope,
    )
}
