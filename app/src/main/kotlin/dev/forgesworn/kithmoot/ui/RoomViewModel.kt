package dev.forgesworn.kithmoot.ui

import dev.forgesworn.kithmoot.discovery.BoxDiscovery
import dev.forgesworn.kithmoot.discovery.BoxRelayReader
import dev.forgesworn.kithmoot.session.RoomWork
import dev.forgesworn.kithmoot.session.AssignmentSnapshot
import dev.forgesworn.kithmoot.session.AvailableAssignmentAction
import dev.forgesworn.kithmoot.storage.AssignmentVault

import dev.forgesworn.kithmoot.protocol.CardResult
import dev.forgesworn.kithmoot.protocol.ContactCardBuilder
import dev.forgesworn.kithmoot.protocol.ContactCards
import dev.forgesworn.kithmoot.protocol.Lane
import dev.forgesworn.kithmoot.protocol.laneOfRelays
import dev.forgesworn.kithmoot.storage.ContactBook
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import dev.forgesworn.kithmoot.ui.room.PublicProfile
import dev.forgesworn.kithmoot.ui.room.decodePublicProfile

import android.app.Application
import android.content.Intent
import dev.forgesworn.kithmoot.account.AccountSession
import dev.forgesworn.kithmoot.account.SharedProjects
import dev.forgesworn.kithmoot.account.ProjectAccountSnapshot
import dev.forgesworn.kithmoot.account.ProjectRoomChoice
import dev.forgesworn.kithmoot.account.selectedProjectRoom
import dev.forgesworn.kithmoot.account.checkProjectRoomAdmission
import dev.forgesworn.kithmoot.protocol.SharedProject
import dev.forgesworn.kithmoot.storage.ProjectVault
import dev.forgesworn.kithmoot.account.BunkerPointer
import dev.forgesworn.kithmoot.account.BunkerSigner
import dev.forgesworn.kithmoot.account.InstalledSigner
import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.account.Nip46Client
import dev.forgesworn.kithmoot.account.Nip55Bridge
import dev.forgesworn.kithmoot.account.Nip55Signer
import dev.forgesworn.kithmoot.account.NostrAccount
import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.account.SignerException
import dev.forgesworn.kithmoot.account.npubOf
import dev.forgesworn.kithmoot.account.SignetSignIn
import dev.forgesworn.kithmoot.account.installedSigners
import dev.forgesworn.kithmoot.account.npubOf
import dev.forgesworn.kithmoot.account.openAccount
import dev.forgesworn.kithmoot.account.secretKeyFrom
import dev.forgesworn.kithmoot.account.shortNpub
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.cadence.CadenceClient
import dev.forgesworn.kithmoot.cadence.CadenceLeaseVault
import dev.forgesworn.kithmoot.cadence.CadenceOwnership
import dev.forgesworn.kithmoot.cadence.CadenceRoomTransport
import dev.forgesworn.kithmoot.cadence.StoredCadenceLease
import dev.forgesworn.kithmoot.epoch.CadenceEpochCoordinate
import dev.forgesworn.kithmoot.epoch.EpochPhase
import dev.forgesworn.kithmoot.epoch.EpochVault
import dev.forgesworn.kithmoot.epoch.EpochRecoveryResponder
import dev.forgesworn.kithmoot.epoch.StoredRoomEpoch
import dev.forgesworn.kithmoot.storage.RoomRecoveryException
import dev.forgesworn.kithmoot.storage.RoomStorageException
import dev.forgesworn.kithmoot.storage.SavedRoom
import dev.forgesworn.kithmoot.storage.SavedRoomSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CompletableFuture
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.CircleGrantStatus
import dev.forgesworn.kithmoot.protocol.CircleGrantTerms
import dev.forgesworn.kithmoot.protocol.KIND_CIRCLE_EVENT_GRANT
import dev.forgesworn.kithmoot.protocol.KIND_ROSTER
import dev.forgesworn.kithmoot.protocol.RosterEntry
import dev.forgesworn.kithmoot.protocol.encodeRosterEvent
import dev.forgesworn.kithmoot.media.LocalTrack
import dev.forgesworn.kithmoot.media.WebRtcEngine
import dev.forgesworn.kithmoot.protocol.JoinUrlException
import dev.forgesworn.kithmoot.protocol.InvitationPayload
import dev.forgesworn.kithmoot.protocol.encodePersistentInvitation
import dev.forgesworn.kithmoot.session.requestPersistentAdmission
import dev.forgesworn.kithmoot.session.GroupInvitationException
import dev.forgesworn.kithmoot.protocol.KindredTier
import dev.forgesworn.kithmoot.protocol.KIND_INVITATION_GRANT
import dev.forgesworn.kithmoot.protocol.KIND_INVITATION_REQUEST
import dev.forgesworn.kithmoot.protocol.KIND_INVITATION_RETIREMENT
import dev.forgesworn.kithmoot.protocol.RoomAdmission
import dev.forgesworn.kithmoot.protocol.Room
import dev.forgesworn.kithmoot.protocol.EpochKeys
import dev.forgesworn.kithmoot.protocol.RekeyNotice
import dev.forgesworn.kithmoot.protocol.RoomEpoch
import dev.forgesworn.kithmoot.protocol.RoomInvitationHost
import dev.forgesworn.kithmoot.protocol.createRoomInvitation
import dev.forgesworn.kithmoot.protocol.decodeRoomAdmissionGrant
import dev.forgesworn.kithmoot.protocol.decodeInvitationRequest
import dev.forgesworn.kithmoot.protocol.decodeInvitationRetirement
import dev.forgesworn.kithmoot.protocol.decodeInvitationUrl
import dev.forgesworn.kithmoot.protocol.decodeJoinUrl
import dev.forgesworn.kithmoot.protocol.deriveRoom
import dev.forgesworn.kithmoot.protocol.deriveEpoch
import dev.forgesworn.kithmoot.protocol.deriveInvitationId
import dev.forgesworn.kithmoot.protocol.encodeInvitationGrant
import dev.forgesworn.kithmoot.protocol.encodeInvitationRequest
import dev.forgesworn.kithmoot.protocol.encodeInvitationRetirement
import dev.forgesworn.kithmoot.protocol.encodeInvitationUrl
import dev.forgesworn.kithmoot.protocol.encodeJoinUrl
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.OkHttpRelaySockets
import dev.forgesworn.kithmoot.relay.RelayPool
import dev.forgesworn.kithmoot.relay.LinkConsent
import dev.forgesworn.kithmoot.relay.LinkConsentState
import dev.forgesworn.kithmoot.relay.CircleGrantPlan
import dev.forgesworn.kithmoot.relay.LinkRelayAddress
import dev.forgesworn.kithmoot.relay.HybridRelaySockets
import dev.forgesworn.kithmoot.relay.ActiveLinkRoute
import dev.forgesworn.kithmoot.relay.RelayAuthenticator
import dev.forgesworn.kithmoot.relay.RelayAuthenticatorProvider
import dev.forgesworn.kithmoot.protocol.BothyPairing
import dev.forgesworn.kithmoot.service.ScreenShareService
import dev.forgesworn.kithmoot.session.ChatMessage
import dev.forgesworn.kithmoot.session.ChatReaction
import dev.forgesworn.kithmoot.session.decodePrivateConversationInvite
import dev.forgesworn.kithmoot.session.dmPolicy
import dev.forgesworn.kithmoot.session.invitePeer
import dev.forgesworn.kithmoot.session.isDmPolicy
import dev.forgesworn.kithmoot.session.openInvite
import dev.forgesworn.kithmoot.session.sealInvite
import dev.forgesworn.kithmoot.session.WebAppAddress
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import dev.forgesworn.kithmoot.session.RoomIdentity
import dev.forgesworn.kithmoot.session.RoomSession
import dev.forgesworn.kithmoot.session.EpochGateResult
import dev.forgesworn.kithmoot.session.currentCircleGuestDevices
import dev.forgesworn.kithmoot.session.QuietTransport
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.BoxCadence
import dev.forgesworn.kithmoot.protocol.CadenceLeaseOptions
import dev.forgesworn.kithmoot.protocol.CadenceScope
import dev.forgesworn.kithmoot.protocol.DeadDrop
import dev.forgesworn.kithmoot.protocol.QuietKeys
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import dev.forgesworn.kithmoot.session.mediaAudience
import dev.forgesworn.kithmoot.session.Roles
import dev.forgesworn.kithmoot.session.SecondaryIdentity
import dev.forgesworn.kithmoot.session.decodeInvitationPairingLink
import dev.forgesworn.kithmoot.session.decodePairingLink
import dev.forgesworn.kithmoot.session.encodeInvitationPairingLink
import dev.forgesworn.kithmoot.session.encodePairingLink
import dev.forgesworn.kithmoot.ui.room.ParticipantTile
import dev.forgesworn.kithmoot.ui.room.buildTiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.AudioTrack
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.VideoTrack

/** Which screen the app is on. Two screens; a navigation library would be scaffolding. */
enum class Stage { START, ROOM }

/** The signed-in Nostr account as the start screen shows it. */
data class AccountView(
    val pubkey: String,
    val npub: String,
    val short: String,
    /** `nip55`, `bunker` or `local`. */
    val method: String,
    /** The signer app's name, for a NIP-55 account. */
    val signerLabel: String? = null,
    val profile: PublicProfile? = null,
    val name: String? = null,
) {
    val shownName: String get() = profile?.name ?: name ?: short
}

data class StartState(
    val homeTab: String = "chats",
    val projects: ProjectAccountSnapshot = ProjectAccountSnapshot(),
    val projectsBusy: Boolean = false,
    val projectError: String? = null,
    val webAppAddress: String = WebAppAddress.DEFAULT_ORIGIN,
    val joinUrl: String = "",
    val relays: String = DEFAULT_RELAYS.joinToString("\n"),
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val roomName: String = "",
    val persistentGroup: Boolean = true,
    val loadingRooms: Boolean = true,
    val storageError: Boolean = false,
    val savedRooms: List<SavedRoomSummary> = emptyList(),
    val linkConnectedRooms: Set<String> = emptySet(),
    /** Rooms where this account issued live guest grants and may revoke them without leaving Bothy. */
    val linkGrantOwnerRooms: Set<String> = emptySet(),
    /** Signed in as this person; every room from here is joined as them. */
    val account: AccountView? = null,
    /** A sign-in is under way: the signer app is up, the bunker is being reached, or Signet has the browser. */
    val signingIn: Boolean = false,
    val signInError: String? = null,
    /** Signer apps found on this phone, by name. */
    val signers: List<InstalledSigner> = emptyList(),
    /** A contact card opened as a link: offered, and kept only on a press. */
    val cardOffer: CardOffer? = null,
    /** Relays marked by hand as boxes of the person's circle, one per line:
     *  a box's drop tier fronted as wss:// and named to them by its keeper.
     *  Saved on the phone; a contact card's boxes join them without being saved. */
    val circleBoxes: String = "",
)

/** A contact card met at the door, before anything is kept. */
data class CardOffer(
    val link: String,
    val name: String?,
    val boxes: Int,
    /** Set once the card has been added; the offer then reads as done. */
    val added: Boolean = false,
)

/** One contact, as the cards sheet lists it. */
data class ContactBoxRow(val p: String, val revision: String, val description: String, val checking: Boolean)

data class ContactRow(
    val p: String,
    val npub: String,
    val name: String?,
    /** One line per box: its relays and whether it is dialled on the card's endorsement or a refreshed address. */
    val boxes: List<ContactBoxRow>,
    val expires: Long,
)

data class RoomState(
    val roomId: String = "",
    val name: String = "",
    val joinUrl: String = "",
    val relaysUp: Int = 0,
    val relaysTotal: Int = 0,
    /** The lane the next message will take, from the room's relays. */
    val lane: Lane? = null,
    /** A quiet room: chat rides the gift-wrap stream as dead drops. See session/QuietTransport.kt. */
    val quiet: Boolean = false,
    /** Whether this device may post in the quiet room: two devices per person can, others read. */
    val quietCanSend: Boolean = true,
    val cadence: CadenceViewState? = null,
    val tiles: List<ParticipantTile> = emptyList(),
    val chat: List<ChatMessage> = emptyList(),
    /** A two-member room whose invitation must travel sealed through another room. */
    val privateConversation: Boolean = false,
    /** Current people who can be chosen for a new signer-sealed private conversation. */
    val privateConversationPeers: List<String> = emptyList(),
    val privateConversationBusy: Boolean = false,
    val profilesEnabled: Boolean = false,
    val profiles: Map<String, PublicProfile> = emptyMap(),
    val selfParticipant: String = "",
    val selfDevice: String = "",
    val micOn: Boolean = false,
    val cameraOn: Boolean = false,
    val screenOn: Boolean = false,
    /** True when this device took up a pairing link rather than opening the room. */
    val secondary: Boolean = false,
    val canAddDevice: Boolean = false,
    val canRotateInvitation: Boolean = false,
    val pairingLink: String? = null,
    /**
     * Whether anything in this room that says it is an agent is sent this
     * device's camera and microphone.
     *
     * Off by default, and it is a switch on the SENDER: off means the tracks
     * are never handed to the connection to an agent, so the media does not
     * leave this device for them. A request not to listen would be a request;
     * not sending is a fact.
     */
    val agentsMayHear: Boolean = false,
    /** How many members of this room say they are agents. The switch is
     *  hidden when there are none, because it would mean nothing. */
    val agentCount: Int = 0,
    /** The observed successor epoch while recovery is pending or terminal. */
    val movedOn: Int? = null,
    val roomUpdate: String? = null,
    val work: AssignmentSnapshot = AssignmentSnapshot(),
    val workActions: List<AvailableAssignmentAction> = emptyList(),
    val workBusy: Boolean = false,
    val workError: String? = null,
    val workCompleted: Long = 0,
    val chatSending: Boolean = false,
    val chatSendError: String? = null,

    /** Set when the media stack could not be brought up. The room still works without it. */
    val mediaFault: String? = null,
    val notice: String? = null,
    /** The contact book, as the cards sheet shows it. See storage/ContactBook.kt. */
    val contacts: List<ContactRow> = emptyList(),
    /** The last word on a card pasted in: added, or the step it failed. */
    val cardStatus: String? = null,
    /** This person's own card as a link, once made. Only the device holding the identity can make one. */
    val myCard: String? = null,
    val canShowCard: Boolean = false,
) {
    val self: ParticipantTile? get() = tiles.firstOrNull { it.isSelf }
    val deviceCount: Int get() = self?.deviceCount ?: 1
}

data class CadenceViewState(
    val eligible: Boolean = false,
    val busy: Boolean = false,
    val state: String = "off",
    val detail: String = "This quiet room is not connected to a cadence-ready Bothy.",
    val startEpoch: Long? = null,
    val endEpoch: Long? = null,
    val queueCount: Int = 0,
    val sentCount: Int = 0,
    val failedCount: Int = 0,
)

/** Relays used when a room is opened here, or when a join URL names none. */
val DEFAULT_RELAYS: List<String> = listOf("wss://relay.damus.io", "wss://nos.lol")

/**
 * Where a kind-0 profile is looked for, beyond the room's own relays.
 *
 * A room on somebody's own box holds the room's events and nothing else; a
 * member's profile lives wherever they published it, which for almost
 * everybody is the public relays. Asked only on the room's relays, a person
 * who signed in with a real Nostr account still showed as a short code.
 * Read from only, and only while the profiles switch is on.
 */
val PROFILE_RELAYS: List<String> = listOf("wss://purplepag.es", "wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net")

/** How long a device credential is good for. A day outlives any meeting. */
private const val CREDENTIAL_TTL_SECONDS = 24L * 60 * 60
/** How long a contact card this phone hands out is good for. */
private const val CARD_TTL_SECONDS = 7L * 24 * 60 * 60
private const val INVITATION_TIMEOUT_MS = 60_000L
private const val INVITATION_RETRY_MS = 2_000L
private const val CIRCLE_GRANT_LIFETIME_SECONDS = 30L * 24 * 60 * 60
private const val CIRCLE_ROSTER_FRESH_SECONDS = 75L
private class RetiredInvitationException : Exception()

/**
 * Everything the two screens need, and the only thing that owns a session.
 *
 * The screens are deliberately inert - they read state and call methods here.
 * That keeps the join, leave and re-join paths in one place, which matters
 * because the interesting failure in this application is a half-torn-down room:
 * a relay pool still publishing after the user has left, or a second session
 * opened over the top of a live one.
 */
class RoomViewModel(application: Application) : AndroidViewModel(application) {

    private val _stage = MutableStateFlow(Stage.START)
    val stage: StateFlow<Stage> = _stage.asStateFlow()

    private val _start = MutableStateFlow(
        StartState(
            relays = application.getSharedPreferences("kithmoot.display", android.content.Context.MODE_PRIVATE).getString("relaySettings", null) ?: DEFAULT_RELAYS.joinToString("\n"),
            circleBoxes = application.getSharedPreferences("kithmoot.display", android.content.Context.MODE_PRIVATE).getString("circleBoxes", "") ?: "",
            webAppAddress = application.getSharedPreferences("kithmoot.display", android.content.Context.MODE_PRIVATE).getString("webAppAddress", null) ?: WebAppAddress.DEFAULT_ORIGIN,
        ),
    )
    val start: StateFlow<StartState> = _start.asStateFlow()

    private val _room = MutableStateFlow(RoomState())
    val room: StateFlow<RoomState> = _room.asStateFlow()

    /**
     * Every renderable video track, keyed `device|trackId`.
     *
     * Kept apart from [RoomState] on purpose. Tracks arrive and vanish on
     * WebRTC's own threads at a rate that has nothing to do with the roster, and
     * folding them into the room state would rebuild every tile each time a
     * keyframe-worth of plumbing changed.
     */
    private val _videos = MutableStateFlow<Map<String, VideoTrack>>(emptyMap())
    val videos: StateFlow<Map<String, VideoTrack>> = _videos.asStateFlow()

