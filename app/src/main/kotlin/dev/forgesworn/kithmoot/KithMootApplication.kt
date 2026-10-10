package dev.forgesworn.kithmoot

import android.app.Application
import dev.forgesworn.kithmoot.account.AccountStore
import dev.forgesworn.kithmoot.account.RendezvousVault
import dev.forgesworn.kithmoot.storage.ContactBook
import dev.forgesworn.kithmoot.storage.EncryptedRoomStorage
import dev.forgesworn.kithmoot.storage.RollbackResistantRoomStorage
import dev.forgesworn.kithmoot.storage.RoomRepository
import dev.forgesworn.kithmoot.relay.LinkTransportVault
import dev.forgesworn.kithmoot.relay.LinkTransportManager
import dev.forgesworn.kithmoot.relay.ReflectiveLinkTransportRuntime
import dev.forgesworn.kithmoot.relay.LinkConsentVault
import dev.forgesworn.kithmoot.relay.Nip77EventIndex
import dev.forgesworn.kithmoot.relay.Nip77OfferArchive
import dev.forgesworn.kithmoot.cadence.CadenceLeaseVault
import dev.forgesworn.kithmoot.cadence.CadenceClient
import dev.forgesworn.kithmoot.epoch.EpochVault
import dev.forgesworn.kithmoot.epoch.RoomMembers

/**
 * Owns one serialised repository for saved room access across activities.
 * Live connections belong to the view model that opened the room.
 */
private const val VMLS_PREVIEW = "preview"

class KithMootApplication : Application() {
    /** App-private recordings are excluded from Android cloud/device backup. */
    val recordings by lazy {
        dev.forgesworn.kithmoot.media.recording.LocalRecordingStore(java.io.File(noBackupFilesDir, "recordings"))
            .apply { recover() }
    }
    /** Finalising an explicit local recording outlives room/Activity teardown. */
    val recordingExports = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    /** Where signer intents wait for their answer, so an activity recreated meanwhile does not lose it. */
    val signerRelay = dev.forgesworn.kithmoot.account.SignerRelay()

    val savedRooms: RoomRepository by lazy { RoomRepository(EncryptedRoomStorage(this)) }

    /** The Nostr account this phone is signed in as, in its own vault. */
    val accounts: AccountStore by lazy { AccountStore(EncryptedRoomStorage(this, "kithmoot.account.v1")) }

    /** Root-derived rendezvous material, deliberately isolated from accounts, rooms and contacts. */
    val rendezvous: RendezvousVault by lazy { RendezvousVault(EncryptedRoomStorage(this, "kithmoot.rendezvous.v1")) }

    /** The people this phone holds a contact card for, in their own vault. */
    val contacts: ContactBook by lazy { ContactBook(EncryptedRoomStorage(this, "kithmoot.contacts.v1")) }

    /** Link credentials have their own encrypted vault, distinct from rooms and accounts. */
    val linkTransport: LinkTransportVault by lazy { LinkTransportVault(EncryptedRoomStorage(this, "kithmoot.link-transport.v1")) }

    /** Account-and-room permissions are deliberately separate from Link route credentials. */
    val linkConsents: LinkConsentVault by lazy { LinkConsentVault(EncryptedRoomStorage(this, "kithmoot.link-consent.v1")) }

    /** Outer Nostr-event metadata only, encrypted separately from rooms and accounts. */
    val nip77Events: Nip77EventIndex by lazy {
        Nip77EventIndex(EncryptedRoomStorage(this, "kithmoot.nip77-events.v1", 2 * 1024 * 1024))
    }

    /** Encrypted outer events retained only for a later explicit NIP-77 custody offer. */
    val nip77Offers: Nip77OfferArchive by lazy {
        Nip77OfferArchive(EncryptedRoomStorage(this, "kithmoot.nip77-offers.v1", 2 * 1024 * 1024))
    }

    /** Counter ownership survives timeouts and restarts in a dedicated encrypted journal. */
    val cadenceLeases: CadenceLeaseVault by lazy {
        CadenceLeaseVault(RollbackResistantRoomStorage(this, "kithmoot.cadence.v1", 1024 * 1024))
    }

