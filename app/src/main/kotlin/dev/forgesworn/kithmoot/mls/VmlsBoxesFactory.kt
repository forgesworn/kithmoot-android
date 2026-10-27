package dev.forgesworn.kithmoot.mls

import android.content.Context
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.account.EngineCore
import dev.forgesworn.kithmoot.account.AccountNetworkPolicy
import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.relay.OkHttpRelaySockets
import dev.forgesworn.kithmoot.relay.RelaySocketFactory
import kotlinx.coroutines.CoroutineScope
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
    val publicCarriers = PublicVmlsCarriers(app.accountNetwork, core.scope)
    return VmlsRuntime(
        vault = core.vault,
        link = app.linkEngine,
        store = VmlsRoomStore(EncryptedRoomStorage(app, "kithmoot.vmls-rooms.v1", 1024 * 1024)),
        ledger = VmlsGrantLedger(EncryptedRoomStorage(app, "kithmoot.vmls-grants.v1", 1024 * 1024)),
        invites = VmlsInviteStore(EncryptedRoomStorage(app, "kithmoot.vmls-links.v1", 256 * 1024)),
        carriers = publicCarriers::invitations,
        requestCarriers = publicCarriers::requests,
        // The rendezvous child is keyed by the signed-in account's NIP-46 client key.
        rendezvous = { persona ->
            app.accounts.load()?.takeIf { it.pubkey == persona }?.clientSecretKey
                ?.let { key -> app.rendezvous.active(persona, Schnorr.publicKeyHex(key)) }
        },
        quiet = core.quiet,
        scope = core.scope,
    )
}

/** Both public invitation and directory/DM carriers obey the installation network choice.
 * Capture permission per new carrier, so retained pools cannot survive a mode round trip.
 * Private Link transports and the account's NIP-46 signer are composed elsewhere. */
internal class PublicVmlsCarriers(private val policy: AccountNetworkPolicy, private val scope: CoroutineScope,
    private val sockets: RelaySocketFactory = OkHttpRelaySockets()) {
    fun invitations(relays: List<String>): VmlsCarrier = requests(relays, null)
    fun requests(relays: List<String>, signer: ParticipantSigner?): VmlsCarrier =
        RelayCarrier(relays, scope, signer, policy.ordinarySockets(sockets))
}