    private var sessionScope: CoroutineScope? = null
    private var pool: RelayPool? = null
    /** The pool profiles are read from: the room's relays and the public
     *  profile relays. Separate from the room's own, which must never be
     *  widened to public relays by a lookup. */
    private var profilePool: RelayPool? = null
    /** The quiet wrapper over the pool when the room is a quiet one; chat rides through it in drops. */
    private var quietTransport: QuietTransport? = null
    private var session: RoomSession? = null
    private var roomWork: RoomWork? = null
    private var engine: WebRtcEngine? = null
    private var identity: RoomIdentity? = null
    private var roomSecret: ByteArray? = null
    private var roomInvitation: InvitationPayload? = null
    private var roomInvitationHost: RoomInvitationHost? = null
    private var invitationHostJob: Job? = null
    private var relayUrls: List<String> = emptyList()
    private var opening: Job? = null

    /**
     * Serialises opening and closing a room.
     *
     * Leaving and joining are both several steps long and both tear down the
     * same fields. Without this, a quick leave-then-join interleaves the two and
     * the new session's relay pool is stopped by the old session's teardown.
     */
    private val gate = Mutex()
    private val circleGrantGate = Mutex()
    private val cadenceGate = Mutex()
    private val entering = AtomicBoolean(false)
    private val savedRooms = (application as KithMootApplication).savedRooms
    private var savedRoom: SavedRoom? = null

    // --- the Nostr account ---------------------------------------------------

    private val accounts = (application as KithMootApplication).accounts
    private val contacts = (application as KithMootApplication).contacts
    private val linkConsents = (application as KithMootApplication).linkConsents
    private val linkEngine = (application as KithMootApplication).linkEngine
    private val cadenceClient: CadenceClient = (application as KithMootApplication).cadenceClient
    private val cadenceLeases: CadenceLeaseVault = (application as KithMootApplication).cadenceLeases
    private val roomEpochs: EpochVault = (application as KithMootApplication).roomEpochs
    private val display = application.getSharedPreferences("kithmoot.display", android.content.Context.MODE_PRIVATE)
    private val selectedWebApp: WebAppAddress get() = WebAppAddress.parse(_start.value.webAppAddress)

    /** Save only a valid explicit site choice; signing in holds its own snapshot. */
    fun onWebAppAddressChanged(value: String): Boolean {
        if (_start.value.signingIn || _start.value.busy) return false
        val address = runCatching { WebAppAddress.parse(value) }.getOrNull() ?: return false
        if (!runCatching { display.edit().putString("webAppAddress", address.origin).commit() }.getOrDefault(false)) return false
        _start.update { it.copy(webAppAddress = address.origin) }
        return true
    }

    private val boxPreferencesGate = Any()
    private val boxRelayRevision = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var discoveryRelays = display.getString("boxReadRelays", null) ?: DEFAULT_RELAYS.joinToString("\n")
    private val boxDiscovery by lazy {
        BoxDiscovery(contacts, { unavailable ->
            BoxRelayReader(parseRelays(discoveryRelays), OkHttpRelaySockets(), CoroutineScope(viewModelScope.coroutineContext + Dispatchers.IO), unavailable)
        }, ::refreshContacts)
    }


    /** The circle's relays as the lane check needs them: the contact book's
     *  boxes and the relays marked by hand, both normalised as the lane check
     *  normalises. Asked each time, so a mark or a card moves the lane at once. */
    private fun circleRelaySet(): Set<String> =
        boxDiscovery.circleRelays() + circleMarks(_start.value.circleBoxes)

    fun onCircleBoxesChanged(value: String) {
        _start.update { it.copy(circleBoxes = value) }
        display.edit().putString("circleBoxes", value).apply()
        refreshContacts()
    }
    private var accountSession: AccountSession? = null
    private var sharedProjects: SharedProjects? = null
    private var projectsScope: CoroutineScope? = null
    private var projectsLifecycle: Job? = null
    private val projectEditing = AtomicBoolean(false)
    private var accountScope: CoroutineScope? = null
    private val accountGate = Mutex()
    /** The Signet pairing under way: waiting on a relay for Signet to take up the invitation. */
    private var signetPairing: Job? = null

    /** Set by the activity: the only way a signer intent can be started and answered. */
    var signerBridge: Nip55Bridge? = null

    private val _browser = MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** URLs the activity should open in the browser: the Signet sign-in. */
    val browser: SharedFlow<String> = _browser.asSharedFlow()

    /** The person's signer, when signed in. What every new room is joined as. */
    private val accountSigner: ParticipantSigner? get() = accountSession?.signer