    /**
     * Active and pending room epoch secrets have their own rollback-resistant journal. The
     * recent epochs this device can hand on to another member's device live beside it, in an
     * encrypted store of their own: advisory, since a requester checks all of it.
     */
    val roomEpochs: EpochVault by lazy {
        EpochVault(
            RollbackResistantRoomStorage(this, "kithmoot.epoch.v1", 1024 * 1024),
            EncryptedRoomStorage(this, "kithmoot.epoch-history.v1", 4 * 1024 * 1024),
        )
    }

    /** Tidies self-destructing rooms away, for the app and the background service alike. */
    val selfDestructor: dev.forgesworn.kithmoot.service.RoomSelfDestructor by lazy { dev.forgesworn.kithmoot.service.RoomSelfDestructor(this) }

    /** Who each room knows, for its epoch desks (kithmoot#207). Advisory: see `RoomMembers`. */
    val roomMembers: RoomMembers by lazy { RoomMembers(EncryptedRoomStorage(this, "kithmoot.room-members.v1", 1024 * 1024)) }

    /** One engine owner for the whole process; room consent selects any usable route later. */
    val linkEngine: LinkTransportManager by lazy { LinkTransportManager(linkTransport, ReflectiveLinkTransportRuntime()) }

    private val vmlsPreferences by lazy { getSharedPreferences("kithmoot.vmls", MODE_PRIVATE) }
    private val vmlsPreviewState by lazy { kotlinx.coroutines.flow.MutableStateFlow(vmlsPreferences.getBoolean(VMLS_PREVIEW, false)) }

    /**
     * VMLS rooms and the restore witness, off until the person turns them on in
     * Settings. They need a Bothy box that offers VMLS, and most people have
     * none: while this is off nothing of theirs runs, shows or asks.
     */
    val vmlsPreview: kotlinx.coroutines.flow.StateFlow<Boolean> get() = vmlsPreviewState

    fun setVmlsPreview(on: Boolean) {
        vmlsPreferences.edit().putBoolean(VMLS_PREVIEW, on).apply()
        vmlsPreviewState.value = on
    }

    private val restoreWitnessEngine by lazy { dev.forgesworn.kithmoot.account.restoreWitness(this) }

    /** The restore-witness enrolment (P3-03b-2): null while the VMLS preview is off. */
    val restoreWitness: dev.forgesworn.kithmoot.account.RestoreWitness? get() = if (vmlsPreview.value) restoreWitnessEngine else null

    /** Ends the vault's session on sign-out or an account switch (§6.2). */
    val vaultSessionEnd: dev.forgesworn.kithmoot.account.VaultSessionEnd by lazy { dev.forgesworn.kithmoot.account.vaultSessionEnd(this) }

    private val vmlsEngine = lazy { dev.forgesworn.kithmoot.mls.vmlsBoxes(this) }

    /** VMLS rooms' runtime (P3-03b-3): null while the VMLS preview is off. */
    val vmlsBoxes: dev.forgesworn.kithmoot.mls.VmlsBoxes? get() = if (vmlsPreview.value) vmlsEngine.value else null

    /**
     * The Link routes VMLS boxes keep, whatever the switch says, so the route
     * sweep never unpairs a box because the preview was turned off. A runtime
     * that is running answers (it knows a pairing in progress); otherwise the
     * saved routes are read without starting the engine. An unreadable store
     * keeps every route, as the runtime does.
     */
    fun vmlsRouteIds(): Set<String> = vmlsBoxesIfStarted?.routeIds() ?: try {
        dev.forgesworn.kithmoot.mls.VmlsRoomStore(EncryptedRoomStorage(this, "kithmoot.vmls-rooms.v1", 1024 * 1024))
            .routes().mapTo(HashSet()) { it.routeId }
    } catch (_: dev.forgesworn.kithmoot.storage.RoomStorageException) { linkEngine.routeIds() }

    /** The runtime only if something already started it. */
    val vmlsBoxesIfStarted: dev.forgesworn.kithmoot.mls.VmlsBoxes? get() = if (vmlsEngine.isInitialized()) vmlsEngine.value else null

    /** In-app updates, checked against the signed release manifest. */
    val updates: dev.forgesworn.kithmoot.update.AppUpdates by lazy { dev.forgesworn.kithmoot.update.AppUpdates(this) }

    /** Dormant until a consented paired Bothy is explicitly probed by product UI. */
    val cadenceClient: CadenceClient by lazy { CadenceClient(linkEngine, linkConsents) }
}