    init {
        refreshSavedRooms()
        restoreAccount()
        viewModelScope.launch {
            kotlinx.coroutines.yield()
            withContext(Dispatchers.IO) {
                while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                    runCatching { boxDiscovery.tick() }.onFailure { note("Box checks could not read the contact vault.") }
                    delay(30_000)
                }
            }
        }
    }

    private fun restoreAccount() {
        viewModelScope.launch(Dispatchers.IO) {
            val saved = try { accounts.load() } catch (_: RoomStorageException) { null } ?: return@launch
            val bridge = signerBridge ?: LateBridge { signerBridge }
            try {
                adopt(openAccount(saved, getApplication(), bridge, newAccountScope()), saved)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _start.update { it.copy(signInError = e.message ?: "The saved account could not be opened.") }
            }
        }
    }

    /** A bridge that waits for the activity to hand one over, so a restore started before the screen is up still works. */
    private class LateBridge(private val current: () -> Nip55Bridge?) : Nip55Bridge {
        override suspend fun request(intent: Intent): Intent? {
            var bridge = current()
            var waited = 0
            while (bridge == null && waited < 5_000) { kotlinx.coroutines.delay(100); waited += 100; bridge = current() }
            return (bridge ?: throw SignerException("The screen is not ready to open the signer.")).request(intent)
        }
    }

    private fun newAccountScope(): CoroutineScope {
        accountScope?.cancel()
        return CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job])).also { accountScope = it }
    }

    private suspend fun adopt(session: AccountSession, account: NostrAccount) = accountGate.withLock {
        stopSharedProjects()
        accountSession?.close()
        accountSession = session
        val view = AccountView(
            pubkey = account.pubkey, npub = account.npub, short = shortNpub(account.pubkey), method = account.method,
            // The signer's name as the phone shows it; on a restore the sheet's list is not loaded yet, so ask the phone.
            signerLabel = account.signerPackage?.let { pkg ->
                (_start.value.signers.ifEmpty { installedSigners(getApplication()) }).firstOrNull { it.packageName == pkg }?.label ?: pkg
            },
            name = account.displayName,
        )
        _start.update { it.copy(account = view, signingIn = false, signInError = null) }
        lookUpAccountProfile(account.pubkey)
        startSharedProjects(session)
        viewModelScope.launch(Dispatchers.IO) { recoverCircleGrantCleanup(account.pubkey, session.signer) }
    }

    private suspend fun stopSharedProjects() {
        projectsScope?.cancel()
        projectsLifecycle?.join()
        sharedProjects?.close()
        sharedProjects = null; projectsScope = null; projectsLifecycle = null
    }

    private fun startSharedProjects(account: AccountSession) {
        if (!account.signer.canEncrypt) {
            _start.update { it.copy(projects = ProjectAccountSnapshot(error = "Your signer needs private-data support to sync projects.")) }; return
        }
        val relays = try { parseRelays(_start.value.relays).ifEmpty { DEFAULT_RELAYS } }
        catch (_: Exception) { _start.update { it.copy(projects = ProjectAccountSnapshot(error = "Check your relay settings, then sync projects again.")) }; return }
        val scope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]) + Dispatchers.IO)
        projectsScope = scope
        val pool = RelayPool(relays, OkHttpRelaySockets(), scope)
        val directory = SharedProjects(account.signer, pool, ProjectVault(getApplication(), account.signer.pubkey), scope)
        sharedProjects = directory
        _start.update { it.copy(projects = ProjectAccountSnapshot(syncing = true), projectError = null) }
        projectsLifecycle = scope.launch {
            pool.start()
            val observer = launch { directory.state.collect { value ->
                if (sharedProjects === directory) _start.update { it.copy(projects = value) }
            } }
            try { directory.open(); kotlinx.coroutines.awaitCancellation() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (sharedProjects === directory) _start.update { it.copy(projects = directory.state.value) } }
            finally { directory.close(); pool.stop(); observer.cancel() }
        }
    }

    fun showHomeTab(tab: String) { if (tab in listOf("chats", "projects")) _start.update { it.copy(homeTab = tab) } }

    fun refreshSharedProjects() {
        viewModelScope.launch(Dispatchers.IO) { accountGate.withLock {
            val account = accountSession ?: return@withLock
            stopSharedProjects(); startSharedProjects(account)
        } }
    }

    private suspend fun projectChange(action: suspend (SharedProjects) -> Unit): Boolean {
        val directory = sharedProjects ?: return false
        val scope = projectsScope ?: return false
        if (!projectEditing.compareAndSet(false, true)) return false
        _start.update { it.copy(projectsBusy = true, projectError = null) }
        return try {
            withContext(scope.coroutineContext) { action(directory) }; true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (sharedProjects === directory) _start.update { it.copy(projectError = e.message ?: "The project change could not be saved.") }
            false
        } finally { projectEditing.set(false); _start.update { it.copy(projectsBusy = false) } }
    }

    fun retryProjectSends() { viewModelScope.launch { projectChange { it.retry() } } }
    fun followSharedProject(project: SharedProject, joined: Boolean) { viewModelScope.launch {
        projectChange { it.follow(project.reference, joined, project.heads, java.util.UUID.randomUUID().toString()) }
    } }
    suspend fun saveSharedProject(project: SharedProject?, definition: JsonObject): Boolean = projectChange {
        val request = java.util.UUID.randomUUID().toString()
        if (project == null) it.create(definition, request) else it.update(project.reference, project.heads, definition, request)
    }

    suspend fun availableProjectRooms(): List<ProjectRoomChoice> = withContext(Dispatchers.IO) {
        val actor = accountSigner?.pubkey ?: return@withContext emptyList()
        savedRooms.list().mapNotNull { summary -> savedRooms.get(summary.id)?.takeIf {
            it.participant == actor && it.invitation?.invitation?.persistent == true && !it.retired && !it.movedOn
        }?.let { ProjectRoomChoice(it.id, dev.forgesworn.kithmoot.protocol.DisplayName.sanitise(it.name) ?: "Room", it.joinUrl) } }
    }

    fun openSharedProjectRoom(project: SharedProject, room: ProjectRoomChoice) = enter {
        val directory = sharedProjects ?: throw RoomRecoveryException("Sign in to open this project.")
        val actor = accountSigner ?: throw RoomRecoveryException("Sign in to open this project.")
        fun checkSelection() {
            if (sharedProjects !== directory || accountSigner !== actor) throw RoomRecoveryException("The signed-in account changed.")
            selectedProjectRoom(directory.state.value, project.reference, room.room, project.authority)
        }
        checkSelection()
        val selected = selectedProjectRoom(directory.state.value, project.reference, room.room, project.authority)
        val saved = savedRooms.get(room.room)
        if (saved != null) {
            if (saved.participant != actor.pubkey) throw RoomRecoveryException("This room is saved under another identity. Open it there, or forget it before joining with this account.")
            val who = saved.identity(epochSeconds(), actor)
            val derived = deriveRoom(saved.secret)
            checkProjectRoomAdmission(room.room, derived.roomId); checkSelection()
            open(derived, saved.secret, saved.relays, who, saved.secondary, saved.joinUrl, saved.invitation, saved.host(epochSeconds()), saved.policy, saved)
        } else {
            val invitation = decodeInvitationUrl(selected.link) ?: throw RoomRecoveryException("This project needs a persistent room invitation.")
            if (!invitation.invitation.persistent || decodeInvitationPairingLink(selected.link) != null) throw RoomRecoveryException("This is not a project room invitation.")
            val relays = invitation.relays.ifEmpty { parseRelays(_start.value.relays).ifEmpty { DEFAULT_RELAYS } }
            val admission = requestAdmission(invitation, relays) ?: throw RoomRecoveryException("The group invitation could not be loaded. Try again.")
            val derived = deriveRoom(admission.secret)
            try { checkProjectRoomAdmission(room.room, derived.roomId); checkSelection() }
            catch (e: Exception) { admission.secret.fill(0); throw e }
            val at = epochSeconds()
            val who = try {
                PrimaryIdentity.createWith(actor, derived.roomId, at + CREDENTIAL_TTL_SECONDS, at).also { checkSelection() }
            } catch (e: Exception) { admission.secret.fill(0); throw e }
            open(derived, admission.secret, relays, who, false, encodeInvitationUrl(selectedWebApp.joinBase, invitation.invitation, relays, invitation.policy),
                invitation, admission.delegate, invitation.policy, localName = selected.name)
        }
    }

    /** The person's own kind 0, from the public profile relays, so the account line carries their name and picture. */
    private fun lookUpAccountProfile(pubkey: String) {
        val scope = accountScope ?: return
        scope.launch {
            val pool = RelayPool(PROFILE_RELAYS, OkHttpRelaySockets(), scope)
            pool.start()
            try {
                kotlinx.coroutines.withTimeoutOrNull(10_000) {
                    pool.subscribe(listOf(Filter(kinds = listOf(0), authors = listOf(pubkey), limit = 1))).collect { event ->
                        val profile = decodePublicProfile(event, setOf(pubkey), epochSeconds()) ?: return@collect
                        _start.update { state ->
                            val account = state.account?.takeIf { it.pubkey == pubkey } ?: return@update state
                            val old = account.profile
                            if (old != null && old.createdAt >= profile.createdAt) state else state.copy(account = account.copy(profile = profile))
                        }
                    }
                }
            } finally { pool.stop() }
        }
    }

    fun refreshSigners() {
        val found = runCatching { installedSigners(getApplication()) }.getOrDefault(emptyList())
        _start.update { state -> state.copy(signers = found, account = state.account?.let { account ->
            account.copy(signerLabel = account.signerLabel?.let { label -> found.firstOrNull { it.packageName == label }?.label ?: label }) }) }
    }

    private fun signIn(block: suspend () -> Pair<AccountSession, NostrAccount>) {
        if (_start.value.signingIn) return
        _start.update { it.copy(signingIn = true, signInError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val (session, account) = block()
                accounts.save(account)
                adopt(session, account)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val message = when (e) {
                    is SignerException -> e.message
                    is RoomStorageException -> "The account could not be saved on this device."
                    else -> "Sign-in failed. Try again."
                }
                _start.update { it.copy(signingIn = false, signInError = message) }
            }
        }
    }

    /** A signer app on this phone: Amber, Cambium, or whatever answers `nostrsigner:`. */
    fun signInWithSignerApp(packageName: String) = signIn {
        val bridge = signerBridge ?: throw SignerException("The screen is not ready to open the signer.")
        val signer = Nip55Signer.connect(getApplication(), bridge, packageName)
        val account = NostrAccount(signer.pubkey, "nip55", signerPackage = signer.packageName, signedInAt = epochSeconds())
        AccountSession(account, signer) to account
    }

    /**
     * Signet: this app mints a NIP-46 invitation, the browser takes it to
     * mysignet.app, the person approves there, and Signet pairs with us over
     * a relay. The sign-in completes on the relay; the browser coming back is
     * only the person returning.
     */
    fun signInWithSignet() {
        if (_start.value.signingIn) return
        val webApp = runCatching { selectedWebApp }.getOrElse {
            _start.update { it.copy(signInError = "Choose a valid HTTPS site in Site settings.") }
            return
        }
        val scope = newAccountScope()
        val clientKey = Entropy.bytes(32)
        val secret = Entropy.bytes(16).toHex()
        val relays = SignetSignIn.RELAYS
        val pool = RelayPool(relays, OkHttpRelaySockets(), scope)
        pool.start()
        _start.update { it.copy(signingIn = true, signInError = null) }
        signetPairing = scope.launch {
            try {
                val waiting = async { Nip46Client.awaitNostrConnect(clientKey, relays, secret, pool) }
                _browser.emit(SignetSignIn.url(SignetSignIn.nostrConnectUri(Schnorr.publicKeyHex(clientKey), relays, secret, webApp = webApp), webApp))
                val pointer = waiting.await()
                val client = Nip46Client(pointer, clientKey, pool, scope)
                val pubkey = client.getPublicKey()
                val account = NostrAccount(pubkey, "bunker", bunkerUri = pointer.toUri(), clientSecretKey = clientKey, signedInAt = epochSeconds())
                accounts.save(account)
                adopt(AccountSession(account, BunkerSigner(pubkey, client, onClose = pool::stop)), account)
            } catch (e: CancellationException) { pool.stop(); throw e }
            catch (e: Exception) {
                pool.stop()
                _start.update { it.copy(signingIn = false, signInError = when (e) {
                    is SignerException -> e.message
                    is RoomStorageException -> "The account could not be saved on this device."
                    else -> "Sign-in with Signet failed. Try again."
                }) }
            } finally { signetPairing = null }
        }
    }

    /** The browser came back from Signet. Approval is finished on the relay; a refusal ends the wait. */
    fun completeSignetSignIn(link: String) {
        when (SignetSignIn.parse(link) ?: return) {
            SignetSignIn.Outcome.APPROVED -> if (signetPairing != null) note("Signet approved. Finishing the pairing…")
            SignetSignIn.Outcome.DENIED -> {
                signetPairing?.cancel()
                _start.update { it.copy(signingIn = false, signInError = "Signet declined the sign-in.") }
            }
        }
    }

    /** Somebody gave up on a sign-in the browser or the signer never finished. */
    fun cancelSignIn() {
        signetPairing?.cancel()
        signetPairing = null
        _start.update { it.copy(signingIn = false) }
    }

    /** A pasted `bunker://` link: any NIP-46 signer, a Heartwood included. */
    fun signInWithBunker(text: String) {
        val uri = text.trim()
        if (BunkerPointer.parse(uri) == null) {
            _start.update { it.copy(signInError = "That is not a bunker link. It starts with bunker:// and names at least one relay.") }
            return
        }
        connectBunker(uri, expected = null, displayName = null)
    }

    private fun connectBunker(uri: String, expected: String?, displayName: String?) = signIn {
        val pointer = BunkerPointer.parse(uri) ?: throw SignerException("That is not a bunker link.")
        val scope = newAccountScope()
        val clientKey = Entropy.bytes(32)
        val pool = RelayPool(pointer.relays, OkHttpRelaySockets(), scope)
        pool.start()
        val client = Nip46Client(pointer, clientKey, pool, scope)
        try {
            client.connect()
            val pubkey = client.getPublicKey()
            if (expected != null && pubkey != expected) throw SignerException("The signer holds ${shortNpub(pubkey)}, not the account Signet named.")
            val account = NostrAccount(pubkey, "bunker", bunkerUri = uri, clientSecretKey = clientKey, displayName = displayName, signedInAt = epochSeconds())
            AccountSession(account, BunkerSigner(pubkey, client, onClose = pool::stop)) to account
        } catch (e: Exception) {
            client.close(); pool.stop()
            throw e
        }
    }

    /** The last resort: a pasted nsec, kept in the encrypted vault on this phone. */
    fun signInWithSecretKey(text: String) = signIn {
        val key = secretKeyFrom(text) ?: throw SignerException("That is not a private key. It starts with nsec1 or is 64 hex characters.")
        val signer = LocalSigner(key)
        val account = NostrAccount(signer.pubkey, "local", secretKey = key, signedInAt = epochSeconds())
        AccountSession(account, signer) to account
    }

    fun signOut() {
        if (_stage.value != Stage.START) { note("Leave the room before signing out."); return }
        viewModelScope.launch(Dispatchers.IO) {
            accountGate.withLock {
                stopSharedProjects()
                accountSession?.close()
                accountSession = null
                accountScope?.cancel()
                accountScope = null
            }
            try { accounts.clear() } catch (_: RoomStorageException) { /* Nothing was saved. */ }
            _start.update { it.copy(account = null, signInError = null, signingIn = false, projects = ProjectAccountSnapshot(), projectError = null) }
        }
    }

    fun dismissSignInError() { _start.update { it.copy(signInError = null) } }

    /** The GL context the renderers share. Null until the media stack is up. */
    val eglBase: EglBase? get() = engine?.eglBase

    // --- start screen --------------------------------------------------------

    fun onRoomNameChanged(value: String) {
        _start.update { it.copy(roomName = value.take(80)) }
    }

    fun refreshSavedRooms() {
        _start.update { it.copy(loadingRooms = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                recoverLinkActivation()
                accountSession?.let { recoverCircleGrantCleanup(it.account.pubkey, it.signer) }
                val rooms = savedRooms.list()
                val linked = activeLinkRooms()
                _start.update { it.copy(savedRooms = rooms, linkConnectedRooms = linked, linkGrantOwnerRooms = activeGrantOwnerRooms(), loadingRooms = false, storageError = false, error = null) }
            } catch (_: RoomStorageException) { storageFailed() }
        }
    }

    /** Finish a committed relay change after process death and discard every incomplete or orphaned route. */
    private fun recoverLinkActivation() {
        linkConsents.all().forEach { consent ->
            val roomUsesLink = savedRooms.get(consent.roomId)?.relays == listOf(consent.canonicalUrl)
            when (consent.state) {
                LinkConsentState.ACTIVE -> Unit
                LinkConsentState.ACTIVATING -> if (roomUsesLink) {
                    linkConsents.put(consent.copy(state = LinkConsentState.ACTIVE))
                } else if (consent.grants.isEmpty()) discardLinkConsent(consent)
                LinkConsentState.REVOKING -> Unit
                LinkConsentState.WITHDRAWING -> Unit
                LinkConsentState.RETIRED -> Unit
                LinkConsentState.PENDING -> if (consent.grants.isEmpty()) discardLinkConsent(consent)
            }
        }
        val consentedRoutes = linkConsents.all().mapTo(mutableSetOf()) { it.routeId }
        linkEngine.routeIds().filterNot(consentedRoutes::contains).forEach(linkEngine::remove)
    }

    private fun discardLinkConsent(consent: LinkConsent) {
        linkConsents.remove(consent.accountPubkey, consent.roomId, consent.bothyNodeId)
        linkEngine.remove(consent.routeId)
    }

    private fun activeLinkRooms(): Set<String> = linkConsents.all()
        .filter { it.state in setOf(LinkConsentState.ACTIVE, LinkConsentState.REVOKING, LinkConsentState.WITHDRAWING, LinkConsentState.RETIRED) }
        .mapTo(mutableSetOf()) { it.roomId }

    private fun activeGrantOwnerRooms(): Set<String> = linkConsents.all()
        .filter { consent -> consent.grants.any { it.active.tagValue("p") != consent.accountPubkey } && !consent.grantsRevoked && consent.state in setOf(LinkConsentState.ACTIVE, LinkConsentState.REVOKING) }
        .mapTo(mutableSetOf()) { it.roomId }

    private fun unexpiredRevocations(consent: LinkConsent): List<NostrEvent> = consent.grants
        .map { it.revoked }
        .filter { it.tagValue("expiration")?.toLongOrNull()?.let { expiry -> expiry > epochSeconds() } == true }

    private fun unexpiredGuestRevocations(consent: LinkConsent): List<NostrEvent> = consent.grants
        .filter { it.active.tagValue("p") != consent.accountPubkey }
        .map { it.revoked }
        .filter { it.tagValue("expiration")?.toLongOrNull()?.let { expiry -> expiry > epochSeconds() } == true }

    private fun afterGuestRevocation(consent: LinkConsent): LinkConsent = consent.copy(
        state = LinkConsentState.ACTIVE,
        grants = consent.grants.filter { it.active.tagValue("p") == consent.accountPubkey },
        grantsRevoked = false,
    )

    private data class CadenceContext(
        val scope: CadenceScope,
        val publicRelays: List<String>,
        val grantExpiresAt: Long,
        val deviceSlot: Int,
        val roomKey: ByteArray,
    )

    private data class CadenceAccess(val context: CadenceContext?, val reason: String?)

    private fun activeRoomEpoch(record: SavedRoom): EpochKeys {
        val stored = record.authority?.let { roomEpochs.get(record.id) }
        return if (stored == null) {
            val room = deriveRoom(record.secret)
            EpochKeys(0, room.roomId, room.roomKey)
        } else {
            require(stored.phase == EpochPhase.ACTIVE || stored.phase == EpochPhase.PENDING_CADENCE_RETIREMENT) {
                if (stored.phase == EpochPhase.CLOSED) "This room was closed" else "You were removed from this room"
            }
            deriveEpoch(RoomEpoch(stored.currentEpoch, stored.currentSecret))
        }
    }

    private fun cadenceAccess(record: SavedRoom, who: RoomIdentity, secondary: Boolean): CadenceAccess {
        val epoch = activeRoomEpoch(record)
        val consent = linkConsents.all().singleOrNull {
            it.accountPubkey == who.participant && it.roomId == record.id &&
                it.state in setOf(LinkConsentState.ACTIVE, LinkConsentState.REVOKING)
        } ?: return CadenceAccess(null, "Connect this quiet room to its Bothy before scheduling cover.")
        if (consent.grantsRevoked) {
            return CadenceAccess(null, "Reconnect Bothy to install this device's cadence grant.")
        }
        val nodeId = consent.canonicalUrl.removePrefix("ws://").removeSuffix("/events")
        if (!runCatching { BoxCadence.server(nodeId) }.isSuccess) {
            return CadenceAccess(null, "The saved Bothy route is not a canonical Link address.")
        }
        val now = epochSeconds()
        val grant = consent.grants.singleOrNull { plan ->
            plan.active.tagValue("p") == who.participant && plan.active.tagValue("device") == who.devicePubkey &&
                plan.active.tagValue("status") == CircleGrantStatus.ACTIVE.wire &&
                plan.active.tagValue("expiration")?.toLongOrNull()?.let { it > now } == true
        } ?: return CadenceAccess(null, "Reconnect Bothy from the room creator to install this device's cadence grant.")
        val grantId = grant.active.tagValue("grant")
            ?: return CadenceAccess(null, "The saved cadence grant is incomplete. Reconnect Bothy.")
        val publicRelays = consent.previousRelays.filter { it.startsWith("wss://") }.distinct()
        if (publicRelays.size < 2) {
            return CadenceAccess(null, "Cadence needs at least two canonical public WSS relays from the room's earlier route.")
        }
        return CadenceAccess(CadenceContext(
            CadenceScope(nodeId, record.id, epoch.id, epoch.epoch.toLong() + 1, who.participant, who.devicePubkey, who.credential, grantId),
            publicRelays,
            requireNotNull(grant.active.tagValue("expiration")).toLong(),
            if (secondary) 1 else 0,
            epoch.key,
        ), null)
    }

    private fun initialCadenceView(access: CadenceAccess, room: String, device: String): CadenceViewState {
        if (access.context == null) return CadenceViewState(detail = access.reason ?: "Cadence is unavailable.")
        return runCatching {
            cadenceLeases.all(room, device).filter { it.ownership != CadenceOwnership.ENDED }.maxByOrNull { it.plan.generation }
                ?.let { cadenceView(it, eligible = true) }
                ?: CadenceViewState(eligible = true, detail = "Bothy can take over this phone's quiet cadence for up to twelve hours.")
        }.getOrElse {
            CadenceViewState(state = "blocked", detail = "The cadence ownership journal could not be opened. Delegated counters remain unavailable.")
        }
    }

    private fun cadenceView(lease: StoredCadenceLease, eligible: Boolean, busy: Boolean = false): CadenceViewState {
        val receipt = lease.receipt
        val state = if (lease.ownership == CadenceOwnership.CLIENT_EXCLUDED) "unresolved"
            else if (receipt?.code == "stopping") "stopping" else receipt?.state ?: "unresolved"
        val detail = when (state) {
            "unresolved" -> "The lease reply was not confirmed. KithMoot kept its exact bytes and will retry without reclaiming the counters."
            "staged" -> "Bothy accepted the schedule. This phone keeps sending until the delegated start epoch."
            "active" -> "Bothy owns this device's quiet cadence and queued messages during the scheduled window."
            "stopping" -> "Bothy will stop real sends at the safe boundary, then keep cover until the original end epoch."
            "cover" -> "Real sends have stopped. Bothy keeps the fixed cover pattern until the original end epoch."
            "ended" -> "Bothy reports this schedule ended. Its delegated counters are released."
            else -> "Bothy returned cadence state $state."
        }
        return CadenceViewState(
            eligible = eligible,
            busy = busy,
            state = state,
            detail = detail,
            startEpoch = lease.plan.startEpoch,
            endEpoch = lease.plan.endEpoch,
            queueCount = receipt?.queueCount ?: 0,
            sentCount = receipt?.sentItemIds?.size ?: 0,
            failedCount = receipt?.failedItemIds?.size ?: 0,
        )
    }

    /** Resolve Bob's current signed device roster before the public room relay is removed. */
    private suspend fun circleGuestDevices(room: SavedRoom, self: String): List<Pair<String, String>> {
        if (room.invitation?.invitation?.persistent != true) {
            throw RoomRecoveryException("Bothy can currently shelter only a persistent conversation.")
        }
        val members = room.policy?.members
            ?.takeIf { it.size == 2 && self in it }
            ?: throw RoomRecoveryException("Bothy can currently shelter only a two-person persistent conversation.")
        val guest = members.single { it != self }
        val scope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))
        val source = RelayPool(room.relays, OkHttpRelaySockets(), scope)
        return try {
            source.start()
            val now = epochSeconds()
            val events = source.queryStored(listOf(Filter(kinds = listOf(KIND_ROSTER), tags = mapOf("#d" to listOf(room.id)))))
            val latest = currentCircleGuestDevices(
                events, room.id, deriveRoom(room.secret).roomKey, guest, now, CIRCLE_ROSTER_FRESH_SECONDS,
            )
            if (latest.isEmpty()) {
                throw RoomRecoveryException("The other person must have this conversation open before Bothy can grant their current device.")
            }
            latest
        } finally {
            source.stop()
            scope.cancel()
        }
    }

    private suspend fun circleGrantPlans(
        signer: ParticipantSigner,
        server: String,
        room: String,
        guests: List<Pair<String, String>>,
    ): List<CircleGrantPlan> {
        val createdAt = epochSeconds()
        val expiration = createdAt + CIRCLE_GRANT_LIFETIME_SECONDS
        return guests.map { (persona, device) ->
            val terms = CircleGrantTerms(server, room, persona, device, Entropy.bytes(16).toHex(), expiration)
            CircleGrantPlan(
                signer.sign(KIND_CIRCLE_EVENT_GRANT, createdAt, terms.tags(CircleGrantStatus.ACTIVE), ""),
                signer.sign(KIND_CIRCLE_EVENT_GRANT, createdAt + 1, terms.tags(CircleGrantStatus.REVOKED), ""),
            )
        }
    }

    /** Publish through one exact paired route. A second exact send resolves a lost OK safely. */
    private suspend fun publishShelteredEvents(consent: LinkConsent, signer: ParticipantSigner, events: List<NostrEvent>) {
        val scope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))
        val authenticator = object : RelayAuthenticator {
            override val pubkey = signer.pubkey
            override suspend fun sign(url: String, challenge: String) = signer.sign(
                22242, epochSeconds(), listOf(listOf("relay", url), listOf("challenge", challenge)), "",
            )
        }
        val sockets = HybridRelaySockets(OkHttpRelaySockets(), linkEngine, ActiveLinkRoute { url ->
            consent.routeId.takeIf { url == consent.canonicalUrl }
        })
        val relay = RelayPool(listOf(consent.canonicalUrl), sockets, scope,
            authenticators = RelayAuthenticatorProvider { url -> authenticator.takeIf { url == consent.canonicalUrl } })
        try {
            relay.start()
            withTimeoutOrNull(20_000) { relay.connected.first { consent.canonicalUrl in it } }
                ?: throw RoomRecoveryException("Bothy's authenticated relay did not become ready.")
            for (event in events) {
                var failure: Exception? = null
                var confirmed = false
                repeat(2) {
                    if (confirmed) return@repeat
                    try {
                        confirmed = relay.publishConfirmed(event)
                        if (!confirmed) throw RoomRecoveryException("Bothy refused a required sheltered event.")
                    } catch (e: Exception) {
                        failure = e
                    }
                }
                if (!confirmed) throw RoomRecoveryException(failure?.message ?: "Bothy did not confirm a required sheltered event.")
            }
        } finally {
            relay.stop()
            scope.cancel()
        }
    }

    /** Finish authority withdrawal after process death while retaining the only route that can reach it. */
    private suspend fun recoverCircleGrantCleanup(account: String, signer: ParticipantSigner) = circleGrantGate.withLock {
        val pending = linkConsents.all().filter { consent ->
            consent.accountPubkey == account && when (consent.state) {
                LinkConsentState.PENDING -> consent.grants.isNotEmpty()
                LinkConsentState.ACTIVATING -> consent.grants.isNotEmpty() && savedRooms.get(consent.roomId)?.relays != listOf(consent.canonicalUrl)
                LinkConsentState.REVOKING -> consent.grants.isNotEmpty()
                LinkConsentState.WITHDRAWING -> true
                LinkConsentState.RETIRED -> true
                LinkConsentState.ACTIVE -> false
            }
        }
        for (consent in pending) {
            try {
                if (consent.state != LinkConsentState.RETIRED) {
                    val revocations = if (consent.state == LinkConsentState.REVOKING) {
                        unexpiredGuestRevocations(consent)
                    } else unexpiredRevocations(consent)
                    revocations.takeIf { it.isNotEmpty() }?.let { publishShelteredEvents(consent, signer, it) }
                }
                when (consent.state) {
                    LinkConsentState.REVOKING -> linkConsents.put(afterGuestRevocation(consent))
                    LinkConsentState.WITHDRAWING -> {
                        linkEngine.retire(consent.routeId).get()
                        val retired = consent.copy(state = LinkConsentState.RETIRED)
                        linkConsents.put(retired)
                        completeRetiredLinkCleanup(retired)
                    }
                    LinkConsentState.RETIRED -> completeRetiredLinkCleanup(consent)
                    else -> discardLinkConsent(consent)
                }
            } catch (e: Exception) {
                _start.update { it.copy(error = e.message ?: "Bothy grant withdrawal is waiting to retry.") }
            }
        }
        _start.update { it.copy(savedRooms = savedRooms.list(), linkConnectedRooms = activeLinkRooms(), linkGrantOwnerRooms = activeGrantOwnerRooms()) }
    }

    private fun completeRetiredLinkCleanup(consent: LinkConsent) {
        runCatching { linkEngine.finalize(consent.routeId).get() }
        savedRooms.update(consent.roomId) { it.withRelays(consent.previousRelays) }
            ?: throw RoomRecoveryException("This room is no longer saved on this device.")
        discardLinkConsent(consent)
    }

    private fun storageFailed() {
        _start.update { it.copy(loadingRooms = false, storageError = true, savedRooms = emptyList(),
            error = "Saved rooms could not be unlocked or saved. Try again. Your saved data has been kept.") }
    }

    fun forgetRoom(id: String) = changeSavedRooms {
        if (linkConsents.all().any { it.roomId == id }) {
            throw RoomRecoveryException("Disconnect Bothy and confirm grant withdrawal before forgetting this room.")
        }
        savedRooms.get(id)?.let { AssignmentVault(getApplication(),id,it.participant).reset() }
        savedRooms.forget(id)
    }
    fun renameRoom(id: String, name: String) = changeSavedRooms { savedRooms.update(id) { it.renamed(name) } }
    fun setRoomProject(id: String, project: String) = changeSavedRooms { savedRooms.update(id) { it.inProject(project) } }
    fun resetSavedRooms() = changeSavedRooms {
        if (linkConsents.all().isNotEmpty()) {
            throw RoomRecoveryException("Disconnect Bothy from every room and confirm grant withdrawal before resetting saved rooms.")
        }
        linkConsents.reset()
        savedRooms.reset()
    }

    fun pairBothy(roomId: String, code: String) {
        if (_stage.value != Stage.START || !entering.compareAndSet(false, true)) return
        _start.update { it.copy(busy = true, error = null, notice = null) }
        viewModelScope.launch(Dispatchers.IO) {
            if (!circleGrantGate.tryLock()) {
                entering.set(false)
                _start.update { it.copy(busy = false, error = "Bothy is still finishing an earlier grant change.") }
                return@launch
            }
            var message: String? = null
            try {
                val room = savedRooms.get(roomId) ?: throw RoomRecoveryException("This room is no longer saved on this device.")
                val account = accountSession?.account ?: throw RoomRecoveryException("Sign in as this room's account before connecting Bothy.")
                require(room.viaAccount && room.participant == account.pubkey) { "This saved room is not owned by the signed-in account." }
                val pairing = BothyPairing.parse(code, epochSeconds())
                if (linkConsents.all().any { it.accountPubkey == account.pubkey && it.roomId == room.id }) {
                    throw RoomRecoveryException("This room already has a Bothy connection or a withdrawal waiting to finish.")
                }
                val signer = accountSession?.signer ?: throw RoomRecoveryException("The signed-in account is no longer available.")
                val nip55 = signer as? Nip55Signer ?: throw RoomRecoveryException("The first Bothy journey requires an on-device NIP-55 signer.")
                // Persistent invitation hosts are retained only by the creator;
                // the invitation key itself is deliberately unrelated to their account.
                val issuesGrants = room.host(epochSeconds()) != null
                val guests = if (issuesGrants) circleGuestDevices(room, account.pubkey) else emptyList()
                nip55.requestPermissions(if (issuesGrants) listOf(22242, KIND_CIRCLE_EVENT_GRANT) else listOf(22242))
                val canonical = LinkRelayAddress.canonicalForNode(pairing.linkNodeId)
                val at = epochSeconds()
                val identity = room.identity(at, signer)
                val plans = if (issuesGrants) circleGrantPlans(
                    signer, canonical, room.id, listOf(identity.participant to identity.devicePubkey) + guests,
                ) else emptyList()
                val readiness = encodeRosterEvent(
                    RosterEntry(identity.participant, identity.devicePubkey, identity.credential, updatedAt = at),
                    room.id, room.secret, identity.deviceSecretKey,
                )
                val route = try {
                    linkEngine.pair(pairing.card, pairing.pairingSecret, pairing.expiresAt).get()
                } catch (error: java.util.concurrent.ExecutionException) {
                    throw error.cause ?: error
                }
                var consent = LinkConsent(account.pubkey, room.id, pairing.linkNodeId, route.routeId,
                    canonical, room.relays, LinkConsentState.PENDING, plans)
                try { linkConsents.put(consent) } catch (e: Exception) {
                    runCatching { linkEngine.remove(route.routeId) }
                    throw e
                }
                var roomChanged = false
                var publicationStarted = false
                try {
                    publicationStarted = true
                    publishShelteredEvents(consent, signer, plans.map { it.active } + readiness)
                    consent = consent.copy(state = LinkConsentState.ACTIVATING).also(linkConsents::put)
                    savedRooms.update(room.id) { it.withRelays(listOf(canonical)) }
                        ?: throw RoomRecoveryException("This room is no longer saved on this device.")
                    roomChanged = true
                    linkConsents.put(consent.copy(state = LinkConsentState.ACTIVE))
                    message = if (issuesGrants) {
                        "Bothy is connected to ${room.name}; ${guests.size} guest device grant${if (guests.size == 1) "" else "s"} confirmed."
                    } else "Bothy is connected to ${room.name}; the creator's grant for this device was confirmed."
                } catch (e: Exception) {
                    if (roomChanged) savedRooms.update(room.id) { it.withRelays(room.relays) }
                    val withdrawn = !publicationStarted || plans.isEmpty() || runCatching {
                        publishShelteredEvents(consent, signer, plans.map { it.revoked })
                    }.isSuccess
                    if (withdrawn) discardLinkConsent(consent)
                    throw RoomRecoveryException(if (withdrawn)
                        "Bothy could not confirm the room grants. Your current relays are unchanged."
                    else "Bothy could not confirm grant withdrawal. Your current relays are unchanged and KithMoot kept the route to finish cleanup.")
                }
                _start.update { it.copy(savedRooms = savedRooms.list(), linkConnectedRooms = activeLinkRooms(), linkGrantOwnerRooms = activeGrantOwnerRooms(), error = null, notice = message) }
            } catch (_: RoomStorageException) { storageFailed() }
            catch (e: Exception) { _start.update { it.copy(error = e.message ?: "Bothy could not be connected.") } }
            finally { circleGrantGate.unlock(); entering.set(false); _start.update { it.copy(busy = false) } }
        }
    }

    fun disconnectBothy(roomId: String) {
        if (_stage.value != Stage.START || !entering.compareAndSet(false, true)) return
        _start.update { it.copy(busy = true, error = null, notice = null) }
        viewModelScope.launch(Dispatchers.IO) {
            if (!circleGrantGate.tryLock()) {
                entering.set(false)
                _start.update { it.copy(busy = false, error = "Bothy is still finishing an earlier grant change.") }
                return@launch
            }
            try {
                val room = savedRooms.get(roomId) ?: throw RoomRecoveryException("This room is no longer saved on this device.")
                val account = accountSession?.account ?: throw RoomRecoveryException("Sign in as this room's account before disconnecting Bothy.")
                val signer = accountSession?.signer ?: throw RoomRecoveryException("The signed-in account is no longer available.")
                val consent = linkConsents.all().singleOrNull {
                    it.accountPubkey == account.pubkey && it.roomId == room.id &&
                        it.state in setOf(LinkConsentState.ACTIVE, LinkConsentState.REVOKING, LinkConsentState.WITHDRAWING, LinkConsentState.RETIRED)
                } ?: throw RoomRecoveryException("This room is not connected through Bothy.")
                if (consent.state == LinkConsentState.RETIRED) {
                    completeRetiredLinkCleanup(consent)
                    _start.update { it.copy(savedRooms = savedRooms.list(), linkConnectedRooms = activeLinkRooms(), linkGrantOwnerRooms = activeGrantOwnerRooms(), notice = "Bothy access was withdrawn from ${room.name}.") }
                    return@launch
                }
                if (consent.state in setOf(LinkConsentState.ACTIVE, LinkConsentState.REVOKING)) {
                    stopCadenceBeforeDisconnect(room, signer)
                }
                linkConsents.put(consent.copy(state = LinkConsentState.WITHDRAWING))
                if (consent.grants.isNotEmpty() && !consent.grantsRevoked) {
                    unexpiredRevocations(consent).takeIf { it.isNotEmpty() }?.let { publishShelteredEvents(consent, signer, it) }
                }
                try {
                    linkEngine.retire(consent.routeId).get()
                } catch (error: java.util.concurrent.ExecutionException) {
                    throw error.cause ?: error
                }
                val retired = consent.copy(state = LinkConsentState.RETIRED)
                linkConsents.put(retired)
                completeRetiredLinkCleanup(retired)
                _start.update { it.copy(savedRooms = savedRooms.list(), linkConnectedRooms = activeLinkRooms(), linkGrantOwnerRooms = activeGrantOwnerRooms(), notice = "Bothy access was withdrawn from ${room.name}.") }
            } catch (_: RoomStorageException) { storageFailed() }
            catch (e: Exception) { _start.update { it.copy(error = e.message ?: "Bothy access could not be withdrawn; KithMoot kept the route for retry.") } }
            finally { circleGrantGate.unlock(); entering.set(false); _start.update { it.copy(busy = false) } }
        }
    }

    /** Revoke Bob's authority while Alice keeps her keeper connection and retained ciphertext. */
    fun revokeBothyGuests(roomId: String) {
        if (_stage.value != Stage.START || !entering.compareAndSet(false, true)) return
        _start.update { it.copy(busy = true, error = null, notice = null) }
        viewModelScope.launch(Dispatchers.IO) {
            if (!circleGrantGate.tryLock()) {
                entering.set(false)
                _start.update { it.copy(busy = false, error = "Bothy is still finishing an earlier grant change.") }
                return@launch
            }
            try {
                val room = savedRooms.get(roomId) ?: throw RoomRecoveryException("This room is no longer saved on this device.")
                val account = accountSession?.account ?: throw RoomRecoveryException("Sign in as this room's account before revoking guest access.")
                val signer = accountSession?.signer ?: throw RoomRecoveryException("The signed-in account is no longer available.")
                val consent = linkConsents.all().singleOrNull {
                    it.accountPubkey == account.pubkey && it.roomId == room.id &&
                        it.state in setOf(LinkConsentState.ACTIVE, LinkConsentState.REVOKING) &&
                        it.grants.any { plan -> plan.active.tagValue("p") != account.pubkey } && !it.grantsRevoked
                } ?: throw RoomRecoveryException("This room has no active guest grants issued by this account.")
                val revoking = consent.copy(state = LinkConsentState.REVOKING).also(linkConsents::put)
                unexpiredGuestRevocations(revoking).takeIf { it.isNotEmpty() }?.let { publishShelteredEvents(revoking, signer, it) }
                linkConsents.put(afterGuestRevocation(revoking))
                _start.update { it.copy(linkConnectedRooms = activeLinkRooms(), linkGrantOwnerRooms = activeGrantOwnerRooms(), notice = "Bothy confirmed guest access was revoked for ${room.name}.") }
            } catch (_: RoomStorageException) { storageFailed() }
            catch (e: Exception) { _start.update { it.copy(error = e.message ?: "Bothy guest revocation is waiting to retry.") } }
            finally { circleGrantGate.unlock(); entering.set(false); _start.update { it.copy(busy = false) } }
        }
    }

    private fun changeSavedRooms(change: () -> Unit) {
        if (_stage.value != Stage.START || !entering.compareAndSet(false, true)) return
        _start.update { it.copy(busy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                change()
                val rooms = savedRooms.list()
                val linked = activeLinkRooms()
                _start.update { it.copy(savedRooms = rooms, linkConnectedRooms = linked, linkGrantOwnerRooms = activeGrantOwnerRooms(), storageError = false, error = null) }
            } catch (_: RoomStorageException) { storageFailed() }
            catch (e: Exception) { _start.update { it.copy(error = e.message ?: "The saved room could not be changed.") } }
            finally { entering.set(false); _start.update { it.copy(busy = false) } }
        }
    }

    fun reopenRoom(id: String) = enter {
        openSaved(savedRooms.get(id) ?: throw RoomRecoveryException("This room is no longer saved on this device."))
    }

    private suspend fun openSaved(saved: SavedRoom) {
        val who = saved.identity(epochSeconds(), accountSigner)
        open(deriveRoom(saved.secret), saved.secret, saved.relays, who, saved.secondary,
            saved.joinUrl, saved.invitation, saved.host(epochSeconds()), saved.policy, saved)
    }

    /** The saved identity for this room if there is one, else the signed-in account, else a key made here for this room. */
    private suspend fun primaryFor(roomId: String, now: Long): RoomIdentity = savedRooms.get(roomId)?.identity(now, accountSigner)
        ?: accountSigner?.let { PrimaryIdentity.createWith(it, roomId, now + CREDENTIAL_TTL_SECONDS, now) }
        ?: PrimaryIdentity.create(roomId, now + CREDENTIAL_TTL_SECONDS, now)

    /** Storage and network failures stay on the entry screen; parallel taps cannot open two sessions. */
    private fun enter(block: suspend () -> Unit) {
        if (_stage.value != Stage.START) {
            note("Leave this room before opening another. The invitation will be waiting on the home screen.")
            return
        }
        if (!entering.compareAndSet(false, true)) return
        _start.update { it.copy(busy = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            var opened = false
            try {
                start.first { !it.loadingRooms }
                if (!_start.value.storageError) {
                    block()
                    opened = session != null
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                gate.withLock { closeSession(); _room.value = RoomState(); _stage.value = Stage.START }
                when (e) {
                    is RoomStorageException -> storageFailed()
                    is RoomRecoveryException -> _start.update { it.copy(error = e.message) }
                    is GroupInvitationException -> _start.update { it.copy(error = e.message) }
                    else -> _start.update { it.copy(error = "The room could not be opened. Try again.") }
                }
            } finally {
                // Publish room controls only once entry has released its guard.
                // Otherwise a fast Leave tap can be silently rejected. Keep the
                // unlock and UI transition on Main so a tap cannot interleave.
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    entering.set(false)
                    _start.update { it.copy(busy = false) }
                    if (opened && session != null) _stage.value = Stage.ROOM
                }
            }
        }
    }

    fun onJoinUrlChanged(value: String) {
        _start.value = _start.value.copy(joinUrl = value, error = null)
    }

    fun onRelaysChanged(value: String) {
        boxRelayRevision.incrementAndGet()
        _start.value = _start.value.copy(relays = value, error = null)
        // Keep the last valid network choice across process restarts. Partial
        // text being edited is not a replacement for working relay settings.
        if (runCatching { parseRelays(value) }.isSuccess) display.edit().putString("relaySettings", value).apply()
        act { runCatching { synchronized(boxPreferencesGate) { boxDiscovery.disableAll() } }.onFailure { note("Box checks could not be stopped in the saved preferences.") } }
    }

    fun onPersistentGroupChanged(value: Boolean) {
        _start.update { it.copy(persistentGroup = value, error = null) }
    }

    /**
     * Opens a room.
     *
     * All of it runs off the main thread. Making a participant key, minting a
     * credential and announcing are a key generation, two signatures and a
     * NIP-44 encryption, and the first of them loads libsecp256k1 - which on a
     * cold, busy device is comfortably long enough for the platform to call the
     * application unresponsive.
     */
    fun startRoom() {
        val relays = parseRelays(_start.value.relays)
        if (relays.isEmpty()) {
            _start.value = _start.value.copy(error = "Name at least one relay.")
            return
        }
        val name = _start.value.roomName
        val persistent = true
        enter {
            val secret = Entropy.bytes(32)
            val invitationHost = createRoomInvitation(persistent)
            val invitation = InvitationPayload(invitationHost.invitation, relays, null)
            val derived = deriveRoom(secret)
            val at = epochSeconds()
            val primary = accountSigner?.let { PrimaryIdentity.createWith(it, derived.roomId, at + CREDENTIAL_TTL_SECONDS, at) }
                ?: PrimaryIdentity.create(
                    roomId = derived.roomId,
                    expiresAt = at + CREDENTIAL_TTL_SECONDS,
                    createdAt = at,
                )
            if (persistent) publishGroup(invitationHost, secret, relays)
            open(
                derived = derived,
                secret = secret,
                relays = relays,
                who = primary,
                secondary = false,
                joinUrl = encodeInvitationUrl(selectedWebApp.joinBase, invitation.invitation, relays),
                invitation = invitation,
                invitationHost = invitationHost,
                localName = name,
            )
        }
    }

    /** Joins from a pasted or tapped link. The one entry point for both. */
    fun joinFromUrl(raw: String) {
        val url = raw.trim()
        if (url.isEmpty()) {
            _start.value = _start.value.copy(error = "Paste a join link first.")
            return
        }
        enter { join(url) }
    }

    private suspend fun join(url: String) {
        // A contact card opened as a link is not a room. It is offered, and
        // nothing is kept until the person presses the button.
        val read = ContactCards.read(url, epochSeconds())
        if (read is CardResult.Ok) {
            val held = runCatching { contacts.get(read.card.p) }.getOrNull()
            _start.value = _start.value.copy(busy = false, error = null,
                cardOffer = CardOffer(url, read.card.name, read.boxes.size, added = held != null && held.readAt >= read.card.issued))
            return
        }
        val invitation = try {
            decodeInvitationUrl(url)
        } catch (e: JoinUrlException) {
            _start.value = _start.value.copy(busy = false, error = e.message ?: "That is not a join link.")
            return
        } catch (_: Exception) {
            _start.value = _start.value.copy(busy = false, error = "That is not a join link.")
            return
        }

        if (invitation != null) {
            if (decodeInvitationPairingLink(url) == null) {
                savedRooms.findInvitation(url)?.let { openSaved(it); return }
            }
            joinInvitation(url, invitation)
            return
        }

        val payload = try {
            decodeJoinUrl(url)
        } catch (e: JoinUrlException) {
            _start.value = _start.value.copy(busy = false, error = e.message ?: "That is not a join link.")
            return
        } catch (_: Exception) {
            _start.value = _start.value.copy(busy = false, error = "That is not a join link.")
            return
        }

        val derived = deriveRoom(payload.secret)
        val relays = payload.relays.ifEmpty { parseRelays(_start.value.relays).ifEmpty { DEFAULT_RELAYS } }
        val at = epochSeconds()

        // A pairing link carries a device key and a credential, so this device
        // joins as another of that person's devices rather than as a stranger.
        val pairing = decodePairingLink(url)
        if (pairing != null) {
            val secondary = SecondaryIdentity.adopt(
                credential = pairing.credential,
                deviceSecretKey = pairing.deviceSecretKey,
                roomId = derived.roomId,
                now = at,
            )
            if (secondary == null) {
                _start.value = _start.value.copy(
                    busy = false,
                    error = "That pairing link has expired, or it was minted for a different room.",
                )
                return
            }
            open(
                derived,
                payload.secret,
                relays,
                secondary,
                secondary = true,
                joinUrl = encodeJoinUrl(selectedWebApp.joinBase, payload.secret, relays, payload.policy),
                policy = payload.policy,
            )
            return
        }

        savedRooms.get(derived.roomId)?.let { openSaved(it); return }
        val primary = primaryFor(derived.roomId, at)
        open(
            derived,
            payload.secret,
            relays,
            primary,
            secondary = primary is SecondaryIdentity,
            joinUrl = encodeJoinUrl(selectedWebApp.joinBase, payload.secret, relays, payload.policy),
            policy = payload.policy,
        )
    }

    private suspend fun joinInvitation(url: String, payload: InvitationPayload) {
        val relays = payload.relays.ifEmpty { parseRelays(_start.value.relays).ifEmpty { DEFAULT_RELAYS } }
        val admission = try {
            requestAdmission(payload, relays)
        } catch (e: GroupInvitationException) {
            _start.update { it.copy(busy = false, error = e.message) }
            return
        } catch (_: RetiredInvitationException) {
            _start.value = _start.value.copy(
                busy = false,
                error = "This invitation was retired. Ask for the current room link.",
            )
            return
        }
        if (admission == null) {
            _start.value = _start.value.copy(
                busy = false,
                error = if (payload.invitation.persistent) "The group invitation could not be loaded from its relays. Try again."
                    else "The room is not answering this invitation. Ask for a fresh link.",
            )
            return
        }
        val secret = admission.secret

        val derived = deriveRoom(secret)
        val at = epochSeconds()
        val pairing = decodeInvitationPairingLink(url)
        if (pairing == null && !payload.invitation.persistent) {
            savedRooms.get(derived.roomId)?.takeIf { it.invitation?.invitation?.persistent == true }
                ?.let { openSaved(it); return }
        }
        if (pairing != null) {
            val secondary = SecondaryIdentity.adopt(
                credential = pairing.credential,
                deviceSecretKey = pairing.deviceSecretKey,
                roomId = derived.roomId,
                now = at,
            )
            if (secondary == null) {
                _start.value = _start.value.copy(
                    busy = false,
                    error = "That pairing link has expired, or it was minted for a different room.",
                )
                return
            }
            open(
                derived,
                secret,
                relays,
                secondary,
                secondary = true,
                joinUrl = encodeInvitationUrl(selectedWebApp.joinBase, payload.invitation, relays, payload.policy),
                invitation = payload,
                invitationHost = admission.delegate,
                policy = payload.policy,
            )
            return
        }

        val primary = primaryFor(derived.roomId, at)
        open(
            derived,
            secret,
            relays,
            primary,
            secondary = primary is SecondaryIdentity,
            joinUrl = encodeInvitationUrl(selectedWebApp.joinBase, payload.invitation, relays, payload.policy),
            invitation = payload,
            invitationHost = admission.delegate,
            policy = payload.policy,
        )
    }

    /** Exchange the bearer for a traffic secret and a bounded responder
     * delegation, without an account or prompt. */
    private suspend fun requestAdmission(payload: InvitationPayload, relays: List<String>): RoomAdmission? {
        if (payload.invitation.persistent) return withGroupRelays(relays) { transport ->
            try {
                requestPersistentAdmission(payload.invitation) { transport.queryStored(it) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                throw GroupInvitationException("The group invitation could not be loaded from its relays. Try again.")
            } catch (e: GroupInvitationException) { throw e
            } catch (_: Exception) {
                throw GroupInvitationException("The group invitation could not be loaded from its relays. Try again.")
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val transport = RelayPool(relays, OkHttpRelaySockets(), scope)
        val requesterKey = Entropy.bytes(32)
        val request = encodeInvitationRequest(payload.invitation, requesterKey, epochSeconds())
        val invitationId = deriveInvitationId(payload.invitation)
        transport.start()
        return try {
            withTimeoutOrNull(INVITATION_TIMEOUT_MS) {
                coroutineScope {
                    // Start collecting before the first publish. Invitation
                    // events are ephemeral, so subscribing one line later is
                    // enough to miss a fast response for good.
                    val response = async(start = CoroutineStart.UNDISPATCHED) {
                        transport.subscribe(
                            listOf(
                                Filter(
                                    kinds = listOf(KIND_INVITATION_GRANT),
                                    tags = mapOf(
                                        "#d" to listOf(invitationId),
                                        "#p" to listOf(Schnorr.publicKeyHex(requesterKey)),
                                    ),
                                ),
                                Filter(
                                    authors = listOf(payload.invitation.canonicalInviter),
                                    kinds = listOf(KIND_INVITATION_RETIREMENT),
                                    tags = mapOf("#d" to listOf(invitationId)),
                                ),
                            ),
                        ).mapNotNull { event ->
                            if (decodeInvitationRetirement(event, payload.invitation)) {
                                throw RetiredInvitationException()
                            }
                            decodeRoomAdmissionGrant(
                                event,
                                payload.invitation,
                                requesterKey,
                                request.id,
                                epochSeconds(),
                            )
                        }.first()
                    }
                    val retry = launch {
                        while (isActive) {
                            transport.publish(request)
                            delay(INVITATION_RETRY_MS)
                        }
                    }
                    try {
                        response.await()
                    } finally {
                        retry.cancel()
                    }
                }
            }
        } finally {
            transport.stop()
            scope.cancel()
        }
    }

    private suspend fun <T> withGroupRelays(relays: List<String>, action: suspend (RelayPool) -> T): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val transport = RelayPool(relays, OkHttpRelaySockets(), scope)
        transport.start()
        return try { action(transport) } finally { transport.stop(); scope.cancel() }
    }

    private suspend fun publishGroup(host: RoomInvitationHost, secret: ByteArray, relays: List<String>) {
        try {
            withGroupRelays(relays) {
                if (!it.publishConfirmed(encodePersistentInvitation(host, secret, epochSeconds()))) {
                    throw GroupInvitationException("The relays refused this group invitation. Try again or choose another relay.")
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            throw GroupInvitationException("The group invitation could not be saved to its relays. Try again.")
        } catch (e: GroupInvitationException) { throw e
        } catch (_: Exception) {
            throw GroupInvitationException("The group invitation could not be saved to its relays. Try again.")
        }
    }

    /** Auto-admit holders of the current link while any admitted member is
     * online, and stop permanently on the creator's durable tombstone. */
    private fun serveInvitation(
        scope: CoroutineScope,
        transport: RelayPool,
        host: RoomInvitationHost,
        secret: ByteArray,
    ): Job {
        val invitationId = deriveInvitationId(host.invitation)
        val responder = Schnorr.publicKeyHex(host.inviterSecretKey)
        return scope.launch {
            val answered = LinkedHashSet<String>()
            var retired = false
            transport.subscribe(
                listOf(
                    Filter(
                        kinds = listOf(KIND_INVITATION_REQUEST),
                        tags = mapOf(
                            "#d" to listOf(invitationId),
                            "#p" to listOf(host.invitation.canonicalInviter),
                        ),
                    ),
                    Filter(
                        authors = listOf(host.invitation.canonicalInviter),
                        kinds = listOf(KIND_INVITATION_RETIREMENT),
                        tags = mapOf("#d" to listOf(invitationId)),
                    ),
                ),
            ).collect { event ->
                if (event.kind == KIND_INVITATION_RETIREMENT) {
                    if (!decodeInvitationRetirement(event, host.invitation)) return@collect
                    retired = true
                    gate.withLock {
                        if (roomInvitation?.invitation == host.invitation) {
                            roomInvitationHost = null
                            savedRoom?.let { persistLiveRoom(it.id) { saved -> saved.invitationRetired() } }
                            _room.value = _room.value.copy(
                                canRotateInvitation = false,
                                notice = "This invitation was retired by its creator. The live room is unchanged.",
                            )
                        }
                    }
                    return@collect
                }
                if (host.invitation.persistent || retired || dev.forgesworn.kithmoot.protocol.verifyInvitationDelegation(host.invitation, host.delegation, epochSeconds()) == null) return@collect
                val request = decodeInvitationRequest(event, host.invitation, epochSeconds()) ?: return@collect
                // Lenient relays sometimes retain and replay ephemeral
                // requests. A newly admitted delegate must not answer the
                // request that admitted itself.
                if (request.device == responder) return@collect
                if (!answered.add(request.requestId)) return@collect
                while (answered.size > 256) answered.remove(answered.first())
                transport.publish(
                    encodeInvitationGrant(
                        host,
                        request.device,
                        request.requestId,
                        secret,
                        epochSeconds(),
                    ),
                )
            }
        }
    }

    // --- session lifecycle ---------------------------------------------------

    private suspend fun open(
        derived: Room,
        secret: ByteArray,
        relays: List<String>,
        who: RoomIdentity,
        secondary: Boolean,
        joinUrl: String,
        invitation: InvitationPayload? = null,
        invitationHost: RoomInvitationHost? = null,
        policy: dev.forgesworn.kithmoot.protocol.RoomPolicy? = null,
        restoring: SavedRoom? = null,
        localName: String = "",
    ) = gate.withLock {
        if (policy != null && policy.tier != KindredTier.OPEN) {
            _start.value = _start.value.copy(
                busy = false,
                error = "This room requires a Kindred proof. This Android build cannot obtain one yet.",
            )
            return@withLock
        }
        val previous = savedRooms.get(derived.roomId)
        if (previous != null && (previous.participant != who.participant || (!previous.secondary && secondary))) {
            throw RoomRecoveryException("This room is saved with a different identity. Forget the saved room first if you want to replace it.")
        }
        val record = (restoring ?: SavedRoom.create(secret, who, joinUrl, relays,
            previous?.name ?: localName, epochSeconds(), invitationHost,
            previous?.authority ?: invitation?.invitation?.canonicalInviter)
            .let { if (previous != null) it.retainingHistory(previous) else it }).opened(epochSeconds())
        savedRooms.save(record)
        var durableEpoch = record.authority?.let {
            roomEpochs.initialise(record.id, it, record.secret, epochSeconds())
        }
        if (durableEpoch?.phase == EpochPhase.PENDING_CADENCE_RETIREMENT) {
            durableEpoch = cadenceGate.withLock { resumePendingRoomEpoch(record, who, secondary, durableEpoch!!) }
        }
        if (durableEpoch?.phase == EpochPhase.REMOVED) throw RoomRecoveryException("You were removed from this room")
        if (durableEpoch?.phase == EpochPhase.CLOSED) throw RoomRecoveryException("This room was closed")
        val openedEpoch = durableEpoch?.let { deriveEpoch(RoomEpoch(it.currentEpoch, it.currentSecret)) }
            ?: EpochKeys(0, derived.roomId, derived.roomKey)
        val epochAuthorityHost = record.host(epochSeconds())?.takeIf {
            it.delegation.isEmpty() && record.authority == Schnorr.publicKeyHex(it.inviterSecretKey)
        }
        val epochResponder = epochAuthorityHost?.let {
            EpochRecoveryResponder(roomEpochs, record.id, it.inviterSecretKey, record.policy, ::epochSeconds)
        }
        val summaries = savedRooms.list()
        _start.update { it.copy(savedRooms = summaries) }
        closeSession()
        savedRoom = record
        val scope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))
        val linkRoute = ActiveLinkRoute { url -> linkConsents.activeRoute(who.participant, record.id, url) }
        val socketFactory = HybridRelaySockets(OkHttpRelaySockets(), linkEngine, linkRoute)
        val accountIdentity = who as? PrimaryIdentity
        val authenticators = RelayAuthenticatorProvider { url ->
            linkConsents.activeRoute(who.participant, record.id, url)?.let {
                accountIdentity?.let { primary -> object : RelayAuthenticator {
                    override val pubkey = primary.participant
                    override suspend fun sign(url: String, challenge: String) = primary.signer.sign(22242, epochSeconds(),
                        listOf(listOf("relay", url), listOf("challenge", challenge)), "")
                } }
            }
        }
        val transport = RelayPool(relays, socketFactory, scope, circle = ::circleRelaySet, authenticators = authenticators)
        // A quiet room's chat rides in drops: wrap the pool, and keep what the
        // wrapper owes the device between visits. The device holding the
        // identity is slot 0, the device it paired slot 1; each draws from its
        // own half of the member's drop keys. See session/QuietTransport.kt.
        val quietMembers = policy?.members?.takeIf { policy.quiet }
        val quietFingerprint = QuietTransport.fingerprintFor(openedEpoch.key)
        val savedQuietState = record.quietState?.let {
            checkNotNull(quietStateFromJson(it)) { "The saved quiet queue is invalid." }
        }
        val discardedOldQuiet = savedQuietState != null &&
            savedQuietState.keyFingerprint != quietFingerprint && !(openedEpoch.epoch == 0 && savedQuietState.keyFingerprint == null)
        if (discardedOldQuiet) {
            checkNotNull(savedRooms.update(record.id) {
                it.withQuietState(quietStateToJson(QuietTransport.QuietState(emptyMap(), emptyList(), emptySet(), quietFingerprint)))
            })
        }
        val quiet = if (quietMembers != null) QuietTransport(
            transport, openedEpoch.key, who.participant, quietMembers, if (secondary) 1 else 0, scope,
            restore = savedQuietState?.takeUnless { discardedOldQuiet },
            onState = { state -> checkNotNull(savedRooms.update(derived.roomId) { it.withQuietState(quietStateToJson(state)) }) },
            reservedCounters = { epoch -> cadenceLeases.reservedCounters(derived.roomId, who.devicePubkey, epoch).toSet() },
            onRekeyed = { rejected ->
                if (rejected.isNotEmpty()) _room.update { state ->
                    val noun = if (rejected.size == 1) "message was" else "messages were"
                    state.copy(
                        chatSendError = "Conversation rekeyed. ${rejected.size} retained $noun not sent; the local copy remains in this conversation.",
                    )
                }
            },
        ) else null
        quietTransport = quiet
        val cadenceAccess = if (quiet != null) cadenceAccess(record, who, secondary) else CadenceAccess(null, null)
        val cadenceTransport = quiet?.let { quietRoom ->
            CadenceRoomTransport(
                quietRoom,
                scope,
                leaseAt = { epoch -> cadenceLeases.all(derived.roomId, who.devicePubkey)
                    .singleOrNull { it.ownership != CadenceOwnership.ENDED && epoch in it.plan.startEpoch until it.plan.endEpoch } },
                retain = quietRoom::retainForBox,
                queue = { lease, event ->
                    val context = cadenceAccess.context
                    if (context == null) {
                        CompletableFuture<Boolean>().also {
                            it.completeExceptionally(IllegalStateException(cadenceAccess.reason ?: "Cadence authority is unavailable."))
                        }
                    } else {
                        val queued = CompletableFuture<Boolean>()
                        scope.launch(Dispatchers.IO) {
                            try {
                                val result = cadenceGate.withLock {
                                    val current = cadenceLeases.all(derived.roomId, who.devicePubkey).single {
                                        it.plan.leaseId == lease.plan.leaseId && it.plan.generation == lease.plan.generation
                                    }
                                    cadenceClient.queue(
                                        who.participant, context.scope, who, current, cadenceQueueId(event.id), event,
                                        epochSeconds(), cadenceLeases,
                                    ).get()
                                }
                                _room.update { state -> state.copy(cadence = cadenceView(result.lease, eligible = true)) }
                                queued.complete(true)
                            } catch (error: Exception) {
                                queued.completeExceptionally((error as? java.util.concurrent.ExecutionException)?.cause ?: error)
                            }
                        }
                        queued
                    }
                },
                release = { eventId -> check(quietRoom.confirmQueued(eventId)) { "The confirmed quiet message was not retained locally." } },
                onFailure = { message -> _room.update { state -> state.copy(
                    chatSendError = message,
                    notice = message,
                    cadence = state.cadence?.copy(
                        state = "unresolved-message",
                        detail = "A quiet message is retained on this phone. Retry to resolve Bothy's queue receipt.",
                    ),
                ) } },
            )
        }
        val live = RoomSession(
            derived,
            who,
            cadenceTransport ?: quiet ?: transport,
            scope,
            policy = policy,
            // The root inviter, and the only key whose rekey this client
            // believes. A legacy link carries none, and a room opened from
            // one goes quiet the old way if it ever moves on.
            authority = record.authority,
            initialEpoch = openedEpoch,
            epochGate = if (record.authority == null) null else { event, notice ->
                withContext(Dispatchers.IO) {
                    cadenceGate.withLock { commitRoomEpoch(record, who, secondary, event, notice) }
                }
            },
            onEpochApplied = { _, next ->
                roomWork?.rekey(next.id, next.key)
            },
            onEpochBlocked = ::stopMediaForEpoch,
            onEpochReady = {
                session?.let { current -> startMedia(current, scope, who) }
            },
            epochResponder = epochResponder?.let { responder ->
                { request -> responder.answer(request) }
            },
        )

        sessionScope = scope
        pool = transport
        session = live
        identity = who
        roomSecret = secret
        roomInvitation = record.invitation
        roomInvitationHost = record.host(epochSeconds())
        relayUrls = relays

        _room.value = RoomState(
            roomId = derived.roomId,
            name = record.name,
            joinUrl = selectedWebApp.roomLink(record.joinUrl),
            relaysTotal = relays.size,
            lane = laneOfRelays(relays, circleRelaySet()),
            privateConversation = isDmPolicy(policy),
            selfParticipant = who.participant,
            selfDevice = who.devicePubkey,
            secondary = secondary,
            quiet = quiet != null,
            quietCanSend = quiet?.canSend ?: true,
            cadence = if (quiet == null) null else initialCadenceView(cadenceAccess, derived.roomId, who.devicePubkey),
            canAddDevice = who is PrimaryIdentity,
            canRotateInvitation = record.host(epochSeconds())?.delegation?.isEmpty() == true,
            canShowCard = who is PrimaryIdentity,
            notice = if (discardedOldQuiet) "Messages retained under the previous room key were marked Conversation rekeyed." else null,
        )
        observeRoomEpoch(live, scope)
        if (quiet != null) {
            _room.update { state -> state.copy(cadence = state.cadence?.copy(busy = cadenceAccess.context != null)) }
            scope.launch(Dispatchers.IO) {
                try {
                    if (cadenceAccess.context != null) cadenceGate.withLock { refreshCadence(record, who, secondary) }
                } catch (error: Exception) {
                    val message = (error as? java.util.concurrent.ExecutionException)?.cause?.message ?: error.message ?: "Bothy's cadence status is unavailable."
                    _room.update { state -> state.copy(cadence = state.cadence?.copy(state = "blocked", detail = message)) }
                } finally {
                    _room.update { state -> state.copy(cadence = state.cadence?.copy(busy = false)) }
                }
            }
        }
        _start.update { it.copy(error = null) }
        refreshContacts()

        val profileTransport = RelayPool((relays + PROFILE_RELAYS).distinct(), OkHttpRelaySockets(), scope)
        profilePool = profileTransport
        scope.launch {
            _room.map { state -> if (state.profilesEnabled) (state.tiles.map { it.participant } + state.chat.map { it.participant }).distinct().sorted().take(500) else emptyList() }
                .distinctUntilChanged().collectLatest { authors ->
                    if (authors.isEmpty()) return@collectLatest
                    val requested = authors.toSet()
                    kotlinx.coroutines.withTimeoutOrNull(10_000) {
                        profileTransport.subscribe(listOf(Filter(kinds = listOf(0), authors = authors, limit = authors.size))).collect { event ->
                            val profile = decodePublicProfile(event, requested, epochSeconds()) ?: return@collect
                            _room.update { state ->
                                if (!state.profilesEnabled || state.roomId != derived.roomId) state else {
                                    val old = state.profiles[event.pubkey]
                                    if (old != null && (old.createdAt > profile.createdAt || (old.createdAt == profile.createdAt && old.eventId >= profile.eventId))) state
                                    else state.copy(profiles = state.profiles + (event.pubkey to profile))
                                }
                            }
                        }
                    }
                }
        }
        transport.start()
        profileTransport.start()
        record.host(epochSeconds())?.let { host ->
            invitationHostJob = serveInvitation(scope, transport, host, secret)
        }
        live.join()
        if (live.epochState.value !is dev.forgesworn.kithmoot.session.RoomEpochState.Active) return@withLock
        // Verification, replay and encrypted persistence must not run on the UI thread.
        val workScope=CoroutineScope(scope.coroutineContext+Dispatchers.IO)
        val liveEpoch=live.epochKeys()
        val work = RoomWork(record.id,derived.roomKey,who,quiet?:transport,
            AssignmentVault(getApplication(),record.id,who.participant),workScope,policy,
            initialTrafficRoomId=liveEpoch.id,initialTrafficRoomKey=liveEpoch.key)
        roomWork=work
        scope.launch { work.journal.state.collect { snapshot -> _room.update { if(roomWork===work)it.copy(work=snapshot)else it } } }
        scope.launch { work.actions.collect { actions -> _room.update { if(roomWork===work)it.copy(workActions=actions)else it } } }
        scope.launch { work.error.collect { error -> if(error!=null)_room.update{if(roomWork===work)it.copy(workError=error)else it} } }
        scope.launch(Dispatchers.IO) {
            try {work.open()} catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){_room.update {if(roomWork===work)it.copy(workError="Shared work could not connect. Check the room connection and try again.")else it}}
        }
        // This device plays the room's audio unless one of your others takes it
        // over. Claiming rather than assuming is what lets that handover happen.
        live.claim(Roles.MONITOR)

        scope.launch {
            combine(live.participants, live.chat) { people, chat -> people to chat }
                .collect { (people, chat) ->
                    _room.value = _room.value.copy(
                        tiles = buildTiles(people, who.participant, who.devicePubkey, cardNames),
                        chat = chat,
                        privateConversationPeers = if (accountSigner != null && !isDmPolicy(policy) && quiet == null) {
                            people.map { it.participant }.filter { it != who.participant }
                        } else emptyList(),
                    )
                }
        }
        scope.launch {
            transport.connected.collect { up ->
                gate.withLock {
                    if (session !== live) return@withLock
                    _room.update { it.copy(relaysUp = up.size) }
                    // The transport's offline queue is bounded and expires. Replay
                    // durable retirements on reconnect, including rotations made
                    // during this session, so a long outage cannot drop them.
                    if (up.isNotEmpty()) savedRoom?.retirements?.forEach(transport::publish)
                }
            }
        }
        scope.launch {
            live.localRoles.collect { roles ->
                // Another of your devices has taken the microphone. Let go of
                // the hardware rather than sitting on a hot mic: the roster
                // already stops anyone hearing this one, but a person looking at
                // a lit microphone button believes they are being heard.
                if (_room.value.micOn && !roles.holdsMic && roles.micDevice != null) {
                    engine?.localMedia?.stopMicrophone()
                }
            }
        }

        startMedia(live, scope, who)
    }

    /** Build media only while this exact session is active at one traffic epoch. */
    private fun startMedia(live: RoomSession, scope: CoroutineScope, who: RoomIdentity) {
        if (session !== live || engine != null) return
        opening?.cancel()
        opening = scope.launch {
            val built = withContext(Dispatchers.Default) { runCatching {
                WebRtcEngine(getApplication(), live, this@launch, iceServers())
            } }
            val media = built.getOrElse { failure ->
                _room.update { it.copy(
                    mediaFault = "Audio and video are unavailable on this device: " +
                        (failure.message ?: failure::class.java.simpleName),
                ) }
                return@launch
            }
            if (session !== live) { media.dispose(); return@launch }
            engine = media
            media.localMedia.onScreenShareStopped = { stopScreenShare() }
            media.localMedia.onCameraLost = { cameraLost() }
            media.start()

            launch {
                combine(media.remoteTracks, media.localMedia.tracks) { remote, local ->
                    buildMap {
                        for (track in remote) (track.track as? VideoTrack)?.let { put(key(track.device, track.trackId), it) }
                        for (track in local) (track.track as? VideoTrack)?.let { put(key(who.devicePubkey, track.trackId), it) }
                    }
                }.collect { _videos.value = it }
            }
            launch {
                combine(media.remoteTracks, live.participants, live.localRoles) { remote, people, roles ->
                    val mine = people.firstOrNull { it.participant == who.participant }
                        ?.devices?.map { it.device }?.toSet() ?: emptySet()
                    val listeningHere = roles.monitorDevice == null || roles.holdsMonitor
                    remote.mapNotNull { track ->
                        (track.track as? AudioTrack)?.let { it to (listeningHere && track.device !in mine) }
                    }
                }.collect { decisions -> for ((track, play) in decisions) runCatching { track.setEnabled(play) } }
            }
            launch { media.localMedia.tracks.collect(::onLocalTracks) }
            launch {
                live.agentDevices.collect { agents ->
                    _room.update { it.copy(agentCount = agents.size) }
                    applyAudience(media, agents)
                }
            }
        }
    }

    /** Tear down peer connections before an old epoch can continue media exchange. */
    private fun stopMediaForEpoch() {
        opening?.cancel()
        opening = null
        engine?.stop()
        engine?.dispose()
        engine = null
        _videos.value = emptyMap()
        _room.update { it.copy(micOn = false, cameraOn = false, screenOn = false, agentCount = 0) }
    }

    private fun observeRoomEpoch(live: RoomSession, scope: CoroutineScope) {
        scope.launch {
            live.epochState.collect { state ->
                if (session !== live) return@collect
                when (state) {
                    is dev.forgesworn.kithmoot.session.RoomEpochState.Active -> _room.update {
                        it.copy(movedOn = null, roomUpdate = null, notice = if (state.epoch > 0) "Secure room update complete." else it.notice)
                    }
                    is dev.forgesworn.kithmoot.session.RoomEpochState.Updating -> _room.update {
                        it.copy(movedOn = state.epoch, roomUpdate = "updating", notice = "Secure room update is waiting for Bothy to retire the old schedule.")
                    }
                    is dev.forgesworn.kithmoot.session.RoomEpochState.RecoveryNeeded -> _room.update {
                        it.copy(movedOn = state.expectedEpoch, roomUpdate = "recovery", notice = "${state.reason}. Nothing will be sent under the old room key.")
                    }
                    is dev.forgesworn.kithmoot.session.RoomEpochState.Removed -> {
                        roomWork?.close(); invitationHostJob?.cancel(); roomInvitationHost = null
                        _room.update { it.copy(movedOn = state.epoch, roomUpdate = "removed", canRotateInvitation = false, notice = "You were removed from this room") }
                    }
                    is dev.forgesworn.kithmoot.session.RoomEpochState.Closed -> {
                        roomWork?.close(); invitationHostJob?.cancel(); roomInvitationHost = null
                        _room.update { it.copy(movedOn = state.epoch, roomUpdate = "closed", canRotateInvitation = false, notice = "This room was closed") }
                    }
                }
            }
        }
    }

    /**
     * Turn "agents can hear me" on or off, and act on it now.
     *
     * The rule is applied to every connection this device holds, so an agent
     * that was receiving stops receiving at once rather than at the next
     * renegotiation - and one that arrives later is judged by the same rule
     * when its connection is opened.
     */
    fun setAgentsMayHear(on: Boolean) {
        _room.value = _room.value.copy(agentsMayHear = on)
        val media = engine ?: return
        applyAudience(media, session?.agentDevices?.value ?: emptySet())
    }

    private fun applyAudience(media: WebRtcEngine, agents: Set<String>) {
        media.setAudience(mediaAudience(agents, _room.value.agentsMayHear))
    }

    fun leave() {
        if (!entering.compareAndSet(false, true)) return
        val live = session
        // The screen changes at once; the last announce and the teardown are a
        // signature and a pile of socket closes, and nobody should watch them.
        _videos.value = emptyMap()
        _room.value = RoomState()
        _stage.value = Stage.START
        _start.update { it.copy(busy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                gate.withLock { try { live?.leave() } finally { closeSession() } }
            } finally {
                entering.set(false)
                _start.update { it.copy(busy = false) }
                refreshSavedRooms()
            }
        }
    }

    fun retryRoomUpdate() {
        val record = savedRoom ?: return
        val live = session ?: return
        if (sessionScope == null) return
        if (_room.value.roomUpdate !in setOf("updating", "recovery")) return
        _room.update { it.copy(notice = "Retrying the secure room update…") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (roomEpochs.get(record.id)?.phase == EpochPhase.PENDING_CADENCE_RETIREMENT) {
                    gate.withLock { if (session === live) closeSession() }
                    val saved = savedRooms.get(record.id) ?: throw RoomRecoveryException("This room is no longer saved on this device")
                    openSaved(saved)
                    return@launch
                }
                live.retryEpoch()
                if (live.epochState.value is dev.forgesworn.kithmoot.session.RoomEpochState.Active && roomWork == null) {
                    gate.withLock { if (session === live) closeSession() }
                    val saved = savedRooms.get(record.id) ?: throw RoomRecoveryException("This room is no longer saved on this device")
                    openSaved(saved)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (session == null) {
                    _room.value = RoomState()
                    _stage.value = Stage.START
                    _start.update {
                        it.copy(error = error.message ?: "The secure room update is still unavailable. Open the room to retry.")
                    }
                } else {
                    _room.update { it.copy(roomUpdate = "recovery", notice = error.message ?: "The secure room update is still unavailable. Try again.") }
                }
            }
        }
    }

    private fun closeSession() {
        roomWork?.close()
        roomWork = null
        dev.forgesworn.kithmoot.ui.room.forgetProfilePictures()
        opening?.cancel()
        opening = null
        ScreenShareService.stop(getApplication())
        engine?.stop()
        engine?.dispose()
        engine = null
        quietTransport?.stop()
        quietTransport = null
        pool?.stop()
        profilePool?.stop()
        profilePool = null
        pool = null
        session = null
        identity = null
        savedRoom = null
        roomSecret = null
        roomInvitation = null
        roomInvitationHost = null
        invitationHostJob?.cancel()
        invitationHostJob = null
        sessionScope?.coroutineContext?.get(Job)?.cancel()
        sessionScope = null
    }

    override fun onCleared() {
        boxDiscovery.close()
        super.onCleared()
        closeSession()
    }

    /** Runs a control off the main thread. Every one of them ends in a signature. */
    private fun act(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.Default) { block() }
    }

    fun submitWork(assignment:String?,operation:kotlinx.serialization.json.JsonObject,head:String?) {
        val work=roomWork?:return
        if(_room.value.workBusy)return
        _room.update{it.copy(workBusy=true,workError=null)}
        val request=dev.forgesworn.kithmoot.crypto.Entropy.bytes(16).toHex()
        sessionScope?.launch(Dispatchers.IO) {
            try {work.journal.submit(assignment,operation,request,head)
                _room.update{if(roomWork===work)it.copy(workCompleted=it.workCompleted+1)else it}
            }catch(cancelled:CancellationException){throw cancelled}
            catch(error:Exception){_room.update{if(roomWork===work)it.copy(workError=error.message?:"The update could not be confirmed")else it}}
            finally{_room.update{if(roomWork===work)it.copy(workBusy=false)else it}}
        }
    }
    fun retryWork() {
        val work=roomWork?:return
        if(_room.value.workBusy)return
        _room.update{it.copy(workBusy=true,workError=null)}
        sessionScope?.launch(Dispatchers.IO) {
            try{work.journal.retry();_room.update{if(roomWork===work)it.copy(workCompleted=it.workCompleted+1)else it}}
            catch(cancelled:CancellationException){throw cancelled}
            catch(error:Exception){_room.update{if(roomWork===work)it.copy(workError=error.message?:"The saved update could not be confirmed")else it}}
            finally{_room.update{if(roomWork===work)it.copy(workBusy=false)else it}}
        }
    }
    fun refreshWorkActions() {
        val work=roomWork?:return
        sessionScope?.launch(Dispatchers.IO){try{if(!work.journal.state.value.ready)work.journal.refreshHistory();work.refreshActions();_room.update{if(roomWork===work)it.copy(workError=null)else it}}
            catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){_room.update{if(roomWork===work)it.copy(workError="Agent discovery could not be refreshed")else it}}}
    }

    // --- controls ------------------------------------------------------------

    fun toggleMicrophone() = act {
        val media = engine?.localMedia ?: return@act note("No microphone on this device.")
        val live = session ?: return@act
        if (_room.value.micOn) {
            media.stopMicrophone()
            live.release(Roles.MIC)
        } else {
            // The claim goes first, and not for tidiness: the moment a track
            // appears the roster is republished, and a device that published a
            // microphone it had not yet claimed would see one of its own others
            // still holding the role and shut itself straight back off.
            live.claim(Roles.MIC)
            if (media.startMicrophone() == null) {
                live.release(Roles.MIC)
                return@act note("The microphone would not start.")
            }
        }
    }

    fun toggleCamera() = act {
        val media = engine?.localMedia ?: return@act note("No camera on this device.")
        if (_room.value.cameraOn) {
            media.stopCamera()
        } else if (media.startCamera() == null) {
            note("No camera is available here.")
        }
    }

    fun switchCamera() {
        engine?.localMedia?.switchCamera()
    }

    /**
     * The camera went away without being asked.
     *
     * The capturer is already dead by the time this arrives; what is left is to
     * let go of it, so the roster stops advertising a camera track that carries
     * nothing and the control stops claiming to be on. Handed to [act] rather
     * than run here, because it arrives on the capturer's own thread and
     * releasing the capturer from there would wait on that same thread.
     */
    private fun cameraLost() = act {
        if (!_room.value.cameraOn) return@act
        engine?.localMedia?.stopCamera()
        note("Android took the camera away. Tap Camera to start it again.")
    }

    /**
     * Starts sharing the screen from the consent the user has just given.
     *
     * The foreground service goes up first and we wait for it to actually be
     * foreground. On Android 14 and later the platform refuses to create a
     * projection at all unless a `mediaProjection` service is already running,
     * and the failure is a `SecurityException` rather than a null.
     */
    fun startScreenShare(permission: Intent) {
        val media = engine?.localMedia ?: return note("Screen sharing needs the media stack.")
        val scope = sessionScope ?: return
        scope.launch {
            ScreenShareService.start(getApplication())
            val running = withTimeoutOrNull(5_000) { ScreenShareService.running.first { it } }
            if (running != true) {
                ScreenShareService.stop(getApplication())
                return@launch note("Android would not start the screen-sharing notification.")
            }
            val started = withContext(Dispatchers.Default) { runCatching { media.startScreenShare(permission) } }
            if (started.getOrNull() == null) {
                ScreenShareService.stop(getApplication())
                note("Screen sharing did not start: " + (started.exceptionOrNull()?.message ?: "the capture was refused"))
            }
        }
    }

    fun stopScreenShare() = act {
        engine?.localMedia?.stopScreenShare()
        ScreenShareService.stop(getApplication())
    }

    fun screenShareDeclined() {
        note("Screen sharing needs Android's permission. Nothing was shared.")
    }

    fun setProfilesEnabled(enabled: Boolean) {
        if (!enabled) dev.forgesworn.kithmoot.ui.room.forgetProfilePictures()
        _room.update { it.copy(profilesEnabled = enabled, profiles = if (enabled) it.profiles else emptyMap()) }
    }

    fun refreshCadence() = cadenceAction { record, who, secondary ->
        refreshCadence(record, who, secondary)
    }

    fun startCadence() = cadenceAction { record, who, secondary ->
        val access = cadenceAccess(record, who, secondary)
        val context = requireNotNull(access.context) { access.reason ?: "Cadence is unavailable." }
        val existing = currentCadence(record.id, who.devicePubkey)
        if (existing != null) {
            refreshCadence(record, who, secondary)
            return@cadenceAction
        }
        require(!_room.value.chatSending) {
            "Wait for this phone's current quiet message to finish before scheduling Bothy."
        }
        require(quietTransport?.pending == 0) {
            "Wait for this phone's queued quiet messages to leave before scheduling Bothy."
        }
        val now = epochSeconds()
        val status = cadenceClient.status(who.participant, context.scope, cadenceId(), who, now).get().answer
        if (!status.ready) {
            _room.update { it.copy(cadence = CadenceViewState(
                eligible = true,
                state = "not-ready",
                detail = "Bothy is not ready: ${status.missing.joinToString(", ")}.",
            )) }
            return@cadenceAction
        }
        val start = status.earliestStartEpoch
        val credentialExpiry = who.credential.tagValue("expiration")?.toLongOrNull()
            ?: throw IllegalStateException("The device credential has no expiry.")
        val end = minOf(start + 12, credentialExpiry / 3600, context.grantExpiresAt / 3600)
        require(end > start) { "This device credential expires too soon. Reopen the room and try again." }
        val generation = (cadenceLeases.all(record.id, who.devicePubkey).maxOfOrNull { it.plan.generation } ?: 0) + 1
        val options = CadenceLeaseOptions(
            context.scope, cadenceId(), cadenceId(), generation, context.deviceSlot,
            status.currentEpoch, start, end, context.roomKey, context.publicRelays, listOf("local"), epochSeconds(),
        )
        val result = cadenceClient.stage(who.participant, options, who, epochSeconds(), cadenceLeases).get()
        _room.update { it.copy(cadence = cadenceView(result.lease, eligible = true)) }
    }

    fun stopCadence() = cadenceAction { record, who, secondary ->
        stopCadence(record, who, secondary, "Bothy could not stop the schedule.")
    }

    private fun cadenceAction(action: suspend (SavedRoom, RoomIdentity, Boolean) -> Unit) {
        val record = savedRoom ?: return
        val who = identity ?: return
        val scope = sessionScope ?: return
        if (_room.value.cadence?.busy == true) return
        _room.update { state -> state.copy(cadence = state.cadence?.copy(busy = true)) }
        scope.launch(Dispatchers.IO) {
            try {
                cadenceGate.withLock { action(record, who, _room.value.secondary) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = (error as? java.util.concurrent.ExecutionException)?.cause?.message ?: error.message ?: "Cadence could not be changed."
                _room.update { state -> state.copy(cadence = state.cadence?.copy(state = "blocked", detail = message)) }
            } finally {
                _room.update { state -> state.copy(cadence = state.cadence?.copy(busy = false)) }
            }
        }
    }

    private fun refreshCadence(record: SavedRoom, who: RoomIdentity, secondary: Boolean) {
        val access = cadenceAccess(record, who, secondary)
        val context = access.context
        if (context == null) {
            _room.update { it.copy(cadence = CadenceViewState(detail = access.reason ?: "Cadence is unavailable.")) }
            return
        }
        val current = currentCadence(record.id, who.devicePubkey)
        if (current == null) {
            val status = cadenceClient.status(who.participant, context.scope, cadenceId(), who, epochSeconds()).get().answer
            _room.update { it.copy(cadence = CadenceViewState(
                eligible = true,
                state = if (status.ready) "off" else "not-ready",
                detail = if (status.ready) "Bothy is ready to take over this phone's quiet cadence for up to twelve hours."
                    else "Bothy is not ready: ${status.missing.joinToString(", ")}.",
            )) }
            return
        }
        val result = if (current.ownership == CadenceOwnership.CLIENT_EXCLUDED) {
            try {
                cadenceClient.retryStage(who.participant, context.scope, who, current, epochSeconds(), cadenceLeases).get()
            } catch (_: Exception) {
                cadenceClient.leaseStatus(who.participant, context.scope, who, current, cadenceId(), epochSeconds(), cadenceLeases).get()
            }
        } else {
            cadenceClient.leaseStatus(who.participant, context.scope, who, current, cadenceId(), epochSeconds(), cadenceLeases).get()
        }
        val lease = recoverCadenceQueue(context, who, result.lease)
        _room.update { it.copy(cadence = cadenceView(lease, eligible = true)) }
    }

    private fun stopCadence(record: SavedRoom, who: RoomIdentity, secondary: Boolean, failure: String) {
        val access = cadenceAccess(record, who, secondary)
        val context = requireNotNull(access.context) { access.reason ?: failure }
        val current = currentCadence(record.id, who.devicePubkey) ?: return
        if (current.ownership != CadenceOwnership.BOX_OWNED || current.receipt?.state in setOf("cover", "ended")) return
        val boundary = maxOf(DeadDrop.epochIndexAt(epochSeconds()) + 2, current.plan.startEpoch)
        if (boundary > current.plan.endEpoch) {
            refreshCadence(record, who, secondary)
            return
        }
        val result = cadenceClient.stop(
            who.participant, context.scope, who, current, cadenceId(), boundary,
            epochSeconds(), cadenceLeases,
        ).get()
        _room.update { it.copy(cadence = cadenceView(result.lease, eligible = true)) }
    }

    private fun currentCadence(room: String, device: String): StoredCadenceLease? = cadenceLeases.all(room, device)
        .filter { it.ownership != CadenceOwnership.ENDED }
        .maxByOrNull { it.plan.generation }

    private fun epochCadence(record: SavedRoom, who: RoomIdentity, epoch: EpochKeys): StoredCadenceLease? =
        cadenceLeases.all(record.id, who.devicePubkey)
            .filter {
                it.ownership != CadenceOwnership.ENDED &&
                    it.plan.trafficRoom == epoch.id && it.plan.roomGeneration == epoch.epoch.toLong() + 1
            }
            .maxByOrNull { it.plan.generation }

    private fun cadenceCoordinate(lease: StoredCadenceLease?) = lease?.let {
        CadenceEpochCoordinate(
            it.plan.nodeId, it.plan.leaseId, it.plan.generation,
            it.plan.trafficRoom, it.plan.roomGeneration,
        )
    }

    private fun cadenceRekeyId(cause: String, lease: StoredCadenceLease, nextRoomGeneration: Long): String =
        Digests.sha256(
            "kithmoot/cadence/rekey/v1\u0000$cause\u0000${lease.plan.leaseId}\u0000${lease.plan.generation}\u0000$nextRoomGeneration"
                .toByteArray(Charsets.UTF_8),
        ).toHex().take(32)

    /** Resolve a possibly lost lease result, then advance Bothy's durable room-generation fence. */
    private fun retireCadenceForEpoch(
        record: SavedRoom,
        who: RoomIdentity,
        secondary: Boolean,
        cause: String,
        nextRoomGeneration: Long,
        coordinate: CadenceEpochCoordinate?,
    ): StoredCadenceLease? {
        if (coordinate == null) return null
        val access = cadenceAccess(record, who, secondary)
        val context = requireNotNull(access.context) { access.reason ?: "Bothy cadence authority is unavailable" }
        require(
            context.scope.nodeId == coordinate.nodeId && context.scope.trafficRoom == coordinate.trafficRoom &&
                context.scope.roomGeneration == coordinate.roomGeneration
        ) { "The pending room update does not match the saved Bothy scope" }
        var current = cadenceLeases.all(record.id, who.devicePubkey).singleOrNull {
            it.plan.nodeId == coordinate.nodeId && it.plan.leaseId == coordinate.leaseId &&
                it.plan.generation == coordinate.generation && it.plan.trafficRoom == coordinate.trafficRoom &&
                it.plan.roomGeneration == coordinate.roomGeneration
        } ?: throw RoomRecoveryException("The pending room update has lost its Bothy lease journal")
        if (current.ownership == CadenceOwnership.CLIENT_EXCLUDED) {
            current = try {
                cadenceClient.retryStage(who.participant, context.scope, who, current, epochSeconds(), cadenceLeases).get().lease
            } catch (_: Exception) {
                cadenceClient.leaseStatus(
                    who.participant, context.scope, who, current,
                    cadenceRekeyId(cause, current, nextRoomGeneration), epochSeconds(), cadenceLeases,
                ).get().lease
            }
        }
        if (current.ownership == CadenceOwnership.ENDED || current.receipt?.state in setOf("cover", "ended")) return current
        require(current.ownership == CadenceOwnership.BOX_OWNED) { "Bothy's old room ownership is unresolved" }
        val result = cadenceClient.rekey(
            who.participant, context.scope, who, current,
            cadenceRekeyId(cause, current, nextRoomGeneration), nextRoomGeneration,
            epochSeconds(), cadenceLeases,
        ).get()
        require(result.lease.receipt?.code == "rekeyed" && result.lease.receipt?.state in setOf("cover", "ended")) {
            "Bothy did not prove that old room sends were retired"
        }
        _room.update { state -> state.copy(cadence = state.cadence?.copy(
            state = result.lease.receipt?.state ?: "cover",
            detail = "Bothy retired real sends under the previous room key and is finishing its promised cover.",
            failedCount = result.lease.receipt?.failedItemIds?.size ?: 0,
        )) }
        return result.lease
    }

    private fun commitRoomEpoch(
        record: SavedRoom,
        who: RoomIdentity,
        secondary: Boolean,
        event: NostrEvent,
        notice: RekeyNotice,
    ): EpochGateResult {
        val durable = roomEpochs.get(record.id) ?: throw RoomRecoveryException("The room epoch journal is missing")
        if (durable.phase == EpochPhase.ACTIVE && durable.currentEpoch == notice.epoch && notice.secret != null) {
            require(durable.currentSecret.contentEquals(notice.secret)) { "The committed room epoch has a different secret" }
            return EpochGateResult.COMMITTED
        }
        require(
            durable.currentEpoch + 1 == notice.epoch || notice.catchUp && notice.epoch > durable.currentEpoch
        ) { "The room update skipped an unproved epoch" }
        val current = deriveEpoch(RoomEpoch(durable.currentEpoch, durable.currentSecret))
        val lease = epochCadence(record, who, current)
        val coordinate = cadenceCoordinate(lease)
        if (notice.closed || notice.secret == null && notice.removed.any { it.equals(who.participant, ignoreCase = true) }) {
            retireCadenceForEpoch(record, who, secondary, event.id, notice.epoch.toLong() + 1, coordinate)
            roomEpochs.terminal(record.id, durable.currentEpoch, notice, event.id, epochSeconds())
            return EpochGateResult.COMMITTED
        }
        require(notice.secret != null) { "The room authority must restore this device" }
        if (notice.catchUp) {
            roomEpochs.beginCatchUp(record.id, durable.currentEpoch, notice, event.id, coordinate, epochSeconds())
        } else {
            roomEpochs.beginTransition(record.id, durable.currentEpoch, notice, event.id, coordinate, epochSeconds())
        }
        try {
            retireCadenceForEpoch(record, who, secondary, event.id, notice.epoch.toLong() + 1, coordinate)
        } catch (_: Exception) {
            return EpochGateResult.PENDING
        }
        roomEpochs.activate(record.id, notice.epoch, epochSeconds())
        return EpochGateResult.COMMITTED
    }

    private fun resumePendingRoomEpoch(
        record: SavedRoom,
        who: RoomIdentity,
        secondary: Boolean,
        durable: StoredRoomEpoch,
    ): StoredRoomEpoch {
        val pending = requireNotNull(durable.pending)
        retireCadenceForEpoch(record, who, secondary, pending.cause, pending.epoch.toLong() + 1, pending.cadence)
        return roomEpochs.activate(record.id, pending.epoch, epochSeconds())
    }

    private fun cadenceId(): String = Entropy.bytes(16).toHex()

    private fun cadenceQueueId(eventId: String): String = Digests.sha256(
        "kithmoot/cadence/queue/v1\u0000$eventId".toByteArray(Charsets.UTF_8),
    ).toHex().take(32)

    /** Resolve exact phone-retained messages after a lost queue reply or process restart. */
    private fun recoverCadenceQueue(
        context: CadenceContext,
        who: RoomIdentity,
        lease: StoredCadenceLease,
    ): StoredCadenceLease {
        val quiet = quietTransport ?: return lease
        var current = lease
        for (event in quiet.queuedEvents()) {
            val receipt = current.receipt
            if (event.id in (receipt?.sentItemIds ?: emptyList()) || event.id in (receipt?.failedItemIds ?: emptyList())) {
                quiet.confirmQueued(event.id)
                continue
            }
            val epoch = DeadDrop.epochIndexAt(epochSeconds())
            if (current.ownership != CadenceOwnership.BOX_OWNED || epoch !in current.plan.startEpoch until current.plan.endEpoch) continue
            current = cadenceClient.queue(
                who.participant, context.scope, who, current, cadenceQueueId(event.id), event,
                epochSeconds(), cadenceLeases,
            ).get().lease
            check(quiet.confirmQueued(event.id)) { "The queued quiet message was not retained locally." }
        }
        return current
    }

    /** Real sends stop before the Link route and its circle authority are retired. */
    private suspend fun stopCadenceBeforeDisconnect(record: SavedRoom, signer: ParticipantSigner) = cadenceGate.withLock {
        val who = record.identity(epochSeconds(), signer)
        var current = currentCadence(record.id, who.devicePubkey) ?: return@withLock
        val access = cadenceAccess(record, who, record.secondary)
        val context = access.context ?: throw RoomRecoveryException(access.reason ?: "Cadence authority is unavailable.")
        if (current.ownership == CadenceOwnership.CLIENT_EXCLUDED) {
            current = try {
                cadenceClient.retryStage(who.participant, context.scope, who, current, epochSeconds(), cadenceLeases).get().lease
            } catch (_: Exception) {
                cadenceClient.leaseStatus(who.participant, context.scope, who, current, cadenceId(), epochSeconds(), cadenceLeases).get().lease
            }
        }
        if (current.receipt?.state in setOf("cover", "ended") || current.ownership != CadenceOwnership.BOX_OWNED) return@withLock
        val boundary = maxOf(DeadDrop.epochIndexAt(epochSeconds()) + 2, current.plan.startEpoch)
        if (boundary <= current.plan.endEpoch) {
            cadenceClient.stop(who.participant, context.scope, who, current, cadenceId(), boundary, epochSeconds(), cadenceLeases).get()
        } else {
            cadenceClient.leaseStatus(who.participant, context.scope, who, current, cadenceId(), epochSeconds(), cadenceLeases).get()
        }
    }

    fun sendChat(body: String) = sendChat(body, null)

    private fun sendChat(body: String, reaction: ChatReaction?) {
        val live = session ?: return
        val scope = sessionScope ?: return
        if (_room.value.cadence?.busy == true) {
            note("Finish the quiet schedule change before sending.")
            return
        }
        if (_room.value.chatSending) return
        _room.update { it.copy(chatSending = true, chatSendError = null) }
        scope.launch(Dispatchers.IO) {
            try {
                check(live.sendChatConfirmed(body, reaction)) { "No relay confirmed this message." }
            } catch (_: TimeoutCancellationException) {
                if (session === live) {
                    val message = "No relay confirmed this message."
                    _room.update { it.copy(chatSendError = message, notice = "$message Try again.") }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (session === live) {
                    val message = error.message ?: "The message could not be confirmed."
                    _room.update { it.copy(chatSendError = message, notice = "$message Try again.") }
                }
            } finally {
                if (session === live) _room.update { it.copy(chatSending = false) }
            }
        }
    }

    /** Create, retain and signer-seal a two-person room before leaving the introduction room. */
    fun startPrivateConversation(peer: String) {
        val live = session
        val signer = accountSigner
        val source = savedRoom
        val self = _room.value.selfParticipant
        val currentPeers = _room.value.privateConversationPeers
        if (_stage.value != Stage.ROOM || live == null || source == null) return
        if (signer == null || signer.pubkey != self) {
            note("Sign in with the room's account before starting a private conversation.")
            return
        }
        if (peer !in currentPeers) {
            note("That person is no longer present in this room.")
            return
        }
        if (!entering.compareAndSet(false, true)) {
            note("Finish the current room action first.")
            return
        }
        _room.update { it.copy(privateConversationBusy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            var retained: SavedRoom? = null
            try {
                val policy = dmPolicy(self, peer)
                val secret = Entropy.bytes(32)
                val derived = deriveRoom(secret)
                val host = createRoomInvitation(persistent = true)
                val relays = relayUrls.toList()
                val invitation = InvitationPayload(host.invitation, relays, policy)
                val link = encodeInvitationUrl(selectedWebApp.joinBase, host.invitation, relays, policy)

                publishGroup(host, secret, relays)
                val at = epochSeconds()
                val who = PrimaryIdentity.createWith(signer, derived.roomId, at + CREDENTIAL_TTL_SECONDS, at)
                val sealed = sealInvite(link, peer, derived.roomId, signer)
                if (session !== live || accountSigner !== signer || peer !in _room.value.privateConversationPeers) {
                    throw RoomRecoveryException("The introduction room changed while the signer was open. Try again.")
                }

                val peerName = _room.value.profiles[peer]?.name
                    ?: _room.value.tiles.firstOrNull { it.participant == peer }?.cardName
                    ?: shortNpub(peer)
                retained = SavedRoom.create(
                    secret = secret,
                    identity = who,
                    joinUrl = link,
                    relays = relays,
                    name = "Private with $peerName",
                    now = epochSeconds(),
                    host = host,
                    authority = host.invitation.canonicalInviter,
                )
                savedRooms.save(retained)
                _start.update { it.copy(savedRooms = savedRooms.list()) }
                if (!live.sendInviteConfirmed(sealed)) {
                    throw RoomRecoveryException("The relays refused the private invitation; nobody was told its link.")
                }
                if (session !== live || accountSigner !== signer) {
                    throw RoomRecoveryException("The private invitation was sent, but this room changed before KithMoot could open it. Open the saved private room from Home.")
                }
                open(
                    derived = derived,
                    secret = secret,
                    relays = relays,
                    who = who,
                    secondary = false,
                    joinUrl = link,
                    invitation = invitation,
                    invitationHost = host,
                    policy = policy,
                    restoring = retained,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: RoomStorageException) {
                storageFailed()
                note("The private conversation could not be saved.")
            } catch (e: Exception) {
                val fallback = if (retained != null) " The private room remains saved on Home." else ""
                note((e.message ?: "The private conversation could not be started.") + fallback)
            } finally {
                entering.set(false)
                _room.update { it.copy(privateConversationBusy = false) }
            }
        }
    }

    /** Deliberately open a verified invitation through the account signer that it addresses. */
    fun openPrivateConversation(message: ChatMessage) {
        val live = session ?: return
        val signer = accountSigner
        val invite = message.invite ?: return
        val self = _room.value.selfParticipant
        val peer = invitePeer(invite, self, message.participant)
        if (signer == null || signer.pubkey != self || peer == null || message !in _room.value.chat) {
            note("This private invitation is not addressed to the signed-in room account.")
            return
        }
        if (!entering.compareAndSet(false, true)) {
            note("Finish the current room action first.")
            return
        }
        _room.update { it.copy(privateConversationBusy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val link = openInvite(invite, self, message.participant, signer)
                    ?: throw RoomRecoveryException("The signer could not open this private invitation.")
                val payload = decodePrivateConversationInvite(link, invite, self, message.participant)
                    ?: throw RoomRecoveryException("This invitation is not for the claimed two-person conversation.")
                if (session !== live || accountSigner !== signer) {
                    throw RoomRecoveryException("The introduction room changed while the signer was open. Try again.")
                }

                val existing = savedRooms.get(invite.room)
                if (existing != null) {
                    if (existing.participant != self || existing.policy != payload.policy ||
                        existing.invitation?.invitation != payload.invitation) {
                        throw RoomRecoveryException("A different saved room already uses this invitation's identifier.")
                    }
                    openSaved(existing)
                    return@launch
                }

                val relays = payload.relays
                if (relays.isEmpty()) throw RoomRecoveryException("The private invitation names no relay.")
                val admission = requestAdmission(payload, relays)
                    ?: throw RoomRecoveryException("The private invitation could not be loaded from its relays. Try again.")
                val derived = deriveRoom(admission.secret)
                if (derived.roomId != invite.room) {
                    throw RoomRecoveryException("The private invitation opened a different room than it claimed.")
                }
                val at = epochSeconds()
                val who = PrimaryIdentity.createWith(signer, derived.roomId, at + CREDENTIAL_TTL_SECONDS, at)
                if (session !== live || accountSigner !== signer) {
                    throw RoomRecoveryException("The introduction room changed while the signer was open. Try again.")
                }
                open(
                    derived = derived,
                    secret = admission.secret,
                    relays = relays,
                    who = who,
                    secondary = false,
                    joinUrl = link,
                    invitation = payload,
                    invitationHost = admission.delegate,
                    policy = payload.policy,
                    localName = "Private with ${shortNpub(peer)}",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: RoomStorageException) {
                storageFailed()
                note("The private conversation could not be saved.")
            } catch (e: Exception) {
                note(e.message ?: "The private conversation could not be opened.")
            } finally {
                entering.set(false)
                _room.update { it.copy(privateConversationBusy = false) }
            }
        }
    }

    fun react(message: ChatMessage, emoji: String) = act {
        if (session == null) return@act
        runCatching {
            val reaction = dev.forgesworn.kithmoot.session.toggleReaction(_room.value.chat, message, _room.value.selfParticipant, emoji)
            sendChat(dev.forgesworn.kithmoot.session.reactionText(reaction), reaction)
        }.onFailure { note("The reaction could not be sent. Try again.") }
    }

    // --- adding a device -----------------------------------------------------

    /**
     * Mints a link that makes another device this same person.
     *
     * Only the device holding the participant key can do this, because doing it
     * means signing a credential. A device that joined from a pairing link
     * cannot pass the identity on, which is the point of not giving it the key.
     */
    fun mintPairingLink() = viewModelScope.launch(Dispatchers.Default) {
        val primary = identity as? PrimaryIdentity
            ?: return@launch note("Only the device that opened the room can add another device.")
        val secret = roomSecret ?: return@launch
        val live = session ?: return@launch
        val at = epochSeconds()
        val deviceKey = Entropy.bytes(32)
        val credential = try {
            primary.enrol(
                devicePubkey = Schnorr.publicKeyHex(deviceKey),
                roomId = live.room.roomId,
                expiresAt = at + CREDENTIAL_TTL_SECONDS,
                createdAt = at,
            )
        } catch (e: SignerException) {
            return@launch note(e.message ?: "Your signer did not sign the pairing.")
        }
        _room.value = _room.value.copy(
            pairingLink = roomInvitation?.let { invitation ->
                encodeInvitationPairingLink(
                    base = selectedWebApp.joinBase,
                    invitation = invitation.invitation,
                    relays = relayUrls,
                    policy = invitation.policy,
                    deviceSecretKey = deviceKey,
                    credential = credential,
                )
            } ?: encodePairingLink(
                    base = selectedWebApp.joinBase,
                    secret = secret,
                    relays = relayUrls,
                    deviceSecretKey = deviceKey,
                    credential = credential,
                ),
        )
    }

    fun dismissPairingLink() {
        _room.value = _room.value.copy(pairingLink = null)
    }

    /** Replace the public admission capability without moving the live room. */
    fun rotateInvitation() {
        viewModelScope.launch(Dispatchers.IO) {
            gate.withLock {
                val oldHost = roomInvitationHost
                if (oldHost == null || oldHost.delegation.isNotEmpty()) return@withLock note("Only the device that opened this room can rotate its link.")
                val saved = savedRoom ?: return@withLock
                val secret = roomSecret ?: return@withLock
                val scope = sessionScope ?: return@withLock
                val transport = pool ?: return@withLock
                val nextHost = RoomInvitationHost(
                    dev.forgesworn.kithmoot.protocol.RoomInvitation(Entropy.bytes(32), oldHost.invitation.canonicalInviter, oldHost.invitation.persistent),
                    oldHost.inviterSecretKey,
                )
                if (nextHost.invitation.persistent) {
                    try { publishGroup(nextHost, secret, relayUrls) }
                    catch (e: GroupInvitationException) { return@withLock note(e.message ?: "The new group link could not be saved.") }
                }
                val nextInvitation = InvitationPayload(nextHost.invitation, relayUrls, saved.policy)
                val url = encodeInvitationUrl(selectedWebApp.joinBase, nextHost.invitation, relayUrls, saved.policy)
                val retirement = encodeInvitationRetirement(oldHost.invitation, oldHost.inviterSecretKey, epochSeconds())
                val next = try {
                    saved.rotated(nextHost, url, retirement).also(savedRooms::save)
                } catch (_: Exception) { return@withLock note("The new invitation could not be saved. The current link is unchanged.") }
                savedRoom = next
                transport.publish(retirement)
                invitationHostJob?.cancel()
                roomInvitationHost = nextHost
                roomInvitation = nextInvitation
                invitationHostJob = serveInvitation(scope, transport, nextHost, secret)
                _room.update { it.copy(joinUrl = url, notice = "A fresh link is ready. The old link's retirement will be sent when a relay connects. Existing members stay.") }
            }
        }
    }

    private suspend fun persistLiveRoom(id: String, change: (SavedRoom) -> SavedRoom) {
        withContext(Dispatchers.IO) {
            try {
                val saved = savedRooms.update(id, change)
                if (savedRoom?.id == id) savedRoom = saved
            } catch (_: RoomStorageException) {
                note("The room's changed access could not be saved. Check its current invitation before returning.")
            }
        }
    }

    /** Says something short to the person in the room. Shown once, then cleared. */
    fun showNotice(message: String) = note(message)

    fun dismissNotice() {
        _room.value = _room.value.copy(notice = null)
    }

    // --- internals -----------------------------------------------------------

    private fun onLocalTracks(tracks: List<LocalTrack>) {
        _room.value = _room.value.copy(
            micOn = tracks.any { it.role == Roles.MIC },
            cameraOn = tracks.any { it.role == Roles.CAMERA },
            screenOn = tracks.any { it.role == Roles.SCREEN },
        )
    }

    private fun note(message: String) {
        _room.value = _room.value.copy(notice = message)
    }

    // --- contact cards -------------------------------------------------------

    /** Who holds a card, to the name on it, for the tiles. */
    @Volatile private var cardNames: Map<String, String> = emptyMap()

    /**
     * Re-read the book into the room: the rows, the tiles' badges and the
     * lane, which a new box can move from public to sheltered.
     */
    private fun refreshContacts() {
        val list = try { contacts.list() } catch (e: RoomStorageException) { return note(e.message ?: "Contacts are unavailable.") }
        cardNames = list.associate { it.p to (it.name ?: "") }
        val rows = list.map { c ->
            ContactRow(
                p = c.p, npub = npubOf(c.p), name = c.name,
                boxes = c.boxes.map { b ->
                    ContactBoxRow(b.p, ContactBook.discoveryRevision(c, b), "Box ${npubOf(b.p).take(16)}…: ${boxDiscovery.message(c.p, b.p)}", boxDiscovery.enabled(c.p, b.p))
                },
                expires = c.expires,
            )
        }
        val circle = circleRelaySet()
        val live = session
        _room.update { state ->
            state.copy(
                contacts = rows,
                lane = if (relayUrls.isEmpty()) state.lane else laneOfRelays(relayUrls, circle),
                tiles = if (live == null) state.tiles else buildTiles(live.participants.value, state.selfParticipant, state.selfDevice, cardNames),
            )
        }
    }

    fun checkContactBox(contact: String, box: String, revision: String, on: Boolean) {
        val selectedText = _start.value.relays
        val selectedRevision = boxRelayRevision.get()
        act { synchronized(boxPreferencesGate) {
        try {
            if (on) {
                require(selectedRevision == boxRelayRevision.get()) { "The read relays changed. Check this box again when ready." }
                val selected = parseRelays(selectedText)
                require(selected.isNotEmpty() && selected.size <= 8 && selected.all { dev.forgesworn.kithmoot.protocol.LinkCards.isRelayUrl(it) }) { "Choose one to eight secure read relays on the start screen first." }
                val next = selected.joinToString("\n")
                if (next != discoveryRelays) boxDiscovery.disableAll()
                check(display.edit().putString("boxReadRelays", next).commit()) { "The selected read relays could not be saved." }
                discoveryRelays = next
            }
            require(!on || selectedRevision == boxRelayRevision.get()) { "The read relays changed. Check this box again when ready." }
            boxDiscovery.setEnabled(contact, box, on, revision)
        } catch (e: Exception) { _room.update { it.copy(cardStatus = e.message ?: "The box preference could not be saved.") } }
        } }
    }

    /** A card pasted into the sheet: read, kept, and said back in one line. */
    fun addContactCard(text: String) = act {
        val added = try { contacts.add(text, epochSeconds()) } catch (e: RoomStorageException) {
            return@act _room.update { it.copy(cardStatus = e.message ?: "Contacts are unavailable.") }
        }
        val words = when (added) {
            is ContactBook.Added.Refused -> added.words
            is ContactBook.Added.Ok -> {
                val who = added.contact.name ?: npubOf(added.contact.p).take(16) + "…"
                val boxes = when (added.contact.boxes.size) { 0 -> "no box"; 1 -> "one box"; else -> "${added.contact.boxes.size} boxes" }
                (if (added.replaced) "Updated $who: $boxes" else "Added $who: $boxes") +
                    (if (added.contact.boxes.isNotEmpty()) ". Their box is endorsed by this card; its message endpoint still needs verification." else ".")
            }
        }
        _room.update { it.copy(cardStatus = words) }
        boxDiscovery.reconcile()
        refreshContacts()
    }

    fun forgetContact(p: String) = act {
        try { contacts.forget(p) } catch (e: RoomStorageException) { return@act note(e.message ?: "Contacts are unavailable.") }
        _room.update { it.copy(cardStatus = null) }
        boxDiscovery.reconcile()
        refreshContacts()
    }

    /** The card a person met at the door, kept now that they pressed the button. */
    fun addOfferedCard() = act {
        val offer = _start.value.cardOffer ?: return@act
        val added = try { contacts.add(offer.link, epochSeconds()) } catch (e: RoomStorageException) {
            return@act _start.update { it.copy(error = e.message ?: "Contacts are unavailable.") }
        }
        when (added) {
            is ContactBook.Added.Refused -> _start.update { it.copy(error = added.words) }
            is ContactBook.Added.Ok -> _start.update { it.copy(error = null, cardOffer = offer.copy(added = true)) }
        }
    }

    fun dismissCardOffer() {
        _start.update { it.copy(cardOffer = null, joinUrl = if (it.joinUrl == it.cardOffer?.link) "" else it.joinUrl) }
    }

    /**
     * This person's own card: a kind 21641 event signed by whatever holds
     * the identity, with the room's relays as their public relays and no
     * box, because this phone runs none. Seven days.
     */
    fun showMyCard() = viewModelScope.launch(Dispatchers.Default) {
        val primary = identity as? PrimaryIdentity
            ?: return@launch note("Only the device that holds your identity can make your card.")
        val at = epochSeconds()
        val event = try {
            val rz = Schnorr.publicKeyHex(contacts.rendezvousSecret())
            val eph = Schnorr.publicKeyHex(Entropy.bytes(32))
            val relays = relayUrls.filter { it.startsWith("wss://") }.take(ContactCards.MAX_RELAYS)
            // The name on the card is the one the account carries, if any:
            // a room's name is the room's, not the person's.
            val name = _start.value.account?.profile?.name ?: accountSession?.account?.displayName
            val content = ContactCardBuilder.content(rz = rz, eph = eph, relays = relays, name = name?.takeIf { it.isNotBlank() })
            primary.signer.sign(ContactCards.KIND, at, ContactCardBuilder.tags(at + CARD_TTL_SECONDS), content)
        } catch (e: SignerException) {
            return@launch note(e.message ?: "Your signer did not sign the card.")
        } catch (e: RoomStorageException) {
            return@launch note(e.message ?: "Contacts are unavailable.")
        } catch (e: IllegalArgumentException) {
            return@launch note(e.message ?: "This room's relays cannot go on a card.")
        }
        // The signer returned an event; the reader says whether it is still the card.
        val link = ContactCardBuilder.link(selectedWebApp.joinBase, event)
        val read = ContactCards.read(link, at)
        if (read !is CardResult.Ok || read.card.p != primary.participant) {
            return@launch note("Your signer returned something that is not your card.")
        }
        _room.update { it.copy(myCard = link) }
    }

    fun dismissMyCard() {
        _room.update { it.copy(myCard = null) }
    }

    private fun iceServers(): List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
    )
}

internal fun key(device: String, trackId: String): String = "$device|$trackId"

internal fun epochSeconds(): Long = System.currentTimeMillis() / 1000

/** Accepts a list separated by newlines, commas or spaces, and keeps only websocket URLs. */
/** The hand-marked circle boxes, normalised as `ContactBook.normalise` does, so a
 *  typed URL and a room's relay compare equal. Lines that are not relay URLs are ignored. */
internal fun circleMarks(text: String): Set<String> =
    parseRelays(text).mapNotNull(ContactBook::normalise).toSet()

internal fun parseRelays(text: String): List<String> = text
    .split('\n', ',', ' ', '\t')
    .map { it.trim() }
    .filter { it.startsWith("ws://") || it.startsWith("wss://") }
    .distinct()

/** The quiet state as the saved room keeps it. */
internal fun quietStateToJson(state: QuietTransport.QuietState): JsonObject = buildJsonObject {
    put("used", buildJsonObject {
        for ((member, u) in state.used) put(member, buildJsonObject { put("epoch", u.epoch); put("counters", buildJsonArray { for (c in u.counters) add(JsonPrimitive(c)) }) })
    })
    put("queued", buildJsonArray { for (e in state.queued) add(e.toJson()) })
    put("boxPending", buildJsonArray { for (id in state.boxPending.sorted()) add(JsonPrimitive(id)) })
    state.keyFingerprint?.let { put("keyFingerprint", it) }
}

internal fun quietStateFromJson(json: JsonObject): QuietTransport.QuietState? = runCatching {
    val used = (json["used"] as? JsonObject)?.mapValues { (_, v) ->
        val o = v.jsonObject
        QuietKeys.UsedCounters(o.getValue("epoch").jsonPrimitive.long, o.getValue("counters").jsonArray.map { it.jsonPrimitive.int }.toSet())
    } ?: emptyMap()
    val queued = (json["queued"] as? JsonArray)?.map { NostrEvent.fromJson(it.jsonObject) } ?: emptyList()
    require(queued.size <= QuietTransport.MAX_PENDING && queued.map { it.id }.distinct().size == queued.size)
    val retainedIds = queued.mapTo(mutableSetOf()) { it.id }
    val boxPending = json["boxPending"]?.jsonArray?.map {
        it.jsonPrimitive.also { value -> require(value.isString) }.content
    } ?: emptyList()
    require(boxPending.size <= QuietTransport.MAX_PENDING && boxPending.distinct().size == boxPending.size)
    require(boxPending.all { it.matches(Regex("[0-9a-f]{64}")) && it in retainedIds })
    val keyFingerprint = json["keyFingerprint"]?.jsonPrimitive?.content
    require(keyFingerprint == null || keyFingerprint.matches(Regex("[0-9a-f]{64}")))
    QuietTransport.QuietState(used, queued, boxPending.toSet(), keyFingerprint)
}.getOrNull()
