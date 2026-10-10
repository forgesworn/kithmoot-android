package dev.forgesworn.kithmoot.ui

import dev.forgesworn.kithmoot.session.sealFile

import dev.forgesworn.kithmoot.protocol.canonicalRelayUrl
import dev.forgesworn.kithmoot.protocol.isSafeRoomRelayUrl
import dev.forgesworn.kithmoot.protocol.MAX_INVITATION_RELAYS
import dev.forgesworn.kithmoot.epoch.NativeKeeperCreation
import dev.forgesworn.kithmoot.epoch.NativeKeeperEntry
import dev.forgesworn.kithmoot.epoch.NativeKeeperController
import dev.forgesworn.kithmoot.epoch.NativeHostingState
import dev.forgesworn.kithmoot.epoch.NativeHostingStatus
import dev.forgesworn.kithmoot.epoch.NativeHostingLifecycle
import dev.forgesworn.kithmoot.epoch.NativeKeeperEndpoints
import dev.forgesworn.kithmoot.storage.NativeKeeperVault
import dev.forgesworn.kithmoot.storage.NativeRoomCreation
import dev.forgesworn.kithmoot.storage.RoomRekeyVault

import android.util.Log
import dev.forgesworn.kithmoot.protocol.decodeLivePersistentDescriptor
import dev.forgesworn.kithmoot.session.withLivePersistentRoomAdmission
import dev.forgesworn.kithmoot.relay.*
import dev.forgesworn.kithmoot.service.RoomRouteTransitions
import dev.forgesworn.kithmoot.discovery.BoxDiscovery
import dev.forgesworn.kithmoot.discovery.BoxRelayReader
import dev.forgesworn.kithmoot.session.ChatAttachment
import dev.forgesworn.kithmoot.session.ChatArtwork
import dev.forgesworn.kithmoot.session.resolveCatalogueArtwork
import dev.forgesworn.kithmoot.session.artworkFallback
import dev.forgesworn.kithmoot.session.MAX_CHAT_TEXT_LENGTH
import dev.forgesworn.kithmoot.session.MAX_MEDIA_SOURCE_BYTES
import dev.forgesworn.kithmoot.session.SealedMedia
import dev.forgesworn.kithmoot.session.sealMedia
import dev.forgesworn.kithmoot.session.mediaStorageOrigin
import dev.forgesworn.kithmoot.session.mediaAuthorisation
import dev.forgesworn.kithmoot.session.uploadMedia
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import dev.forgesworn.kithmoot.session.RoomWork
import dev.forgesworn.kithmoot.session.AssignmentSnapshot
import dev.forgesworn.kithmoot.session.AvailableAssignmentAction
import dev.forgesworn.kithmoot.storage.AssignmentVault

import dev.forgesworn.kithmoot.protocol.CardResult
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.KIND_DM_RELAYS
import dev.forgesworn.kithmoot.protocol.canonicalRoomRelayUrl
import dev.forgesworn.kithmoot.protocol.dmRelayListTags
import dev.forgesworn.kithmoot.protocol.latestDmRelayList
import dev.forgesworn.kithmoot.protocol.relaysForPrivateConversation
import dev.forgesworn.kithmoot.protocol.ContactCardBuilder
import dev.forgesworn.kithmoot.protocol.ContactCards
import dev.forgesworn.kithmoot.protocol.Lane
import dev.forgesworn.kithmoot.protocol.laneOfRelayUrl
import dev.forgesworn.kithmoot.protocol.laneOfRelays
import dev.forgesworn.kithmoot.protocol.RoomRelaysRecord
import dev.forgesworn.kithmoot.protocol.RoomNameRecord
import dev.forgesworn.kithmoot.protocol.peekRekeyEpoch
import dev.forgesworn.kithmoot.protocol.readRekeyEvidence
import dev.forgesworn.kithmoot.protocol.KIND_ROOM_REKEY
import dev.forgesworn.kithmoot.protocol.invitationRelaysFrom
import dev.forgesworn.kithmoot.storage.ContactBook
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import dev.forgesworn.kithmoot.ui.room.PublicProfile
import dev.forgesworn.kithmoot.ui.room.decodePublicProfile

import android.app.Application
import android.content.Intent
import dev.forgesworn.kithmoot.account.AccountSession
import dev.forgesworn.kithmoot.account.ProfileMetadata
import dev.forgesworn.kithmoot.account.checkedSignedEvent
import dev.forgesworn.kithmoot.relay.RelayChoice
import dev.forgesworn.kithmoot.relay.RelaySelection
import dev.forgesworn.kithmoot.relay.RelayHealth
import dev.forgesworn.kithmoot.relay.RoomRelays
import dev.forgesworn.kithmoot.relay.combinedRelayHealth
import dev.forgesworn.kithmoot.account.AccountRoom
import dev.forgesworn.kithmoot.account.RoomBookmarks
import dev.forgesworn.kithmoot.account.RoomBookmarkSnapshot
import dev.forgesworn.kithmoot.account.learnBookmarkLifetime
import dev.forgesworn.kithmoot.account.SyncedGroup
import dev.forgesworn.kithmoot.account.syncedGroup
import dev.forgesworn.kithmoot.storage.RoomBookmarkVault
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
import dev.forgesworn.kithmoot.account.AccountWriteHold
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
import dev.forgesworn.kithmoot.account.requireSameRetainedAccount
import dev.forgesworn.kithmoot.account.shortNpub
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.service.DeliveryCandidate
import dev.forgesworn.kithmoot.cadence.CadenceClient
import dev.forgesworn.kithmoot.cadence.CadenceLeaseVault
import dev.forgesworn.kithmoot.cadence.CadenceOwnership
import dev.forgesworn.kithmoot.cadence.CadenceRenewal
import dev.forgesworn.kithmoot.cadence.CadenceRoomTransport
import dev.forgesworn.kithmoot.cadence.CadenceSchedule
import dev.forgesworn.kithmoot.cadence.CadenceScopeKey
import dev.forgesworn.kithmoot.cadence.StoredCadenceLease
import dev.forgesworn.kithmoot.epoch.CadenceEpochCoordinate
import dev.forgesworn.kithmoot.epoch.EpochPhase
import dev.forgesworn.kithmoot.epoch.activeEpochFor
import dev.forgesworn.kithmoot.epoch.EpochVault
import dev.forgesworn.kithmoot.epoch.EpochRecoveryResponder
import dev.forgesworn.kithmoot.epoch.MemberEpochResponder
import dev.forgesworn.kithmoot.epoch.asDesk
import dev.forgesworn.kithmoot.epoch.StoredRoomEpoch
import dev.forgesworn.kithmoot.storage.RoomRecoveryException
import dev.forgesworn.kithmoot.storage.RoomStorageException
import dev.forgesworn.kithmoot.storage.SavedRoom
import dev.forgesworn.kithmoot.storage.SavedRoomSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import dev.forgesworn.kithmoot.session.epochTroubleLines
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
import dev.forgesworn.kithmoot.media.CallVolume
import dev.forgesworn.kithmoot.media.effects.BackgroundChoice
import dev.forgesworn.kithmoot.media.effects.BackgroundPreference
import dev.forgesworn.kithmoot.media.effects.SeaScene
import dev.forgesworn.kithmoot.media.effects.SharedPreferencesBackgroundStore
import dev.forgesworn.kithmoot.media.LocalTrack
import dev.forgesworn.kithmoot.media.SharedPreferencesVolumeStore
import dev.forgesworn.kithmoot.media.WebRtcEngine
import dev.forgesworn.kithmoot.media.resolveRemoteByRole
import dev.forgesworn.kithmoot.media.roleKey
import dev.forgesworn.kithmoot.media.shouldPlayRemoteAudio
import dev.forgesworn.kithmoot.protocol.JoinUrlException
import dev.forgesworn.kithmoot.protocol.InvitationPayload
import dev.forgesworn.kithmoot.protocol.encodePersistentInvitation
import dev.forgesworn.kithmoot.session.requestPersistentAdmission
import dev.forgesworn.kithmoot.session.RetiredInvitationException
import dev.forgesworn.kithmoot.session.GroupInvitationException
import dev.forgesworn.kithmoot.session.INVITATION_NOT_FOUND
import dev.forgesworn.kithmoot.session.MissingGroupInvitationException
import dev.forgesworn.kithmoot.session.isMissingInvitation
import dev.forgesworn.kithmoot.session.linkOnlyRelays
import dev.forgesworn.kithmoot.session.widerInvitationRelays
import dev.forgesworn.kithmoot.protocol.KindredTier
import dev.forgesworn.kithmoot.protocol.KIND_INVITATION_GRANT
import dev.forgesworn.kithmoot.protocol.KIND_INVITATION_REQUEST
import dev.forgesworn.kithmoot.protocol.KIND_INVITATION_RETIREMENT
import dev.forgesworn.kithmoot.protocol.RoomAdmission
import dev.forgesworn.kithmoot.protocol.CallMembership
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
import dev.forgesworn.kithmoot.relay.Nip77Reconciliation
import dev.forgesworn.kithmoot.relay.Nip77OfferArchive
import dev.forgesworn.kithmoot.relay.HybridRelaySockets
import dev.forgesworn.kithmoot.relay.ActiveLinkRoute
import dev.forgesworn.kithmoot.relay.RelayAuthenticator
import dev.forgesworn.kithmoot.relay.RelayAuthenticatorProvider
import dev.forgesworn.kithmoot.relay.RelaySocketFactory
import dev.forgesworn.kithmoot.relay.OrbotTorRelaySockets
import dev.forgesworn.kithmoot.relay.RelayPolicy
import dev.forgesworn.kithmoot.relay.TorCarrierTimings
import dev.forgesworn.kithmoot.relay.TorOnlyRelayUrls
import dev.forgesworn.kithmoot.protocol.BothyPairing
import dev.forgesworn.kithmoot.service.ScreenShareService
import dev.forgesworn.kithmoot.session.ChatMessage
import dev.forgesworn.kithmoot.session.KIND_CHAT
import dev.forgesworn.kithmoot.session.deriveChatChannel
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
import dev.forgesworn.kithmoot.session.RoomChatForwarder
import dev.forgesworn.kithmoot.session.RoomForwardingBinding
import dev.forgesworn.kithmoot.storage.RoomSharingVault
import dev.forgesworn.kithmoot.session.EpochGateResult
import dev.forgesworn.kithmoot.session.currentCircleGuestDevices
import dev.forgesworn.kithmoot.session.QuietTransport
import dev.forgesworn.kithmoot.session.ConferenceLength
import dev.forgesworn.kithmoot.session.conferenceEndedMessage
import dev.forgesworn.kithmoot.protocol.conferenceEnded
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.BoxCadence
import dev.forgesworn.kithmoot.protocol.CadenceLeaseOptions
import dev.forgesworn.kithmoot.protocol.CadenceScope
import dev.forgesworn.kithmoot.protocol.DeadDrop
import dev.forgesworn.kithmoot.protocol.QuietKeys
import dev.forgesworn.kithmoot.protocol.RENDEZVOUS_PROVISION_MAX_SECONDS
import dev.forgesworn.kithmoot.protocol.RendezvousProvisionExpect
import dev.forgesworn.kithmoot.account.RendezvousVaultResult
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
import dev.forgesworn.kithmoot.session.callsOf
import dev.forgesworn.kithmoot.session.starter
import dev.forgesworn.kithmoot.session.SecondaryIdentity
import dev.forgesworn.kithmoot.session.decodeInvitationPairingLink
import dev.forgesworn.kithmoot.session.decodePairingLink
import dev.forgesworn.kithmoot.session.encodeInvitationPairingLink
import dev.forgesworn.kithmoot.session.encodePairingLink
import dev.forgesworn.kithmoot.ui.room.ParticipantTile
import dev.forgesworn.kithmoot.ui.room.MicrophoneAction
import dev.forgesworn.kithmoot.ui.room.buildTiles
import dev.forgesworn.kithmoot.ui.room.microphoneAction
import dev.forgesworn.kithmoot.ui.room.JoinDecision
import dev.forgesworn.kithmoot.ui.room.SingleBuild
import dev.forgesworn.kithmoot.ui.room.joinDecision
import dev.forgesworn.kithmoot.ui.room.callToDeclare
import dev.forgesworn.kithmoot.epoch.pastEpochsFor
import dev.forgesworn.kithmoot.ui.room.mediaMissingNote
import dev.forgesworn.kithmoot.ui.room.LiveMark
import dev.forgesworn.kithmoot.ui.room.MarkAuthor
import dev.forgesworn.kithmoot.ui.room.ShareMarks
import dev.forgesworn.kithmoot.ui.room.shortId
import dev.forgesworn.kithmoot.ui.room.roomLane
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
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
import dev.forgesworn.kithmoot.protocol.MeetingPolicy
import dev.forgesworn.kithmoot.protocol.RecordingView
import dev.forgesworn.kithmoot.protocol.RecordingCaptureNotice
import dev.forgesworn.kithmoot.protocol.meetingAllows
import dev.forgesworn.kithmoot.protocol.meetingGated
import dev.forgesworn.kithmoot.session.MeetingNews
import dev.forgesworn.kithmoot.session.MeetingSnapshot
import org.webrtc.AudioTrack
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.VideoTrack

/** Which screen the app is on. Two screens; a navigation library would be scaffolding. */
enum class Stage { START, ROOM }

/** Recheck after entry exclusion: the sign-out request must still target this session. */
internal fun signOutStillTargets(requested: Any?, current: Any?, stage: Stage, roomOpen: Boolean): Boolean =
    stage == Stage.START && !roomOpen && requested === current

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

/** Public state only; the child scalar is never part of Compose state. */
data class RendezvousView(
    val activeIndex: Long? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

data class StartState(
    val unfinishedNativeRoom: SavedRoomSummary? = null,
    val roomBookmarks: RoomBookmarkSnapshot = RoomBookmarkSnapshot(),
    val roomSyncBusy: Boolean = false,
    val roomSyncError: String? = null,
    val projects: ProjectAccountSnapshot = ProjectAccountSnapshot(),
    val projectsBusy: Boolean = false,
    val projectError: String? = null,
    val webAppAddress: String = WebAppAddress.DEFAULT_ORIGIN,
    val joinUrl: String = "",
    val relays: String = DEFAULT_RELAYS.joinToString("\n"),
    val relayChoices: List<RelayChoice> = emptyList(),
    val relayHealth: Map<String, RelayHealth> = emptyMap(),
    val profileMetadata: JsonObject? = null,
    val profileBaseId: String? = null,
    val profileBaseAt: Long = 0,
    val profileBusy: Boolean = false,
    val profileMessage: String? = null,
    /** The account's own DM relay list (NIP-17, kind 10050) as last looked up
     *  or saved; null until looked up. */
    val dmRelays: List<String>? = null,
    /** A constrained room profile: new local identity, onion relays and Orbot only. */
    val anonymousMode: Boolean = false,
    val busy: Boolean = false,
    /** A room has been opening for [STOP_OPENING_AFTER_MS]: offer the way out. */
    val canStopOpening: Boolean = false,
    /** "Opening Wednesday standup…" while a tapped room opens, so a slow
     *  relay never looks like a tap that did nothing. */
    val opening: String? = null,
    val error: String? = null,
    val notice: String? = null,
    /** Rooms that self-destructed on this device, newest first: one greyed row
     *  each, naming none, until dismissed or seven days pass. */
    val destructTombstones: List<dev.forgesworn.kithmoot.storage.DestructTombstone> = emptyList(),
    val roomName: String = "",
    val persistentGroup: Boolean = true,
    /** How long a new room runs: Never, or a conference room that ends and is wiped from relays. */
    val conferenceLength: ConferenceLength = ConferenceLength.NEVER,
    val roomDurationSeconds: Int = 7200,
    /** When a room with an end ends: self-destruct (owner decision D1, the
     *  default) or keep a read-only copy. Offered only for a room with an end:
     *  this phone cannot end a room early, so it never makes a room with no end
     *  that could only be destroyed from another device. */
    val roomDestruct: Boolean = true,
    val loadingRooms: Boolean = true,
    val storageError: Boolean = false,
    val savedRooms: List<SavedRoomSummary> = emptyList(),
    /** Locally retained authenticated message times, scoped to the selected account. */
    val latestMessageTimes: Map<String, Long> = emptyMap(),
    val linkConnectedRooms: Set<String> = emptySet(),
    /** Rooms where this account issued live guest grants and may revoke them without leaving Bothy. */
    val linkGrantOwnerRooms: Set<String> = emptySet(),
    /** Signed in as this person; every room from here is joined as them. */
    val account: AccountView? = null,
    /** Present only for a NIP-46 account whose device-held recipient key may receive a Vennel child. */
    val rendezvous: RendezvousView? = null,
    /** A release build retained this debug-preview identity and needs the same account from an external signer. */
    val retainedAccount: AccountView? = null,
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
    /** Whether public Nostr profiles (names, pictures) are looked up and shown. Device-wide; Settings reads it with no room open. */
    val publicProfiles: Boolean = true,
    /** Verified kind-0 decoration for private conversation peers, memory only. */
    val privateChatProfiles: Map<String, PublicProfile> = emptyMap(),
    /** Whether the person's own camera is shown mirrored. Device-wide, like [publicProfiles]. */
    val mirrorSelf: Boolean = true,
)

internal fun privateChatProfileScope(state: StartState): Pair<String?, List<String>> {
    val offline = state.savedRooms.filter { !it.route.internet }.map { it.id }.toSet()
    return state.account?.pubkey to if (!state.publicProfiles) emptyList() else
        dev.forgesworn.kithmoot.ui.start.mergeRooms(state.savedRooms, state.roomBookmarks.rooms,
            state.account != null, state.account?.pubkey).filterNot { it.id in offline }
            .mapNotNull { it.privatePeer }.distinct().sorted().take(500)
}

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

/** "<who> renamed the room to “<name>”", at the time the rename was sent. */
/** What joining a recorded call was asked for, carried out on a yes. */
enum class RecordingConsent { JOIN, JOIN_WITH_MIC, MICROPHONE, CAMERA }

/** Said wherever a control is locked, the same way every time. */
const val MEETING_LOCKED = "This call is in meeting mode: only speakers can use a microphone, camera or screen share. Raise your hand to ask to speak."

data class RoomNote(val id: String, val participant: String, val name: String, val sentAt: Long)

/** The chat's line for a rename read for the first time: once per rename,
 *  never for a carried copy, and only in the room it was read in. */
internal fun RoomState.withRenameRead(roomId: String, rename: RoomNameRecord): RoomState {
    val by = rename.by ?: return this
    if (this.roomId != roomId || roomNotes.any { it.id == rename.id }) return this
    return copy(roomNotes = (roomNotes + RoomNote(rename.id, by, rename.name, rename.sentAt)).takeLast(50))
}

/** The open room's title follows its shared name. */
internal fun RoomState.withSharedName(roomId: String, shared: RoomNameRecord): RoomState =
    if (this.roomId != roomId || name == shared.name) this else copy(name = shared.name)

/**
 * The room as a secure update moves it: [state] is [RoomEpochState.Active] or
 * [RoomEpochState.Updating]; any other leaves it as it is.
 *
 * A scheduled turn of the key removed nobody, so it is not announced: the room moves on with
 * no line either side of it, as the web client's does. Without a Bothy schedule the move is a
 * local write and over at once, so there is nothing to show. With one it waits on Bothy and can
 * stall, and the update panel is what holds the retry, so even a scheduled turn shows it then.
 */
internal fun RoomState.withEpochProgress(state: dev.forgesworn.kithmoot.session.RoomEpochState): RoomState = when (state) {
    is dev.forgesworn.kithmoot.session.RoomEpochState.Active ->
        // A scheduled turn that did show the panel, waiting on Bothy, says it is done too.
        copy(movedOn = null, roomUpdate = null, notice = if (state.epoch > 0 && (!state.scheduled || roomUpdate == "updating")) "Secure room update complete." else notice)
    is dev.forgesworn.kithmoot.session.RoomEpochState.Updating ->
        if (state.scheduled && cadence == null) this
        else copy(movedOn = state.epoch, roomUpdate = "updating", notice = if (cadence != null) "Secure room update is waiting for Bothy to retire the old schedule." else "Secure room update in progress.")
    else -> this
}

/** Somebody asking to be let into the room: their participant key, and how to name them. */
data class LetInAsk(val participant: String, val label: String)

data class RoomState(
    val notificationChatRequest: Int = 0,
    /** Bumped to bring the room to its call view: an answered call opens there. */
    val callViewRequest: Int = 0,
    val roomId: String = "",
    val name: String = "",
    val joinUrl: String = "",
    /** This room stays on its constrained Orbot/onion carrier. */
    val anonymous: Boolean = false,
    val route: RoomRoute = RoomRoute.INTERNET,
    val nearby: RoomBleState? = null,
    val sharing: RoomSharingState? = null,
    /** Public source observation; never a root key or permission to sign. */
    val nativeHosting: NativeHostingState? = null,
    val nativeHostingBusy: Boolean = false,
    val relaysUp: Int = 0,
    val relaysTotal: Int = 0,
    /** The lane the next message will take, from the room's relays. */
    val lane: Lane? = null,
    /** A quiet room: chat rides the gift-wrap stream as dead drops. See session/QuietTransport.kt. */
    val quiet: Boolean = false,
    /** Whether this device may post in the quiet room: two devices per person can, others read. */
    val quietCanSend: Boolean = true,
    val cadence: CadenceViewState? = null,
    /** A deliberate, IDs-only comparison with the currently connected circle box. */
    val nip77: Nip77ViewState? = null,
    val tiles: List<ParticipantTile> = emptyList(),
    /** Participants whose microphone is carrying speech right now, this one
     *  included while its microphone is live and unmuted. From audio levels:
     *  see media/Speaking.kt. */
    val speaking: Set<String> = emptySet(),
    /** Fading screen-share drawing, keyed by the advertised share track id
     *  (`TileTrack.trackId`). See ui/room/ShareMarks.kt. */
    val shareMarks: Map<String, List<LiveMark>> = emptyMap(),
    val chat: List<ChatMessage> = emptyList(),
    val chatAttachments: List<ChatAttachment> = emptyList(),
    val recordingDrafts: List<dev.forgesworn.kithmoot.media.recording.RecordingShareDraft> = emptyList(),
    val recordingStorageChoice: dev.forgesworn.kithmoot.media.recording.RecordingStorageChoice? = null,
    val recordingUploadRunning: Boolean = false,
    val chatArtwork: List<ChatArtwork> = emptyList(),
    val mediaBusy: Boolean = false,
    /** Lines the chat shows that nobody typed: who renamed the room, once
     *  per rename read this visit. */
    val roomNotes: List<RoomNote> = emptyList(),
    /** A two-member room whose invitation must travel sealed through another room. */
    val privateConversation: Boolean = false,
    /** Current people who can be chosen for a new signer-sealed private conversation. */
    val privateConversationPeers: List<String> = emptyList(),
    val privateConversationBusy: Boolean = false,
    val profilesEnabled: Boolean = false,
    /** Whether this device shows its own camera as a mirror. Only the preview:
     *  what the room receives is never flipped. See [RoomViewModel.setMirrorSelf]. */
    val mirrorSelf: Boolean = true,
    val profiles: Map<String, PublicProfile> = emptyMap(),
    val selfParticipant: String = "",
    val selfDevice: String = "",
    val mediaConnections: Map<String, String> = emptyMap(),
    val listeningHere: Boolean = true,
    /**
     * This device's media stack is engaged: peers connected, local capture
     * possible, remote audio routed here.
     *
     * NOT membership of the call - see [onCall], and never read as that. It
     * is true from the moment a room opens, because opening a room is how you
     * hear the people already in it, and a person who only listens has
     * declared nothing to anybody.
     */
    val mediaRunning: Boolean = true,
    /**
     * This device has declared on the roster that it is on the room's call.
     *
     * The mirror of `RoomSession.currentCall() != null`, and the only thing
     * that may be read as "on the call" - exactly as the web client reads
     * `session.call`. Having the engine running is not being on a call: a
     * person who opens a room and listens is not on one until they press
     * Start or Join, or switch a microphone, camera or screen on.
     */
    val onCall: Boolean = false,
    /** Opened beside a call in another room: chat and work only, no call. */
    val chatOnly: Boolean = false,
    val callChanging: Boolean = false,
    /**
     * Audio and video do not exist yet on this device and are still expected.
     *
     * True while the room is waiting for its epoch to become active, and
     * while the engine is being built. It is what the call control shows
     * instead of a button that does nothing, and what makes a Join pressed
     * now worth remembering rather than refusing.
     */
    val mediaStarting: Boolean = false,
    /**
     * Join was pressed before there was anything to join with, and is
     * remembered. Carried out the moment the engine exists, cleared by Leave
     * and by giving up on the epoch.
     */
    val callJoinPending: Boolean = false,
    /**
     * A remembered [callJoinPending] join asked for the microphone too -
     * answering a ringing call, rather than a manual Join. Carried out
     * alongside [callJoinPending] once the engine exists, and cleared with it.
     */
    val callJoinMicPending: Boolean = false,
    /**
     * Devices on the room's current call that are not this one.
     *
     * Own other devices count: a call taken on the laptop is one this phone
     * may join, and the control says so. Read off the roster's call
     * memberships, never from who happens to have a track, so somebody
     * listening in from a train with everything switched off still counts as
     * being on the call. See `ui/room/CallStance.kt`.
     */
    val callOtherDevices: Int = 0,
    val micOn: Boolean = false,
    /**
     * This device's microphone is running but silenced at the source.
     *
     * Different from [micOn] being false, which means there is no microphone
     * here at all: a muted device is still in the conversation, and the room is
     * told so on the roster rather than left to guess from an absent track.
     */
    val micMuted: Boolean = false,
    val cameraOn: Boolean = false,
    val screenOn: Boolean = false,
    /** The room's authority has the call in meeting mode: only its speakers
     *  can talk or show video. See protocol/Meeting.kt. */
    val meetingOn: Boolean = false,
    /** This person may use a microphone, camera or screen share: always,
     *  outside meeting mode. */
    val meetingSpeaker: Boolean = true,
    /** This person's hand is up, asking to speak. */
    val handUp: Boolean = false,
    /** Hands raised by people not on the stage. */
    val handsRaised: Int = 0,
    /** This device holds the room's authority key, so it may turn meeting
     *  mode on and off and choose the speakers. */
    val meetingModerator: Boolean = false,
    /** The speakers the room's meeting policy names, on or off. */
    val meetingSpeakers: List<String> = emptyList(),
    /** Raised hands: participant -> unix seconds raised. */
    val raisedHands: Map<String, Long> = emptyMap(),
    /** What the room's recording notice says right now. */
    val recording: RecordingView = RecordingView.Off,
    val recordingCapture: RecordingCaptureNotice? = null,
    val nativeRecording: Boolean = false,
    val nativeRecordingPaused: Boolean = false,
    val nativeRecordingBusy: Boolean = false,
    val recordingVideoDevices: List<dev.forgesworn.kithmoot.media.recording.RecordingVideoEndpoint> = emptyList(),
    val recordingVideoSupported: Boolean = false,
    val recordingStopPending: Boolean = false,
    val recordingStopRetrying: Boolean = false,
    /** Something was pressed that would put this device on a recorded call,
     *  and the person is being asked first. Null when nothing is asked. */
    val recordingConsent: RecordingConsent? = null,
    /**
     * What is drawn behind this device's camera picture, if anything.
     *
     * Off by default and remembered on this device between calls: somebody who
     * has a reason to hide their room has that reason on Tuesday as well.
     * See media/effects/BackgroundChoice.kt.
     */
    val background: BackgroundChoice = BackgroundPreference.DEFAULT,
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
    /** What went wrong with the room's epochs this visit (`epochTroubleLines`), kept until dismissed. */
    val epochTrouble: List<String> = emptyList(),
    /** People the room does not know asking to come in, after a removal (kithmoot#207). */
    val letInAsks: List<LetInAsk> = emptyList(),
    val invitationAdmissions: List<dev.forgesworn.kithmoot.session.PendingInvitationAdmission> = emptyList(),
    val work: AssignmentSnapshot = AssignmentSnapshot(),
    val workActions: List<AvailableAssignmentAction> = emptyList(),
    val workBusy: Boolean = false,
    val workError: String? = null,
    val workCompleted: Long = 0,
    val chatSending: Boolean = false,
    val chatSendError: String? = null,
    val chatPending: Boolean = false,
    /** Messages kept on this phone and not yet in the room's log, oldest first; shown at the end of the chat. */
    val pendingChats: List<dev.forgesworn.kithmoot.session.PendingChat> = emptyList(),

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
    /** A conference room's end, unix seconds; null for a room that does not end. */
    val endsAt: Long? = null,
    /** This conference room has reached its end: nothing more is sent, and the link no longer opens it. */
    val conferenceEnded: Boolean = false,
    /** The room self-destructs when it ends (`SavedRoom.destruct`). */
    val destruct: Boolean = false,
    /** When this device first knew the room, for scaling its countdown. */
    val startsAt: Long? = null,
) {
    val canShareInvitation: Boolean get() = !privateConversation && movedOn == null && !conferenceEnded &&
        joinUrl.isNotBlank() && !privateConversationBusy &&
        (nativeHosting == null || nativeHosting.canShareInvitation && !nativeHostingBusy)
    internal fun withNativeUnknownApprovals(asks: List<LetInAsk>): RoomState =
        copy(letInAsks = if (nativeHosting?.canShareInvitation == true) asks else emptyList())
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
    /** Offered while the running lease can still be followed by a contiguous one. */
    val renewable: Boolean = false,
    /** End of a staged renewal that follows the shown lease. */
    val renewedUntilEpoch: Long? = null,
)

data class Nip77ViewState(
    val available: Boolean = false,
    val busy: Boolean = false,
    /** A fetch is offered only after this session's explicit comparison. */
    val fetchAvailable: Boolean = false,
    /** An offer is available only for exact phone-only IDs retained in the encrypted local archive. */
    val offerAvailable: Boolean = false,
    val detail: String = "Connect this account-owned room to its verified Bothy before comparing history.",
)

private data class Nip77ReconciliationPlan(
    val account: String,
    val roomId: String,
    val relayUrl: String,
    val address: String,
    val since: Long,
    val until: Long,
    val fetchIds: Set<String>,
    val offerIds: Set<String>,
)

/** Relays used when a room is opened here, or when a join URL names none. */
// A publish succeeds when any writable relay acknowledges it (see
// NostrRelayPool), so a third default relay only adds redundancy - it is not
// a single point either client depends on. Matches the web client's default
// list (`src/agent.ts` `DEFAULT_RELAYS`).
val DEFAULT_RELAYS: List<String> = listOf("wss://nos.lol", "wss://relay.primal.net", "wss://nostr.mom")

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

/** How often self-destructing rooms are looked at: whether one is due, and its heads-up. */
private const val DESTRUCT_CHECK_MS = 15_000L

/** How long a device credential is good for. A day outlives any meeting. */
private const val CREDENTIAL_TTL_SECONDS = 24L * 60 * 60
/** How long a reply from a notification waits for a relay: a receiver has ten seconds in all. */
private const val NOTICE_REPLY_CONFIRM_MS = 8_000L
/** Seconds before each automatic retry of a kept message, the last repeating. */
private val PENDING_RETRY_SECONDS = listOf(5, 15, 30, 60)
/** One at a time, in order, so an open room's inbox writes never land after its close's read-through. */
private val backgroundInboxWrites = Dispatchers.IO.limitedParallelism(1)
/** How long starting a private conversation waits for the two DM relay lists. */
private const val DM_RELAY_LOOKUP_MS = 2_500L
/** How long a contact card this phone hands out is good for. */
private const val CARD_TTL_SECONDS = 7L * 24 * 60 * 60
/** How often the banner's picture of which rooms cannot answer calls is looked at again. */
private const val REACHABILITY_CHECK_MS = 60_000L
private const val INVITATION_TIMEOUT_MS = 90_000L
private const val GROUP_INVITATION_REFRESH_MS = 6L * 60 * 60 * 1000
/** How long the one background read of a group invitation, for the room's relays, may take. */
private const val ROOM_RELAYS_READ_MS = 20_000L
private const val CIRCLE_GRANT_LIFETIME_SECONDS = 30L * 24 * 60 * 60
private const val CIRCLE_ROSTER_FRESH_SECONDS = 75L

/**
 * Everything about getting into a room and onto its call, in one logcat tag.
 *
 * `adb logcat -s KithMootJoin` is the whole of the diagnosis for "it took a
 * couple of goes". Pubkeys are cut to eight hex characters, as the media
 * lines already are, and no room secret, room key or invitation ever goes
 * near it.
 */
internal const val JOIN_LOG = "KithMootJoin"

/** How long a room tap waits for the last room to finish closing. */
private const val ENTRY_GATE_WAIT_MS = 30_000L

/**
 * How long a deliberate Bothy action waits for grant recovery, which runs on
 * every sign-in restore and room refresh, before saying it is still busy.
 */
private const val CIRCLE_GRANT_WAIT_MS = 15_000L

/** Shown on the start screen while a tap is waiting behind a teardown. */
/** How long a room may take to open before the way out is offered. */
internal const val STOP_OPENING_AFTER_MS = 8_000L

/** The display preference behind [RoomViewModel.setMirrorSelf]. */
private const val MIRROR_SELF = "mirrorSelf"
private const val FINISHING_LAST_ROOM = "Finishing leaving the last room…"

/**
 * How long a room may sit at a non-active epoch before audio and video are
 * declared a failure. Generous: a secure room update has a relay round trip
 * and an authority in it.
 */
private const val EPOCH_ACTIVATION_TIMEOUT_MS = 60_000L


internal fun roomEntryFailureMessage(error: Exception): String = when (error) {
    is SignerException,
    is RoomRecoveryException,
    is GroupInvitationException -> error.message ?: "The room could not be opened. Try again."
    else -> "The room could not be opened. Try again."
}

/** Bounded code locations only. Exception messages can contain private data. */
internal fun roomEntryFailureDiagnostic(error: Exception): String =
    dev.forgesworn.kithmoot.session.codeLocationDiagnostic(error)

/**
 * Everything the two screens need, and the only thing that owns a session.
 *
 * The screens are deliberately inert - they read state and call methods here.
 * That keeps the join, leave and re-join paths in one place, which matters
 * because the interesting failure in this application is a half-torn-down room:
 * a relay pool still publishing after the user has left, or a second session
 * opened over the top of a live one.
 */
class RoomViewModel @JvmOverloads constructor(
    application: Application,
    /**
     * A second instance, beside a call running in the first: a room opened
     * here is for reading and writing only. It never starts media, never
     * touches the chat notification or screen-share service the call's
     * instance owns, and borrows that instance's account rather than opening
     * another. See MainActivity, which decides which instance is on screen.
     */
    val chatOnly: Boolean = false,
    private val nearbyLinkFactory: (Application) -> NativeRoomMeshLink = ::createAndroidRoomMeshLink,
    /** Optional constructor dependency for private TLS qualification. Normal
     * activities leave this null and use the strict production client. */
    private val recordingUploadClient: okhttp3.OkHttpClient? = null,
) : AndroidViewModel(application) {

    /** The room the call is in, which this chat-only instance must never
     *  open a second session on. */
    @Volatile var callRoomId: String? = null

    private val _stage = MutableStateFlow(Stage.START)
    val stage: StateFlow<Stage> = _stage.asStateFlow()
    @Volatile internal var lastRoomEntryDiagnostic: String? = null
        private set
    internal fun nativeHostState() = nativeKeeperController?.state?.value
    internal fun nativeHostFailureDiagnostic() = nativeKeeperController?.let {
        "keeper=${it.failureDiagnostic} receiver=${it.receiverFailureDiagnostic()}"
    }

    private val _start = MutableStateFlow(
        StartState(
            relays = application.getSharedPreferences("kithmoot.display", android.content.Context.MODE_PRIVATE).getString("relaySettings", null) ?: DEFAULT_RELAYS.joinToString("\n"),
            circleBoxes = application.getSharedPreferences("kithmoot.display", android.content.Context.MODE_PRIVATE).getString("circleBoxes", "") ?: "",
            webAppAddress = application.getSharedPreferences("kithmoot.display", android.content.Context.MODE_PRIVATE).getString("webAppAddress", null) ?: WebAppAddress.DEFAULT_ORIGIN,
            publicProfiles = application.getSharedPreferences("kithmoot.display", android.content.Context.MODE_PRIVATE).getBoolean("publicProfiles", true),
            mirrorSelf = application.getSharedPreferences("kithmoot.display", android.content.Context.MODE_PRIVATE).getBoolean(MIRROR_SELF, true),
        ),
    )
    val start: StateFlow<StartState> = _start.asStateFlow()

    /** The remembered background, read before [_room] because the room's first
     *  value carries it: a device that has chosen a sea must never come up on
     *  its owner's room, not even for the frame it takes to load a preference. */
    private val backgrounds = BackgroundPreference(
        SharedPreferencesBackgroundStore(
            application.getSharedPreferences("kithmoot.display", android.content.Context.MODE_PRIVATE),
        ),
    )
    private val _room = MutableStateFlow(RoomState(background = backgrounds.load()))
    val room: StateFlow<RoomState> = _room.asStateFlow()
    val notifications = dev.forgesworn.kithmoot.notifications.ChatNotifications(application)
    fun notificationReading(reading: Boolean) { if (chatOnly) return; notifications.reading = reading; notifications.refresh() }
    fun notificationForeground(foreground: Boolean) { if (chatOnly) return; notifications.foreground = foreground; notifications.refresh() }

    /** See notifications/IncomingCallRingCoordinator.kt. One per open room,
     *  same as [notifications] above. */
    private val callRinger = dev.forgesworn.kithmoot.notifications.IncomingCallRingCoordinator(application, source = "room")
    // Declared before init, whose collectors run at once on Main.immediate.
    /** Held by Telecom: a phone call answered over this one. Remote sound is
     *  silenced (see the remote audio collector in startMedia) and a live
     *  microphone muted, so neither leaks into or over the phone call. */
    private val callHeld = MutableStateFlow(false)
    /** Whether [holdCall] muted the microphone, so [resumeCall] unmutes only that. */
    @Volatile private var micMutedForHold = false

    val callRingBanner: StateFlow<dev.forgesworn.kithmoot.notifications.IncomingCall?> get() = callRinger.banner
    fun dismissCallRingBanner() = callRinger.dismissBanner()
    fun setCallRingForeground(foreground: Boolean) { callRinger.foreground = foreground }
    fun callRingMode(roomId: String) = callRinger.modeFor(roomId)
    fun setCallRingMode(roomId: String, mode: dev.forgesworn.kithmoot.notifications.CallRingMode) = callRinger.setMode(roomId, mode)
    fun openNotificationRoom(id: String) {
        if (!Regex("[a-f0-9]{64}").matches(id)) return
        if (_room.value.roomId == id && _stage.value == Stage.ROOM) {
            _room.update { it.copy(notificationChatRequest = it.notificationChatRequest + 1) }
        } else if (_start.value.savedRooms.none { it.id == id }) {
            return
        } else if (_stage.value == Stage.START) {
            reopenRoom(id)
        } else if (_stage.value == Stage.ROOM) {
            // Another room is open. A call there is kept by MainActivity,
            // which opens this one beside it instead, so this one has no call:
            // leave it and open the room the notice was for.
            leave()
            if (_stage.value != Stage.START) return
            viewModelScope.launch {
                _start.first { !it.busy }
                if (_stage.value == Stage.START) reopenRoom(id)
            }
        }
    }


    /**
     * Every renderable video track, keyed `device|role`.
     *
     * Kept apart from [RoomState] on purpose. Tracks arrive and vanish on
     * WebRTC's own threads at a rate that has nothing to do with the roster, and
     * folding them into the room state would rebuild every tile each time a
     * keyframe-worth of plumbing changed.
     *
     * Role, not the WebRTC track id, because a receiver's track id never
     * matches the sender's once a slot is swapped with `replaceTrack`, and a
     * renegotiation mints a fresh one regardless (H5, call reliability spec).
     */
    private val _videos = MutableStateFlow<Map<String, VideoTrack>>(emptyMap())
    val videos: StateFlow<Map<String, VideoTrack>> = _videos.asStateFlow()

    private var sessionScope: CoroutineScope? = null
    private var pool: RelayPool? = null
    private var nearbyOwner: RoomNearbyOwner? = null
    @Volatile private var freshNearbyOpening = false
    @Volatile private var freshNearbyRoomId: String? = null
    private class FreshNearbyEntry(val owner: RoomNearbyOwner, val mesh: RoomMeshTransport,
        val transport: RoomTransport, val route: RoomRoute, val relay: RelayPool?, val scope: CoroutineScope,
        val join: suspend (RoomSession) -> Unit)
    private var nearbyTransport: RoomMeshTransport? = null
    private var closingNearby: RoomNearbyOwner? = null
    /** The pool profiles are read from: the room's relays and the public
     *  profile relays. Separate from the room's own, which must never be
     *  widened to public relays by a lookup. */
    private var profilePool: RelayPool? = null
    /** The quiet wrapper over the pool when the room is a quiet one; chat rides through it in drops. */
    private var quietTransport: QuietTransport? = null
    private var session: RoomSession? = null
    private val sharingLock = Any()
    private val sharingPreparation = Mutex()
    @Volatile private var sharingGeneration = 0L
    private var sharingOwner: RoomChatForwarder? = null
    private var sharingWatch: Job? = null
    private var roomWork: RoomWork? = null
    /** The open room's meeting policy, recording notice and raised hands, as
     *  its [roomWork] reads them. Empty while no room is open, and in a room
     *  with no shared work (an anonymous one), which has no meeting mode. */
    private val meetingState = MutableStateFlow(MeetingSnapshot())
    /** The recording this person agreed to be on the call for, by id, so the
     *  question is asked once per recording and per call, not per button. */
    @Volatile private var consentedRecording: String? = null
    /** This room's send path for a reply typed on its notification, as registered with [dev.forgesworn.kithmoot.notifications.OpenRoomReplies]. */
    private var noticeReplier: Pair<String, suspend (String) -> dev.forgesworn.kithmoot.notifications.ReplyOutcome>? = null
    /** The open room's latest messages, so closing it can tell the background inbox what it showed. */
    @Volatile private var shownChat: List<dev.forgesworn.kithmoot.session.ChatMessage> = emptyList()
    private var engine: WebRtcEngine? = null
    private val nativeRecordingLock = Any()
    private var nativeRecordingGeneration = 0L
    private var nativeRecordingStarting = false
    private data class NativeRecordingOwner(val id: String, val media: WebRtcEngine, val work: RoomWork, val file: java.io.File,
        val capture: dev.forgesworn.kithmoot.media.recording.CallAudioCapture,
        val origin: dev.forgesworn.kithmoot.media.recording.RecordingOrigin,
        val layout: dev.forgesworn.kithmoot.media.recording.RecordingVideoLayout?,
        val selected: dev.forgesworn.kithmoot.media.recording.RecordingEndpointKey?, val selectedName: String,
        val speaker: dev.forgesworn.kithmoot.media.recording.RecordingSpeaker?)
    private var nativeRecordingOwner: NativeRecordingOwner? = null
    private val recordingApplication get() = getApplication<dev.forgesworn.kithmoot.KithMootApplication>()
    val recordingExport get() = recordingApplication.recordings.export
    fun recordingExportDetails(file: java.io.File) = recordingApplication.recordings.details(file)
    private val _recordingExportBusy = MutableStateFlow(false)
    val recordingExportBusy = _recordingExportBusy.asStateFlow()
    private val _recordingExportError = MutableStateFlow<String?>(null)
    val recordingExportError = _recordingExportError.asStateFlow()
    data class RecordingAdded(val sourceName: String, val room: String, val request: Long)
    private val _recordingAdded = MutableStateFlow<RecordingAdded?>(null)
    val recordingAdded = _recordingAdded.asStateFlow()
    private var recordingAddedRequest = 0L
    private var recordingUploadJob: Job? = null
    fun cancelRecordingUpload() { recordingUploadJob?.cancel() }
    fun acknowledgeRecordingAdded(request: Long) {
        _recordingAdded.update { if (it?.request == request) null else it }
    }
    /** Screen-share drawing, received over signalling. See session/RoomSession.kt
     *  `annotations` and ui/room/ShareMarks.kt. Reset with the session in [closeSession]. */
    private var shareMarks = ShareMarks()
    private var marksTicker: Job? = null
    private var identity: RoomIdentity? = null
    private var roomSecret: ByteArray? = null
    private var roomInvitation: InvitationPayload? = null
    private var roomInvitationHost: RoomInvitationHost? = null
    private var invitationHostJob: Job? = null
    private var invitationAdmissionDesk: dev.forgesworn.kithmoot.session.TemporaryRoomAdmissionDesk? = null
    @Volatile private var nativeKeeperEntry: NativeKeeperEntry? = null
    @Volatile private var nativeKeeperController: NativeKeeperController? = null
    private var closingKeeper: Job? = null
    private var relayUrls: List<String> = emptyList()
    private var anonymousRoom: Boolean = false
    // This instance's share of AccountWriteHold: one for a Tor-only room being
    // entered (admission, publishing its group), one for its open session.
    private val torOnlyHoldLock = Any()
    private var torOnlyEntryHeld = false
    private var torOnlySessionHeld = false

    private fun holdForTorOnlyEntry() = synchronized(torOnlyHoldLock) {
        if (!torOnlyEntryHeld) { torOnlyEntryHeld = true; AccountWriteHold.process.torOnlyRoomOpened() }
    }

    private fun releaseTorOnlyEntry() = synchronized(torOnlyHoldLock) {
        if (torOnlyEntryHeld) { torOnlyEntryHeld = false; AccountWriteHold.process.torOnlyRoomClosed() }
    }
    private var opening: Job? = null
    /** One WebRTC engine per session, however many callers ask for one. */
    private val mediaBuild = SingleBuild<WebRtcEngine> { runCatching { it.stop() }; runCatching { it.dispose() } }

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
    private val entering = EntryGate()
    /** A Leave has been pressed and has not settled. Blocks the call self-heal. */
    @Volatile private var leavingCall = false
    /** The call this device last said it was on in this room, until Leave.
     *  What a rebuilt session rejoins while it cannot see the call yet - see
     *  [callToDeclare]. */
    @Volatile private var lastCallId: String? = null
    /** The room tap waiting behind a teardown, if any: always the latest one. */
    private val queuedEnter = LatestRequest<QueuedEnter>()
    /** The entry under way, a tap waiting for the last room to close, and the
     *  timer that offers a way out of either. See [stopOpening]. */
    private var entryJob: Job? = null
    private var entryWait: Job? = null
    private var stopOpeningTimer: Job? = null
    private val savedRooms = (application as KithMootApplication).savedRooms
    private val workspaceLock = Any()
    private var workspaceJob: Job? = null
    private var workspaceGeneration = 0L
    private val workspaceReaders = linkedMapOf<String, dev.forgesworn.kithmoot.session.WorkspaceActivityReader>()
    private val _workspace = MutableStateFlow(dev.forgesworn.kithmoot.session.WorkspaceSnapshot())
    val workspace = _workspace.asStateFlow()
    private val _workspaceOrigin = MutableStateFlow<dev.forgesworn.kithmoot.session.WorkspaceOrigin?>(null)
    val workspaceOrigin = _workspaceOrigin.asStateFlow()
    private val _workspaceProjectsRequest = MutableStateFlow(0)
    val workspaceProjectsRequest = _workspaceProjectsRequest.asStateFlow()
    fun requestWorkspaceProjects() { _workspaceProjectsRequest.update { it + 1 } }

    /** The foreground panel owns its readers. No room session or signer is
     * constructed; the canonical origin journals remain the only task store. */
    fun openWorkspaceActivity() {
        closeWorkspaceActivity()
        val account = _start.value.account?.pubkey ?: return
        val generation = synchronized(workspaceLock) { workspaceGeneration }
        _workspace.value = dev.forgesworn.kithmoot.session.WorkspaceSnapshot(account)
        fun publish(room: String? = null, activity: dev.forgesworn.kithmoot.session.WorkspaceActivitySnapshot? = null) = synchronized(workspaceLock) {
            if (workspaceGeneration == generation && _start.value.account?.pubkey == account) {
                if (room != null && activity != null) _workspace.update { snapshot -> snapshot.copy(rooms = snapshot.rooms.map {
                    if (it.room == room) it.copy(activity = activity) else it
                }) }
            }
        }
        workspaceJob = viewModelScope.launch(Dispatchers.IO) {
            launch { _start.map { it.account?.pubkey }.distinctUntilChanged().collect { next ->
                synchronized(workspaceLock) { if (next != account && workspaceGeneration == generation) closeWorkspaceActivity() }
            } }
            try {
                while (isActive) {
                    if (_start.value.account?.pubkey != account) break
                    val records = savedRooms.list().filter { it.account == account && it.openedAt > 0 &&
                        !it.anonymous && !it.ended && it.endsAt == null && !it.destruct }
                    val ids = records.map { it.id }.toSet()
                    synchronized(workspaceLock) {
                        if (workspaceGeneration != generation) return@launch
                        workspaceReaders.keys.filter { it !in ids }.forEach { workspaceReaders.remove(it)?.close() }
                        workspaceReaders.values.forEach { it.validate() }
                    }
                    val projects = _start.value.projects.projects.filter { it.joined && !it.archived && !it.conflicted && !it.withdrawn }
                    val rows = records.map { summary ->
                        val project = projects.firstOrNull { p -> p.definition?.get("rooms")?.jsonArray?.any {
                            it.jsonObject["room"]?.jsonPrimitive?.content == summary.id
                        } == true }
                        val record = savedRooms.get(summary.id)
                        val inbox = record?.let { dev.forgesworn.kithmoot.storage.BackgroundInboxVault(getApplication(), it.id,
                            it.participant, it.devicePubkey).inbox.state() }
                        val previous = _workspace.value.rooms.firstOrNull { it.room == summary.id }
                        dev.forgesworn.kithmoot.session.WorkspaceRoomActivity(summary.id, summary.name,
                            project?.key ?: summary.project, project?.name ?: summary.project,
                            previous?.activity ?: dev.forgesworn.kithmoot.session.WorkspaceActivitySnapshot(),
                            inbox ?: dev.forgesworn.kithmoot.session.BackgroundInbox.State(0, 0, emptyList(), emptyList()),
                            project?.definition?.get("members")?.jsonArray.orEmpty().map { member ->
                                val value = member.jsonObject
                                dev.forgesworn.kithmoot.session.Named(value.getValue("pubkey").jsonPrimitive.content,
                                    value["name"]?.jsonPrimitive?.content, value["kind"] == JsonPrimitive("agent"))
                            })
                    }
                    synchronized(workspaceLock) {
                        if (workspaceGeneration != generation) return@launch
                        _workspace.value = dev.forgesworn.kithmoot.session.WorkspaceSnapshot(account, rows)
                    }
                    for (summary in records) {
                        if (synchronized(workspaceLock) { summary.id in workspaceReaders }) continue
                        val record = savedRooms.get(summary.id) ?: continue
                        val stored = roomEpochs.get(record.id)
                        val eligible = record.workspaceAdmission(account, epochSeconds()) &&
                            !keepsOwnRelays(record) && (record.authority == null || stored != null) &&
                            (stored == null || stored.phase == EpochPhase.ACTIVE && stored.removed.none { it == account } &&
                                stored.currentEpoch >= (record.epochHint ?: 0))
                        if (!eligible) {
                            publish(record.id, dev.forgesworn.kithmoot.session.WorkspaceActivitySnapshot(error = "Open this room to check its current activity."))
                            continue
                        }
                        val epoch = stored?.currentEpoch ?: 0
                        val stable = deriveRoom(record.secret)
                        val root = deriveEpoch(RoomEpoch(epoch, stored?.currentSecret ?: record.secret))
                        val childScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
                        val pool = RelayPool(record.relays, OkHttpRelaySockets(), childScope, writeRelays = emptySet())
                        val vault = AssignmentVault(getApplication(), record.id, account)
                        val reader = dev.forgesworn.kithmoot.session.WorkspaceActivityReader(record.id, stable.roomKey, account, pool,
                            dev.forgesworn.kithmoot.session.AssignmentSource { vault.load() }, childScope, record.policy,
                            initialTrafficRoomId = root.id, initialTrafficRoomKey = root.key, epoch = epoch, authority = record.authority,
                            valid = {
                                val current = savedRooms.get(record.id)
                                val latest = roomEpochs.get(record.id)
                                _start.value.account?.pubkey == account && current?.workspaceAdmission(account, epochSeconds()) == true &&
                                    current.devicePubkey == record.devicePubkey && current.authority == record.authority &&
                                    current.policy == record.policy && current.relays == record.relays && (current.epochHint ?: 0) <= epoch &&
                                    !keepsOwnRelays(current) && (latest == null && stored == null || latest?.phase == EpochPhase.ACTIVE &&
                                        latest.currentEpoch == epoch && latest.removed.none { it == account })
                            }, onClosed = { event, destruct ->
                                viewModelScope.launch(Dispatchers.IO) {
                                    val current = savedRooms.get(record.id) ?: return@launch
                                    if (_start.value.account?.pubkey != account || current.participant != account) return@launch
                                    val latest = roomEpochs.get(record.id) ?: return@launch
                                    val previous = deriveEpoch(RoomEpoch(latest.currentEpoch, latest.currentSecret))
                                    val deviceKey = current.deviceSecretKey()
                                    val notice = try { dev.forgesworn.kithmoot.protocol.decodeRekeyEvent(event, current.id, current.authority ?: return@launch, previous, deviceKey) }
                                        finally { deviceKey.fill(0) }
                                    if (notice?.closed != true) return@launch
                                    roomEpochs.terminal(current.id, latest.currentEpoch, notice, event.id, epochSeconds())
                                    if (destruct) { savedRooms.update(current.id) { it.withDestruct() }; selfDestruct(current.id, force = true) }
                                }
                            }, release = { pool.stop(); childScope.cancel() })
                        stable.roomKey.fill(0); root.key.fill(0); stored?.currentSecret?.fill(0)
                        synchronized(workspaceLock) {
                            if (workspaceGeneration != generation) { reader.close(); return@launch }
                            workspaceReaders[record.id] = reader
                        }
                        launch { reader.state.collect { publish(record.id, it) } }
                        pool.start()
                        launch { reader.open() }
                    }
                    delay(5_000)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { synchronized(workspaceLock) {
                if (workspaceGeneration == generation) _workspace.value = dev.forgesworn.kithmoot.session.WorkspaceSnapshot(account,
                    error = "Saved activity could not be verified. Your existing data has been kept.")
                if (workspaceGeneration == generation) { workspaceReaders.values.forEach { it.close() }; workspaceReaders.clear() }
            }; coroutineContext.cancelChildren() }
        }
    }

    fun closeWorkspaceActivity() = synchronized(workspaceLock) {
        workspaceGeneration++; workspaceJob?.cancel(); workspaceJob = null
        workspaceReaders.values.forEach { it.close() }; workspaceReaders.clear()
        _workspace.value = dev.forgesworn.kithmoot.session.WorkspaceSnapshot()
    }

    /** All actions still pass the normal saved-room entry and credential gates. */
    fun openWorkspaceOrigin(target: dev.forgesworn.kithmoot.session.WorkspaceOrigin) {
        viewModelScope.launch(Dispatchers.IO) {
            val record = savedRooms.get(target.room)
            val owner = borrowedAccountOwner
            if (_start.value.account?.pubkey != target.account || record?.viaAccount != true || record.participant != target.account ||
                owner != null && (owner._start.value.account?.pubkey != target.account || owner.accountSession !== accountSession)) {
                showNotice("That room is no longer available to this account."); return@launch
            }
            _workspaceOrigin.update { previous -> target.copy(request = (previous?.request ?: 0) + 1) }
            if (_stage.value != Stage.ROOM || _room.value.roomId != target.room) reopenRoom(target.room)
        }
    }
    fun clearWorkspaceOrigin() { _workspaceOrigin.value = null }
    private var savedRoom: SavedRoom? = null
    /** Kept only in memory, discarded on room/account/session changes. */
    private var nip77Plan: Nip77ReconciliationPlan? = null

    // --- the Nostr account ---------------------------------------------------

    private val accounts = (application as KithMootApplication).accounts
    private val rendezvous = (application as KithMootApplication).rendezvous
    private val contacts = (application as KithMootApplication).contacts
    private val linkConsents = (application as KithMootApplication).linkConsents
    private val nip77Events = (application as KithMootApplication).nip77Events
    private val nip77Offers: Nip77OfferArchive = (application as KithMootApplication).nip77Offers
    private val linkEngine = (application as KithMootApplication).linkEngine
    private val cadenceClient: CadenceClient = (application as KithMootApplication).cadenceClient
    private val cadenceLeases: CadenceLeaseVault = (application as KithMootApplication).cadenceLeases
    private val roomEpochs: EpochVault = (application as KithMootApplication).roomEpochs
    private val roomMembers: dev.forgesworn.kithmoot.epoch.RoomMembers = (application as KithMootApplication).roomMembers
    private val display = application.getSharedPreferences("kithmoot.display", android.content.Context.MODE_PRIVATE)
    private val callVolume = CallVolume(SharedPreferencesVolumeStore(display))
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
    @Volatile private var retainedLegacyAccount: NostrAccount? = null
    private var sharedProjects: SharedProjects? = null
    /** A chat-only visitor reads the call instance's directory, without
     * opening another writer or taking ownership of its lifecycle. */
    private var borrowedAccountOwner: RoomViewModel? = null
    private var projectsScope: CoroutineScope? = null
    private var projectsLifecycle: Job? = null
    private var pendingProfile: NostrEvent? = null
    private var pendingRelayList: NostrEvent? = null
    private val relayHealthGate = Any()
    private val relayHealthSources = mutableMapOf<String, Map<String, RelayHealth>>()
    private fun reportRelayHealth(source: String, health: Map<String, RelayHealth>) = synchronized(relayHealthGate) {
        relayHealthSources[source] = health
        _start.update { it.copy(relayHealth = combinedRelayHealth(relayHealthSources.values)) }
    }
    private var roomBookmarks: RoomBookmarks? = null
    private var roomBookmarkScope: CoroutineScope? = null
    private var roomBookmarkLifecycle: Job? = null
    private val roomBookmarkEditing = Mutex()
    private val projectEditing = AtomicBoolean(false)
    private var accountScope: CoroutineScope? = null
    private val accountGate = Mutex()
    /** Serialises startup restore with replacement of a retained preview account. */
    private val accountStoreGate = Mutex()
    /** The Signet pairing under way: waiting on a relay for Signet to take up the invitation. */
    private var signetPairing: Job? = null

    /** Set by the activity: the only way a signer intent can be started and answered. */
    var signerBridge: Nip55Bridge? = null

    private val _browser = MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** URLs the activity should open in the browser: the Signet sign-in. */
    val browser: SharedFlow<String> = _browser.asSharedFlow()

    /** The person's signer, when signed in. What every new room is joined as. */
    private val accountSigner: ParticipantSigner? get() = accountSession?.signer

    /** The signed-in account's signer, for the VMLS boxes page's grants (P3-03b-3). */
    private var packGrant: Pair<ParticipantSigner, Long>? = null
    fun memberPackAvailable(): Boolean = packGrant?.let { it.first === accountSigner && it.second > System.currentTimeMillis() } == true
    suspend fun unlockMemberPacks(): Boolean {
        check(_room.value.route.internet) { "Choose an Internet connection to unlock member packs." }
        val actor = accountSigner ?: throw IllegalStateException("Connect your Nostr signer to unlock member packs.")
        val granted = dev.forgesworn.kithmoot.account.unlockCultPack(actor, registry = {
            withContext(Dispatchers.IO) {
                val client = okhttp3.OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).callTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build()
                client.newCall(okhttp3.Request.Builder().url(dev.forgesworn.kithmoot.account.CULT_REGISTRY).build()).execute().use { response ->
                    check(response.isSuccessful) { "The pack membership registry could not be reached. Try again." }
                    val body = checkNotNull(response.body)
                    require(body.contentLength() <= 128_000)
                    val bytes = body.byteStream().use { it.readNBytes(128_001) }
                    require(bytes.size <= 128_000)
                    bytes.toString(Charsets.UTF_8)
                }
            }
        })
        check(accountSigner === actor) { "The account changed. Try again." }
        packGrant = if (granted) actor to (System.currentTimeMillis() + 600_000) else null
        return granted
    }

    fun vmlsSigner(): ParticipantSigner? = accountSigner

    /** Where a VMLS room's link points, as today's invitation links do. */
    fun vmlsJoinBase(): String = selectedWebApp.joinBase

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val drafts = recordingApplication.recordingShareDrafts
                combine(room.map { it.roomId }.distinctUntilChanged(), drafts.revision) { roomId, _ -> roomId }
                    .collect { roomId ->
                        val retained = drafts.list(roomId)
                        _room.update { if (it.roomId == roomId) it.copy(recordingDrafts = retained) else it }
                    }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { note("Private recording drafts could not be opened: ${failure.message ?: "storage unavailable"}") }
        }
        if (!chatOnly) viewModelScope.launch {
            room.collect { value ->
                notifications.onCall = dev.forgesworn.kithmoot.ui.room.inACall(
                    onCall = value.onCall,
                    mediaRunning = value.mediaRunning,
                    micOn = value.micOn,
                    cameraOn = value.cameraOn,
                    screenOn = value.screenOn,
                )
                notifications.refresh()
            }
        }
        // The call as a self-managed Telecom call (see telecom/CallTelecom.kt),
        // following onCall so every way in and out of a call is covered:
        // Join, Answer, a remembered join, Leave, leaving the room.
        if (!chatOnly) viewModelScope.launch {
            room.map { it.onCall }.distinctUntilChanged().collect { onCall ->
                if (onCall) {
                    dev.forgesworn.kithmoot.telecom.CallTelecom.callJoined(getApplication(), _room.value.name)
                } else {
                    callHeld.value = false
                    micMutedForHold = false
                    dev.forgesworn.kithmoot.telecom.CallTelecom.callLeft()
                }
            }
        }
        if (!chatOnly) viewModelScope.launch {
            dev.forgesworn.kithmoot.telecom.CallTelecom.events.collect { event ->
                when (event) {
                    dev.forgesworn.kithmoot.telecom.CallTelecomEvent.HANG_UP -> leaveCall()
                    dev.forgesworn.kithmoot.telecom.CallTelecomEvent.HOLD -> holdCall()
                    dev.forgesworn.kithmoot.telecom.CallTelecomEvent.RESUME -> resumeCall()
                }
            }
        }
        refreshSavedRooms()
        // Home observes encrypted message receipts; opening/read timestamps
        // and relay subscription cursors are never conversation activity.
        viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.flow.combine(start, stage, dev.forgesworn.kithmoot.storage.BackgroundInboxVault.revision) { state, currentStage, revision ->
                Triple(state.account?.pubkey, if (currentStage == Stage.START) state.savedRooms.filter {
                    !it.anonymous && (it.account == null || it.account == state.account?.pubkey)
                } else emptyList(), revision)
            }.distinctUntilChanged().collectLatest { selection ->
                val times = selection.second.mapNotNull { summary ->
                    runCatching {
                        val record = savedRooms.get(summary.id) ?: return@runCatching null
                        if (record.anonymous || record.viaAccount && record.participant != selection.first) return@runCatching null
                        val time = dev.forgesworn.kithmoot.storage.BackgroundInboxVault(getApplication(), record.id, record.participant, record.devicePubkey).inbox.state().latestMessageAt
                        (record.id to time).takeIf { time > 0 }
                    }.getOrNull()
                }.toMap()
                _start.update { state ->
                    val eligible = if (stage.value == Stage.START) state.savedRooms.filter {
                        !it.anonymous && (it.account == null || it.account == state.account?.pubkey)
                    } else emptyList()
                    state.copy(latestMessageTimes = if (state.account?.pubkey == selection.first && eligible == selection.second) times else emptyMap())
                }
            }
        }
        // The list needs pictures before a conversation is opened. Keep the
        // same public-profile switch and account boundary as the room itself.
        viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.flow.combine(start, stage) { state, currentStage ->
                if (currentStage == Stage.START) privateChatProfileScope(state) else state.account?.pubkey to emptyList()
            }.distinctUntilChanged().collectLatest { selection ->
                _start.update { state -> state.copy(privateChatProfiles =
                    if (privateChatProfileScope(state) == selection) state.privateChatProfiles.filterKeys { it in selection.second }
                    else emptyMap()) }
                val authors = selection.second
                if (authors.isEmpty()) return@collectLatest
                kotlinx.coroutines.coroutineScope {
                    RoomRouteTransitions.stable {
                    // Re-read persisted routes after waiting for a mode change.
                    val selected = _start.value.copy(savedRooms = savedRooms.list())
                    if (privateChatProfileScope(selected) != selection || stage.value != Stage.START) return@stable
                    val scope = CoroutineScope(kotlin.coroutines.coroutineContext)
                    val transport = RelayPool(PROFILE_RELAYS, OkHttpRelaySockets(), scope, writeRelays = emptySet())
                    try {
                        transport.start()
                        kotlinx.coroutines.withTimeoutOrNull(10_000) {
                            transport.subscribe(listOf(Filter(kinds = listOf(0), authors = authors, limit = authors.size))).collect { event ->
                                val profile = decodePublicProfile(event, authors.toSet(), epochSeconds()) ?: return@collect
                                _start.update { state ->
                                    if (stage.value != Stage.START || privateChatProfileScope(state) != selection) state else {
                                        val old = state.privateChatProfiles[event.pubkey]
                                        if (old != null && (old.createdAt > profile.createdAt ||
                                            old.createdAt == profile.createdAt && old.eventId >= profile.eventId)) state
                                        else state.copy(privateChatProfiles = state.privateChatProfiles + (event.pubkey to profile))
                                    }
                                }
                            }
                        }
                    } finally { transport.stop() }
                    }
                }
            }
        }
        // Account sync and the other chat/call instance can learn a deadline
        // after this instance opened the room. Follow committed local lifetime
        // changes in every instance, even when a bookmark needs no further write.
        viewModelScope.launch(Dispatchers.IO) {
            savedRooms.revision.collect {
                try { reconcileOpenRoomLifetime() }
                catch (e: CancellationException) { throw e }
                catch (_: RoomStorageException) { storageFailed() }
            }
        }
        // Self-destructing rooms: those whose end came while this phone was
        // off go now, the rest when theirs comes; the heads-up at red; and the
        // tombstone rows, which age out by themselves.
        if (!chatOnly) viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                runCatching { runDueDestructs() }
                launch { runCatching { dev.forgesworn.kithmoot.storage.MediaUploadLedger(getApplication()).retry() } }
                launch { runCatching { recordingApplication.recordingUploadJournal.retry { origin, hash, auth ->
                    dev.forgesworn.kithmoot.session.deleteUploadedMedia(origin, hash, auth)
                } } }
                runCatching { sendDestructHeadsUps(epochSeconds()) }
                runCatching { destructTombstones.list(epochSeconds()) }.getOrNull()?.let { rows ->
                    if (rows != _start.value.destructTombstones) _start.update { it.copy(destructTombstones = rows) }
                }
                delay(DESTRUCT_CHECK_MS)
            }
        }
        // Which Ring me rooms cannot answer a call yet, kept current for the banner:
        // whenever the rooms or the account change, and as credentials run down.
        if (!chatOnly) viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.flow.merge(
                start.map { it.account?.pubkey to it.savedRooms }.distinctUntilChanged().map { },
                kotlinx.coroutines.flow.flow { while (true) { emit(Unit); delay(REACHABILITY_CHECK_MS) } },
            ).collect { runCatching { dev.forgesworn.kithmoot.service.CredentialRenewal.refresh(getApplication(), post = false) } }
        }
        // The call's instance owns the account, its sync and the box checks;
        // a chat-only one is handed the account by `borrowAccount`.
        if (!chatOnly) restoreAccount()
        if (!chatOnly) viewModelScope.launch {
            kotlinx.coroutines.yield()
            withContext(Dispatchers.IO) {
                while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                    runCatching { boxDiscovery.tick() }.onFailure { note("Box checks could not read the contact vault.") }
                    delay(30_000)
                }
            }
        }
    }

    /** Sign this chat-only instance in as `host` is, without opening a second
     *  signer connection or a second copy of the account's sync. */
    fun borrowAccount(host: RoomViewModel) {
        check(chatOnly) { "Only a chat-only instance borrows an account." }
        borrowedAccountOwner = host
        accountSession = host.accountSession
        signerBridge = host.signerBridge
        _start.update { it.copy(account = host._start.value.account) }
    }

    private var restoring: kotlinx.coroutines.Job? = null

    /**
     * Waits for the saved account to be opened at start-up, if it is being.
     * A call answered from a cold start reaches the room before the account
     * has: a room joined as the account cannot open without it.
     */
    suspend fun awaitAccountRestored() { restoring?.join() }

    private fun restoreAccount() {
        restoring = viewModelScope.launch(Dispatchers.IO) {
            accountStoreGate.withLock {
                if (accountSession != null) return@withLock
                val saved = try { accounts.load() } catch (_: RoomStorageException) { null } ?: return@withLock
                val bridge = signerBridge ?: LateBridge { signerBridge }
                try {
                    adopt(openAccount(saved, getApplication(), bridge, newAccountScope()), saved)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (saved.method == "local") {
                        retainedLegacyAccount = saved
                        _start.update { it.copy(retainedAccount = accountView(saved), signInError = null) }
                    } else {
                        _start.update { it.copy(signInError = e.message ?: "The saved account could not be opened.") }
                    }
                }
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

    /** Moves the vault's session epoch durably. In-process it cancels even when the write fails, and the store is reset, so the next start fails safe. */
    private fun endVaultSession() {
        try { getApplication<KithMootApplication>().vaultSessionEnd.end() } catch (_: Exception) { }
    }

    private fun newAccountScope(): CoroutineScope {
        accountScope?.cancel()
        return CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job])).also { accountScope = it }
    }

    private suspend fun adopt(session: AccountSession, account: NostrAccount) = accountGate.withLock {
        stopRoomBookmarks()
        stopSharedProjects()
        // Signing in over another session is an account change: cancel the old
        // one's vault work. A cold start has no session, so journals still replay.
        if (accountSession != null) endVaultSession()
        accountSession?.close()
        accountSession = session
        pendingProfile = null
        pendingRelayList = null
        synchronized(relayHealthGate) { relayHealthSources.clear() }
        val accountRelays = display.getString("relayChoices.${session.signer.pubkey}", null)
            ?.let { runCatching { RelaySelection.decode(it) }.getOrNull() }
        _start.update { it.copy(relayChoices = accountRelays.orEmpty(), profileMetadata = null, profileMessage = null, profileBusy = false,
            relays = accountRelays?.filter { choice -> choice.read || choice.write }?.joinToString("\n") { choice -> choice.url } ?: it.relays) }
        // A bunker creates this scope while opening its relay pool, whereas a
        // local NIP-55 signer has no transport to create one. The public
        // profile lookup and shared-project recovery need it in both cases.
        if (accountScope == null) newAccountScope()
        _start.update { it.copy(account = accountView(account), rendezvous = rendezvousView(account), retainedAccount = null, signingIn = false, signInError = null) }
        lookUpAccountProfile(account.pubkey)
        startSharedProjects(session)
        startRoomBookmarks(session)
        viewModelScope.launch(Dispatchers.IO) { recoverCircleGrantCleanup(account.pubkey, session.signer) }
    }

    private fun accountView(account: NostrAccount) = AccountView(
        pubkey = account.pubkey, npub = account.npub, short = shortNpub(account.pubkey), method = account.method,
        // The signer's name as the phone shows it; on a restore the sheet's list is not loaded yet, so ask the phone.
        signerLabel = account.signerPackage?.let { pkg ->
            (_start.value.signers.ifEmpty { installedSigners(getApplication()) }).firstOrNull { it.packageName == pkg }?.label ?: pkg
        },
        name = account.displayName,
    )

    private fun rendezvousView(account: NostrAccount): RendezvousView? {
        val clientKey = account.clientSecretKey ?: return null
        val device = runCatching { Schnorr.publicKeyHex(clientKey) }.getOrNull() ?: return null
        val active = runCatching { rendezvous.active(account.pubkey, device) }.getOrNull()
        return RendezvousView(activeIndex = active?.receipt?.index)
    }

    private suspend fun saveAndAdopt(session: AccountSession, account: NostrAccount) {
        accountStoreGate.withLock {
            try {
                val retained = retainedLegacyAccount ?: if (
                    getApplication<Application>().applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0
                ) accounts.load()?.takeIf { it.method == "local" } else null
                requireSameRetainedAccount(retained, account)
                accounts.save(account)
            } catch (e: Exception) {
                session.close()
                throw e
            }
            retainedLegacyAccount = null
            adopt(session, account)
        }
    }

    private suspend fun stopRoomBookmarks() {
        roomBookmarkScope?.cancel()
        roomBookmarkLifecycle?.join()
        roomBookmarks?.close()
        roomBookmarks = null; roomBookmarkScope = null; roomBookmarkLifecycle = null
        reportRelayHealth("rooms", emptyMap())
        _start.update { it.copy(roomBookmarks = RoomBookmarkSnapshot(), roomSyncBusy = false, roomSyncError = null, relayHealth = emptyMap()) }
    }

    private fun startRoomBookmarks(account: AccountSession) {
        if (!account.signer.canEncrypt) {
            _start.update { it.copy(roomBookmarks = RoomBookmarkSnapshot(error = "Your signer needs private-data support to sync chats and rooms.")) }; return
        }
        val relays = try { parseRelays(_start.value.relays).ifEmpty { DEFAULT_RELAYS } }
        catch (_: Exception) { _start.update { it.copy(roomBookmarks = RoomBookmarkSnapshot(error = "Check Relay settings, then retry room sync.")) }; return }
        val scope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]) + Dispatchers.IO)
        val pool = RelayPool(relays, OkHttpRelaySockets(), scope, readRelays = selectedReadRelays(relays), writeRelays = selectedWriteRelays(relays))
        val bookmarks = RoomBookmarks(account.signer, pool, RoomBookmarkVault(getApplication(), account.signer.pubkey), scope,
            beforePublish = AccountWriteHold.process::awaitReleased,
            onTombstone = { roomId -> if (!chatOnly) viewModelScope.launch(Dispatchers.IO) { bookmarkTombstoned(roomId) } })
        roomBookmarks = bookmarks; roomBookmarkScope = scope
        _start.update { it.copy(roomBookmarks = RoomBookmarkSnapshot(syncing = true), roomSyncError = null) }
        roomBookmarkLifecycle = scope.launch {
            pool.start()
            val healthObserver = launch { pool.health.collect { value -> if (roomBookmarks === bookmarks) reportRelayHealth("rooms", value) } }
            val observer = launch { bookmarks.state.collect { value ->
                if (roomBookmarks === bookmarks) {
                    _start.update { it.copy(roomBookmarks = value) }
                    reconcileBookmarkedLifetimes(bookmarks, value)
                }
            } }
            // Once the account's bookmarks have loaded, give any room it already
            // lists the secret this phone holds for it, so another device can open it.
            val sharing = launch {
                bookmarks.state.first { it.ready || it.error != null }
                if (roomBookmarks === bookmarks && bookmarks.state.value.ready) changeRoomBookmarks { shareGroupAdmissions(it) }
            }
            try { bookmarks.open(); kotlinx.coroutines.awaitCancellation() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (roomBookmarks === bookmarks) _start.update { it.copy(roomBookmarks = bookmarks.state.value) } }
            finally { withContext(NonCancellable) { bookmarks.close(); pool.stop(); observer.cancel(); healthObserver.cancel(); sharing.cancel() } }
        }
    }

    /** A locally saved room must learn an expiry that another device synced later. */
    private suspend fun reconcileBookmarkedLifetimes(bookmarks: RoomBookmarks, snapshot: RoomBookmarkSnapshot) {
        if (!snapshot.ready || bookmarks.identity != accountSigner?.pubkey) return
        var changed = false
        gate.withLock {
            if (roomBookmarks !== bookmarks || bookmarks.identity != accountSigner?.pubkey) return@withLock
            for (bookmark in snapshot.rooms) {
                val saved = savedRooms.get(bookmark.roomId) ?: continue
                val learned = saved.learnBookmarkLifetime(bookmark, bookmarks.identity)
                if (learned === saved) continue
                val updated = savedRooms.update(saved.id) { it.learnBookmarkLifetime(bookmark, bookmarks.identity) } ?: continue
                changed = true
                if (savedRoom?.id == updated.id) {
                    savedRoom = updated
                    _room.update { if (it.roomId == updated.id) it.copy(endsAt = updated.ends, destruct = updated.destruct, startsAt = updated.startsAt) else it }
                    val live = session
                    val scope = sessionScope
                    if (live != null && scope != null && updated.ends != saved.ends) updated.ends?.let { end ->
                        live.learnRoomEnd(end)
                        endConferenceAt(live, scope, end, updated.id)
                    }
                }
            }
        }
        if (changed) { refreshSavedRooms(); runDueDestructs() }
    }

    fun refreshRoomBookmarks() { AccountWriteHold.process.personActed(); viewModelScope.launch(Dispatchers.IO) { accountGate.withLock {
        val account = accountSession ?: return@withLock
        stopRoomBookmarks(); startRoomBookmarks(account)
        val bookmarks = roomBookmarks ?: return@withLock
        roomBookmarkScope?.launch {
            bookmarks.state.first { it.ready || it.error != null }
            if (bookmarks.state.value.ready) bookmarks.retry()
        }
    } } }

    private fun changeRoomBookmarks(action: suspend (RoomBookmarks) -> Unit) {
        val bookmarks = roomBookmarks ?: return
        val scope = roomBookmarkScope ?: return
        scope.launch { roomBookmarkEditing.withLock {
            if (roomBookmarks !== bookmarks) return@withLock
            _start.update { it.copy(roomSyncBusy = true, roomSyncError = null) }
            try { action(bookmarks) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (roomBookmarks === bookmarks) _start.update {
                it.copy(roomSyncError = "This room change could not be synced. Your rooms on this phone have been kept. Check your signer and retry.")
            } }
            finally { if (roomBookmarks === bookmarks) _start.update { it.copy(roomSyncBusy = false) } }
        } }
    }

    private fun accountBookmark(room: SavedRoom, account: String): AccountRoom? {
        if (!room.viaAccount || room.participant != account || room.anonymous || room.secondary || room.retired || room.movedOn) return null
        return try { RoomBookmarks.validateLink(room.joinUrl, room.id)
            AccountRoom(room.id, room.joinUrl, room.name, room.openedAt, groupAdmission(room), room.ends, room.destruct, room.startsAt)
        } catch (_: Exception) { null }
    }

    /** The secret of a group this phone has joined, for the account's bookmark.
     *  Persistent groups only: they carry no delegated, expiring permission. */
    private fun groupAdmission(room: SavedRoom): String? =
        if (room.invitation?.invitation?.persistent == true) room.secret.toHex() else null

    /** Rooms already bookmarked without their secret get it once, so a new
     *  device can open them. Never adds a room to the account on its own. */
    private suspend fun shareGroupAdmissions(bookmarks: RoomBookmarks) {
        val listed = bookmarks.state.value.rooms.associateBy { it.roomId }
        for (summary in savedRooms.list()) {
            val bookmarked = listed[summary.id] ?: continue
            if (bookmarked.admission != null) continue
            val saved = savedRooms.get(summary.id) ?: continue
            val mine = accountBookmark(saved, bookmarks.identity) ?: continue
            // The bookmark's own link, name and time stay as they are: only the secret is added.
            if (mine.admission != null) bookmarks.save(bookmarked.copy(admission = mine.admission))
        }
    }

    fun importAccountRooms() {
        AccountWriteHold.process.personActed()
        changeRoomBookmarks { bookmarks ->
            for (summary in savedRooms.list()) {
                val saved = savedRooms.get(summary.id) ?: continue
                accountBookmark(saved, bookmarks.identity)?.let { bookmarks.save(it) }
            }
        }
    }

    fun removeAccountRoom(roomId: String) { AccountWriteHold.process.personActed(); changeRoomBookmarks { it.remove(roomId) } }

    /** The account record is only a locator. Verify admission before saving or opening any room. */
    fun openAccountRoom(room: AccountRoom) = enter(label = room.name?.takeIf { it.isNotBlank() } ?: "That conversation",
        opening = openingLine(room.name, "the conversation")) {
        val bookmarks = roomBookmarks ?: throw RoomRecoveryException("Sign in to open this conversation.")
        val actor = accountSigner ?: throw RoomRecoveryException("Sign in to open this conversation.")
        fun checkSelection() {
            check(roomBookmarks === bookmarks && accountSigner === actor && bookmarks.identity == actor.pubkey) { "The signed-in account changed." }
            check(bookmarks.state.value.rooms.any { it.roomId == room.roomId && it.link == room.link }) { "This conversation changed. Choose it again." }
        }
        checkSelection(); RoomBookmarks.validateLink(room.link, room.roomId)
        val saved = savedRooms.get(room.roomId)?.let { existing ->
            val learned = existing.learnBookmarkLifetime(room, actor.pubkey)
            if (learned !== existing) savedRooms.update(room.roomId) { it.learnBookmarkLifetime(room, actor.pubkey) } else existing
        }
        if (saved != null) {
            check(saved.participant == actor.pubkey) { "This room is saved under another identity. Open it with that identity first." }
            val who = savedIdentity(saved); checkSelection()
            open(deriveRoom(saved.secret), saved.secret, savedRoomRelays(saved, room.link), who, saved.secondary, saved.joinUrl,
                saved.invitation, saved.host(epochSeconds()), saved.policy, saved)
        } else {
            val invitation = decodeInvitationUrl(room.link)
            val legacy = if (invitation == null) decodeJoinUrl(room.link) else null
            val relays = (invitation?.relays ?: legacy!!.relays).ifEmpty { parseRelays(_start.value.relays).ifEmpty { DEFAULT_RELAYS } }
            check(!anonymousFor(relays)) { "Anonymous rooms stay on their original device." }
            // A group joined on another device lets this one in by the secret
            // its bookmark carries; the signed invitation may be long gone.
            val synced = invitation?.let { syncedGroupFor(it.invitation) }?.takeIf { it.room.roomId == room.roomId }
            var foundFurther = emptyList<String>()
            val admission = invitation?.let { synced?.admission ?: requestAdmission(it, relays) { found -> foundFurther = found } ?: throw RoomRecoveryException("Access could not be restored. Keep another member online and try again.") }
            val secret = admission?.secret ?: legacy!!.secret
            val derived = deriveRoom(secret)
            try { check(derived.roomId == room.roomId) { "This invitation admitted a different room." }; checkSelection() }
            catch (e: Exception) { secret.fill(0); throw e }
            val at = epochSeconds()
            val who = PrimaryIdentity.createWith(actor, derived.roomId, at + CREDENTIAL_TTL_SECONDS, at)
            checkSelection()
            val localUrl = selectedWebApp.joinBase + "#" + room.link.substringAfter('#')
            open(derived, secret, relays + foundFurther, who, false, localUrl, invitation, admission?.delegate,
                invitation?.policy ?: legacy?.policy, localName = room.label, ends = admission?.endsAt, expectedEpoch = admission?.epoch,
                destruct = admission?.destruct == true, startsAt = room.startsAt)
        }
    }

    private suspend fun stopSharedProjects() {
        projectsScope?.cancel()
        projectsLifecycle?.join()
        sharedProjects?.close()
        sharedProjects = null; projectsScope = null; projectsLifecycle = null
        reportRelayHealth("projects", emptyMap())
    }

    private fun startSharedProjects(account: AccountSession) {
        if (!account.signer.canEncrypt) {
            _start.update { it.copy(projects = ProjectAccountSnapshot(error = "Your signer needs private-data support to sync projects.")) }; return
        }
        val relays = try { parseRelays(_start.value.relays).ifEmpty { DEFAULT_RELAYS } }
        catch (_: Exception) { _start.update { it.copy(projects = ProjectAccountSnapshot(error = "Check your relay settings, then sync projects again.")) }; return }
        val scope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]) + Dispatchers.IO)
        projectsScope = scope
        val pool = RelayPool(relays, OkHttpRelaySockets(), scope, readRelays = selectedReadRelays(relays), writeRelays = selectedWriteRelays(relays))
        val directory = SharedProjects(account.signer, pool, ProjectVault(getApplication(), account.signer.pubkey), scope,
            beforePublish = AccountWriteHold.process::awaitReleased)
        sharedProjects = directory
        _start.update { it.copy(projects = ProjectAccountSnapshot(syncing = true), projectError = null) }
        projectsLifecycle = scope.launch {
            pool.start()
            val healthObserver = launch { pool.health.collect { value -> if (sharedProjects === directory) reportRelayHealth("projects", value) } }
            val observer = launch { directory.state.collect { value ->
                if (sharedProjects === directory) _start.update { it.copy(projects = value) }
            } }
            try { directory.open(); kotlinx.coroutines.awaitCancellation() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (sharedProjects === directory) _start.update { it.copy(projects = directory.state.value) } }
            finally { withContext(NonCancellable) { directory.close(); pool.stop(); observer.cancel(); healthObserver.cancel() } }
        }
    }


    fun refreshSharedProjects() {
        AccountWriteHold.process.personActed()
        viewModelScope.launch(Dispatchers.IO) { accountGate.withLock {
            val account = accountSession ?: return@withLock
            stopSharedProjects(); startSharedProjects(account)
        } }
    }

    private suspend fun projectChange(action: suspend (SharedProjects) -> Unit): Boolean {
        val directory = sharedProjects ?: return false
        val scope = projectsScope ?: return false
        if (!projectEditing.compareAndSet(false, true)) return false
        AccountWriteHold.process.personActed()
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
        fun readingDirectory() = sharedProjects ?: borrowedAccountOwner?.takeIf {
            it._start.value.account?.pubkey == _start.value.account?.pubkey && it.accountSession === accountSession
        }?.sharedProjects
        val directory = readingDirectory() ?: throw RoomRecoveryException("Sign in to open this project.")
        val actor = accountSigner ?: throw RoomRecoveryException("Sign in to open this project.")
        fun checkSelection() {
            if (readingDirectory() !== directory || accountSigner !== actor) throw RoomRecoveryException("The signed-in account changed.")
            selectedProjectRoom(directory.state.value, project.reference, room.room, project.authority)
        }
        checkSelection()
        val selected = selectedProjectRoom(directory.state.value, project.reference, room.room, project.authority)
        val saved = savedRooms.get(room.room)
        if (saved != null) {
            if (saved.participant != actor.pubkey) throw RoomRecoveryException("This room is saved under another identity. Open it there, or forget it before joining with this account.")
            val who = savedIdentity(saved)
            val derived = deriveRoom(saved.secret)
            checkProjectRoomAdmission(room.room, derived.roomId); checkSelection()
            open(derived, saved.secret, savedRoomRelays(saved, selected.link), who, saved.secondary, saved.joinUrl, saved.invitation, saved.host(epochSeconds()), saved.policy, saved)
        } else {
            val invitation = decodeInvitationUrl(selected.link) ?: throw RoomRecoveryException("This project needs a persistent room invitation.")
            if (!invitation.invitation.persistent || decodeInvitationPairingLink(selected.link) != null) throw RoomRecoveryException("This is not a project room invitation.")
            val relays = invitation.relays.ifEmpty { parseRelays(_start.value.relays).ifEmpty { DEFAULT_RELAYS } }
            var foundFurther = emptyList<String>()
            val admission = requestAdmission(invitation, relays) { foundFurther = it } ?: throw RoomRecoveryException(INVITATION_NOT_FOUND)
            val derived = deriveRoom(admission.secret)
            try { checkProjectRoomAdmission(room.room, derived.roomId); checkSelection() }
            catch (e: Exception) { admission.secret.fill(0); throw e }
            val at = epochSeconds()
            val who = try {
                PrimaryIdentity.createWith(actor, derived.roomId, at + CREDENTIAL_TTL_SECONDS, at).also { checkSelection() }
            } catch (e: Exception) { admission.secret.fill(0); throw e }
            open(derived, admission.secret, relays + foundFurther, who, false, encodeInvitationUrl(selectedWebApp.joinBase, invitation.invitation, relays, invitation.policy),
                invitation, admission.delegate, invitation.policy, localName = selected.name, ends = admission.endsAt, expectedEpoch = admission.epoch,
                destruct = admission.destruct)
        }
    }

    /** The person's own kind 0, from the public profile relays, so the account line carries their name and picture. */
    private fun lookUpAccountProfile(pubkey: String) {
        val scope = accountScope ?: return
        scope.launch {
            val profileRelays = accountRelayChoices().filter { it.read }.map { it.url }
            val pool = RelayPool(profileRelays, OkHttpRelaySockets(), scope, writeRelays = emptySet())
            pool.start()
            try {
                kotlinx.coroutines.withTimeoutOrNull(10_000) {
                    pool.subscribe(listOf(Filter(kinds = listOf(0), authors = listOf(pubkey), limit = 1))).collect { event ->
                        val profile = decodePublicProfile(event, setOf(pubkey), epochSeconds()) ?: return@collect
                        _start.update { state ->
                            val account = state.account?.takeIf { it.pubkey == pubkey } ?: return@update state
                            val old = account.profile
                            if (old != null && (old.createdAt > profile.createdAt || old.createdAt == profile.createdAt && old.eventId <= profile.eventId)) state else state.copy(account = account.copy(profile = profile))
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
        AccountWriteHold.process.personActed()
        if (_start.value.signingIn) return
        _start.update { it.copy(signingIn = true, signInError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val (session, account) = block()
                saveAndAdopt(session, account)
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
        AccountWriteHold.process.personActed()
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
                saveAndAdopt(AccountSession(account, BunkerSigner(pubkey, client, onClose = pool::stop)), account)
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
        AccountWriteHold.process.personActed()
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

    /** Synthetic local accounts exist only for installed debug-test fixtures. */
    internal fun installLocalTestAccount(key: ByteArray, emulateExternalSigner: Boolean = false) {
        check(getApplication<Application>().applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            "Local test accounts are unavailable in release builds"
        }
        require(key.size == 32) { "A test key is 32 bytes" }
        signIn {
            val local = LocalSigner(key)
            val signer: ParticipantSigner = if (emulateExternalSigner) object : ParticipantSigner by local {} else local
            val account = NostrAccount(signer.pubkey, "local", secretKey = key, signedInAt = epochSeconds())
            AccountSession(account, signer) to account
        }
    }

    fun signOutFromAccountMenu() {
        if (_stage.value == Stage.START) { signOut(); return }
        viewModelScope.launch {
            leave()
            stage.first { it == Stage.START }
            start.first { !it.busy }
            signOut()
        }
    }

    fun signOut() {
        if (_stage.value != Stage.START) { note("Leave the room before signing out."); return }
        val requestedSession = accountSession
        viewModelScope.launch(Dispatchers.IO) {
            val acquired = entering.awaitAcquire(ENTRY_GATE_WAIT_MS)
            if (!acquired) {
                _start.update { it.copy(error = "The room is still closing. Try signing out again shortly.") }
                return@launch
            }
            try {
                var signedOut = false
                accountStoreGate.withLock {
                    if (!signOutStillTargets(requestedSession, accountSession, _stage.value, session != null)) {
                        _start.update { it.copy(error = "The room or account changed. Sign out again from the current account.") }
                        return@withLock
                    }
                    val signedOutAccount = accountSession?.account?.pubkey
                    // First, before anything is released: queued VMLS work for this
                    // account must find the session already over (§6.2).
                    endVaultSession()
                    // Finish every pending write and clear its journal before
                    // releasing the account identity that owns it.
                    signedOutAccount?.let { account ->
                        try {
                            savedRooms.list().filter { it.account == account }.forEach { room ->
                                savedRooms.get(room.id)?.let { saved ->
                                    dev.forgesworn.kithmoot.storage.PendingChatVault(getApplication(), saved.id,
                                        saved.participant, saved.devicePubkey).outbox.clear()
                                }
                            }
                        } catch (_: RoomStorageException) { /* Unreadable rooms: nothing to clear. */ }
                    }
                    accountGate.withLock {
                        stopRoomBookmarks()
                        stopSharedProjects()
                        accountSession?.close()
                        synchronized(relayHealthGate) { relayHealthSources.clear(); _start.update { it.copy(relayHealth = emptyMap()) } }
                        accountSession = null
                        accountScope?.cancel()
                        accountScope = null
                    }
                    try { accounts.clear() } catch (_: RoomStorageException) { /* Nothing was saved. */ }
                    signedOutAccount?.let { account ->
                        try { rendezvous.clear(account) } catch (_: RoomStorageException) { /* Best-effort local cleanup. */ }
                        try { nip77Events.clear(account) } catch (_: RoomStorageException) { /* Best-effort local cleanup. */ }
                        try { nip77Offers.clear(account) } catch (_: RoomStorageException) { /* Best-effort local cleanup. */ }
                    }
                    retainedLegacyAccount = null
                    signedOut = true
                }
                if (signedOut) _start.update { it.copy(account = null, rendezvous = null, retainedAccount = null,
                    signInError = null, signingIn = false, projects = ProjectAccountSnapshot(), projectError = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _start.update { it.copy(error = error.message ?: "Sign out could not finish. Your saved data was kept.") }
            } finally {
                entering.release()
            }
        }
    }

    /**
     * Start one explicit person-device ceremony. The index is deliberately
     * supplied by the owner: this phone cannot infer the person's active root
     * index from its own local vault. Heartwood still requires an exact
     * physical approval for the request it sees over the relay.
     */
    fun provisionRendezvous(index: Long) {
        if (_stage.value != Stage.START || _start.value.signingIn || _start.value.busy) return
        if (index !in 0..0xffffffffL) {
            _start.update { it.copy(rendezvous = it.rendezvous?.copy(message = "Choose a rendezvous index from 0 to 4294967295.")) }
            return
        }
        val session = accountSession ?: run {
            _start.update { it.copy(rendezvous = it.rendezvous?.copy(message = "Sign in to the Heartwood bunker first.")) }
            return
        }
        val account = session.account
        val signer = session.signer as? BunkerSigner ?: run {
            _start.update { it.copy(rendezvous = it.rendezvous?.copy(message = "Rendezvous setup needs a compatible Heartwood bunker connection.")) }
            return
        }
        val clientKey = account.clientSecretKey ?: run {
            _start.update { it.copy(rendezvous = it.rendezvous?.copy(message = "This bunker connection has no retained device key.")) }
            return
        }
        val device = runCatching { Schnorr.publicKeyHex(clientKey) }.getOrElse {
            _start.update { it.copy(rendezvous = it.rendezvous?.copy(message = "This bunker device key is invalid.")) }
            return
        }
        val nonce = Entropy.bytes(16)
        // Leave 120 seconds for ordinary clock skew and the owner's physical
        // approval, while staying below Heartwood's hard ten-minute ceiling.
        val expiresAt = epochSeconds() + RENDEZVOUS_PROVISION_MAX_SECONDS - 120
        _start.update { it.copy(rendezvous = (it.rendezvous ?: RendezvousView()).copy(busy = true, message = "Approve this exact device and index on Heartwood now…")) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = signer.provisionRendezvous(index, nonce, expiresAt)
                val result = accountGate.withLock {
                    if (accountSession !== session) throw SignerException("The signed-in account changed before the rendezvous child arrived.")
                    rendezvous.accept(response, RendezvousProvisionExpect(account.pubkey, device, nonce, epochSeconds()), clientKey)
                }
                when (result) {
                    is RendezvousVaultResult.Accepted -> _start.update {
                        it.copy(rendezvous = RendezvousView(activeIndex = result.receipt.index, message = "Rendezvous child for index ${result.receipt.index} is protected on this phone."))
                    }
                    is RendezvousVaultResult.Refused -> _start.update {
                        it.copy(rendezvous = (it.rendezvous ?: RendezvousView()).copy(busy = false, message = "Heartwood's response was refused: ${result.reason}. Nothing was stored."))
                    }
                }
            } catch (error: Exception) {
                _start.update {
                    it.copy(rendezvous = (it.rendezvous ?: RendezvousView()).copy(busy = false, message = when (error) {
                        is SignerException -> error.message ?: "Heartwood refused the rendezvous request."
                        else -> "Rendezvous setup did not complete. Nothing was stored."
                    }))
                }
            } finally {
                nonce.fill(0)
            }
        }
    }

    fun dismissSignInError() { _start.update { it.copy(signInError = null) } }

    /** The GL context the renderers share. Null until the media stack is up. */
    val eglBase: EglBase? get() = engine?.eglBase

    // --- self-destruct -------------------------------------------------------
    //
    // A room whose group invitation or closing rekey says `destruct` (fold-kit
    // 0.9.0) is tidied away on this device when it ends, without asking: by its
    // time while open, at the next start when this phone was off, or when its
    // closing rekey arrives. The design's order: leave; ask the room's relays
    // to delete what this device's key signed (NIP-09), which needs that key,
    // so the saved room stays until then; tombstone the account's bookmark;
    // wipe everything this phone keeps for the room ([RoomWipeStep]); leave a
    // tombstone row that names no room. What it cannot do, it says nothing
    // about: copies others kept, and relays that ignore deletion.

    private val destructor: dev.forgesworn.kithmoot.service.RoomSelfDestructor = (application as KithMootApplication).selfDestructor
    private val _destructEffect = MutableStateFlow(0L)
    val destructEffect: StateFlow<Long> = _destructEffect

    private val destructTombstones get() = destructor.tombstones

    /** Out of [roomId] on this screen, and off its call, saying it self-destructed. */
    private suspend fun leaveForDestruct(roomId: String) {
        try { recordingApplication.forgetRecordingsForRoom(roomId) }
        catch (failure: Exception) {
            // Metadata/storage failure still revokes in-memory retention and
            // must not leave the destroyed room's capture running locally.
            if (_room.value.roomId == roomId) stopNativeRecording()
            throw failure
        }
        gate.withLock {
            if (savedRoom?.id != roomId) return@withLock
            val live = session
            _videos.value = emptyMap()
            try { live?.leave() } catch (e: CancellationException) { throw e } catch (_: Exception) { } finally { closeSession() }
        }
        closingKeeper?.join()
        if (_room.value.roomId == roomId) {
            _room.value = RoomState(background = backgrounds.load())
            _stage.value = Stage.START
            _start.update { it.copy(notice = dev.forgesworn.kithmoot.session.SELF_DESTRUCTED_MESSAGE, error = null) }
        }
    }

    /**
     * Tidy [roomId] away on this device, if it self-destructs and its end has
     * come ([force]: another of the person's devices already tidied it away).
     * Leaving happens in any instance showing the room; the rest only in the
     * call's instance, which owns the account. The tidy-up itself is
     * [dev.forgesworn.kithmoot.service.RoomSelfDestructor], shared with the
     * background service.
     */
    private suspend fun selfDestruct(roomId: String, force: Boolean = false) {
        val saved = runCatching { savedRooms.get(roomId) }.getOrNull() ?: return
        if (!saved.destruct || !(force || destructor.due(saved))) return
        if (!destructor.claim(roomId)) return
        val wasOnScreen = _room.value.roomId == roomId
        try {
            withContext(NonCancellable) { leaveForDestruct(roomId) }
            if (chatOnly) return
            val outcome = destructor.run(roomId, force = force, claimed = true, inboxQueue = backgroundInboxWrites) { room, delete ->
                // The room's own relays are read and written whatever this phone's relay choices say.
                withGroupRelays(dev.forgesworn.kithmoot.service.RoomSelfDestructor.destructRelays(room), room.anonymous, room.sharedRelays.toSet()) { delete(it) }
            }
            if (outcome is dev.forgesworn.kithmoot.service.RoomSelfDestructor.Outcome.Done) {
                if (wasOnScreen && outcome.left.isEmpty()) _destructEffect.update { it + 1 }
                sendOwedBookmarkTombstones()
            }
            val rooms = runCatching { savedRooms.list() }.getOrNull()
            _start.update { state -> state.copy(savedRooms = rooms ?: state.savedRooms, destructTombstones = destructTombstones.list(epochSeconds())) }
        } finally {
            destructor.release(roomId)
        }
    }

    /**
     * The person's other devices learn a room is gone from the account's
     * bookmark tombstone, even if they missed its end. Sent for every room
     * tidied away here or by the background service, once the account's
     * bookmarks are loaded; a room the account never listed owes nothing.
     */
    private fun sendOwedBookmarkTombstones() {
        val bookmarks = roomBookmarks ?: return
        val snapshot = bookmarks.state.value
        if (!snapshot.ready) return
        for (roomId in destructor.owedBookmarks(bookmarks.identity)) {
            if (snapshot.rooms.any { it.roomId == roomId }) changeRoomBookmarks {
                it.remove(roomId)
                destructor.bookmarkTombstoned(bookmarks.identity, roomId)
            } else destructor.bookmarkTombstoned(bookmarks.identity, roomId)
        }
    }

    /**
     * The account's bookmarks say a room was removed. A self-destructing room
     * this phone still holds goes too: another of the person's devices tidied
     * it away, perhaps at an end this phone missed. Any other room is left
     * exactly as it was ([wipesOnBookmarkTombstone]).
     */
    private suspend fun bookmarkTombstoned(roomId: String) {
        val saved = runCatching { savedRooms.get(roomId) }.getOrNull()
        if (!wipesOnBookmarkTombstone(saved)) return
        selfDestruct(roomId, force = true)
    }

    /**
     * The room on screen was closed by its authority. When it self-destructs,
     * by its invitation or by the closing rekey, it goes. A device that missed
     * the rekey (told by the authority's refusal, which carries no flag) reads
     * the closing rekey from the room's relays with the key of the epoch it
     * left; found only when it was one epoch behind at most and while relays
     * keep it. Not found: the room just ends, as it always did.
     */
    private suspend fun roomClosed(roomId: String) {
        val saved = runCatching { savedRooms.get(roomId) }.getOrNull() ?: return
        if (!saved.destruct) {
            if (saved.invitation?.invitation?.persistent != true || !closingRekeyDestructs(saved)) return
            runCatching { savedRooms.update(roomId) { it.withDestruct() } }
        }
        selfDestruct(roomId)
    }

    private suspend fun closingRekeyDestructs(saved: SavedRoom): Boolean {
        val authority = saved.authority ?: return false
        val stored = runCatching { roomEpochs.get(saved.id) }.getOrNull() ?: return false
        val previous = deriveEpoch(RoomEpoch(stored.currentEpoch, stored.currentSecret))
        val filter = Filter(kinds = listOf(KIND_ROOM_REKEY), authors = listOf(authority), tags = mapOf("#d" to listOf(saved.id)), limit = 50)
        val events = try {
            withGroupRelays(dev.forgesworn.kithmoot.service.RoomSelfDestructor.destructRelays(saved), saved.anonymous, saved.sharedRelays.toSet()) { it.queryAvailable(listOf(filter), 8_000) }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
        return events.any { readRekeyEvidence(it, saved.id, authority, previous.epoch, previous.key)?.destruct == true }
    }

    private val destructSweep = Mutex()

    /** Tidy away every self-destructing room whose end has come. One sweep at a time. */
    private suspend fun runDueDestructs() {
        if (!destructSweep.tryLock()) return
        try {
            val now = epochSeconds()
            val due = runCatching { savedRooms.list() }.getOrDefault(emptyList()).filter { it.destruct && !destructor.waiting(it.id, now) }
            for (summary in due) selfDestruct(summary.id)
            sendOwedBookmarkTombstones()
        } finally { destructSweep.unlock() }
    }

    /** The heads-up at red, once per room whoever sends it; not for the room on screen, which has its pill. */
    private fun sendDestructHeadsUps(now: Long) {
        destructor.sendHeadsUps(now) { notifications.foreground && _room.value.roomId == it }
    }

    /** Remove one self-destructed room's row. */
    fun dismissTombstone(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            destructTombstones.dismiss(id)
            _start.update { it.copy(destructTombstones = destructTombstones.list(epochSeconds())) }
        }
    }

    // --- start screen --------------------------------------------------------

    private suspend fun reconcileOpenRoomLifetime() = gate.withLock {
        val current = savedRoom ?: return@withLock
        val stored = savedRooms.get(current.id) ?: return@withLock
        if (stored.participant != current.participant || stored.devicePubkey != current.devicePubkey) return@withLock
        val learned = current.withRoomLifetime(stored.ends, stored.destruct, stored.startsAt)
        savedRoom = learned
        _room.update { state ->
            if (state.roomId != learned.id) state
            else state.copy(endsAt = learned.ends, destruct = learned.destruct, startsAt = learned.startsAt)
        }
        val live = session
        val scope = sessionScope
        if (live != null && scope != null && learned.ends != current.ends) learned.ends?.let { end ->
            live.learnRoomEnd(end)
            endConferenceAt(live, scope, end, learned.id)
        }
    }

    fun onRoomNameChanged(value: String) {
        _start.update { it.copy(roomName = value.take(80)) }
    }

    fun refreshSavedRooms() {
        _start.update { it.copy(loadingRooms = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                recoverLinkActivation()
                accountSession?.let { session ->
                    // Leaving a Tor-only room must not publish the account's revocations at that moment.
                    if (!AccountWriteHold.process.isHeld) recoverCircleGrantCleanup(session.account.pubkey, session.signer)
                    else viewModelScope.launch(Dispatchers.IO) {
                        AccountWriteHold.process.awaitReleased()
                        if (accountSession === session) recoverCircleGrantCleanup(session.account.pubkey, session.signer)
                    }
                }
                val rooms = savedRooms.list()
                val pendingNative = NativeRoomCreation.open(getApplication(), savedRooms).use { it.pending() }
                sweepForgottenRooms()
                val linked = activeLinkRooms()
                _start.update { it.copy(savedRooms = rooms, unfinishedNativeRoom = pendingNative, linkConnectedRooms = linked, linkGrantOwnerRooms = activeGrantOwnerRooms(), loadingRooms = false, storageError = false, error = null) }
            } catch (_: RoomStorageException) { storageFailed() }
            catch (error: Exception) { _start.update { it.copy(loadingRooms = false, error = error.message ?: "Room creation recovery could not be inspected.") } }
        }
    }

    /**
     * Erase the epoch keys and member lists of rooms no longer saved: rooms forgotten before
     * forgetting erased them, and any whose erasure failed. Never on a saved-room list that
     * could not be read, which would sweep every room.
     */
    private fun sweepForgottenRooms() {
        val saved = { savedRooms.list().mapTo(mutableSetOf()) { it.id }.apply { freshNearbyRoomId?.let { add(it) } } }
        runCatching { roomEpochs.retainOnly(saved) }
        runCatching { roomMembers.retainOnly(saved) }
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
        // The routes are read first: one paired after this read is never swept, whoever names it.
        val routes = linkEngine.routeIds()
        val consentedRoutes = linkConsents.all().mapTo(mutableSetOf()) { it.routeId }
        // VMLS boxes keep their own routes (P3-03b-3 decision 16).
        consentedRoutes += getApplication<KithMootApplication>().vmlsRouteIds()
        routes.filterNot(consentedRoutes::contains).forEach(linkEngine::remove)
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

    private val CadenceContext.key get() = CadenceScopeKey(scope.nodeId, scope.trafficRoom, scope.roomGeneration)

    private fun activeRoomEpoch(record: SavedRoom): EpochKeys {
        val stored = record.authority?.let { roomEpochs.get(record.id) }
        return activeEpochFor(record, stored) ?: throw IllegalArgumentException(
            if (stored?.phase == EpochPhase.CLOSED) "This room was closed" else "You were removed from this room"
        )
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
        return runCatching { scheduleView(access.context, room, device) }.getOrElse {
            CadenceViewState(state = "blocked", detail = "The cadence ownership journal could not be opened. Delegated counters remain unavailable.")
        }
    }

    /** The lease Bothy is running now, with any renewal that follows it. */
    private fun scheduleView(context: CadenceContext, room: String, device: String): CadenceViewState {
        val leases = cadenceLeases.all(room, device)
        val epoch = DeadDrop.epochIndexAt(epochSeconds())
        val primary = CadenceSchedule.primary(leases, context.key, epoch)
            ?: return CadenceViewState(eligible = true, detail = "Bothy can take over this phone's quiet cadence for up to twelve hours.")
        val view = cadenceView(primary, eligible = true)
        val successor = CadenceSchedule.successor(leases, context.key, primary)
        if (successor?.ownership == CadenceOwnership.CLIENT_EXCLUDED) return view.copy(
            state = "unresolved",
            detail = "Bothy's reply to the renewal was not confirmed. KithMoot kept its exact bytes and will retry without reclaiming the counters.",
        )
        // Stop lowers only real sends; a staged renewal's cover is already promised to its end.
        val detail = if (successor != null && view.state in setOf("stopping", "cover")) {
            "Real sends stop at the safe boundary. Bothy keeps the fixed cover pattern until the renewal's end, which a stop cannot shorten."
        } else view.detail
        return view.copy(
            detail = detail,
            renewable = CadenceSchedule.renewable(leases, context.key, epoch),
            renewedUntilEpoch = successor?.plan?.endEpoch,
        )
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

    /**
     * A tap on pair, disconnect or revoke usually lands while start-up grant
     * recovery still holds the gate; refusing it then would drop the user's
     * intent, so wait for recovery first and refuse only if it stays busy.
     */
    private suspend fun awaitCircleGrantGate(): Boolean =
        withTimeoutOrNull(CIRCLE_GRANT_WAIT_MS) { circleGrantGate.lock() } != null

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
        if (id == callRoomId) throw RoomRecoveryException("Your call is in this room. Leave the call before forgetting it.")
        if (linkConsents.all().any { it.roomId == id }) {
            throw RoomRecoveryException("Disconnect Bothy and confirm grant withdrawal before forgetting this room.")
        }
        savedRooms.get(id)?.let {
            closingKeeper?.join()
            forgetNativeStores(it)
            recordingApplication.forgetRecordingsForRoom(id)
            RoomSharingVault(getApplication(), it.id, it.participant, it.devicePubkey).forget()
            AssignmentVault(getApplication(),id,it.participant).reset()
            dev.forgesworn.kithmoot.storage.PendingChatVault(getApplication(),
                it.id, it.participant, it.devicePubkey).outbox.clear()
        }
        savedRooms.forget(id)
        // Its keys go with it. What cannot be erased now, the next load's sweep erases.
        runCatching { roomEpochs.forget(id) }
        roomMembers.forget(id)
    }
    private fun forgetNativeStores(saved: SavedRoom) {
        saved.nativeAuthority?.let { b ->
            check(savedRoom?.id != saved.id) { "Leave this room before forgetting its host state." }
            RoomRekeyVault(getApplication(), dev.forgesworn.kithmoot.epoch.RoomRekeyBinding(
                b.room, b.authority, b.device, b.meshScope, b.relays, b.route)).forget()
            NativeKeeperVault.forSavedRoom(getApplication(), saved).forget()
        }
    }
    fun setRoomProject(id: String, project: String) = changeSavedRooms { savedRooms.update(id) { it.inProject(project) } }
    fun setRoomPinned(id: String, pinned: Boolean) = changeSavedRooms { savedRooms.update(id) { it.withPinned(pinned) } }

    /** The link for the room row's "Share invite link": read fresh from
     *  storage, handed straight to the share intent, and never kept in
     *  [StartState] or logged (design-home-rooms.md section 10). */
    suspend fun inviteLinkFor(id: String): String? = withContext(Dispatchers.IO) {
        val saved = savedRooms.get(id) ?: return@withContext null
        val summary = saved.summary()
        if (!summary.canShareInvite) return@withContext null
        if (saved.nativeAuthority != null) {
            if (nativeKeeperEntry?.binding?.pin == saved.nativeAuthority?.pin) {
                if (!canShareRoomInvitation(_room.value.nativeHosting)) return@withContext null
            } else {
                val available = runCatching {
                    NativeKeeperVault.forSavedRoom(getApplication(), saved).open().use { it.canReadStoredInvitation() }
                }.getOrDefault(false)
                if (!available) return@withContext null
            }
        }
        runCatching { selectedWebApp.roomLink(saved.joinUrl) }.getOrNull()
    }
    fun resetSavedRooms() = changeSavedRooms {
        if (linkConsents.all().isNotEmpty()) {
            throw RoomRecoveryException("Disconnect Bothy from every room and confirm grant withdrawal before resetting saved rooms.")
        }
        closingKeeper?.join()
        NativeRoomCreation.open(getApplication(), savedRooms).use { creation ->
            val readable = dev.forgesworn.kithmoot.storage.NativeSavedReset.records(savedRooms) {
                dev.forgesworn.kithmoot.storage.NativeSavedReset.inspect(getApplication())
            }
            // Inventory first: AtomicFile's intent read can remove an orphaned .new.
            creation.requireSavedReset()
            readable.forEach(::forgetNativeStores)
            linkConsents.reset()
            readable.forEach { saved ->
                recordingApplication.forgetRecordingsForRoom(saved.id)
                RoomSharingVault(getApplication(), saved.id, saved.participant, saved.devicePubkey).forget()
                dev.forgesworn.kithmoot.storage.PendingChatVault(getApplication(),
                    saved.id, saved.participant, saved.devicePubkey).outbox.clear()
            }
            dev.forgesworn.kithmoot.storage.NativeSavedReset.requireCleared(
                dev.forgesworn.kithmoot.storage.NativeSavedReset.inspect(getApplication()))
            dev.forgesworn.kithmoot.notifications.CallerNames.reset(getApplication())
            savedRooms.reset()
            runCatching { roomEpochs.reset() }
            roomMembers.reset()
        }
    }

    fun pairBothy(roomId: String, code: String) {
        if (!takeStartScreenGate()) return
        _start.update { it.copy(busy = true, error = null, notice = null) }
        viewModelScope.launch(Dispatchers.IO) {
            if (!awaitCircleGrantGate()) {
                entering.release()
                _start.update { it.copy(busy = false, error = "Bothy is still finishing an earlier grant change.") }
                return@launch
            }
            var message: String? = null
            try {
                val room = savedRooms.get(roomId) ?: throw RoomRecoveryException("This room is no longer saved on this device.")
                require(!room.route.nearby) { "Choose Internet only before connecting this room to Bothy." }
                require(!room.anonymous) { "Bothy is unavailable in an anonymous room." }
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
                    room.id, room.secret, identity.deviceSecretKey, roomEnds = room.ends,
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
            finally { circleGrantGate.unlock(); entering.release(); _start.update { it.copy(busy = false) } }
        }
    }

    fun disconnectBothy(roomId: String) {
        if (!takeStartScreenGate()) return
        _start.update { it.copy(busy = true, error = null, notice = null) }
        viewModelScope.launch(Dispatchers.IO) {
            if (!awaitCircleGrantGate()) {
                entering.release()
                _start.update { it.copy(busy = false, error = "Bothy is still finishing an earlier grant change.") }
                return@launch
            }
            try {
                val room = savedRooms.get(roomId) ?: throw RoomRecoveryException("This room is no longer saved on this device.")
                require(!room.route.nearby) { "Choose Internet only before connecting this room to Bothy." }
                require(!room.anonymous) { "Bothy is unavailable in an anonymous room." }
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
            finally { circleGrantGate.unlock(); entering.release(); _start.update { it.copy(busy = false) } }
        }
    }

    /** Revoke Bob's authority while Alice keeps her keeper connection and retained ciphertext. */
    fun revokeBothyGuests(roomId: String) {
        if (!takeStartScreenGate()) return
        _start.update { it.copy(busy = true, error = null, notice = null) }
        viewModelScope.launch(Dispatchers.IO) {
            if (!awaitCircleGrantGate()) {
                entering.release()
                _start.update { it.copy(busy = false, error = "Bothy is still finishing an earlier grant change.") }
                return@launch
            }
            try {
                val room = savedRooms.get(roomId) ?: throw RoomRecoveryException("This room is no longer saved on this device.")
                require(!room.route.nearby) { "Choose Internet only before connecting this room to Bothy." }
                require(!room.anonymous) { "Bothy is unavailable in an anonymous room." }
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
            finally { circleGrantGate.unlock(); entering.release(); _start.update { it.copy(busy = false) } }
        }
    }

    private fun changeSavedRooms(change: suspend () -> Unit) {
        if (!takeStartScreenGate()) return
        _start.update { it.copy(busy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                change()
                val rooms = savedRooms.list()
                val linked = activeLinkRooms()
                _start.update { it.copy(savedRooms = rooms, linkConnectedRooms = linked, linkGrantOwnerRooms = activeGrantOwnerRooms(), storageError = false, error = null) }
            } catch (_: RoomStorageException) { storageFailed() }
            catch (e: Exception) { _start.update { it.copy(error = e.message ?: "The saved room could not be changed.") } }
            finally { entering.release(); _start.update { it.copy(busy = false) } }
        }
    }

    fun setRoomRoute(id: String, route: RoomRoute) = changeSavedRooms {
        RoomRouteTransitions.stable {
            check(!dev.forgesworn.kithmoot.notifications.ActiveRoomRegistry.isOpen(id)) {
                "Leave the room before changing its connection."
            }
            val saved = checkNotNull(savedRooms.get(id)) { "This room is no longer saved." }
            checkNearbyRoute(saved, route)
            dev.forgesworn.kithmoot.service.BackgroundCallListenerService.changeRoomRoute {
                checkNotNull(savedRooms.update(id) { it.withRoute(route) })
            }
        }
    }

    private fun checkNearbyRoute(saved: SavedRoom, route: RoomRoute = saved.route) {
        if (!route.nearby) return
        check(!saved.movedOn && !saved.ended(epochSeconds())) { "This room has ended." }
        check(!chatOnly && callRoomId == null) { "Finish the call before opening a nearby room." }
        check(!saved.anonymous && saved.policy?.quiet != true && saved.quietState == null) {
            "Nearby connections are not yet available for anonymous or quiet rooms."
        }
        check(cadenceLeases.all(saved.id, saved.devicePubkey).none { it.ownership != CadenceOwnership.ENDED }) {
            "Stop this room's Bothy schedule before choosing a nearby connection."
        }
        check(linkConsents.all().none { it.roomId == saved.id }) {
            "Disconnect this room's Bothy before choosing a nearby connection."
        }
        check(route.internet || !saved.destruct) { "Self-destructing rooms still need an Internet cleanup route." }
    }

    private suspend fun savedIdentity(saved: SavedRoom): RoomIdentity {
        checkNearbyRoute(saved)
        return if (saved.route.internet) saved.identity(epochSeconds(), accountSigner, lifetime = callCredentialLifetime(saved.id))
            else saved.offlineIdentity(epochSeconds(), accounts.load()?.pubkey)
    }

    fun nearbyPermissionDenied() {
        _start.update { it.copy(error = "Allow Nearby devices to open this room over Bluetooth. Its connection choice has not changed.") }
    }

    fun reopenRoom(id: String) = _start.value.savedRooms.firstOrNull { it.id == id }?.name.let { name ->
        enter(label = name?.takeIf { it.isNotBlank() } ?: "That room", opening = openingLine(name, "the room")) {
            openSaved(savedRooms.get(id) ?: throw RoomRecoveryException("This room is no longer saved on this device."))
        }
    }

    /**
     * A saved room reads every relay its links name, not only the ones saved
     * here: its history may live on a relay this device never saved. The
     * room's own relays come first and are never cut, whatever a stale
     * bookmark or link says. A room sheltered behind a Bothy, or an anonymous
     * one, takes no link hints, because adding public relays would undo that
     * choice; `open` adds only the room relays its guard accepts.
     */
    private fun savedRoomRelays(saved: SavedRoom, openedFrom: String? = null): List<String> {
        if (saved.nativeAuthority != null) return saved.relays
        if (keepsOwnRelays(saved)) return saved.relays
        val bookmark = _start.value.roomBookmarks.rooms.firstOrNull { it.roomId == saved.id }?.link
        val linked = listOfNotNull(saved.joinUrl, bookmark, openedFrom).distinct().map(::linkRelays)
        return RoomRelays.atOpen(saved.relays, linked, room = saved.sharedRelays)
    }

    private fun openingLine(name: String?, fallback: String): String =
        "Opening ${name?.takeIf { it.isNotBlank() } ?: fallback}…"

    private suspend fun openSaved(saved: SavedRoom) {
        if (saved.anonymous) holdForTorOnlyEntry()
        // Also reached outside runEnter (a room update retry), so the entry share is given back here too.
        try {
            val who = savedIdentity(saved)
            open(deriveRoom(saved.secret), saved.secret, savedRoomRelays(saved), who, saved.secondary,
                saved.joinUrl, saved.invitation, saved.host(epochSeconds()), saved.policy, saved,
                anonymous = saved.anonymous)
        } finally { releaseTorOnlyEntry() }
    }

    /** How long a credential minted now for this saved room lasts: longer for a Ring me room, which must stay reachable. */
    private fun callCredentialLifetime(roomId: String): Long =
        dev.forgesworn.kithmoot.service.credentialLifetimeFor(
            dev.forgesworn.kithmoot.service.BackgroundRingSettings(getApplication()).enabled(),
            dev.forgesworn.kithmoot.notifications.CallRingSettings(getApplication()).modeFor(roomId),
        )

    /** The saved identity for this room if there is one, else the signed-in account, else a key made here for this room. */
    private suspend fun primaryFor(roomId: String, now: Long): RoomIdentity = savedRooms.get(roomId)?.let { savedIdentity(it) }
        ?: accountSigner?.let { PrimaryIdentity.createWith(it, roomId, now + CREDENTIAL_TTL_SECONDS, now) }
        ?: PrimaryIdentity.create(roomId, now + CREDENTIAL_TTL_SECONDS, now)

    /** Anonymous rooms never take up an account signer or an existing identity. */
    private fun localPrimary(roomId: String, now: Long): PrimaryIdentity =
        PrimaryIdentity.create(roomId, now + CREDENTIAL_TTL_SECONDS, now)

    /** Onion invitations select the constrained carrier automatically; the explicit switch rejects clearnet input. */
    private fun anonymousFor(relays: List<String>): Boolean {
        val onionOnly = relays.isNotEmpty() && runCatching {
            TorOnlyRelayUrls.assertRoomTransport(relays, emptyList())
        }.isSuccess
        if (_start.value.anonymousMode) return TorOnlyRelayUrls.assertRoomTransport(relays, emptyList()).let { true }
        return onionOnly
    }

    /**
     * A start-screen act that needs the entry gate.
     *
     * True when it may go ahead. False leaves a reason on the start screen,
     * which is the screen the person is looking at: a refusal nobody can see
     * is the same to them as the app doing nothing at all.
     */
    private fun takeStartScreenGate(): Boolean {
        if (_stage.value != Stage.START) return false
        if (entering.tryAcquire()) return true
        _start.update { it.copy(busy = true, error = null, notice = FINISHING_LAST_ROOM) }
        viewModelScope.launch {
            // Nothing to carry out afterwards - these are settings changes, not
            // a room being opened - but the "still finishing" line must not be
            // left standing once it is no longer true.
            entering.awaitAcquire(ENTRY_GATE_WAIT_MS).also { if (it) entering.release() }
            _start.update { it.copy(busy = false, notice = null) }
        }
        return false
    }

    /** A room tap that is waiting for the last room to finish closing. */
    private class QueuedEnter(val label: String, val opening: String, val block: suspend () -> Unit)

    /** Storage and network failures stay on the entry screen; parallel taps cannot open two sessions. */
    private fun enter(label: String = "That room", opening: String = "Opening the room…", block: suspend () -> Unit) {
        // Whatever the person opens wins over reopening a parked room.
        parkedRoomId = null
        if (_stage.value != Stage.START) {
            // The person is in a room, so the room's own snackbar is where they
            // will see this. See KithMootApp's notice effect.
            note("Leave this room before opening another. The invitation will be waiting on the home screen.")
            Log.i(JOIN_LOG, "enter refused reason=already-in-a-room")
            return
        }
        armStopOpening()
        if (entering.tryAcquire()) {
            runEnter(opening, block)
            return
        }
        // The gate is held, and on this screen that is almost always the last
        // room still tearing down - a farewell over relays, an engine to
        // dispose, sockets to close. A tap dropped here is what "it takes a
        // couple of goes to rejoin" is made of, so it is never dropped.
        //
        // The LATEST tap wins. Somebody who taps a room, thinks better of it
        // and taps another is asking for the second one, and opening the first
        // would be worse than the silence this replaced.
        val startsTheWait = queuedEnter.offer(QueuedEnter(label, opening, block))
        _start.update { it.copy(busy = true, error = null, notice = waitingNotice(label)) }
        if (!startsTheWait) {
            Log.i(JOIN_LOG, "enter queued replaced an earlier waiting tap")
            return
        }
        Log.i(JOIN_LOG, "enter waiting reason=previous-room-still-closing")
        entryWait = viewModelScope.launch {
            var taken = false
            try {
                taken = entering.awaitAcquire(ENTRY_GATE_WAIT_MS)
                val latest = queuedEnter.take()
                if (!taken) {
                    Log.i(JOIN_LOG, "enter refused reason=previous-room-did-not-finish-closing")
                    disarmStopOpening()
                    _start.update {
                        it.copy(busy = false, notice = null, error = "The last room is still closing. Try that room again in a moment.")
                    }
                    return@launch
                }
                if (latest == null || _stage.value != Stage.START) {
                    entering.release()
                    taken = false
                    disarmStopOpening()
                    _start.update { it.copy(busy = false, notice = null) }
                    Log.i(JOIN_LOG, "enter dropped reason=nothing-left-to-open")
                    return@launch
                }
                _start.update { it.copy(notice = null) }
                taken = false
                runEnter(latest.opening, latest.block)
            } catch (cancelled: CancellationException) {
                if (taken) entering.release()
                throw cancelled
            }
        }
    }

    /** Offer a way out of a room that is taking too long to open: a tester's
     *  old room sat on "Opening" until the app was killed. */
    private fun armStopOpening() {
        stopOpeningTimer?.cancel()
        // Finishing either way disarms this first, so firing means still waiting.
        stopOpeningTimer = viewModelScope.launch {
            delay(STOP_OPENING_AFTER_MS)
            _start.update { it.copy(canStopOpening = true) }
        }
    }

    private fun disarmStopOpening() {
        stopOpeningTimer?.cancel()
        stopOpeningTimer = null
        _start.update { it.copy(canStopOpening = false) }
    }

    /**
     * Stop and go back to your rooms. A room that will not open must never be
     * a reason to quit the app. The rooms come back at once; the entry
     * finishes cancelling behind them, still holding the entry gate, so a
     * room tapped straight away waits for it as it would for any last room.
     * A docked call belongs to the other instance and carries on.
     */
    fun stopOpening() {
        if (_stage.value != Stage.START) return
        Log.i(JOIN_LOG, "enter stopped by the person")
        queuedEnter.take()
        entryWait?.cancel()
        entryWait = null
        entryJob?.cancel()
        entryJob = null
        disarmStopOpening()
        _start.update { it.copy(busy = false, notice = null, opening = null) }
    }

    /** "Finishing leaving the last room… Wednesday standup will open next." */
    private fun waitingNotice(label: String): String = "$FINISHING_LAST_ROOM $label will open next."

    /** The body of [enter], with the gate already held. */
    private fun runEnter(opening: String, block: suspend () -> Unit) {
        lastRoomEntryDiagnostic = null
        _start.update { it.copy(busy = true, error = null, opening = opening) }
        entryJob = viewModelScope.launch(Dispatchers.IO) {
            var opened = false
            try {
                start.first { !it.loadingRooms }
                if (!_start.value.storageError) {
                    block()
                    opened = session != null
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                lastRoomEntryDiagnostic = roomEntryFailureDiagnostic(e)
                val oldJob = gate.withLock {
                    sessionScope?.coroutineContext?.get(Job).also { closeSession() }
                        .also { _room.value = RoomState(background = backgrounds.load()); _stage.value = Stage.START }
                }
                oldJob?.join()
                val pending = runCatching { NativeRoomCreation.open(getApplication(), savedRooms).use { it.pending() } }.getOrNull()
                if (pending != null) _start.update { it.copy(unfinishedNativeRoom = pending) }
                when (e) {
                    is RoomStorageException -> storageFailed()
                    else -> _start.update { it.copy(error = roomEntryFailureMessage(e)) }
                }
            } finally {
                releaseTorOnlyEntry()
                // Stopped by the person: whatever part of the room had opened
                // is closed again, never shown. See [stopOpening].
                val stopped = !isActive
                if (stopped) withContext(NonCancellable) {
                    val oldJob = gate.withLock {
                        sessionScope?.coroutineContext?.get(Job).also { closeSession() }
                            .also { _room.value = RoomState(background = backgrounds.load()) }
                    }
                    oldJob?.join()
                }
                // Publish room controls only once entry has released its guard.
                // Otherwise a fast Leave tap can be silently rejected. Keep the
                // unlock and UI transition on Main so a tap cannot interleave.
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    entering.release()
                    // A stopped entry already gave the screen back, and a room
                    // tapped since then owns its busy line and its timer.
                    if (!stopped) {
                        disarmStopOpening()
                        _start.update { it.copy(busy = false, opening = null) }
                        if (opened && session != null) {
                            _stage.value = Stage.ROOM
                            // Opened behind other apps: the wait to park starts now.
                            if (!appVisible && parkJob?.isActive != true) armPark()
                        }
                    }
                }
            }
        }
    }

    fun onJoinUrlChanged(value: String) {
        _start.value = _start.value.copy(joinUrl = value, error = null)
    }

    fun onRelaysChanged(value: String) {
        boxRelayRevision.incrementAndGet()
        _start.value = _start.value.copy(relays = value, relayChoices = emptyList(), error = null)
        // Keep the last valid network choice across process restarts. Partial
        // text being edited is not a replacement for working relay settings.
        if (runCatching { parseRelays(value) }.isSuccess) display.edit().putString("relaySettings", value).apply()
        act { runCatching { synchronized(boxPreferencesGate) { boxDiscovery.disableAll() } }.onFailure { note("Box checks could not be stopped in the saved preferences.") } }
    }

    fun accountRelayChoices(): List<RelayChoice> = _start.value.relayChoices.ifEmpty {
        runCatching { parseRelays(_start.value.relays).ifEmpty { DEFAULT_RELAYS }.map { RelayChoice(it) } }
            .getOrDefault(DEFAULT_RELAYS.map { RelayChoice(it) })
    }

    private fun selectedReadRelays(urls: List<String>): Set<String> = urls.filter { url ->
        accountRelayChoices().firstOrNull { it.url.removeSuffix("/") == url.removeSuffix("/") }?.read != false
    }.toSet()
    private fun selectedWriteRelays(urls: List<String>): Set<String> = urls.filter { url ->
        accountRelayChoices().firstOrNull { it.url.removeSuffix("/") == url.removeSuffix("/") }?.write != false
    }.toSet()

    fun saveAccountRelays(choices: List<RelayChoice>): String? {
        if (_stage.value != Stage.START || _start.value.busy || _start.value.signingIn) return "Leave the room before changing relay connections."
        val clean = try { RelaySelection.validate(choices) } catch (e: Exception) { return e.message ?: "Check the relay addresses." }
        val key = accountSigner?.pubkey?.let { "relayChoices.$it" } ?: "relayChoices.visitor"
        val relays = clean.filter { it.read || it.write }.joinToString("\n") { it.url }
        if (!display.edit().putString(key, RelaySelection.encode(clean)).putString("relaySettings", relays).commit()) return "Relay settings could not be saved."
        _start.update { it.copy(relays = relays, relayChoices = clean, relayHealth = emptyMap()) }
        boxRelayRevision.incrementAndGet()
        refreshRoomBookmarks(); refreshSharedProjects()
        return null
    }

    private suspend fun <T> withAccountRelayPool(actor: ParticipantSigner, action: suspend (RelayPool) -> T): T {
        val scope = CoroutineScope(kotlin.coroutines.coroutineContext)
        val urls = accountRelayChoices().filter { it.read || it.write }.map { it.url }
        val pool = RelayPool(urls, OkHttpRelaySockets(), scope, readRelays = selectedReadRelays(urls), writeRelays = selectedWriteRelays(urls))
        pool.start()
        val observer = scope.launch { pool.health.collect { health ->
            if (accountSigner === actor) reportRelayHealth("profile", health)
        } }
        try { return action(pool) } finally { observer.cancel(); pool.stop()
            if (accountSigner === actor) reportRelayHealth("profile", pool.health.value) }
    }

    /** Read current signed metadata before offering an editor, so unknown fields survive. */
    fun loadEditableProfile() {
        val actor = accountSigner ?: return
        if (_start.value.profileBusy) return
        _start.update { it.copy(profileBusy = true, profileMessage = null, profileMetadata = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val event = withAccountRelayPool(actor) { pool -> ProfileMetadata.latest(
                    pool.queryStored(listOf(Filter(kinds = listOf(0), authors = listOf(actor.pubkey), limit = 1))), actor.pubkey, epochSeconds()) }
                if (accountSigner !== actor) return@launch
                val known = _start.value.account?.profile
                check(known == null || event != null && (event.createdAt > known.createdAt || event.createdAt == known.createdAt && event.id <= known.eventId)) {
                    "The relays did not return the current profile. Retry before editing."
                }
                val metadata = event?.let { kotlinx.serialization.json.Json.parseToJsonElement(it.content).jsonObject } ?: JsonObject(emptyMap())
                _start.update { it.copy(profileMetadata = metadata, profileBaseId = event?.id, profileBaseAt = event?.createdAt ?: 0) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (accountSigner === actor) _start.update { it.copy(profileMessage = "Could not load your current profile. Check the read relays and retry before editing.") } }
            finally { if (accountSigner === actor) _start.update { it.copy(profileBusy = false) } }
        }
    }

    fun publishProfile(values: Map<String, String>) {
        AccountWriteHold.process.personActed()
        val actor = accountSigner ?: return
        val base = _start.value.profileMetadata ?: return
        if (_start.value.profileBusy) return
        val content = try { ProfileMetadata.edit(base, values).toString() }
        catch (e: Exception) { _start.update { it.copy(profileMessage = e.message ?: "Check the profile fields.") }; return }
        val baseId = _start.value.profileBaseId
        val baseAt = _start.value.profileBaseAt
        _start.update { it.copy(profileBusy = true, profileMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withAccountRelayPool(actor) { pool ->
                    val previousPending = pendingProfile?.takeIf { it.pubkey == actor.pubkey && it.content == content }
                    val current = ProfileMetadata.latest(pool.queryStored(listOf(Filter(kinds = listOf(0), authors = listOf(actor.pubkey), limit = 1))), actor.pubkey, epochSeconds())
                    check(current?.id == baseId || previousPending != null && current?.id == previousPending.id) { "Your profile changed on another device. Reload it before publishing." }
                    check(accountSigner === actor) { "The account changed." }
                    val at = maxOf(epochSeconds(), baseAt + 1)
                    val event = previousPending ?: checkedSignedEvent(actor.sign(0, at, emptyList(), content), actor.pubkey, 0, at, emptyList(), content)
                    check(accountSigner === actor) { "The account changed." }
                    pendingProfile = event
                    check(pool.publishConfirmed(event)) { "No write relay accepted the profile. Retry to send the same signed update." }
                    if (accountSigner !== actor) return@withAccountRelayPool
                    pendingProfile = null
                    val profile = decodePublicProfile(event, setOf(actor.pubkey), epochSeconds())
                    _start.update { it.copy(profileMetadata = kotlinx.serialization.json.Json.parseToJsonElement(content).jsonObject,
                        profileBaseId = event.id, profileBaseAt = event.createdAt, account = it.account?.copy(profile = profile),
                        profileMessage = "Profile accepted by a write relay. Other clients may take time to refresh.") }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (accountSigner === actor) _start.update { it.copy(profileMessage = e.message ?: "Profile publication was not confirmed. Check your signer and write relays, then retry.") } }
            finally { if (accountSigner === actor) _start.update { it.copy(profileBusy = false) } }
        }
    }

    /** Look up the account's own DM relay list before offering an editor. */
    fun loadDmRelays() {
        val actor = accountSigner ?: return
        if (_start.value.profileBusy) return
        _start.update { it.copy(profileBusy = true, profileMessage = null, dmRelays = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val lists = lookUpDmRelayLists(listOf(actor.pubkey), accountRelayChoices().filter { it.read }.map { it.url } + PROFILE_RELAYS)
                if (accountSigner === actor) _start.update { it.copy(dmRelays = latestDmRelayList(lists, actor.pubkey)) }
            } finally {
                if (accountSigner === actor) _start.update { it.copy(profileBusy = false) }
            }
        }
    }

    /** Publish the account's DM relay list: where private conversations
     *  started with this person, and by them, are kept from now on. */
    fun publishDmRelays(relays: List<String>) {
        AccountWriteHold.process.personActed()
        val actor = accountSigner ?: return
        if (_start.value.profileBusy) return
        val tags = try { dmRelayListTags(relays) } catch (e: IllegalArgumentException) {
            _start.update { it.copy(profileMessage = "Not saved: ${e.message}.") }; return
        }
        _start.update { it.copy(profileBusy = true, profileMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val urls = (accountRelayChoices().filter { it.write }.map { it.url } + PROFILE_RELAYS + tags.map { it[1] })
                    .mapNotNull { runCatching { canonicalRoomRelayUrl(it) }.getOrNull() }.distinct()
                val pool = RelayPool(urls, OkHttpRelaySockets(), CoroutineScope(kotlin.coroutines.coroutineContext))
                pool.start()
                try {
                    val at = epochSeconds()
                    val event = checkedSignedEvent(actor.sign(KIND_DM_RELAYS, at, tags, ""), actor.pubkey, KIND_DM_RELAYS, at, tags, "")
                    check(accountSigner === actor) { "The account changed." }
                    check(pool.publishConfirmed(event))
                    if (accountSigner === actor) _start.update { it.copy(dmRelays = tags.map { tag -> tag[1] },
                        profileMessage = "Saved. New private conversations will use these relays.") }
                } finally { pool.stop() }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (accountSigner === actor) _start.update { it.copy(profileMessage = "Not saved: no relay accepted the list. Check your signer and connection, then retry.") } }
            finally { if (accountSigner === actor) _start.update { it.copy(profileBusy = false) } }
        }
    }

    fun publishAccountRelayList() {
        AccountWriteHold.process.personActed()
        val actor = accountSigner ?: return
        if (_start.value.profileBusy) return
        val tags = try { RelaySelection.tags(accountRelayChoices()) } catch (_: Exception) { return }
        _start.update { it.copy(profileBusy = true, profileMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try { withAccountRelayPool(actor) { pool ->
                val latest = pool.queryAvailable(listOf(Filter(kinds = listOf(10002), authors = listOf(actor.pubkey), limit = 1)))
                    .filter { it.kind == 10002 && it.pubkey == actor.pubkey && it.createdAt <= epochSeconds() + 60 && Events.verify(it) }
                    .maxOfOrNull { it.createdAt } ?: 0
                val at = maxOf(epochSeconds(), latest + 1)
                val event = pendingRelayList?.takeIf { it.pubkey == actor.pubkey && it.tags == tags }
                    ?: checkedSignedEvent(actor.sign(10002, at, tags, ""), actor.pubkey, 10002, at, tags, "")
                check(accountSigner === actor); pendingRelayList = event; check(pool.publishConfirmed(event))
                if (accountSigner === actor) { pendingRelayList = null; _start.update { it.copy(profileMessage = "Public relay list accepted by a write relay.") } }
            } } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (accountSigner === actor) _start.update { it.copy(profileMessage = "Relay-list publication was not confirmed. Check your signer and write relays.") } }
            finally { if (accountSigner === actor) _start.update { it.copy(profileBusy = false) } }
        }
    }

    fun onAnonymousModeChanged(value: Boolean) {
        _start.update { it.copy(anonymousMode = value, error = null) }
    }

    fun onPersistentGroupChanged(value: Boolean) {
        _start.update { it.copy(persistentGroup = value, error = null) }
    }

    fun onConferenceLengthChanged(value: ConferenceLength) {
        _start.update { it.copy(conferenceLength = value, error = null) }
    }

    fun onRoomDurationChanged(value: Int) {
        _start.update { it.copy(roomDurationSeconds = value.coerceIn(0, 30 * 86400), error = null) }
    }

    fun onRoomDestructChanged(value: Boolean) {
        _start.update { it.copy(roomDestruct = value, error = null) }
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
        val anonymous = _start.value.anonymousMode
        val relays = try {
            val parsed = parseRelays(_start.value.relays)
            if (anonymous) TorOnlyRelayUrls.assertRoomTransport(parsed, emptyList()) else parsed
        } catch (error: IllegalArgumentException) {
            _start.value = _start.value.copy(error = error.message ?: "Anonymous rooms need onion relays.")
            return
        }
        if (relays.isEmpty()) {
            _start.value = _start.value.copy(error = "Name at least one relay.")
            return
        }
        val name = _start.value.roomName
        val persistent = true
        val length = _start.value.conferenceLength
        val durationSeconds = _start.value.roomDurationSeconds
        if (length == ConferenceLength.CUSTOM && durationSeconds < 60) {
            _start.update { it.copy(error = "Choose at least one minute for the room lifetime.") }
            return
        }
        val destructChoice = _start.value.roomDestruct
        enter(label = name.takeIf { it.isNotBlank() } ?: "The new room",
            opening = "Starting ${name.takeIf { it.isNotBlank() } ?: "the room"}…") {
            if (anonymous) holdForTorOnlyEntry()
            val secret = Entropy.bytes(32)
            val invitationHost = createRoomInvitation(persistent)
            val invitation = InvitationPayload(invitationHost.invitation, relays, null)
            val derived = deriveRoom(secret)
            val at = epochSeconds()
            val primary = (if (!anonymous) accountSigner?.let { PrimaryIdentity.createWith(it, derived.roomId, at + CREDENTIAL_TTL_SECONDS, at) } else null)
                ?: PrimaryIdentity.create(
                    roomId = derived.roomId,
                    expiresAt = at + CREDENTIAL_TTL_SECONDS,
                    createdAt = at,
                )
            // A conference room ends at a fixed time; only a group room can.
            val ends = if (persistent) length.endsFrom(at, durationSeconds) else null
            // The room's own relays: the ones this device both reads and
            // writes, fixed now and named in the signed invitation, so every
            // member uses them whatever else they use.
            val roomRelays = if (anonymous) emptyList() else invitationRelaysFrom(relays.filter { it in selectedReadRelays(relays) && it in selectedWriteRelays(relays) })
            // Self-destruct rides beside the end, inside the invitation's encryption.
            val destruct = ends != null && destructChoice
            if (persistent) publishGroup(invitationHost, secret, relays, anonymous, ends, roomRelays, destruct)
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
                anonymous = anonymous,
                ends = ends,
                roomRelays = roomRelays,
                roomRelaysSigned = true,
                destruct = destruct,
            )
        }
    }

    /** A local chat host; the independent journal keeps the sole root signer. */
    fun startNearbyRoom(route: RoomRoute) {
        val chosen = _start.value
        enter(label = "The nearby room", opening = "Starting the nearby room…") {
            require(route.nearby)
            check(appVisible && !chosen.anonymousMode && !chatOnly && callRoomId == null) {
                "Keep KithMoot on screen and finish the call or leave Tor-only mode before starting nearby."
            }
            check(chosen.conferenceLength == ConferenceLength.NEVER) { "Nearby hosting currently needs a room with no fixed end." }
            val relays = if (route.internet) parseRelays(chosen.relays)
                .map(::canonicalRoomRelayUrl).map(::canonicalRelayUrl).distinct().sorted() else emptyList()
            require(!route.internet || relays.size in 1..MAX_INVITATION_RELAYS && relays.all(::isSafeRoomRelayUrl)) {
                "Choose one to $MAX_INVITATION_RELAYS encrypted room relays."
            }
            val at = epochSeconds()
            val creation = NativeKeeperCreation.fresh(at, roomRelays = relays.takeIf { route.internet })
            try {
                val base = creation.roomSecret()
                val invitation = creation.invitation()
                try {
                    val who = PrimaryIdentity.create(creation.room, at + CREDENTIAL_TTL_SECONDS, at)
                    val url = encodeInvitationUrl(selectedWebApp.joinBase, invitation, relays)
                    val draft = SavedRoom.create(base, who, url, relays, chosen.roomName, at,
                        host = null, authority = creation.authority, route = route)
                    val saved = NativeRoomCreation.open(getApplication(), savedRooms).use { it.begin(creation, draft, who.credential) }
                    _start.update { it.copy(savedRooms = savedRooms.list(), unfinishedNativeRoom = null) }
                    openSaved(saved)
                } finally { base.fill(0); invitation.bearer.fill(0) }
            } finally { creation.close() }
        }
    }

    fun recoverNativeRoomCreation() {
        enter(label = "The unfinished room", opening = "Recovering the unfinished room…") {
            val saved = NativeRoomCreation.open(getApplication(), savedRooms).use { it.recover() }
                ?: throw RoomRecoveryException("There is no unfinished room to recover.")
            _start.update { it.copy(savedRooms = savedRooms.list(), unfinishedNativeRoom = null,
                notice = "Room recovered. Open it when you are ready.") }
        }
    }

    /** Joins from a pasted or tapped link. The one entry point for both. */
    fun joinFromUrl(raw: String) {
        val url = raw.trim()
        if (url.isEmpty()) {
            _start.value = _start.value.copy(error = "Paste a join link first.")
            return
        }
        enter(label = "The invitation", opening = "Opening the invitation…") { join(url) }
    }

    /** Explicit nearby/local-identity choice; both stages keep the selected owner. */
    fun joinNearbyFromUrl(rawUrl: String, descriptor: String, route: RoomRoute = RoomRoute.NEARBY) {
        val url = rawUrl.trim()
        enter(label = "The nearby invitation", opening = "Waiting for the nearby room keeper…") {
            require(route.nearby) { "Choose Nearby only or Nearby + Internet for this entry." }
            check(!_start.value.anonymousMode && !chatOnly && callRoomId == null) { "Finish the call or leave anonymous mode before joining nearby." }
            check(appVisible) { "Keep KithMoot on screen while joining nearby." }
            val payload = requireNotNull(decodeInvitationUrl(url)) { "Nearby joining needs a persistent room invitation." }
            check(payload.invitation.persistent && decodeInvitationPairingLink(url) == null) { "Nearby joining needs a persistent invitation, not a device pairing link." }
            check(payload.policy?.quiet != true && ((payload.policy?.tier ?: KindredTier.OPEN) == KindredTier.OPEN)) { "This room's policy is not supported for nearby joining." }
            val context = requireNotNull(decodeLivePersistentDescriptor(descriptor.trim(), payload.invitation)) { "The nearby code does not match this invitation." }
            check(savedRooms.get(context.roomId) == null) { "This room is already saved. Choose its connection in the Connection menu." }
            check(linkConsents.all().none { it.roomId == context.roomId }) { "Disconnect this room's Bothy before joining nearby." }
            val selectedRelays = if (route.internet) {
                if (payload.relays.size !in 1..MAX_INVITATION_RELAYS) throw RoomRecoveryException(
                    "Nearby + Internet needs one to $MAX_INVITATION_RELAYS relays in this invitation.")
                if (!payload.relays.all(::isSafeRoomRelayUrl)) throw RoomRecoveryException(
                    "Use encrypted wss:// room relays; ws:// is allowed only on localhost.")
                payload.relays.map(::canonicalRelayUrl).distinct()
            } else emptyList()
            val oldJob = gate.withLock { sessionScope?.coroutineContext?.get(Job).also { closeSession() } }
            oldJob?.join(); closingNearby?.awaitClosed(); closingNearby = null
            val deviceKey = Entropy.bytes(32)
            var owner: RoomNearbyOwner? = null
            var mesh: RoomMeshTransport? = null
            var relay: RelayPool? = null
            val routeScope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))
            var who: PrimaryIdentity? = null
            var participantKey: ByteArray? = null
            var admittedSecret: ByteArray? = null
            var committed = false
            freshNearbyOpening = true
            try {
                val discovery = RoomNearbyDiscovery.scope(context.roomId)
                owner = roomNearbyOwnership.open(RoomBleConfig(discovery, Entropy.bytes(32).toHex(), RoomNearbyDiscovery.serviceUuid(discovery))) {
                    nearbyLinkFactory(getApplication())
                }
                check(appVisible) { "KithMoot left the foreground while Bluetooth was starting." }
                val entryMesh = RoomMeshTransport(discovery, owner.link)
                mesh = entryMesh
                relay = if (route.internet) RelayPool(selectedRelays, OkHttpRelaySockets(), routeScope) else null
                val entryOwner = owner
                val selected: RoomTransport = relay?.let { HybridRoomTransport(entryMesh, it) } ?: entryMesh
                relay?.start()
                withLivePersistentRoomAdmission(payload.invitation, descriptor.trim(), Schnorr.publicKeyHex(deviceKey), selected,
                    now = ::epochSeconds) { proof, confirm ->
                    val admission = proof.admission
                    admittedSecret = admission.secret
                    if (route.internet && admission.relays?.map(::canonicalRelayUrl)?.toSet() != selectedRelays.toSet()) {
                        throw RoomRecoveryException("The keeper's relay list differs from this invitation. Ask for its current link before joining with Internet.")
                    }
                    check(!admission.destruct) { "For a self-destructing room, use Open on the invitation." }
                    admission.endsAt?.let { check(!conferenceEnded(it, epochSeconds())) { conferenceEndedMessage(it) } }
                    val derived = deriveRoom(admission.secret)
                    check(savedRooms.get(derived.roomId) == null) { "This room is already saved. Choose Nearby in its Connection menu." }
                    check(linkConsents.all().none { it.roomId == derived.roomId }) { "Disconnect this room's Bothy before joining nearby." }
                    freshNearbyRoomId = derived.roomId
                    val at = epochSeconds()
                    val localKey = Entropy.bytes(32).also { participantKey = it }
                    val identity = PrimaryIdentity.create(derived.roomId, at + CREDENTIAL_TTL_SECONDS, at, participantSecretKey = localKey, deviceSecretKey = deviceKey)
                    who = identity
                    open(derived, admission.secret, admission.relays.orEmpty(), identity, false, url,
                        invitation = payload, policy = payload.policy, localName = payload.name.orEmpty(),
                        ends = admission.endsAt, roomRelays = admission.relays.orEmpty(), roomRelaysSigned = admission.relays != null,
                        expectedEpoch = proof.epochHint.toInt(), freshNearby = FreshNearbyEntry(entryOwner, entryMesh, selected, route, relay, routeScope, confirm))
                }
                currentCoroutineContext().ensureActive()
                committed = true
            } finally {
                freshNearbyOpening = false
                try {
                    if (!committed) withContext(NonCancellable) {
                        try {
                            try {
                                val abandoned = gate.withLock { sessionScope?.coroutineContext?.get(Job).also { closeSession() } }
                                abandoned?.join()
                            } finally {
                                try { relay?.stop() } finally {
                                    routeScope.cancel()
                                    try { mesh?.close() } finally { owner?.close(); owner?.awaitClosed() }
                                }
                            }
                        } finally {
                            who?.let { identity -> savedRooms.forgetIfIdentity(context.roomId, identity.participant, identity.devicePubkey) }
                            _start.update { it.copy(savedRooms = savedRooms.list()) }
                        }
                    }
                } finally {
                    freshNearbyRoomId = null
                    if (!committed) { participantKey?.fill(0); admittedSecret?.fill(0); deviceKey.fill(0) }
                }
            }
        }
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
        val ownRelays = parseRelays(_start.value.relays).ifEmpty { DEFAULT_RELAYS }
        val relays = payload.relays.ifEmpty { ownRelays }
        val anonymous = try { anonymousFor(relays) } catch (error: IllegalArgumentException) {
            _start.value = _start.value.copy(busy = false, error = error.message ?: "Anonymous rooms need onion relays.")
            return
        }
        if (anonymous) holdForTorOnlyEntry()
        // The link's relays are the room's own, on first sight; this device's
        // own relays join them rather than standing in for them.
        val pooled = if (anonymous) relays else RoomRelays.atOpen(payload.relays, listOf(ownRelays))
        val roomRelays = if (anonymous) emptyList() else invitationRelaysFrom(payload.relays)
        val at = epochSeconds()

        // A pairing link carries a device key and a credential, so this device
        // joins as another of that person's devices rather than as a stranger.
        val pairing = decodePairingLink(url)
        if (pairing != null) {
            if (anonymous) throw RoomRecoveryException("Anonymous rooms cannot use a paired-device identity.")
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
                pooled,
                secondary,
                secondary = true,
                joinUrl = encodeJoinUrl(selectedWebApp.joinBase, payload.secret, relays, payload.policy),
                policy = payload.policy,
                anonymous = anonymous,
                roomRelays = roomRelays,
            )
            return
        }

        savedRooms.get(derived.roomId)?.let { openSaved(it); return }
        val primary = if (anonymous) localPrimary(derived.roomId, at) else primaryFor(derived.roomId, at)
        open(
            derived,
            payload.secret,
            pooled,
            primary,
            secondary = primary is SecondaryIdentity,
            joinUrl = encodeJoinUrl(selectedWebApp.joinBase, payload.secret, relays, payload.policy),
            policy = payload.policy,
            anonymous = anonymous,
            roomRelays = roomRelays,
        )
    }

    private suspend fun joinInvitation(url: String, payload: InvitationPayload) {
        val ownRelays = parseRelays(_start.value.relays).ifEmpty { DEFAULT_RELAYS }
        val relays = payload.relays.ifEmpty { ownRelays }
        val anonymous = try { anonymousFor(relays) } catch (error: IllegalArgumentException) {
            _start.update { it.copy(busy = false, error = error.message ?: "Anonymous rooms need onion relays.") }
            return
        }
        if (anonymous) holdForTorOnlyEntry()
        // A group this account joined on another device opens by the secret its
        // bookmark carries, without the signed invitation, which public relays
        // drop within a day or two. A room this phone already keeps opens as saved.
        val synced = if (anonymous || decodeInvitationPairingLink(url) != null) null else syncedGroupFor(payload.invitation)
        if (synced != null) savedRooms.get(synced.room.roomId)?.let { synced.admission.secret.fill(0); openSaved(it); return }
        var foundFurther = emptyList<String>()
        val admission = synced?.admission ?: try {
            requestAdmission(payload, relays, anonymous) { foundFurther = it }
        } catch (e: GroupInvitationException) {
            _start.update { it.copy(busy = false, error = e.message) }
            return
        } catch (_: dev.forgesworn.kithmoot.session.DeclinedInvitationException) {
            _start.update { it.copy(busy = false, opening = null, error = "Your request was declined. Ask someone in the room before trying again.") }
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
                error = if (payload.invitation.persistent) INVITATION_NOT_FOUND
                    else "The room is not answering this invitation. Ask for a fresh link.",
            )
            return
        }
        val secret = admission.secret
        // A relay that ignores NIP-40 can still hand over an ended room's invitation.
        admission.endsAt?.takeIf { conferenceEnded(it, epochSeconds()) }?.let { ends ->
            secret.fill(0)
            _start.update { it.copy(busy = false, error = conferenceEndedMessage(ends)) }
            return
        }

        val derived = deriveRoom(secret)
        val at = epochSeconds()
        val pairing = decodeInvitationPairingLink(url)
        // The room's own relays: what its signed invitation names, else the
        // link's hints on first sight. Found beyond the link's relays, the
        // room evidently lives there too, so it is read and written there as
        // well, and so are this device's own relays.
        val signedRelays = admission.relays?.takeUnless { anonymous }
        val roomRelays = if (anonymous) emptyList() else signedRelays ?: invitationRelaysFrom(payload.relays)
        val pooled = if (anonymous) relays + foundFurther else RoomRelays.atOpen(payload.relays, listOf(foundFurther, ownRelays))
        if (pairing == null && !payload.invitation.persistent) {
            savedRooms.get(derived.roomId)?.takeIf { it.invitation?.invitation?.persistent == true }
                ?.let { openSaved(it); return }
        }
        if (pairing != null) {
            if (anonymous) {
                _start.value = _start.value.copy(busy = false, error = "Anonymous rooms cannot use a paired-device identity.")
                return
            }
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
                pooled,
                secondary,
                secondary = true,
                joinUrl = encodeInvitationUrl(selectedWebApp.joinBase, payload.invitation, relays, payload.policy),
                invitation = payload,
                invitationHost = admission.delegate,
                policy = payload.policy,
                anonymous = anonymous,
                ends = admission.endsAt,
                roomRelays = roomRelays,
                roomRelaysSigned = signedRelays != null,
                expectedEpoch = admission.epoch,
                destruct = admission.destruct,
            )
            return
        }

        val primary = if (anonymous) localPrimary(derived.roomId, at) else primaryFor(derived.roomId, at)
        open(
            derived,
            secret,
            pooled,
            primary,
            secondary = primary is SecondaryIdentity,
            joinUrl = encodeInvitationUrl(selectedWebApp.joinBase, payload.invitation, relays, payload.policy),
            invitation = payload,
            invitationHost = admission.delegate,
            policy = payload.policy,
            // The account's own name for the room, else the one its link carries.
            localName = synced?.room?.name ?: payload.name.orEmpty(),
            anonymous = anonymous,
            ends = admission.endsAt,
            roomRelays = roomRelays,
            roomRelaysSigned = signedRelays != null,
            expectedEpoch = admission.epoch,
            destruct = admission.destruct,
        )
    }

    /** The group [invitation] names, from the signed-in account's own room
     *  bookmarks, when one carries the room's secret. Null when signed out or
     *  while the bookmarks still belong to the previous account. */
    private fun syncedGroupFor(invitation: dev.forgesworn.kithmoot.protocol.RoomInvitation): SyncedGroup? {
        val bookmarks = roomBookmarks ?: return null
        if (bookmarks.identity != accountSigner?.pubkey) return null
        return syncedGroup(bookmarks.state.value.rooms, invitation)
    }

    /** Exchange the bearer for a traffic secret and a bounded responder
     * delegation, without an account or prompt. When none of a group link's
     * relays still has its invitation, [onWider] is for asking this device's
     * relays and the defaults too (see session/InvitationLookup.kt for why that
     * is safe and where it never goes); it hears the relays that answered. */
    private suspend fun requestAdmission(
        payload: InvitationPayload,
        relays: List<String>,
        anonymous: Boolean = false,
        onWider: ((List<String>) -> Unit)? = null,
    ): RoomAdmission? {
        if (payload.invitation.persistent) {
            val fetch: suspend (RelayPool) -> RoomAdmission = { transport ->
                try {
                    requestPersistentAdmission(payload.invitation) {
                        transport.queryStored(it, if (anonymous) TorCarrierTimings.FIRST_ANSWER_MS else 15_000)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                    throw MissingGroupInvitationException(INVITATION_NOT_FOUND)
                } catch (e: GroupInvitationException) { throw e
                } catch (_: Exception) {
                    throw MissingGroupInvitationException(INVITATION_NOT_FOUND)
                }
            }
            return try { withGroupRelays(relays, anonymous, fetch) }
            catch (e: GroupInvitationException) {
                val wider = if (onWider != null && !anonymous && isMissingInvitation(e)) {
                    val circle = circleRelaySet()
                    widerInvitationRelays(
                        relays,
                        accountRelayChoices().filter { it.read }.map { it.url },
                        DEFAULT_RELAYS,
                    ) { laneOfRelayUrl(it, circle) == Lane.SHELTERED }
                } else emptyList()
                if (wider.isEmpty()) throw e
                Log.i(JOIN_LOG, "group invitation not on the link's relays; asking ${wider.size} more")
                withGroupRelays(wider, false, fetch).also { onWider!!(wider) }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val transport = RelayPool(relays, if (anonymous) OrbotTorRelaySockets() else OkHttpRelaySockets(), scope,
            policy = if (anonymous) TorCarrierTimings.policy else RelayPolicy(),
            readRelays = if (anonymous) relays.toSet() else selectedReadRelays(relays),
            writeRelays = if (anonymous) relays.toSet() else selectedWriteRelays(relays))
        val accountAtRequest = accountSession
        val actor = if (anonymous) null else accountSigner?.takeIf {
            it.pubkey == _start.value.account?.pubkey
        }
        transport.start()
        return try {
            dev.forgesworn.kithmoot.session.requestTemporaryRoomAdmission(
                transport, payload.invitation,
                name = if (anonymous) null else accountAtRequest?.account?.displayName,
                participant = actor?.pubkey,
                signer = actor,
                timeoutMs = if (anonymous) TorCarrierTimings.FIRST_ANSWER_MS else INVITATION_TIMEOUT_MS,
                stillCurrent = { anonymous || accountSession === accountAtRequest },
                onPhase = { phase ->
                    if (_start.value.busy) _start.update { it.copy(opening = when (phase) {
                        dev.forgesworn.kithmoot.session.AdmissionRequestPhase.SIGNING -> "Confirm your identity in your signer…"
                        dev.forgesworn.kithmoot.session.AdmissionRequestPhase.WAITING -> "Waiting for someone in the room to let you in…"
                    }) }
                },
            )
        } finally {
            transport.stop()
            scope.cancel()
        }
    }

    private suspend fun <T> withGroupRelays(relays: List<String>, anonymous: Boolean = false, action: suspend (RelayPool) -> T): T =
        withGroupRelays(relays, anonymous, emptySet(), action)

    /** [forced]: relays read and written whatever this device's relay choices say, as an open room does its own. */
    private suspend fun <T> withGroupRelays(relays: List<String>, anonymous: Boolean, forced: Set<String>,
                                            action: suspend (RelayPool) -> T): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val transport = RelayPool(relays, if (anonymous) OrbotTorRelaySockets() else OkHttpRelaySockets(), scope,
            policy = if (anonymous) TorCarrierTimings.policy else RelayPolicy(),
            readRelays = if (anonymous) relays.toSet() else selectedReadRelays(relays) + forced.filter { it in relays },
            writeRelays = if (anonymous) relays.toSet() else selectedWriteRelays(relays) + forced.filter { it in relays })
        transport.start()
        return try { action(transport) } finally { transport.stop(); scope.cancel() }
    }

    private suspend fun publishGroup(host: RoomInvitationHost, secret: ByteArray, relays: List<String>, anonymous: Boolean = false, ends: Long? = null, roomRelays: List<String>? = null,
                                     destruct: Boolean = false) {
        try {
            withGroupRelays(relays, anonymous) {
                if (!it.publishConfirmed(encodePersistentInvitation(host, secret, epochSeconds(), ends = ends, relays = roomRelays?.takeIf { it.isNotEmpty() && !anonymous }, destruct = destruct),
                        if (anonymous) TorCarrierTimings.FIRST_ANSWER_MS else 15_000)) {
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

    /** Public relays drop a regular-kind event after hours or days (nos.lol
     *  keeps it under three days, primal.net under one), and a persistent
     *  link is only as durable as that event. The device that made the link
     *  signs it again while the room is open, so a link shared long after
     *  creation still loads. Best effort: a refused write is tried again
     *  next round. A private conversation (a link limited to named members) is
     *  left to lapse: keeping its link alive would turn a chance expiry into a
     *  standing way back in. A conference room's invitation is signed with its
     *  end, and is no longer signed once the room has ended. */
    private fun keepGroupInvitationAlive(scope: CoroutineScope, transport: RelayPool, host: RoomInvitationHost, secret: ByteArray, linkRelays: List<String>, roomRelays: List<String>, ends: Long? = null, fixedRelays: List<String>? = null,
                                         destruct: Boolean = false) {
        scope.launch {
            while (!conferenceEnded(ends, epochSeconds())) {
                try { transport.publishConfirmed(encodePersistentInvitation(host, secret, epochSeconds(), ends = ends, relays = fixedRelays, destruct = destruct)) }
                catch (e: kotlinx.coroutines.CancellationException) { if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e }
                catch (_: Exception) { /* Retried next round. */ }
                // The link that opened the room may name relays the room has
                // since left, and a copy of that link is still out there
                // looking on them. Circle relays are left to the room's own
                // connection.
                val circle = circleRelaySet()
                val extra = linkOnlyRelays(roomRelays, linkRelays) { laneOfRelayUrl(it, circle) == Lane.SHELTERED }
                if (extra.isNotEmpty()) {
                    try { withGroupRelays(extra) { it.publishConfirmed(encodePersistentInvitation(host, secret, epochSeconds(), ends = ends, relays = fixedRelays, destruct = destruct)) } }
                    catch (e: kotlinx.coroutines.CancellationException) { if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e }
                    catch (_: Exception) { /* Retried next round. */ }
                }
                kotlinx.coroutines.delay(GROUP_INVITATION_REFRESH_MS)
            }
        }
    }

    /** Temporary invitations require an individual decision on each admitted
     * device. A retired invitation permanently closes its decision queue. */
    private fun serveInvitation(
        scope: CoroutineScope,
        transport: RoomTransport,
        host: RoomInvitationHost,
        secret: ByteArray,
        epoch: () -> Int?,
    ): Job {
        val desk = dev.forgesworn.kithmoot.session.TemporaryRoomAdmissionDesk(
            scope, transport, host, secret, epoch,
            stillCurrent = { sessionScope === scope && session != null &&
                roomInvitation?.invitation == host.invitation && _room.value.canShareInvitation &&
                (_room.value.endsAt?.let { epochSeconds() < it } ?: true) },
            onRetired = {
                gate.withLock {
                    if (roomInvitation?.invitation == host.invitation) {
                        roomInvitationHost = null
                        savedRoom?.let { persistLiveRoom(it.id) { saved -> saved.invitationRetired() } }
                        _room.update { it.copy(canRotateInvitation = false,
                            notice = "This invitation was retired by its creator. The live room is unchanged.") }
                    }
                }
            },
            onGrantAccepted = {
                _room.update { it.copy(notice = "A relay accepted the admission grant. The guest can now join.") }
            },
            onDeclineAccepted = {
                _room.update { it.copy(notice = "A relay accepted the refusal. The guest can see that their request was declined.") }
            },
        )
        invitationAdmissionDesk = desk
        val serving = desk.start()
        CoroutineScope(scope.coroutineContext + serving).launch {
            desk.pending.collect { rows ->
                if (invitationAdmissionDesk === desk) _room.update { it.copy(invitationAdmissions = rows) }
            }
        }
        serving.invokeOnCompletion {
            if (invitationAdmissionDesk === desk) {
                invitationAdmissionDesk = null
                _room.update { it.copy(invitationAdmissions = emptyList()) }
            }
        }
        return serving
    }

    fun answerInvitationAdmission(requestId: String, admit: Boolean) {
        val desk = invitationAdmissionDesk ?: return
        if (admit) desk.admit(requestId) else desk.decline(requestId)
    }

    fun dismissInvitationAdmission(requestId: String) { invitationAdmissionDesk?.dismiss(requestId) }

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
        anonymous: Boolean = false,
        /** A conference room's end, from its group invitation, when this opening learnt it. */
        ends: Long? = null,
        /** The room's own relays, when this opening learnt them: from its
         *  signed group invitation ([roomRelaysSigned]) or a link's hints. */
        roomRelays: List<String> = emptyList(),
        roomRelaysSigned: Boolean = false,
        /** The epoch the responder that admitted this device said the room
         *  is at (`RoomAdmission.epoch`), when this opening asked one. */
        expectedEpoch: Int? = null,
        /** The room self-destructs, when this opening learnt so (`RoomAdmission.destruct`). */
        destruct: Boolean = false,
        startsAt: Long? = null,
        freshNearby: FreshNearbyEntry? = null,
    ) = gate.withLock {
        if (chatOnly && derived.roomId == callRoomId) {
            throw RoomRecoveryException("Your call is in this room. Use Back to the call to return to it.")
        }
        val openBegan = android.os.SystemClock.elapsedRealtime()
        Log.i(
            JOIN_LOG,
            "opening room=${derived.roomId.take(8)} from=${if (restoring != null) "saved" else "link"} " +
                "relays=${relays.size} anonymous=${restoring?.anonymous ?: anonymous}",
        )
        if (policy != null && policy.tier != KindredTier.OPEN) {
            _start.value = _start.value.copy(
                busy = false,
                error = "This room requires a Kindred proof. This Android build cannot obtain one yet.",
            )
            return@withLock
        }
        val route = freshNearby?.route ?: restoring?.route ?: savedRooms.get(derived.roomId)?.route ?: RoomRoute.INTERNET
        restoring?.let { checkNearbyRoute(it, route) }
        if (route.nearby) check(appVisible) { "Open KithMoot in the foreground to use nearby Bluetooth." }
        val anonymousProfile = restoring?.anonymous ?: anonymous
        val ownRelays = if (anonymousProfile) TorOnlyRelayUrls.assertRoomTransport(relays, emptyList()) else relays
        if (anonymousProfile && policy?.quiet == true) {
            throw RoomRecoveryException("Anonymous rooms do not support quiet-room cadence or Bothy delivery.")
        }
        val previous = savedRooms.get(derived.roomId)
        // An ended conference room is not opened, from its saved copy or from a link.
        (restoring?.ends ?: ends ?: previous?.ends)?.takeIf { conferenceEnded(it, epochSeconds()) }?.let {
            throw RoomRecoveryException(conferenceEndedMessage(it))
        }
        if (previous != null && (previous.participant != who.participant || (!previous.secondary && secondary))) {
            throw RoomRecoveryException("This room is saved with a different identity. Forget the saved room first if you want to replace it.")
        }
        if (previous != null && previous.anonymous != anonymousProfile) {
            throw RoomRecoveryException("This room is already saved with a different network profile.")
        }
        if (anonymousProfile && (secondary || who !is PrimaryIdentity || who.participantKeyForStorage() == null)) {
            throw RoomRecoveryException("Anonymous rooms need a new local primary identity.")
        }
        // A name already kept here stands, unless it is only the stand-in a
        // room got before anybody named it and this opening learnt a real one.
        val keptName = previous?.name?.takeUnless { it == "Room ${derived.roomId.take(8)}" && localName.isNotBlank() }
        val preparedRecord = (restoring ?: SavedRoom.create(secret, who, joinUrl, ownRelays,
            keptName ?: localName, epochSeconds(), invitationHost,
            previous?.authority ?: invitation?.invitation?.canonicalInviter, anonymousProfile,
            ends = ends?.takeIf { invitation?.invitation?.persistent == true },
            destruct = destruct && invitation?.invitation?.persistent == true, route = route)
            .let { if (previous != null) it.retainingHistory(previous) else it }).opened(epochSeconds()).keepingCredential(who)
            .let { if (destruct && !it.destruct && it.invitation?.invitation?.persistent == true) it.withDestruct() else it }
            .let { it.withRoomLifetime(ends, destruct, startsAt) }
            .let { learnRoomRelays(it, roomRelays, roomRelaysSigned) }
            // A room saved before its authority was recorded never followed a
            // rekey; pin the one its link names, as a fresh join would.
            .withInvitationAuthority()
            .withEpochHint(expectedEpoch)
        val record = preparedRecord
        val oldSessionJob = sessionScope?.coroutineContext?.get(Job)
        closeSession(keepEntry = true)
        oldSessionJob?.join()
        closingKeeper?.join(); closingKeeper = null
        closingNearby?.awaitClosed(); closingNearby = null
        val nativeEntry = if (record.nativeAuthority == null) null else NativeKeeperEntry.open(record, roomEpochs,
            { NativeKeeperVault.forSavedRoom(getApplication(), record).open() },
            { binding, initialise -> RoomRekeyVault(getApplication(), binding).open(initialise) })
        nativeKeeperEntry = nativeEntry
        if (freshNearby == null) savedRooms.save(record)
        // Every member's pool includes the room's own relays, first and never
        // cut, so two members always share one. An anonymous room takes only
        // the ones Tor-only mode accepts, and one sheltered behind a Bothy
        // only its route and its circle's relays; it says how many it left out.
        val roomGuard = roomRelayGuard(record)
        val shared = RoomRelays.guarded(record.sharedRelays, roomGuard)
        val activeRelays = nativeEntry?.binding?.relays ?: freshNearby?.relay?.relayUrls ?: if (!route.internet) emptyList() else RoomRelays.atOpen(ownRelays, emptyList(), room = shared.accepted)
            .also { if (anonymousProfile) TorOnlyRelayUrls.assertRoomTransport(it, emptyList()) }
        val forcedRelays = RoomRelays.ofRoom(activeRelays, shared.accepted)
        // The signer has most likely just answered: renew the other Ring me
        // rooms while it will still do so without asking.
        if (route.internet && record.viaAccount) viewModelScope.launch(Dispatchers.IO) {
            dev.forgesworn.kithmoot.service.CredentialRenewal.renewQuietly(getApplication())
        }
        var durableEpoch = record.authority?.let {
            if (nativeEntry != null) requireNotNull(roomEpochs.get(record.id))
            else roomEpochs.initialise(record.id, it, record.secret, epochSeconds())
        }
        if (durableEpoch?.phase == EpochPhase.PENDING_CADENCE_RETIREMENT) {
            durableEpoch = cadenceGate.withLock { resumePendingRoomEpoch(record, who, secondary, durableEpoch!!) }
        }
        if (durableEpoch?.phase == EpochPhase.REMOVED) throw RoomRecoveryException("You were removed from this room")
        if (durableEpoch?.phase == EpochPhase.CLOSED) throw RoomRecoveryException("This room was closed")
        Log.i(JOIN_LOG, "epoch at open durable=${durableEpoch?.currentEpoch ?: "none"} hint=${record.epochHint ?: "none"} authority=${record.authority != null}")
        val openedEpoch = durableEpoch?.let { deriveEpoch(RoomEpoch(it.currentEpoch, it.currentSecret)) }
            ?: EpochKeys(0, derived.roomId, derived.roomKey)
        // The epochs this room left before this opening, so a message that
        // lands late on one is still read (kithmoot-android #128).
        val leftEpochs = pastEpochsFor(record.secret, durableEpoch,
            { roomEpochs.secretAt(record.id, it) }, { roomEpochs.leftAt(record.id, it) }, epochSeconds())
        if (leftEpochs.isNotEmpty()) Log.i(JOIN_LOG, "left epochs at open ${leftEpochs.map { it.keys.epoch }}")
        val epochAuthorityHost = record.host(epochSeconds())?.takeIf {
            it.delegation.isEmpty() && record.authority == Schnorr.publicKeyHex(it.inviterSecretKey)
        }
        // Once the room has removed somebody, its key goes only to people it knows (kithmoot#207):
        // this participant, the authority's member list, whoever this device let in, and anybody
        // in the roster now. Everybody else is asked about.
        var liveForDesks: RoomSession? = null
        val known: (String) -> Boolean = { p ->
            p.equals(who.participant, ignoreCase = true) || roomMembers.knows(record.id, p) || liveForDesks?.hasParticipant(p) == true
        }
        val epochResponder = epochAuthorityHost?.let {
            EpochRecoveryResponder(roomEpochs, record.id, it.inviterSecretKey, derived.roomKey, record.policy, record.ends, ::epochSeconds,
                known = known, onUnknown = { request -> askToLetIn(record.id, request.participant) })
        }
        // Any member in step at an epoch past 0 can bring another member's device up to date
        // while the authority's device is away (kind 20471/20472). Not on the authority's own
        // device, which answers as the authority, and never in an anonymous room.
        val memberDesk = record.authority?.takeIf { !anonymousProfile && epochResponder == null && nativeEntry == null }?.let { roomAuthority ->
            MemberEpochResponder(
                record.id, roomAuthority, who.deviceSecretKey, derived.roomKey, record.policy,
                current = {
                    roomEpochs.get(record.id)?.takeIf { it.phase == EpochPhase.ACTIVE && it.currentEpoch > 0 }
                        ?.let { RoomEpoch(it.currentEpoch, it.currentSecret) }
                },
                secretAt = { roomEpochs.secretAt(record.id, it) },
                rekeyAt = { roomEpochs.rekeyAt(record.id, it) },
                removed = { roomEpochs.get(record.id)?.removed.orEmpty() },
                known = known,
                closed = { roomEpochs.get(record.id)?.phase == EpochPhase.CLOSED },
                now = ::epochSeconds,
                ends = record.ends,
                onUnknown = { request -> askToLetIn(record.id, request.participant) },
            ).asDesk(Dispatchers.IO)
        }
        val summaries = savedRooms.list()
        _start.update { it.copy(savedRooms = summaries) }
        roomBookmarks?.takeIf { route.internet }?.let { bookmarks -> accountBookmark(record, bookmarks.identity)?.let { bookmark ->
            // Opening an account room is the person's own account action: sent now, the
            // bookmark carries its own time rather than one held back minutes.
            AccountWriteHold.process.personActed()
            changeRoomBookmarks { it.save(bookmark) }
        } }
        savedRoom = record
        val scope = freshNearby?.scope ?: CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))
        val linkRoute = ActiveLinkRoute { url -> linkConsents.activeRoute(who.participant, record.id, url) }
        val socketFactory: RelaySocketFactory? = if (freshNearby != null || !route.internet) null else if (anonymousProfile) OrbotTorRelaySockets()
            else HybridRelaySockets(OkHttpRelaySockets(), linkEngine, linkRoute)
        val accountIdentity = who as? PrimaryIdentity
        val authenticators = RelayAuthenticatorProvider { url ->
            if (!anonymousProfile) linkConsents.activeRoute(who.participant, record.id, url)?.let {
                accountIdentity?.let { primary -> object : RelayAuthenticator {
                    override val pubkey = primary.participant
                    override suspend fun sign(url: String, challenge: String) = primary.signer.sign(22242, epochSeconds(),
                        listOf(listOf("relay", url), listOf("challenge", challenge)), "")
                } }
            } else null
        }
        val relay = freshNearby?.relay ?: socketFactory?.let { sockets -> RelayPool(activeRelays, sockets, scope,
            policy = if (anonymousProfile) TorCarrierTimings.policy else RelayPolicy(),
            readRelays = if (anonymousProfile) activeRelays.toSet() else selectedReadRelays(activeRelays) + forcedRelays,
            writeRelays = if (anonymousProfile) activeRelays.toSet() else selectedWriteRelays(activeRelays) + forcedRelays,
            circle = if (anonymousProfile) { { emptySet() } } else ::circleRelaySet,
            authenticators = authenticators) }
        // Register teardown before starting either path. Failure during entry
        // follows the same closeSession path as leaving an established room.
        pool = relay
        sessionScope = scope
        val nearby = if (freshNearby != null) {
            nearbyOwner = freshNearby.owner
            nearbyTransport = freshNearby.mesh
            check(appVisible) { "Keep KithMoot on screen while joining nearby." }
            freshNearby.mesh
        } else if (route.nearby) {
            val discovery = RoomNearbyDiscovery.scope(record.id)
            val owner = roomNearbyOwnership.open(RoomBleConfig(discovery,
                Entropy.bytes(32).toHex(), RoomNearbyDiscovery.serviceUuid(discovery))) {
                nearbyLinkFactory(getApplication())
            }
            nearbyOwner = owner
            check(appVisible) { "KithMoot left the foreground while Bluetooth was starting." }
            RoomMeshTransport(discovery, owner.link).also { nearbyTransport = it }
        } else null
        val transport: RoomTransport = freshNearby?.transport ?: when (route) {
            RoomRoute.INTERNET -> checkNotNull(relay)
            RoomRoute.NEARBY -> checkNotNull(nearby)
            RoomRoute.MIXED -> HybridRoomTransport(checkNotNull(nearby), checkNotNull(relay))
        }
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
                                val view = scheduleView(context, derived.roomId, who.devicePubkey)
                                _room.update { state -> state.copy(cadence = view) }
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
        val pendingChat = if (!anonymousProfile && quiet == null) {
            dev.forgesworn.kithmoot.storage.PendingChatVault(getApplication(), record.id,
                who.participant, who.devicePubkey).outbox
        } else null
        // When the authority rekeyed into each epoch, from the signed rekeys
        // this visit hears: a rename read under an epoch the room has left
        // counts only up to the rekey out of it (protocol/RoomName.kt).
        val rekeyTimes = java.util.concurrent.ConcurrentHashMap<Int, Long>()
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
            initialPastEpochs = leftEpochs,
            initialRemoved = durableEpoch?.removed.orEmpty(),
            // Told the room is further on than this device, ask its authority
            // before saying anything, as the web client does; told nothing,
            // ask once without holding the room up - except in a quiet room,
            // whose traffic is shaped not to say when a member opens it.
            expectedEpoch = if (nativeEntry != null) openedEpoch.epoch else record.epochHint,
            requireFreshEpoch = freshNearby != null,
            epochProbe = quietMembers == null && nativeEntry == null,
            chatOutbox = pendingChat,
            ends = record.ends,
            epochGate = if (anonymousProfile || record.authority == null) null else { event, notice ->
                withContext(Dispatchers.IO) {
                    cadenceGate.withLock { commitRoomEpoch(record, who, secondary, event, notice) }
                }
            },
            onEpochApplied = { notice, next ->
                // A catch-up grant's time is the grant's, not the rekey's.
                if (!notice.catchUp) rekeyTimes.putIfAbsent(notice.epoch, notice.at)
                roomWork?.rekey(next.id, next.key, next.epoch)
            },
            onEpochBlocked = ::stopMediaForEpoch,
            onEpochReady = {
                if (!anonymousProfile && !chatOnly) session?.let { current -> startMedia(current, scope, who) }
            },
            epochResponder = epochResponder?.let { responder ->
                { request -> responder.answer(request) }
            },
            memberEpochDesk = memberDesk,
            onMembers = { members -> withContext(Dispatchers.IO) { roomMembers.setMembers(record.id, members) } },
            onEpochHistory = if (anonymousProfile || record.authority == null) { _, _, _ -> } else { secrets, rekeys, leftAt ->
                record.authority?.let { authority -> for (rekey in rekeys) peekRekeyEpoch(rekey, record.id, authority)?.let { rekeyTimes[it] = rekey.createdAt } }
                withContext(Dispatchers.IO) { roomEpochs.remember(record.id, secrets, rekeys, leftAt) }
            },
            onVerifiedOwnEvent = if (route.internet && !anonymousProfile && accountSession?.account?.pubkey == who.participant) {
                { event: NostrEvent ->
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            nip77Events.record(who.participant, record.id, event)
                            nip77Offers.record(who.participant, record.id, event)
                        }
                    }
                }
            } else {
                { _: NostrEvent -> }
            },
        )

        if (nativeEntry != null) live.holdKeeperStartup()
        sessionScope = scope
        pool = relay
        session = live
        liveForDesks = live
        identity = who
        roomSecret = secret
        roomInvitation = record.invitation
        roomInvitationHost = record.host(epochSeconds())
        relayUrls = activeRelays
        synchronized(torOnlyHoldLock) {
            anonymousRoom = anonymousProfile
            if (anonymousProfile && !torOnlySessionHeld) { torOnlySessionHeld = true; AccountWriteHold.process.torOnlyRoomOpened() }
        }
        releaseTorOnlyEntry()
        val nip77Ready = route.internet && !anonymousProfile && record.viaAccount && accountSession?.account?.pubkey == who.participant &&
            ownRelays.singleOrNull()?.let { LinkRelayAddress.canonical(it) == it && it in circleRelaySet() } == true

        _room.value = RoomState(
            // The device's remembered choice, not the constructor's default:
            // this state becomes the camera's background when media starts.
            background = backgrounds.load(),
            roomId = derived.roomId,
            route = route,
            nearby = nearbyOwner?.link?.state?.value,
            nativeHosting = nativeEntry?.let { NativeHostingState.starting(it.binding).let { state ->
                if (appVisible) state else state.paused()
            } },
            mediaRunning = !route.nearby,
            name = record.name,
            joinUrl = if (nativeEntry == null) selectedWebApp.roomLink(record.joinUrl) else "",
            anonymous = anonymousProfile,
            relaysTotal = activeRelays.size,
            lane = roomLane(activeRelays, anonymousProfile, ::circleRelaySet),
            privateConversation = isDmPolicy(policy),
            chatOnly = chatOnly,
            profilesEnabled = route.internet && !anonymousProfile && display.getBoolean("publicProfiles", true),
            mirrorSelf = display.getBoolean(MIRROR_SELF, true),
            selfParticipant = who.participant,
            selfDevice = who.devicePubkey,
            secondary = secondary,
            quiet = quiet != null,
            quietCanSend = quiet?.canSend ?: true,
            cadence = if (quiet == null) null else initialCadenceView(cadenceAccess, derived.roomId, who.devicePubkey),
            nip77 = if (anonymousProfile) null else Nip77ViewState(
                available = nip77Ready,
                detail = if (nip77Ready) "Compare up to 30 days of this room's outer event IDs with its verified Bothy. No messages move."
                    else "Connect this account-owned room to one verified Link-carried Bothy before comparing history.",
            ),
            canAddDevice = route.internet && who is PrimaryIdentity && !anonymousProfile,
            canRotateInvitation = route.internet && record.host(epochSeconds())?.delegation?.isEmpty() == true,
            canShowCard = route.internet && who is PrimaryIdentity && !anonymousProfile,
            endsAt = record.ends,
            destruct = record.destruct,
            startsAt = record.startsAt,
            notice = listOfNotNull(
                "Messages retained under the previous room key were marked Conversation rekeyed.".takeIf { discardedOldQuiet },
                RoomRelays.refusalNotice(shared.refused.size, torOnly = anonymousProfile),
            ).joinToString(" ").ifEmpty { null },
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
        if (route.internet && !anonymousProfile) refreshContacts()

        // Nearby + Internet selects the room's relay set. Profile reads on this
        // route share it instead of silently opening extra public relay sockets.
        val profileTransport = if (!route.internet || anonymousProfile) null else if (route.nearby) relay
            else RelayPool((activeRelays + PROFILE_RELAYS).distinct(), OkHttpRelaySockets(), scope)
        profilePool = profileTransport
        if (profileTransport != null) scope.launch {
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
        relay?.start()
        profileTransport?.start()
        record.host(epochSeconds())?.let { host ->
            invitationHostJob = serveInvitation(scope, transport, host, secret) { live.epochKeys().epoch }
            if (relay != null && host.invitation.persistent && host.delegation.isEmpty() && record.policy?.members.isNullOrEmpty()) {
                // A room that is anonymous or sheltered behind a Bothy keeps
                // exactly its own relays, as savedRoomRelays does.
                val linkRelays = if (anonymousProfile || linkConsents.all().any { it.roomId == record.id }) emptyList() else record.invitation?.relays.orEmpty()
                // Every copy says what the first said: self-destruct sticks, so a copy without it would only confuse.
                keepGroupInvitationAlive(scope, relay, host, secret, linkRelays, activeRelays, record.ends,
                    record.roomRelays.takeIf { record.roomRelaysSigned && !keepsOwnRelays(record) }, record.destruct)
            }
        }
        if (relay != null && nativeEntry == null) readRoomRelaysOnce(scope, relay, record)
        record.ends?.let { ends -> endConferenceAt(live, scope, ends, record.id) }
        if (freshNearby == null) live.join() else {
            freshNearby.join(live)
            savedRooms.saveNew(record)
            _start.update { it.copy(savedRooms = savedRooms.list()) }
        }
        if (nativeEntry != null) {
            val q = nativeEntry.binding.let { b -> dev.forgesworn.kithmoot.epoch.RoomRekeyBinding(
                b.room, b.authority, b.device, b.meshScope, b.relays, b.route) }
            val controller = nativeEntry.start(live, NativeKeeperEndpoints(q, nearby, relay), scope,
                { session === live && nativeKeeperEntry === nativeEntry && appVisible })
            nativeKeeperController = controller
            fun attached() = session === live && nativeKeeperEntry === nativeEntry &&
                nativeKeeperController === controller && appVisible
            scope.launch {
                controller.hosting.collect { hosting ->
                    _room.update { state ->
                        if (attached() && state.roomId == record.id && state.nativeHosting?.binding?.pin == hosting.binding.pin)
                            state.copy(nativeHosting = hosting,
                                joinUrl = if (hosting.canShareInvitation && !record.retired)
                                    selectedWebApp.roomLink(record.joinUrl) else "",
                                letInAsks = if (hosting.canShareInvitation) controller.unknownParticipants.value
                                    .map { p -> LetInAsk(p, letInLabel(p)) } else emptyList()) else state
                    }
                    if (attached() && hosting.lifecycle in setOf(NativeHostingLifecycle.RETIRED, NativeHostingLifecycle.CLOSED)) {
                        withContext(Dispatchers.IO) {
                            try {
                                if (attached()) {
                                    val current = savedRooms.get(record.id)
                                    if (current != null && current.nativeAuthority?.pin == hosting.binding.pin && !current.retired) {
                                        val repaired = savedRooms.update(record.id) { stored ->
                                            if (stored.nativeAuthority?.pin == hosting.binding.pin) stored.invitationRetired() else stored
                                        }
                                        if (attached()) savedRoom = repaired
                                        _start.update { it.copy(savedRooms = savedRooms.list()) }
                                    }
                                }
                            } catch (cancel: CancellationException) { throw cancel }
                            catch (_: Exception) {
                                _room.update { if (attached()) it.copy(notice =
                                    "The invitation is retired. Its saved-room label could not be updated; reopen to try again.") else it }
                            }
                        }
                    }
                }
            }
            scope.launch {
                controller.unknownParticipants.collect { participants ->
                    _room.update { if (attached()) it.withNativeUnknownApprovals(participants.map { p -> LetInAsk(p, letInLabel(p)) }) else it }
                }
            }
            scope.launch {
                controller.state.collect { state ->
                    if (state == NativeKeeperController.State.Failed || state == NativeKeeperController.State.Suspended)
                        _room.update { if (attached()) it.copy(notice = "Room hosting is paused. Reopen the room to inspect its saved state.") else it }
                    if (state is NativeKeeperController.State.Closed && attached())
                        viewModelScope.launch(Dispatchers.IO) { roomClosed(record.id) }
                }
            }
        }
        loadRoomSharing(live, record, scope)
        if (pendingChat != null) {
            // The list follows the journal; a message leaves it when it shows in the chat.
            scope.launch {
                live.pendingChats.collect { list ->
                    _room.update { if (session === live) it.copy(pendingChats = list, chatPending = list.isNotEmpty()) else it }
                }
            }
            scope.launch(Dispatchers.IO) {
                live.chat.collectLatest { if (live.pendingChats.value.isNotEmpty()) live.reconcilePendingChats() }
            }
            scope.launch(Dispatchers.IO) {
                try {
                    live.refreshPendingChats()
                    live.reconcilePendingChats()
                    if (live.pendingChat()) retryPendingChat()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    _room.update { if (session === live) it.copy(
                        chatSendError = error.message ?: "A message remains on this phone. Retry when connected.") else it }
                }
            }
        }
        Log.i(
            JOIN_LOG,
            "joined room=${derived.roomId.take(8)} device=${who.devicePubkey.take(8)} " +
                "relaysUp=${relay?.connected?.value?.size ?: 0}/${activeRelays.size} " +
                "outbox=${relay?.outboxDepth() ?: -1} " +
                "epoch=${live.epochState.value.javaClass.simpleName} openMs=${android.os.SystemClock.elapsedRealtime() - openBegan}",
        )
        // Everything below is what makes a room a room: the shared work
        // journal, the monitor claim, notifications, the tiles and chat
        // collectors, the relay counter and the media engine. All of it used
        // to be abandoned outright when the room opened at a non-active epoch
        // - one `return@withLock` - and nothing ever came back for it. The
        // only route out was the person pressing Join and being told audio and
        // video were "still starting", which was true and stayed true.
        //
        // So it is a block that runs at an active epoch, which is almost
        // always at once, and otherwise waits on the epoch state itself rather
        // than on a timer that fires once and gives up.
        val activate: suspend () -> Unit = {
            if (!anonymousProfile) {
                // Verification, replay and encrypted persistence must not run on the UI thread.
                val workScope=CoroutineScope(scope.coroutineContext+Dispatchers.IO)
                val liveEpoch=live.epochKeys()
                val work = RoomWork(record.id,derived.roomKey,who,quiet?:transport,
                    AssignmentVault(getApplication(),record.id,who.participant),workScope,policy,
                    initialTrafficRoomId=liveEpoch.id,initialTrafficRoomKey=liveEpoch.key,
                    authority=record.authority,initialRoomRelays=record.roomRelayRecord,
                    onRoomRelays={ relaysRecord,sentAt -> onRoomRelaysReceived(record.id,relaysRecord,sentAt) },
                    ends=record.ends,
                    initialEpoch=liveEpoch.epoch,
                    initialRoomName=record.sharedName,
                    rekeyedAt={ epoch -> rekeyTimes[epoch] ?: runCatching { roomEpochs.rekeyAt(record.id, epoch)?.createdAt }.getOrNull() },
                    onRoomName={ shared -> onRoomNameReceived(record.id, shared) },
                    onRename={ rename -> onRenameRead(derived.roomId, rename) },
                    onMeetingNews={ news -> if (session === live) onMeetingNews(news) },
                    // The room's own key, only on the device that made it: the
                    // one device that may run its calls as meetings.
                    authoritySecretKey=epochAuthorityHost?.inviterSecretKey,
                    recordingStops=recordingApplication.recordingStops)
                roomWork=work
                scope.launch { work.recordingStopPending.collect { pending ->
                    _room.update { if (roomWork === work) it.copy(recordingStopPending = pending != null) else it }
                } }
                scope.launch { work.meeting.state.collect { snapshot -> if (roomWork === work) adoptMeeting(snapshot) } }
                // A notice that stops being reposted turns unconfirmed, then
                // goes, with nothing arriving to say so: the clock does.
                scope.launch { while (isActive) { delay(30_000); if (roomWork === work) showRecording() } }
                scope.launch { work.journal.state.collect { snapshot -> _room.update { if(roomWork===work)it.copy(work=snapshot)else it } } }
                scope.launch { work.actions.collect { actions -> _room.update { if(roomWork===work)it.copy(workActions=actions)else it } } }
                scope.launch { work.error.collect { error -> if(error!=null)_room.update{if(roomWork===work)it.copy(workError=error)else it} } }
                scope.launch(Dispatchers.IO) {
                    try {work.open()} catch(cancelled:CancellationException){throw cancelled}
                    catch(_:Exception){_room.update {if(roomWork===work)it.copy(workError="Shared work could not connect. Check the room connection and try again.")else it}}
                }
            }
            // This device plays the room's audio unless one of your others takes it
            // over. Claiming rather than assuming is what lets that handover happen.
            if (live.localRoles.value.monitorDevice == null) live.claim(Roles.MONITOR)

            // What the background inbox has seen does not alert again here.
            val known = if (chatOnly) emptyList() else withContext(Dispatchers.IO) { backgroundSeen(record) }
            if (!chatOnly) notifications.begin(record.id, record.name, who.participant, epochSeconds(), dev.forgesworn.kithmoot.session.isDmPolicy(record.policy), known)
            // The background service (service/BackgroundCallListenerService.kt)
            // skips any room open here: this coordinator already rings for it,
            // and this room shows its messages. What it received while the room
            // was closed is now read.
            withContext(Dispatchers.IO) { markBackgroundRead(record) }
            // Kept current while open, not only at open and close: if the process
            // dies with the room open, the restarted service must neither alert
            // what was read here again nor lose what alerted here unread.
            if (!chatOnly) notifications.inbox = { read, alerted, shown ->
                CoroutineScope(backgroundInboxWrites).launch {
                    runCatching {
                        val inbox = dev.forgesworn.kithmoot.storage.BackgroundInboxVault(getApplication(), record.id, record.participant, record.devicePubkey).inbox
                        inbox.rememberActivity(shown)
                        alerted.forEach(inbox::recordAlerted)
                        if (read) inbox.markRead(epochSeconds(), shown)
                    }
                }
            }
            dev.forgesworn.kithmoot.notifications.ActiveRoomRegistry.mark(record.id)
            // Reply on this room's notification goes through this session, not a second connection.
            // Read once, off the main thread; whether its credential still lasts is asked at each post.
            val replyRoom = withContext(Dispatchers.IO) {
                dev.forgesworn.kithmoot.service.noticeReplyRoom(getApplication(), record.id, epochSeconds())
            }
            if (!chatOnly) notifications.replyable = {
                replyRoom != null && dev.forgesworn.kithmoot.notifications.canReplyFromNotice(replyRoom, epochSeconds())
            }
            val replier: suspend (String) -> dev.forgesworn.kithmoot.notifications.ReplyOutcome = { text -> replyFromNotice(live, text) }
            noticeReplier = record.id to replier
            dev.forgesworn.kithmoot.notifications.OpenRoomReplies.register(record.id, replier)
            scope.launch {
                combine(live.participants, live.chat) { people, chat -> people to chat }
                    .collect { (people, chat) ->
                        shownChat = chat
                        if (!chatOnly) notifications.accept(chat)
                        // Best-effort, for the background call listener's caller
                        // label while the app is closed - see
                        // service/BackgroundParticipantCache.kt.
                        dev.forgesworn.kithmoot.service.BackgroundParticipantCache(getApplication()).remember(record.id, people)
                        // The names this room shows, so a ring can name its caller.
                        dev.forgesworn.kithmoot.notifications.CallerNames.remember(getApplication(), people.mapNotNull { person ->
                            val name = _room.value.profiles[person.participant]?.name
                                ?: person.devices.firstNotNullOfOrNull { it.name?.takeIf(String::isNotBlank) }
                            name?.let { person.participant to it }
                        }.toMap())
                        // The room's current call is the head of the same list
                        // every other client picks from - see RoomSession.calls.
                        val current = callsOf(people).firstOrNull()
                        val onCall = current?.devices.orEmpty()
                        if (!route.nearby) callRinger.update(record.id, _room.value.name.ifBlank { record.name }, current?.id, current?.starter(people), who.participant, onCall.contains(who.devicePubkey))
                        _room.update { it.copy(
                            tiles = buildTiles(people, who.participant, who.devicePubkey, cardNames, volumesFor(people)),
                            chat = chat,
                            callOtherDevices = onCall.count { device -> device != who.devicePubkey },
                            privateConversationPeers = if (route.internet && !anonymousProfile && accountSigner != null && !isDmPolicy(policy) && quiet == null) {
                                people.map { it.participant }.filter { it != who.participant }
                            } else emptyList(),
                        ) }
                    }
            }
            scope.launch {
                relay?.connected?.collect { up ->
                    gate.withLock {
                        if (session !== live) return@withLock
                        Log.i(
                            JOIN_LOG,
                            "relays up=${up.size}/${activeRelays.size} outbox=${(transport as? RelayPool)?.outboxDepth() ?: -1}",
                        )
                        _room.update { it.copy(relaysUp = up.size) }
                        // The transport's offline queue is bounded and expires. Replay
                        // durable retirements on reconnect, including rotations made
                        // during this session, so a long outage cannot drop them.
                        if (up.isNotEmpty()) savedRoom?.retirements?.forEach(transport::publish)
                        if (up.isNotEmpty() && _room.value.chatPending && pendingChat != null) {
                            scope.launch { retryPendingChat() }
                        }
                    }
                }
            }
            nearbyOwner?.let { owner -> scope.launch {
                owner.link.state.collect { state ->
                    if (session === live) _room.update { it.copy(nearby = state) }
                }
            } }
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

            if (!anonymousProfile && !chatOnly) startMedia(live, scope, who)
        }
        val epochAtOpen = live.epochState.value
        if (epochAtOpen is dev.forgesworn.kithmoot.session.RoomEpochState.Active) {
            Log.i(JOIN_LOG, "epoch active at open epoch=${epochAtOpen.epoch}")
            activate()
        } else {
            Log.i(JOIN_LOG, "epoch not active at open state=${epochAtOpen.javaClass.simpleName} - media waits for it")
            _room.update { it.copy(mediaStarting = true) }
            scope.launch {
                val settled = kotlinx.coroutines.withTimeoutOrNull(EPOCH_ACTIVATION_TIMEOUT_MS) {
                    live.epochState.first {
                        it is dev.forgesworn.kithmoot.session.RoomEpochState.Active ||
                            it is dev.forgesworn.kithmoot.session.RoomEpochState.Removed ||
                            it is dev.forgesworn.kithmoot.session.RoomEpochState.Closed
                    }
                }
                gate.withLock {
                    if (session !== live) return@withLock
                    if (settled is dev.forgesworn.kithmoot.session.RoomEpochState.Active) {
                        Log.i(JOIN_LOG, "epoch became active epoch=${settled.epoch} - starting the room")
                        _room.update { it.copy(mediaStarting = false) }
                        activate()
                    } else {
                        Log.i(JOIN_LOG, "epoch never became active settled=${settled?.javaClass?.simpleName ?: "timeout"}")
                        _room.update {
                            it.copy(
                                mediaStarting = false,
                                callJoinPending = false,
                                callJoinMicPending = false,
                                mediaFault = "This room did not finish its secure update, so audio and video could not start. Leave and open it again.",
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Build media once for this session, however many callers ask.
     *
     * Two do at a recovered epoch - the epoch-ready callback and the waiter
     * that starts the room when its epoch goes active - and "is there an
     * engine yet" is not a guard against them, because it only becomes true
     * after ICE and a factory. See [SingleBuild].
     */
    private fun startMedia(live: RoomSession, scope: CoroutineScope, who: RoomIdentity) {
        if (session !== live || savedRoom?.route?.nearby == true) return
        if (engine != null || mediaBuild.inFlight) return
        _room.update { it.copy(mediaStarting = true) }
        opening = scope.launch {
            val begun = android.os.SystemClock.elapsedRealtime()
            Log.i(JOIN_LOG, "media build begins")
            var remembered = false
            var rememberedMicOn = false
            val installed = mediaBuild.build(
                held = { engine },
                make = {
                    val ice = withContext(Dispatchers.Default) { dev.forgesworn.kithmoot.media.CallIceServers.resolve() }
                    Log.i(JOIN_LOG, "ice resolved servers=${ice.size} turn=${ice.count { server -> server.urls.any { it.startsWith("turn") } }} afterMs=${android.os.SystemClock.elapsedRealtime() - begun}")
                    withContext(Dispatchers.Default) {
                        runCatching { WebRtcEngine(getApplication(), live, this@launch, ice) }
                    }.getOrElse { failure ->
                        Log.w(JOIN_LOG, "media failed to build after ${android.os.SystemClock.elapsedRealtime() - begun}ms", failure)
                        _room.update { it.copy(
                            mediaStarting = false,
                            callJoinPending = false,
                            callJoinMicPending = false,
                            mediaFault = "Audio and video are unavailable on this device: " +
                                (failure.message ?: failure::class.java.simpleName),
                        ) }
                        null
                    }
                },
                install = { media ->
                    synchronized(mediaControlLock) {
                        if (session !== live || engine != null) return@synchronized false
                        Log.i(JOIN_LOG, "media built in ${android.os.SystemClock.elapsedRealtime() - begun}ms")
                        engine = media
                        media.localMedia.onScreenShareStopped = { stopScreenShare() }
                        media.localMedia.onCameraLost = { cameraLost() }
                        media.localMedia.onBackgroundTrouble = { message -> showNotice(message) }
                        media.localMedia.setBackground(_room.value.background)
                        media.localMedia.setAppVisible(appVisible)
                        // A Join pressed while there was nothing to join with.
                        // It was remembered rather than refused, and this is
                        // where it happens - no second tap, no timer.
                        remembered = _room.value.callJoinPending
                        rememberedMicOn = _room.value.callJoinMicPending
                        val running = _room.value.mediaRunning || remembered
                        media.setCallActive(running)
                        media.start()
                        _room.update { it.copy(mediaStarting = false, callJoinPending = false, callJoinMicPending = false, mediaRunning = running) }
                        true
                    }
                },
            )
            if (!installed) return@launch
            val media = engine ?: return@launch
            if (remembered) {
                Log.i(JOIN_LOG, "remembered join carried out now that media exists")
                if (live.localRoles.value.monitorDevice == null) live.claim(Roles.MONITOR)
                adoptRoomCall(pressed = true)
                if (rememberedMicOn) startMicrophoneForJoin(live)
            }
            launch { media.connections.collect { connections -> _room.update { if (session === live) it.copy(mediaConnections = connections) else it } } }
            launch {
                media.speakingGain = { device ->
                    live.participants.value.firstOrNull { p -> p.devices.any { it.device == device } }
                        ?.let { callVolume.gainFor(it.participant).toDouble() } ?: 1.0
                }
                val micLive = _room.map { it.micOn && !it.micMuted }.distinctUntilChanged()
                combine(media.speakingDevices, media.selfSpeaking, micLive, live.participants, meetingState) { devices, self, micOn, people, meeting ->
                    val mine = self && micOn
                    // Nobody off the stage is shown speaking: they are not heard.
                    people.filter { p -> p.devices.any { it.device in devices } && meetingAllows(meeting.meeting, p.participant) }.map { it.participant }.toSet() +
                        (if (mine) setOf(who.participant) else emptySet())
                }.distinctUntilChanged().collect { speaking ->
                    _room.update { if (session === live) it.copy(speaking = speaking) else it }
                }
            }

            launch {
                combine(media.remoteTracks, media.localMedia.tracks, live.participants, meetingState, callHeld) { arrived, local, people, meeting, _ ->
                    // In meeting mode a picture from anybody off the stage is
                    // not shown, whatever their app sends: see protocol/Meeting.kt.
                    val remote = arrived.filter { !meetingGated(meeting.meeting, ownerOf(it.device, people)) }
                    // Role, not the WebRTC track id, is the tile's identity:
                    // a receiver's track id never matches the sender's once a
                    // slot is swapped, and a renegotiation mints a fresh one
                    // regardless. The roster's advertised trackId->role
                    // mapping is what ties a live receiver back to the slot
                    // it fills. See resolveRemoteByRole and H5 in the call
                    // reliability spec.
                    val roleForTrackId: (String, String) -> String? = { device, trackId ->
                        people.firstNotNullOfOrNull { participant ->
                            participant.tracks.firstOrNull { it.device == device && it.trackId == trackId }?.role
                        }
                    }
                    buildMap {
                        putAll(
                            resolveRemoteByRole(
                                remote = remote.filter { it.track is VideoTrack },
                                device = { it.device },
                                trackId = { it.trackId },
                                receiving = { it.receiving },
                                roleForTrackId = roleForTrackId,
                                valueFor = { it.track as VideoTrack },
                                declaredRole = { it.role },
                                // A face before a screen, as the web client guesses.
                                advertisedRoles = { device ->
                                    val advertised = people.flatMap { it.tracks }.filter { it.device == device }.map { it.role }
                                    listOf(Roles.CAMERA, Roles.SCREEN).filter { it in advertised }
                                },
                            ),
                        )
                        for (track in local) (track.track as? VideoTrack)?.let { put(roleKey(who.devicePubkey, track.role), it) }
                    }
                }.collect {
                    _videos.value = it
                    if (session === live && engine === media) refreshRecordingVideo(media, live)
                }
            }
            launch {
                combine(media.localMedia.tracks, media.remoteTracks, live.localRoles) { local, remote, roles ->
                    val listeningHere = media.callActive && (roles.monitorDevice == null || roles.holdsMonitor)
                    _room.update { if (session === live) it.copy(listeningHere = listeningHere) else it }
                    local.any { it.microphoneOn } || (listeningHere && remote.any { it.track is AudioTrack })
                }.distinctUntilChanged().collect { active -> media.audioRouting.setActive(active && media.callActive) }
            }
            launch { dev.forgesworn.kithmoot.telecom.CallTelecom.audio.collect { media.audioRouting.handToTelecom(it) } }
            launch {
                combine(media.remoteTracks, live.participants, live.localRoles, callHeld, meetingState) { remote, people, roles, held, meeting ->
                    val mine = people.firstOrNull { it.participant == who.participant }
                        ?.devices?.map { it.device }?.toSet() ?: emptySet()
                    val listeningHere = media.callActive && (roles.monitorDevice == null || roles.holdsMonitor)
                    // Every remote audio track is judged the same way here
                    // regardless of its advertised role - a microphone and a
                    // screen share's own sound are both just "incoming
                    // sound" once negotiated. See shouldPlayRemoteAudio.
                    // Which participant each device belongs to, so a track
                    // handed over on renegotiation still gets that person's
                    // remembered volume rather than the untouched default.
                    val deviceParticipant = people.flatMap { p -> p.devices.map { it.device to p.participant } }.toMap()
                    // A receiver whose transceiver is not actually receiving
                    // is the stale-muted-receiver shape H5 describes: it must
                    // not keep playing over the live one.
                    remote.filter { it.receiving }.mapNotNull { track ->
                        (track.track as? AudioTrack)?.let { audio ->
                            // In meeting mode, nobody off the stage is played,
                            // and a device nobody can be placed is off it.
                            val play = !held && shouldPlayRemoteAudio(track.device, mine, listeningHere) &&
                                !meetingGated(meeting.meeting, deviceParticipant[track.device])
                            val gain = deviceParticipant[track.device]?.let(callVolume::gainFor) ?: CallVolume.DEFAULT_GAIN
                            Triple(audio, play, gain)
                        }
                    }
                }.collect { decisions ->
                    for ((track, play, gain) in decisions) runCatching {
                        track.setEnabled(play)
                        track.setVolume(gain.toDouble())
                    }
                    // The recording uses this original call's authorised
                    // playback sources, including hold/monitor/meeting gates.
                    // Outgoing microphone mute and app mixing happen inside
                    // WebRtcEngine before its recording callback.
                    runCatching { media.setRecordingInputs(
                        decisions.filter { it.second }.associate { it.first to it.third.toDouble() },
                        localAllowed = media.callActive && !callHeld.value &&
                            meetingAllows(meetingState.value.meeting, who.participant),
                    ) }.onFailure { stopNativeRecording(); note("Recording stopped: ${it.message ?: "audio capture failed"}") }
                }
            }
            launch { media.localMedia.tracks.collect(::onLocalTracks) }
            launch {
                live.agentDevices.collect { agents ->
                    _room.update { it.copy(agentCount = agents.size) }
                    applyAudience(media, agents)
                }
            }
            launch {
                val annotationScope = this
                live.annotations.collect { remote ->
                    val label = live.participants.value
                        .firstOrNull { it.participant == remote.participant }
                        ?.devices?.firstNotNullOfOrNull { it.name }
                        ?: shortId(remote.participant)
                    shareMarks.remember(remote.annotation, MarkAuthor(remote.participant, label))
                    pushShareMarks()
                    ensureMarksTicking(scope = annotationScope)
                }
            }
        }
    }

    fun drawOnShare(annotation: dev.forgesworn.kithmoot.protocol.ScreenAnnotation) {
        val live = session ?: return
        val scope = sessionScope ?: return
        if (!dev.forgesworn.kithmoot.protocol.isValidScreenAnnotation(annotation) ||
            _room.value.tiles.none { tile -> tile.videos.any { it.role == Roles.SCREEN && it.trackId == annotation.shareId } }) return
        shareMarks.remember(annotation, MarkAuthor(live.identity.participant, "You"))
        pushShareMarks()
        ensureMarksTicking(scope)
        scope.launch(Dispatchers.Default) {
            for (device in live.remoteDevices.value) {
                if (session !== live) break
                runCatching { live.sendSignal(device, dev.forgesworn.kithmoot.protocol.SignalBody(
                    type = "annotation", roomId = live.room.roomId, annotation = annotation)) }
            }
        }
    }

    /** Recomputes every share's live marks and publishes them, so a fade in
     *  progress is visible without waiting for the next stroke to arrive. */
    private fun pushShareMarks() {
        val ids = shareMarks.shareIds()
        _room.update { it.copy(shareMarks = ids.associateWith { id -> shareMarks.alive(id) }) }
    }

    /** Keeps [pushShareMarks] running while any mark is still fading, and
     *  stops on its own the moment none are - a fixed timer would either
     *  tick for ever or need its own separate teardown. */
    private fun ensureMarksTicking(scope: CoroutineScope) {
        if (marksTicker?.isActive == true) return
        marksTicker = scope.launch {
            while (shareMarks.any()) {
                delay(100)
                pushShareMarks()
            }
        }
    }

    /** Tear down peer connections before an old epoch can continue media exchange. */
    private fun stopMediaForEpoch() {
        stopNativeRecording()
        opening?.cancel()
        opening = null
        engine?.stop()
        engine?.dispose()
        engine = null
        _videos.value = emptyMap()
        _room.update { it.copy(micOn = false, micMuted = false, cameraOn = false, screenOn = false, agentCount = 0) }
    }

    /**
     * A conference room open at its end stops there: the call and the
     * session close, nothing more is signed (it would already have lapsed on
     * every relay that honours NIP-40), and the room says it has ended. The
     * screen stays, so what was said can still be read until it is left.
     */
    private fun endConferenceAt(live: RoomSession, scope: CoroutineScope, ends: Long, roomId: String) {
        scope.launch {
            val wait = (ends - epochSeconds()) * 1000
            if (wait > 0) kotlinx.coroutines.delay(wait)
            withContext(NonCancellable + Dispatchers.IO) {
                val ended = gate.withLock {
                    if (session !== live) return@withLock false
                    val ended = conferenceEndedMessage(ends)
                    _videos.value = emptyMap()
                    try { live.leave() } finally { closeSession() }
                    _room.update { it.copy(conferenceEnded = true, canRotateInvitation = false, onCall = false, mediaRunning = false,
                        micOn = false, micMuted = false, cameraOn = false, screenOn = false, notice = ended) }
                    true
                }
                // A room that self-destructs does not stay to be read: it goes.
                if (ended && runCatching { savedRooms.get(roomId)?.destruct == true }.getOrDefault(false)) {
                    viewModelScope.launch(Dispatchers.IO) { selfDestruct(roomId) }
                }
            }
        }
    }

    /** How many of this visit's epoch trouble lines have been dismissed. */
    private var epochTroubleDismissed = 0

    private fun observeRoomEpoch(live: RoomSession, scope: CoroutineScope) {
        val closedRoom = savedRoom?.id
        epochTroubleDismissed = 0
        scope.launch {
            kotlinx.coroutines.flow.combine(live.epochGaps, live.epochConflicts, ::epochTroubleLines).collect { lines ->
                if (session !== live) return@collect
                _room.update { it.copy(epochTrouble = lines.drop(epochTroubleDismissed)) }
            }
        }
        scope.launch {
            live.epochState.collect { state ->
                if (session !== live) return@collect
                Log.i(JOIN_LOG, "epoch state=${state.javaClass.simpleName}")
                when (state) {
                    is dev.forgesworn.kithmoot.session.RoomEpochState.Active -> _room.update { it.withEpochProgress(state) }
                    is dev.forgesworn.kithmoot.session.RoomEpochState.Updating -> _room.update { it.withEpochProgress(state) }
                    is dev.forgesworn.kithmoot.session.RoomEpochState.RecoveryNeeded -> _room.update {
                        if (state.waitingToBeLetIn) it.copy(movedOn = state.expectedEpoch, roomUpdate = "letin", notice = null)
                        else it.copy(movedOn = state.expectedEpoch, roomUpdate = "recovery", notice = "${state.reason}. Nothing will be sent under the old room key.")
                    }
                    is dev.forgesworn.kithmoot.session.RoomEpochState.Removed -> {
                        stopRoomSharing()
                        roomWork?.close(); invitationHostJob?.cancel(); roomInvitationHost = null
                        _room.update { it.copy(movedOn = state.epoch, roomUpdate = "removed", canRotateInvitation = false, notice = "You were removed from this room") }
                    }
                    is dev.forgesworn.kithmoot.session.RoomEpochState.Closed -> {
                        stopRoomSharing()
                        roomWork?.close(); invitationHostJob?.cancel(); roomInvitationHost = null
                        _room.update { it.copy(movedOn = state.epoch, roomUpdate = "closed", canRotateInvitation = false, notice = "This room was closed") }
                        closedRoom?.let { id ->
                            if (savedRoom?.nativeAuthority == null) viewModelScope.launch(Dispatchers.IO) { roomClosed(id) }
                            // Native destruction follows the controller's durable Closed state above.
                        }
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
        _room.update { it.copy(agentsMayHear = on) }
        val media = engine ?: return
        applyAudience(media, session?.agentDevices?.value ?: emptySet())
    }

    private fun applyAudience(media: WebRtcEngine, agents: Set<String>) {
        media.setAudience(mediaAudience(agents, _room.value.agentsMayHear))
    }

    /** This device's remembered call volume for every one of `people`, for the tiles. */
    private fun volumesFor(people: List<dev.forgesworn.kithmoot.session.Participant>): Map<String, Float> =
        people.associate { it.participant to callVolume.gainFor(it.participant) }

    /**
     * Set how loud `participant` is on this device only, and act on it now.
     *
     * Remembered for next time (see media/CallVolume.kt), reflected on their
     * tile at once, and pushed straight to every one of their live remote
     * audio tracks - not just the next renegotiation. This is only ever a
     * multiplier: the room's own rules about which tracks may actually play
     * (one of a person's own devices, or a device that has left) are decided
     * in the `remoteTracks` combine in [startMedia] and always win, because a
     * disabled track renders no audio whatever its gain is set to.
     */
    fun setCallVolume(participant: String, gain: Float) {
        val clamped = CallVolume.clamp(gain)
        callVolume.setGain(participant, clamped)
        _room.update { state ->
            state.copy(tiles = state.tiles.map { if (it.participant == participant) it.copy(callVolume = clamped) else it })
        }
        val media = engine ?: return
        val devices = session?.participants?.value
            ?.firstOrNull { it.participant == participant }
            ?.devices?.map { it.device }?.toSet()
            ?: return
        for (track in media.remoteTracks.value) {
            if (track.device in devices) (track.track as? AudioTrack)?.let { runCatching { it.setVolume(clamped.toDouble()) } }
        }
    }

    /** Reads saved consent only. No owner or export authority is restored. */
    private suspend fun loadRoomSharing(live: RoomSession, record: SavedRoom, scope: CoroutineScope) {
        if (record.route != RoomRoute.MIXED || record.anonymous || record.policy?.quiet == true || record.destruct || chatOnly) return
        withContext(Dispatchers.IO) {
            val endpoints = pool?.describe()?.map(::canonicalRelayUrl)?.sorted().orEmpty()
            if (endpoints.size !in 1..8 || linkConsents.all().any { it.roomId == record.id }) return@withContext
            val probe = RoomForwardingBinding(record.id, record.participant, record.devicePubkey,
                RoomNearbyDiscovery.scope(record.id), endpoints, setOf(record.participant))
            if (!live.forwardingProfileMatches(probe)) return@withContext
            val stored = runCatching { RoomSharingVault(getApplication(), record.id, record.participant, record.devicePubkey).selection() }
            synchronized(sharingLock) {
                if (session !== live || !appVisible) return@synchronized
                val selected = stored.getOrNull()?.let { it.pending ?: it.current }?.senders.orEmpty()
                _room.update { it.copy(sharing = RoomSharingState(selected = selected, candidates = selected.sorted(),
                    relays = endpoints, previouslySaved = stored.getOrNull() != null,
                    error = if (stored.isFailure) "Saved sharing settings could not be read. Sharing remains off." else null)) }
            }
            scope.launch(Dispatchers.IO) {
                combine(live.participants, live.chat) { people, chat ->
                    (people.map { it.participant } + chat.map { it.participant }).filter { it != record.participant }
                }.collect { people -> synchronized(sharingLock) {
                    if (session === live) _room.update { state -> state.copy(sharing = state.sharing?.let {
                        it.copy(candidates = (it.selected.sorted() + people.distinct().sorted()).distinct().take(500))
                    }) }
                } }
            }
        }
    }

    /** Invalidate dispatch before cancelling jobs or closing any supplied path. */
    fun stopRoomSharing() = synchronized(sharingLock) {
        sharingGeneration++
        sharingWatch?.cancel(); sharingWatch = null
        sharingOwner?.close(); sharingOwner = null
        _room.update { state -> state.copy(sharing = state.sharing?.copy(enabled = false, busy = false)) }
    }

    fun selectSharingParticipant(participant: String, approved: Boolean) = synchronized(sharingLock) {
        val state = _room.value.sharing ?: return@synchronized
        if (participant !in state.candidates || (approved && state.selected.size >= 32 && participant !in state.selected)) return@synchronized
        stopRoomSharing()
        _room.update { room -> room.copy(sharing = room.sharing?.copy(
            selected = if (approved) state.selected + participant else state.selected - participant, error = null)) }
        prepareRoomSharing(enable = false)
    }

    fun startRoomSharing() = prepareRoomSharing(enable = true)

    private fun prepareRoomSharing(enable: Boolean) {
        val record: SavedRoom
        val live: RoomSession
        val mesh: RoomMeshTransport
        val internet: RelayPool
        val scope: CoroutineScope
        val binding: RoomForwardingBinding
        val ticket: Long
        synchronized(sharingLock) {
            val selection = _room.value.sharing ?: return
            if (selection.busy || selection.enabled || (enable && selection.selected.isEmpty())) return
            if (selection.relays.size !in 1..8) {
                _room.update { it.copy(sharing = it.sharing?.copy(error = "Sharing supports up to eight selected Internet connections.")) }
                return
            }
            record = savedRoom ?: return; live = session ?: return
            mesh = nearbyTransport ?: return; internet = pool ?: return; scope = sessionScope ?: return
            binding = RoomForwardingBinding(record.id, record.participant, record.devicePubkey,
                RoomNearbyDiscovery.scope(record.id), selection.relays, selection.selected)
            stopRoomSharing(); ticket = sharingGeneration
            _room.update { it.copy(sharing = it.sharing?.copy(busy = true, error = null)) }
        }
        // No disk, network or radio work in this guard: transport dispatch calls it.
        val selected = {
            val state = _room.value
            sharingGeneration == ticket && appVisible && _stage.value == Stage.ROOM && session === live &&
                nearbyTransport === mesh && pool === internet && sessionScope === scope &&
                savedRoom?.id == binding.room && state.route == RoomRoute.MIXED && !state.anonymous && !state.quiet &&
                !state.destruct && !state.chatOnly && !state.onCall && !state.callChanging &&
                !state.conferenceEnded && state.movedOn == null && state.sharing?.selected == binding.senders &&
                internet.describe().map(::canonicalRelayUrl).sorted() == binding.relays
        }
        scope.launch(Dispatchers.IO) { sharingPreparation.withLock {
            var ledger: dev.forgesworn.kithmoot.session.RoomForwardingLedger? = null
            try {
                check(selected())
                ledger = RoomSharingVault(getApplication(), record.id, record.participant, record.devicePubkey).prepare(binding)
                synchronized(sharingLock) {
                    check(selected())
                    if (!enable) {
                        _room.update { it.copy(sharing = it.sharing?.copy(busy = false)) }
                        return@withLock
                    }
                    val owner = RoomChatForwarder.start(record, linkConsents, live, mesh, internet,
                        checkNotNull(ledger), scope, selected)
                    sharingOwner = owner; ledger = null // The owner now closes this ledger.
                    _room.update { it.copy(sharing = it.sharing?.copy(enabled = true, busy = false, previouslySaved = true)) }
                    sharingWatch = scope.launch(Dispatchers.IO) watch@ {
                        while (isActive) {
                            delay(1_000)
                            synchronized(sharingLock) {
                                if (sharingGeneration != ticket) return@watch
                                if (owner.isFailed() || !selected() || !live.forwardingProfileMatches(binding)) {
                                    stopRoomSharing()
                                    _room.update { it.copy(sharing = it.sharing?.copy(error =
                                        "Connection sharing stopped. Reopen its settings to resume when the room is ready.")) }
                                    return@watch
                                }
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { synchronized(sharingLock) {
                if (sharingGeneration == ticket) {
                    stopRoomSharing()
                    _room.update { it.copy(sharing = it.sharing?.copy(error =
                        "Connection sharing could not start. The room must be active and its saved sharing state intact.")) }
                }
            } } finally { ledger?.close() }
        } }
    }

    fun leave() {
        if (!entering.tryAcquire()) {
            // The gate is held by an entry that has not finished. Saying so on
            // the room's own snackbar is the point: a Leave tap that does
            // nothing and says nothing is indistinguishable from a frozen app.
            note("This room is still opening. Leave will work in a moment.")
            Log.i(JOIN_LOG, "leave refused reason=room-still-opening")
            return
        }
        val live = session
        stopNativeRecording()
        // Withdraw the native authority before the screen changes or IO leave
        // starts; a queued approval/retry must not keep hosting behind Home.
        nativeKeeperEntry?.close()
        stopRoomSharing()
        // The screen changes at once; the last announce and the teardown are a
        // signature and a pile of socket closes, and nobody should watch them.
        _videos.value = emptyMap()
        _room.value = RoomState(background = backgrounds.load())
        _stage.value = Stage.START
        _start.update { it.copy(busy = true) }
        val began = android.os.SystemClock.elapsedRealtime()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sessionJob = sessionScope?.coroutineContext?.get(Job)
                gate.withLock { try { live?.leave() } finally { closeSession() } }
                sessionJob?.join()
            } finally {
                entering.release()
                Log.i(JOIN_LOG, "left room teardownMs=${android.os.SystemClock.elapsedRealtime() - began}")
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
                    val oldJob = gate.withLock { if (session === live) sessionScope?.coroutineContext?.get(Job)
                        .also { closeSession() } else null }
                    oldJob?.join()
                    val saved = savedRooms.get(record.id) ?: throw RoomRecoveryException("This room is no longer saved on this device")
                    openSaved(saved)
                    return@launch
                }
                live.retryEpoch()
                if (!anonymousRoom && live.epochState.value is dev.forgesworn.kithmoot.session.RoomEpochState.Active && roomWork == null) {
                    val oldJob = gate.withLock { if (session === live) sessionScope?.coroutineContext?.get(Job)
                        .also { closeSession() } else null }
                    oldJob?.join()
                    val saved = savedRooms.get(record.id) ?: throw RoomRecoveryException("This room is no longer saved on this device")
                    openSaved(saved)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (session == null) {
                    _room.value = RoomState(background = backgrounds.load())
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

    private fun closeSession(keepEntry: Boolean = false) {
        stopNativeRecording()
        nativeKeeperEntry?.let { entry ->
            session?.holdKeeperStartup(); entry.close()
            nativeKeeperEntry = null; nativeKeeperController = null
            val previous = closingKeeper
            closingKeeper = CoroutineScope(Dispatchers.IO).launch { previous?.join(); entry.stop() }
        }
        stopRoomSharing()
        roomWork?.close()
        roomWork = null
        meetingState.value = MeetingSnapshot()
        consentedRecording = null
        dev.forgesworn.kithmoot.ui.room.forgetProfilePictures()
        opening?.cancel()
        opening = null
        // Both belong to the call's instance; a chat-only room has neither.
        if (!chatOnly) ScreenShareService.stop(getApplication())
        engine?.stop()
        engine?.dispose()
        engine = null
        marksTicker?.cancel()
        marksTicker = null
        shareMarks = ShareMarks()
        quietTransport?.stop()
        quietTransport = null
        nearbyTransport?.close()
        nearbyTransport = null
        nearbyOwner?.let { owner -> owner.close(); closingNearby = owner }
        nearbyOwner = null
        pool?.stop()
        profilePool?.stop()
        profilePool = null
        pool = null
        // A chat-only room beside a call has no notifications of its own, so it reads through as before.
        val readThrough = chatOnly || notifications.closeReadsThrough()
        if (!chatOnly) notifications.end(keepNotice = !readThrough)
        callRinger.end()
        lastCallId = null
        // At once, unlike the registry's unmark below: a reply arriving now takes the background path.
        noticeReplier?.let { (id, replier) -> dev.forgesworn.kithmoot.notifications.OpenRoomReplies.unregister(id, replier) }
        noticeReplier = null
        // Read through now before the background service takes the room back,
        // or its catch-up would count messages already shown here. A room that
        // was not being read keeps what it alerted unread: the inbox already
        // knows what it read and alerted while open, so the service re-posts
        // only that.
        val shown = shownChat
        shownChat = emptyList()
        savedRoom?.let { closed -> CoroutineScope(backgroundInboxWrites).launch {
            if (readThrough) markBackgroundRead(closed, shown)
            dev.forgesworn.kithmoot.notifications.ActiveRoomRegistry.unmark(closed.id)
        } }
        session = null
        identity = null
        savedRoom = null
        nip77Plan = null
        roomSecret = null
        roomInvitation = null
        roomInvitationHost = null
        invitationHostJob?.cancel()
        invitationHostJob = null
        // closeSession can run more than once; only the first close of a Tor-only room counts.
        synchronized(torOnlyHoldLock) {
            if (torOnlySessionHeld) { torOnlySessionHeld = false; AccountWriteHold.process.torOnlyRoomClosed() }
            anonymousRoom = false
        }
        if (!keepEntry) releaseTorOnlyEntry()
        sessionScope?.coroutineContext?.get(Job)?.cancel()
        sessionScope = null
    }

    override fun onCleared() {
        closeWorkspaceActivity()
        stopRoomSharing()
        if (!chatOnly) dev.forgesworn.kithmoot.telecom.CallTelecom.callLeft()
        boxDiscovery.close()
        super.onCleared()
        closeSession()
    }

    /** Runs a control off the main thread. Every one of them ends in a signature. */
    private val mediaControlLock = Any()

    private fun act(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.Default) { synchronized(mediaControlLock) { block() } }
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

    /** The Ring me rooms where a call cannot be answered yet: what the banner on the rooms list and in the room shows. */
    val reachability: StateFlow<List<dev.forgesworn.kithmoot.service.AtRiskRoom>> =
        dev.forgesworn.kithmoot.service.CredentialRenewal.atRisk

    /** Ringing in the background is off while a room is set to Ring me: the banner offers to turn it back on. */
    val ringingOff: StateFlow<Boolean> = dev.forgesworn.kithmoot.service.CredentialRenewal.ringingOff

    /** The banner's "Turn on": ringing in the background again, as the notification's "Turn back on" does. */
    fun turnBackgroundRingOn() = viewModelScope.launch(Dispatchers.IO) {
        dev.forgesworn.kithmoot.service.turnRingingOn(getApplication())
        dev.forgesworn.kithmoot.service.CredentialRenewal.refresh(getApplication(), post = false)
        showNotice("Calls will ring while KithMoot is closed.")
    }

    private val _renewingCalls = MutableStateFlow(false)
    /** The banner's button was pressed and the signer has not answered yet. */
    val renewingCalls: StateFlow<Boolean> = _renewingCalls.asStateFlow()

    /**
     * The "You can't answer KithMoot calls yet" notice or banner was tapped:
     * renew every Ring me room's credential, with the signer shown for the
     * first and the rest through the window that opens. See
     * service/CredentialRenewal.kt.
     */
    fun renewCallCredentials() = viewModelScope.launch(Dispatchers.IO) {
        start.first { !it.loadingRooms }
        val signer = accountSigner ?: return@launch note("Sign in to stay reachable for calls.")
        if (!_renewingCalls.compareAndSet(false, true)) return@launch
        try {
            val renewed = try {
                dev.forgesworn.kithmoot.service.CredentialRenewal.renewWith(getApplication(), signer)
            } catch (e: SignerException) {
                // The signer did not answer in time: say so, and the button is ready to try again.
                return@launch note(e.message ?: "Your signer did not answer. Try again.")
            }
            if (renewed > 0) showNotice("You can answer calls in your rooms again.")
            else if (reachability.value.isNotEmpty()) showNotice("Your signer did not confirm this phone. Try again.")
        } finally {
            _renewingCalls.value = false
        }
    }

    /**
     * Whether a screen share may be asked for now. Checked before Android's
     * own capture prompt, so a person off the stage, or not yet agreed to a
     * recording, is never asked to grant a capture that cannot be used.
     */
    fun mayShareScreen(): Boolean {
        if (!_room.value.meetingSpeaker) { note(MEETING_LOCKED); return false }
        return !askRecordingConsent(RecordingConsent.JOIN)
    }

    /** Says why a locked control does nothing. */
    fun noteMeetingLocked() = note(MEETING_LOCKED)

    /**
     * Before something puts this device on a call that is being recorded,
     * say so and ask: returns true when it asked, and the press waits on the
     * answer. A device already on the call, or a call nobody is recording,
     * is not asked. Mirrors `consentToRecordedCall` in the web client.
     */
    private fun askRecordingConsent(action: RecordingConsent): Boolean {
        if (_room.value.onCall) return false
        val view = meetingState.value.recordingView(epochSeconds())
        val id = when (view) {
            is RecordingView.On -> view.id
            is RecordingView.Unconfirmed -> view.id
            RecordingView.Off -> return false
        }
        if (consentedRecording == id) return false
        _room.update { it.copy(recording = view, recordingConsent = action) }
        return true
    }

    /** The answer to [askRecordingConsent]. Yes carries out what was pressed;
     *  no leaves the person in the room, off the call. */
    fun answerRecordingConsent(join: Boolean) {
        val asked = _room.value.recordingConsent ?: return
        _room.update { it.copy(recordingConsent = null) }
        if (!join) return
        consentedRecording = when (val view = meetingState.value.recordingView(epochSeconds())) {
            is RecordingView.On -> view.id
            is RecordingView.Unconfirmed -> view.id
            RecordingView.Off -> null
        }
        when (asked) {
            RecordingConsent.JOIN -> joinCall(micOn = false)
            RecordingConsent.JOIN_WITH_MIC -> joinCall(micOn = true)
            RecordingConsent.MICROPHONE -> toggleMicrophone()
            RecordingConsent.CAMERA -> toggleCamera()
        }
    }

    /** Explicit local audio capture. The UI confirms the action before calling
     * this; confirmed signed notices still precede any capture installation. */
    fun startNativeAudioRecording() = startNativeRecording(null, null)

    fun startNativeVideoRecording(layout: dev.forgesworn.kithmoot.media.recording.RecordingVideoLayout,
        selected: dev.forgesworn.kithmoot.media.recording.RecordingEndpointKey?) = startNativeRecording(layout, selected)

    private fun startNativeRecording(layout: dev.forgesworn.kithmoot.media.recording.RecordingVideoLayout?,
        selected: dev.forgesworn.kithmoot.media.recording.RecordingEndpointKey?) {
        val media = engine ?: return note("Join the call before recording.")
        val work = roomWork?.takeIf { it.moderator } ?: return note("Only the room's authority can start recording.")
        val originalSession = session ?: return note("Join the call before recording.")
        val originalCall = originalSession.currentCall() ?: return note("Join the call before recording.")
        val originalState = _room.value
        val origin = dev.forgesworn.kithmoot.media.recording.RecordingOrigin(originalState.roomId, originalCall.id, originalState.name)
        if (dev.forgesworn.kithmoot.media.recording.RecordingCodecs.audioEncoder == null)
            return note("Audio recording is unavailable on this device.")
        if (layout != null && !dev.forgesworn.kithmoot.media.recording.RecordingCodecs.videoSupported)
            return note("Video recording is unavailable on this device. Choose audio only.")
        if (layout != null && !appVisible) return note("Open KithMoot before starting video recording.")
        val initial = recordingVideoSnapshot(media, originalSession, origin)
        val speaker = if (layout == dev.forgesworn.kithmoot.media.recording.RecordingVideoLayout.SPEAKER)
            dev.forgesworn.kithmoot.media.recording.RecordingSpeaker() else null
        val initialSpeaker = speaker?.select(initial.first, _room.value.speaking, android.os.SystemClock.elapsedRealtime())
            ?: initial.first.firstOrNull()
        val chosen = if (speaker != null) initialSpeaker?.key else selected
        val selectedName = initial.first.firstOrNull { it.key == chosen }?.name ?: "Selected device"
        if (layout == dev.forgesworn.kithmoot.media.recording.RecordingVideoLayout.SCREEN_CAMERA &&
            initial.first.none { it.key == selected && it.recordingProfile == 2 && it.allowed })
            return note("Choose a compatible device on this call.")
        val ticket = synchronized(nativeRecordingLock) {
            if (nativeRecordingStarting || nativeRecordingOwner != null || !_room.value.onCall || !media.callActive) return
            if (recordingApplication.recordings.pending() != null) return note("Save or discard the previous recording first.")
            nativeRecordingStarting = true
            ++nativeRecordingGeneration
        }
        _room.update { it.copy(nativeRecordingBusy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            var file: java.io.File? = null
            var noticeId: String? = null
            var installed = false
            try {
                val source = recordingApplication.recordings.begin(
                    format = if (layout == null) dev.forgesworn.kithmoot.media.recording.RecordingFormat.AUDIO else dev.forgesworn.kithmoot.media.recording.RecordingFormat.VIDEO,
                    origin = origin,
                    discardAt = originalState.endsAt?.takeIf { originalState.destruct })
                file = source
                val recordingId = work.startRecording(layout?.wire ?: "audio").id
                noticeId = recordingId
                synchronized(nativeRecordingLock) {
                    check(nativeRecordingGeneration == ticket && engine === media && roomWork === work && session === originalSession &&
                        originalSession.currentCall()?.id == origin.call && _room.value.onCall && media.callActive && (layout == null || appVisible) &&
                        (work.meeting.state.value.recordingView(epochSeconds()) as? RecordingView.On)?.id == recordingId) {
                        "The original call ended before recording could start"
                    }
                    val snapshot = recordingVideoSnapshot(media, originalSession, origin)
                    val activeSpeaker = speaker?.select(snapshot.first, _room.value.speaking, android.os.SystemClock.elapsedRealtime())
                    val captureKey = activeSpeaker?.key ?: chosen
                    val captureName = activeSpeaker?.name ?: selectedName
                    val capture = if (layout == null) media.startAudioRecording(source) else media.startVideoRecording(source,
                        dev.forgesworn.kithmoot.media.recording.RecordingVideoPlan(origin, layout, snapshot.first, captureKey, captureName), snapshot.second)
                    nativeRecordingOwner = NativeRecordingOwner(recordingId, media, work, source, capture, origin, layout, captureKey, captureName, speaker)
                    nativeRecordingStarting = false
                    installed = true
                    _room.update { it.copy(nativeRecording = true, nativeRecordingPaused = false, nativeRecordingBusy = false) }
                }
                viewModelScope.launch {
                    while (isActive) {
                        delay(500)
                        val owns = synchronized(nativeRecordingLock) { nativeRecordingOwner?.id == noticeId }
                        if (!owns) return@launch
                        refreshRecordingVideo(media, originalSession)
                        if (layout != null && !appVisible && !_room.value.nativeRecordingPaused) changeNativeRecordingPause(true)
                        val error = media.audioRecordingError()
                        if (error != null || engine !== media || session !== originalSession ||
                            originalSession.currentCall()?.id != origin.call || !_room.value.onCall ||
                            (work.meeting.state.value.recordingView(epochSeconds()) as? RecordingView.On)?.id != noticeId) {
                            stopNativeRecording()
                            if (error != null) note("Recording stopped: ${error.message ?: "audio capture failed"}")
                            return@launch
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (engine === media) note(error.message ?: "Recording could not start.") }
            finally {
                if (!installed) withContext(NonCancellable) {
                    noticeId?.let { runCatching { work.stopRecording(it) } }
                    file?.let { runCatching { recordingApplication.recordings.abandon(it) } }
                    synchronized(nativeRecordingLock) {
                        if (nativeRecordingGeneration == ticket) {
                            nativeRecordingStarting = false
                            if (engine === media) _room.update { it.copy(nativeRecordingBusy = false) }
                        }
                    }
                }
            }
        }
    }

    private fun recordingVideoSnapshot(media: WebRtcEngine, live: RoomSession,
        origin: dev.forgesworn.kithmoot.media.recording.RecordingOrigin): Pair<List<dev.forgesworn.kithmoot.media.recording.RecordingVideoEndpoint>,
        Map<dev.forgesworn.kithmoot.media.recording.RecordingVideoKey, VideoTrack>> {
        val who = live.identity
        val local = media.localMedia.tracks.value
        val endpoints = dev.forgesworn.kithmoot.media.recording.recordingVideoEndpoints(origin, live.participants.value,
            dev.forgesworn.kithmoot.media.recording.RecordingEndpointKey(who.participant, who.devicePubkey), personName(who.participant).take(256),
            local.any { it.role == Roles.CAMERA }, local.any { it.role == Roles.SCREEN }, callHeld.value || !appVisible,
            allowed = { meetingAllows(meetingState.value.meeting, it) })
        val tracks = buildMap {
            for (endpoint in endpoints) for (role in dev.forgesworn.kithmoot.media.recording.RecordingVideoRole.entries) {
                val wire = if (role == dev.forgesworn.kithmoot.media.recording.RecordingVideoRole.CAMERA) Roles.CAMERA else Roles.SCREEN
                val track = if (endpoint.key.device == who.devicePubkey) local.firstOrNull { it.role == wire }?.track as? VideoTrack
                    else _videos.value[roleKey(endpoint.key.device, wire)]
                track?.let { put(dev.forgesworn.kithmoot.media.recording.RecordingVideoKey(endpoint.key, role), it) }
            }
        }
        return endpoints to tracks
    }

    private fun refreshRecordingVideo(media: WebRtcEngine, live: RoomSession) {
        if (session !== live || engine !== media) return
        val call = live.currentCall() ?: return
        val origin = dev.forgesworn.kithmoot.media.recording.RecordingOrigin(live.room.roomId, call.id, _room.value.name)
        val owner = synchronized(nativeRecordingLock) { nativeRecordingOwner }
        val snapshot = recordingVideoSnapshot(media, live, origin)
        _room.update { if (session === live) it.copy(recordingVideoDevices = snapshot.first,
            recordingVideoSupported = dev.forgesworn.kithmoot.media.recording.RecordingCodecs.videoSupported) else it }
        if (owner == null || owner.media !== media || owner.layout == null || owner.origin.call != call.id) return
        val activeSpeaker = owner.speaker?.select(snapshot.first, _room.value.speaking, android.os.SystemClock.elapsedRealtime())
        runCatching { media.updateRecordingVideo(owner.capture,
            dev.forgesworn.kithmoot.media.recording.RecordingVideoPlan(owner.origin, owner.layout, snapshot.first,
                activeSpeaker?.key ?: owner.selected, activeSpeaker?.name ?: owner.selectedName), snapshot.second)
        }.onFailure { failure -> synchronized(nativeRecordingLock) {
            if (nativeRecordingOwner === owner) { stopNativeRecording(); note("Recording stopped: ${failure.message ?: "video inputs failed"}") }
        } }
    }

    private fun revokeLocalRecordingVideo(role: dev.forgesworn.kithmoot.media.recording.RecordingVideoRole) {
        val who = identity ?: return
        engine?.revokeRecordingVideo(dev.forgesworn.kithmoot.media.recording.RecordingVideoKey(
            dev.forgesworn.kithmoot.media.recording.RecordingEndpointKey(who.participant, who.devicePubkey), role))
    }

    fun toggleNativeRecordingPause() = changeNativeRecordingPause(null)

    private fun changeNativeRecordingPause(requested: Boolean?) {
        val (owner, pause) = synchronized(nativeRecordingLock) {
            val owner = nativeRecordingOwner ?: return
            if (_room.value.nativeRecordingBusy) return
            val pause = requested ?: !_room.value.nativeRecordingPaused
            if (pause == _room.value.nativeRecordingPaused) return
            if (!pause && !appVisible && owner.layout != null) return note("Open KithMoot to resume video recording.")
            _room.update { it.copy(nativeRecordingBusy = true) }
            owner to pause
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Retain the exact capture, rather than looking up whatever
                // recording the engine happens to own when this job executes.
                // A queued Resume must recheck visibility when it executes.
                var effectivePause = pause || (owner.layout != null && !appVisible)
                if (effectivePause) owner.capture.pause() else owner.capture.resume()
                while (true) {
                    val pauseAfterResume = synchronized(nativeRecordingLock) {
                        if (nativeRecordingOwner !== owner) return@launch
                        // Commit against the lifecycle boundary, but do not
                        // hold its monitor during the worker's bounded flush.
                        if (owner.layout != null && !appVisible && !effectivePause) true
                        else {
                            _room.update { it.copy(nativeRecordingPaused = effectivePause, nativeRecordingBusy = false) }
                            false
                        }
                    }
                    if (!pauseAfterResume) break
                    owner.capture.pause()
                    effectivePause = true
                }
            } catch (cancelled: CancellationException) {
                synchronized(nativeRecordingLock) {
                    if (nativeRecordingOwner === owner) stopNativeRecording()
                }
                throw cancelled
            } catch (error: Exception) {
                synchronized(nativeRecordingLock) {
                    if (nativeRecordingOwner === owner) {
                        stopNativeRecording()
                        note("Recording stopped: ${error.message ?: "pause failed"}")
                    }
                }
            }
        }
    }

    /** Synchronously revoke input access before leaving/rekeying; finalisation
     * belongs to the application so closing this room cannot cancel it. */
    fun stopNativeRecording() {
        val held = synchronized(nativeRecordingLock) {
            ++nativeRecordingGeneration
            nativeRecordingStarting = false
            val owner = nativeRecordingOwner.also { nativeRecordingOwner = null }
            _room.update { it.copy(nativeRecording = false, nativeRecordingPaused = false, nativeRecordingBusy = owner != null) }
            owner?.let { it to it.media.takeAudioRecording() }
        } ?: return
        val (owner, capture) = held
        recordingApplication.recordingExports.launch {
            try {
                runCatching { owner.work.stopRecording(owner.id) }.onFailure {
                    if (roomWork === owner.work) note("Capture stopped locally. The room's stop notice could not be confirmed.")
                }
                check(capture != null) { "Recording capture was interrupted" }
                recordingApplication.recordings.complete(capture.finish())
            } catch (error: Exception) {
                runCatching { capture?.discard() }
                runCatching { recordingApplication.recordings.abandon(owner.file) }
                if (roomWork === owner.work) note("Recording could not be exported: ${error.message ?: "capture failed"}")
            } finally {
                if (roomWork === owner.work) _room.update { it.copy(nativeRecordingBusy = false) }
            }
        }
    }

    private val recordingExportMutex = Mutex()
    private val recordingStopRetryMutex = Mutex()
    fun retryRecordingStop() {
        val work = roomWork ?: return
        val id = work.recordingStopPending.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (!recordingStopRetryMutex.tryLock()) return@launch
            try {
                if (roomWork !== work) return@launch
                _room.update { it.copy(recordingStopRetrying = true) }
                work.stopRecording(id)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (roomWork === work) note("Capture is stopped locally. The room's stop notice still needs confirmation; try again when connected.")
            } finally {
                if (roomWork === work) _room.update { it.copy(recordingStopRetrying = false) }
                recordingStopRetryMutex.unlock()
            }
        }
    }
    fun saveRecording(sourceName: String, uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!recordingExportMutex.tryLock()) return@launch
            _recordingExportBusy.value = true
            try {
                val file = recordingApplication.recordings.selectedExport(sourceName)
                recordingApplication.recordings.details(file)
                val resolver = getApplication<android.app.Application>().contentResolver
                try {
                    file.inputStream().use { input ->
                        checkNotNull(resolver.openOutputStream(uri, "wt")) { "The save location is unavailable" }
                            .use { output -> input.copyTo(output, 64 * 1024) }
                    }
                    // A room can be destroyed while the document provider is
                    // copying. Reject that unsaved copy and remove it as well.
                    recordingApplication.recordings.details(file)
                } catch (error: Exception) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw error
                }
                recordingApplication.recordings.discard(file)
                note("Recording saved. Your saved copy remains until you remove it.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val remains = recordingExport.value?.let { runCatching { recordingApplication.recordings.details(it) }.isSuccess } == true
                note("Recording could not be saved: ${error.message ?: "storage unavailable"}." +
                    if (remains) " The local export is still available." else " The original recording is no longer available.")
            }
            finally { _recordingExportBusy.value = false; recordingExportMutex.unlock() }
        }
    }

    fun discardRecordingExport() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!recordingExportMutex.tryLock()) return@launch
            try { recordingExport.value?.let { recordingApplication.recordings.discard(it) } }
            catch (error: Exception) { note(error.message ?: "The local recording could not be removed.") }
            finally { recordingExportMutex.unlock() }
        }
    }

    /** Add retains an independently encrypted draft, never an upload or a
     * message. The original local Save/Discard export remains available. */
    fun addRecordingToOriginalChat(sourceName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!recordingExportMutex.tryLock()) return@launch
            _recordingExportBusy.value = true
            _recordingExportError.value = null
            var ticket: dev.forgesworn.kithmoot.media.recording.RecordingDraftTicket? = null
            var retained = false
            try {
                val exports = recordingApplication.recordings
                val file = exports.selectedExport(sourceName)
                val details = exports.details(file)
                val origin = checkNotNull(details.origin) { "This export has no original chat; save a local copy instead" }
                check(savedRooms.get(origin.room) != null) { "The original chat is no longer saved on this phone" }
                val drafts = recordingApplication.recordingShareDrafts
                val existing = drafts.list(origin.room).firstOrNull { it.sourceName == sourceName && it.origin == origin }
                if (existing == null) {
                    val reservation = drafts.begin(origin, sourceName, details.discardAt).also { ticket = it }
                    val sealed = sealFile(file, reservation.destination,
                        "KithMoot-call-${java.time.LocalDate.now()}.${details.format.extension}", details.format.mime)
                    ensureActive()
                    exports.withSelectedExport(sourceName) { selected, current ->
                        check(selected == file && current == details && savedRooms.get(origin.room) != null) { "The original recording or room changed while adding" }
                        drafts.complete(reservation, sealed)
                    }
                } else {
                    exports.withSelectedExport(sourceName) { selected, current ->
                        check(selected == file && current == details && savedRooms.get(origin.room) != null)
                        check(drafts.selected(existing.id, origin.room) == existing)
                    }
                }
                retained = true
                withContext(Dispatchers.Main) {
                    _recordingAdded.value = RecordingAdded(sourceName, origin.room, ++recordingAddedRequest)
                    note("Recording added privately to its original chat. Nothing has been uploaded or sent.")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                _recordingExportError.value = "Recording could not be added: ${failure.message ?: "private storage unavailable"}."
            } finally {
                if (!retained) ticket?.let { pending ->
                    runCatching { recordingApplication.recordingShareDrafts.abandon(pending) }
                        .onFailure { _recordingExportError.value = "The unfinished encrypted draft could not be removed. The local export has been kept." }
                }
                _recordingExportBusy.value = false
                recordingExportMutex.unlock()
            }
        }
    }

    /** Choosing a public storage identity does not contact or bind the server. */
    fun prepareRecordingStorage(id: String, chosen: String) {
        val room = _room.value.roomId
        if (_room.value.mediaBusy) return
        val scope = sessionScope ?: return
        _room.update { it.copy(mediaBusy = true, chatSendError = null, recordingStorageChoice = null) }
        scope.launch(Dispatchers.IO) {
            try {
                val draft = recordingApplication.recordingShareDrafts.selected(id, room)
                val origin = mediaStorageOrigin(chosen)
                check(draft.storageOrigin == null || draft.storageOrigin == origin) { "This draft is already bound to its chosen storage server" }
                val key = recordingApplication.recordingUploadJournal.identity(origin)
                val choice = dev.forgesworn.kithmoot.media.recording.RecordingStorageChoice(id, room, origin, key)
                _room.update { if (it.roomId == room) it.copy(recordingStorageChoice = choice) else it }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { _room.update { if (it.roomId == room) it.copy(chatSendError = error.message ?: "Storage identity unavailable") else it } }
            finally { _room.update { if (it.roomId == room) it.copy(mediaBusy = false) else it } }
        }
    }

    fun uploadRecordingDraft(id: String, origin: String, consent: Boolean) {
        val live = session ?: return
        val scope = sessionScope ?: return
        val room = _room.value.roomId
        val choice = _room.value.recordingStorageChoice
        if (!consent || choice == null || choice.draft != id || choice.room != room || choice.origin != origin)
            return note("Choose and authorise this recording's storage server before Upload.")
        fun permitted(): Boolean = session === live && _room.value.roomId == room &&
            _room.value.route.internet && !_room.value.anonymous &&
            !dev.forgesworn.kithmoot.protocol.conferenceEnded(_room.value.endsAt, epochSeconds())
        if (!permitted()) return note("Recording uploads need an active original room with Internet enabled.")
        if (_room.value.mediaBusy) return
        _room.update { it.copy(mediaBusy = true, recordingUploadRunning = true, chatSendError = null) }
        // Enter the outer finally before dispatching IO, so cancelling a
        // queued upload cannot strand the controls in their busy state.
        recordingUploadJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(Dispatchers.IO) {
                    val ownerJob = currentCoroutineContext().get(Job)!!
                    val owned = dev.forgesworn.kithmoot.media.recording.RecordingUploadRequest(
                        recordingApplication.recordingShareDrafts, recordingApplication.recordingUploadJournal,
                        id, room, origin, { ownerJob.isActive && permitted() },
                        client = recordingUploadClient,
                    )
                    // Install cancellation cleanup before the blocking PUT.
                    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                        try { this@RoomViewModel.room.collect { if (!permitted()) owned.close() } }
                        finally { owned.close() }
                    }
                    try {
                        owned.upload()
                        ensureActive()
                        if (permitted()) note("Recording uploaded privately. No chat message has been sent.")
                    } finally { owned.close(); watcher.cancel() }
                }
            } catch (cancelled: CancellationException) {
                _room.update { if (session === live && it.roomId == room) it.copy(chatSendError = "Recording upload cancelled. No chat message was sent.") else it }
                throw cancelled
            }
            catch (error: Exception) {
                _room.update { if (session === live && it.roomId == room) it.copy(chatSendError = "Recording upload failed: ${error.message ?: "storage unavailable"}. No chat message was sent.") else it }
            } finally {
                _room.update { if (session === live && it.roomId == room) it.copy(mediaBusy = false, recordingUploadRunning = false) else it }
            }
        }
    }

    /** Send is a separate explicit action. Keep the exact signed message in
     * the recording owner before any handoff; retries never sign a replacement. */
    fun sendRecordingDraft(id: String) {
        val live = session ?: return
        val scope = sessionScope ?: return
        val room = _room.value.roomId
        fun permitted() = session === live && _room.value.roomId == room && !_room.value.anonymous &&
            _room.value.movedOn == null && !_room.value.conferenceEnded &&
            !dev.forgesworn.kithmoot.protocol.conferenceEnded(_room.value.endsAt, epochSeconds())
        if (!permitted()) return note("Open the recording's original chat before Send.")
        if (_room.value.cadence?.busy == true) return note("Finish the quiet schedule change before sending.")
        if (_room.value.mediaBusy || _room.value.chatSending) return
        val durable = !_room.value.quiet
        _room.update { it.copy(mediaBusy = true, chatSending = true, chatSendError = null) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(Dispatchers.IO) {
                    val drafts = recordingApplication.recordingShareDrafts
                    val draft = drafts.selected(id, room)
                    val attachment = checkNotNull(draft.uploaded) { "Upload this recording before Send" }
                    val origin = checkNotNull(draft.storageOrigin)
                    fun ready() = permitted() && recordingApplication.recordingUploadJournal.readyToSend(room, origin, draft.sealed.hash)
                    check(ready()) { "This recording's upload is no longer ready for Send" }
                    val prepared = draft.preparedSend ?: checkNotNull(live.prepareChatForSend(draft.sealed.name,
                        attachments = listOf(attachment))).also { drafts.retainPreparedSend(id, room, it) }
                    ensureActive()
                    fun owned() = ready() && runCatching { drafts.withPreparedSend(id, room, prepared) { } }.isSuccess
                    val confirmed = if (durable) live.sendPreparedChatDurable(prepared,
                        commitGuard = { commit -> drafts.withPreparedSend(id, room, prepared) {
                            check(ready()) { "The original recording's Send was revoked" }; commit()
                        } }, onRetained = {
                            drafts.finishPreparedSend(id, room, prepared)
                            if (session === live) note("Recording message kept in its original chat. Relay confirmation pending.")
                        }) else live.sendPreparedChatConfirmed(prepared, ::owned).also {
                            if (it) drafts.finishPreparedSend(id, room, prepared)
                        }
                    if (session === live) {
                        val pending = live.pendingChats.value.firstOrNull { it.id == prepared.pending.event.id }
                        note(when {
                            confirmed -> "Recording sent to its original chat."
                            durable && pending?.state == dev.forgesworn.kithmoot.session.PendingChatState.MOVED ->
                                "The room changed before this recording message could send. Its unsent message is kept in the original chat."
                            durable && pending?.state == dev.forgesworn.kithmoot.session.PendingChatState.UNKNOWN ->
                                "Recording delivery is unconfirmed. It may have arrived; the original chat keeps the same message."
                            durable -> "Recording message is waiting in its original chat."
                            else -> "The recording message was not confirmed. It may have arrived; Retry Send uses the same message."
                        })
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                _room.update { if (session === live && it.roomId == room)
                    it.copy(chatSendError = "Recording Send could not finish: ${failure.message ?: "message unavailable"}. An earlier attempt may have arrived.") else it }
            } finally {
                _room.update { if (session === live && it.roomId == room) it.copy(mediaBusy = false, chatSending = false) else it }
                if (durable && session === live) scheduleBackoff(live)
            }
        }
    }

    fun removeRecordingDraft(id: String) {
        val room = _room.value.roomId
        if (_room.value.mediaBusy) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                recordingApplication.recordingShareDrafts.discard(id, room) { draft ->
                    draft.storageOrigin?.let { recordingApplication.recordingUploadJournal.discard(it, draft.sealed.hash) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (_room.value.roomId == room) note("Recording draft could not be removed: ${failure.message ?: "storage unavailable"}") }
        }
    }

    /** Raise or lower this person's hand, for the room's authority to see. */
    fun raiseHand(up: Boolean) {
        val work = roomWork ?: return note("Wait for the room to finish connecting, then raise your hand.")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                work.raiseHand(up)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (roomWork === work) note(error.message ?: "Your hand could not be raised. Try again.")
            }
        }
    }

    /**
     * Turn meeting mode on or off: only on the device that made the room.
     * Whoever runs the meeting is on its stage.
     */
    fun setMeetingMode(on: Boolean) = moderate { work ->
        work.setMeetingMode(on)
        null
    }

    /** Put [participant] on the meeting's stage, or take them off it. */
    fun setSpeaker(participant: String, speaking: Boolean) = moderate { work ->
        work.setSpeaker(participant, speaking)
        val name = personName(participant)
        if (speaking) "$name is a speaker now." else "$name is no longer a speaker."
    }

    private fun moderate(change: suspend (RoomWork) -> String?) {
        val work = roomWork?.takeIf { it.moderator } ?: return note("Only the person who made this room can run it as a meeting.")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                change(work)?.let { if (roomWork === work) note(it) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (roomWork === work) note(error.message ?: "The meeting could not be changed. Try again.")
            }
        }
    }

    /** How the room names [participant], as their tile does. */
    private fun personName(participant: String): String {
        val state = _room.value
        val tile = state.tiles.firstOrNull { it.participant == participant }
        return state.profiles[participant]?.name ?: tile?.cardName?.takeIf { it.isNotBlank() } ?: tile?.name
            ?: dev.forgesworn.kithmoot.account.shortNpub(participant)
    }

    /** Which participant [device] belongs to, or null when nobody's roster entry places it. */
    private fun ownerOf(device: String, people: List<dev.forgesworn.kithmoot.session.Participant>): String? =
        people.firstOrNull { p -> p.devices.any { it.device == device } }?.participant

    /**
     * The room's meeting state changed: show it, and hold this device to it.
     * Off the stage, this device stops its own microphone, camera and screen
     * share - the half of meeting mode an honest app does for itself; every
     * other app refusing to play them is the half that does not rely on it.
     */
    private fun adoptMeeting(snapshot: MeetingSnapshot) {
        val before = meetingState.value
        meetingState.value = snapshot
        // Clear excluded cached pictures before deferred policy collectors.
        val currentMedia = engine
        val currentSession = session
        if (currentMedia != null && currentSession != null) refreshRecordingVideo(currentMedia, currentSession)
        val currentRecording = synchronized(nativeRecordingLock) { nativeRecordingOwner?.id }
        if (currentRecording != null && (snapshot.recordingView(epochSeconds()) as? RecordingView.On)?.id != currentRecording) {
            stopNativeRecording()
        }
        val me = _room.value.selfParticipant
        val policy = snapshot.meeting
        val could = _room.value.meetingSpeaker
        val can = meetingAllows(policy, me)
        _room.update { it.copy(
            meetingOn = policy?.on == true,
            meetingSpeaker = can,
            handUp = me in snapshot.hands,
            handsRaised = snapshot.hands.keys.count { p -> !meetingAllows(policy, p) },
            meetingModerator = roomWork?.moderator == true,
            meetingSpeakers = policy?.speakers.orEmpty(),
            raisedHands = snapshot.hands,
            recording = snapshot.recordingView(epochSeconds()),
            recordingCapture = snapshot.recordingCapture(),
        ) }
        // The host hears about a hand as it goes up, not one found in the log.
        if (roomWork?.moderator == true) snapshot.hands.filter { (p, at) -> p !in before.hands && p != me && at >= epochSeconds() - 30 }
            .keys.firstOrNull()?.let { note("${personName(it)} raised their hand.") }
        if (!can) act { stopSendingForMeeting() }
        // Put on the stage, not let off it by the mode ending: that says so itself.
        else if (!could && policy?.on == true) note("You are a speaker now. Your microphone and camera are yours to turn on.")
    }

    private fun showRecording() {
        _room.update { it.copy(recording = meetingState.value.recordingView(epochSeconds()), recordingCapture = meetingState.value.recordingCapture()) }
    }

    /** Off the stage: everything this device sends is stopped, and it says what. */
    private fun stopSendingForMeeting() {
        val state = _room.value
        if (state.meetingSpeaker) return
        val media = engine?.localMedia ?: return
        val stopped = buildList {
            if (state.micOn) { media.stopMicrophone(); runCatching { session?.release(Roles.MIC) }; add("microphone") }
            if (state.cameraOn) { media.stopCamera(); add("camera") }
            if (state.screenOn) { media.stopScreenShare(); ScreenShareService.stop(getApplication()); add("screen share") }
        }
        if (stopped.isEmpty()) return
        note("Meeting mode: only speakers can talk or show video. Your ${stopped.joinToString(" and ")} ${if (stopped.size == 1) "is" else "are"} off.")
    }

    private fun onMeetingNews(news: MeetingNews) = note(when (news) {
        MeetingNews.MeetingOn -> "This call is now in meeting mode: only speakers can talk or show video."
        MeetingNews.MeetingOff -> "Meeting mode is off: everybody can talk again."
        MeetingNews.RecordingOn -> "This call is being recorded. Everybody on the call is told, and sees a notice until it stops."
        MeetingNews.RecordingOff -> "The recording has stopped."
    })

    /** Shows the call rather than the chat: see [RoomState.callViewRequest]. */
    fun showCallView() { _room.update { it.copy(callViewRequest = it.callViewRequest + 1) } }

    fun leaveCall() {
        val live = session ?: return
        if (!_room.value.onCall || _room.value.callChanging) return
        stopNativeRecording()
        // Joining again asks again, if it is still being recorded.
        consentedRecording = null
        // A remembered Join must not survive a Leave; it would put the person
        // straight back on the call they just left.
        _room.update { it.copy(onCall = false, mediaRunning = false, callChanging = true, callJoinPending = false, callJoinMicPending = false) }
        // Shuts the self-heal until this leave has settled. Local media is
        // stopped below before the membership is cleared, but the track list
        // is a flow and its last emission can still be in flight behind us:
        // without this, that emission re-declares a call nobody is on and
        // mints a fresh id for it.
        leavingCall = true
        lastCallId = null
        Log.i(JOIN_LOG, "call leave requested")
        act {
            try {
                if (session !== live) return@act
                // Order matters and is the same as the web client's: local
                // media down first, membership cleared second. The other way
                // round leaves a device advertising tracks for a call it has
                // just said it is not on.
                engine?.setCallActive(false)
                ScreenShareService.stop(getApplication())
                // Local hang-up must complete even during a relay outage or rekey.
                runCatching { live.release(Roles.MIC) }
                runCatching { live.release(Roles.MONITOR) }
                // Off the call is a stated fact, like leaving the room is.
                // Nobody can guess it from an absent track: a device listening
                // in with everything switched off looks the same.
                runCatching { live.setCall(null) }
                _videos.value = emptyMap()
                maybeAskBatteryExemption()
            } finally {
                // `callChanging` is a latch on the call control: while it is set
                // the button is disabled and every joinCall() returns without a
                // word. It used to be cleared only when this was still the live
                // session, and the early return above skipped even that, so a
                // session swapped underneath a Leave left the latch set for the
                // rest of the room's life. It is now always cleared; only the
                // local media flags, which belong to THIS session, are held back
                // when the session has moved on.
                leavingCall = false
                if (session === live) {
                    _room.update { it.copy(callChanging = false, micOn = false, micMuted = false, cameraOn = false, screenOn = false, listeningHere = false, mediaConnections = emptyMap()) }
                } else {
                    _room.update { it.copy(callChanging = false) }
                }
            }
        }
    }

    /**
     * @param micOn Join with the microphone already live - answering a
     *   ringing call, like a phone call, rather than a manual Join. Camera is
     *   never turned on here; a manual Join keeps today's default of both off.
     *   The caller (see [dev.forgesworn.kithmoot.MainActivity]) is responsible
     *   for the RECORD_AUDIO permission ask; a refusal there still calls this
     *   with `micOn = false` rather than failing the join.
     */
    fun joinCall(micOn: Boolean = false) = act {
        if (chatOnly) return@act
        if (_room.value.route.nearby) return@act note("Choose Internet only for audio and video calls while nearby chat is being qualified.")
        if (askRecordingConsent(if (micOn) RecordingConsent.JOIN_WITH_MIC else RecordingConsent.JOIN)) return@act
        val state = _room.value
        val live = session ?: return@act
        when (val decision = joinDecision(
            onCall = state.onCall,
            changing = state.callChanging,
            mediaReady = engine != null,
            mediaStarting = state.mediaStarting,
            mediaFault = state.mediaFault,
        )) {
            is JoinDecision.Ignore -> return@act
            is JoinDecision.Refuse -> {
                Log.i(JOIN_LOG, "call join refused reason=no-media")
                return@act note(decision.message)
            }
            is JoinDecision.WhenReady -> {
                // Remembered, not refused. startMedia carries it out.
                Log.i(JOIN_LOG, "call join remembered reason=media-not-ready-yet")
                _room.update { it.copy(callJoinPending = true, callJoinMicPending = micOn) }
                return@act
            }
            is JoinDecision.Now -> Unit
        }
        val media = engine ?: return@act
        Log.i(JOIN_LOG, "call join now")
        _room.update { it.copy(onCall = true, mediaRunning = true) }
        media.setCallActive(true)
        adoptRoomCall(pressed = true)
        if (live.localRoles.value.monitorDevice == null) live.claim(Roles.MONITOR)
        if (micOn) startMicrophoneForJoin(live)
    }

    private fun holdCall() = act {
        if (!_room.value.onCall || callHeld.value) return@act
        callHeld.value = true
        engine?.revokeRecordingVideo(reason = "The call is on hold")
        val state = _room.value
        if (dev.forgesworn.kithmoot.telecom.holdMutesMic(state.micOn, state.micMuted)) {
            micMutedForHold = engine?.localMedia?.setMicrophoneMuted(true) == true
        }
    }

    private fun resumeCall() = act {
        if (!callHeld.value) return@act
        callHeld.value = false
        if (micMutedForHold) {
            micMutedForHold = false
            engine?.localMedia?.setMicrophoneMuted(false)
        }
    }

    fun listenOnThisDevice() { if (_room.value.mediaRunning) session?.claim(Roles.MONITOR) }

    /**
     * The claim-then-start a joining microphone needs, shared by [joinCall]
     * and the remembered join [startMedia] carries out once the engine
     * exists. Same shape as [toggleMicrophone]'s Start action.
     */
    private fun startMicrophoneForJoin(live: RoomSession) {
        // Joined, but off the stage: in, and listening, with the microphone off.
        if (!_room.value.meetingSpeaker) return note(MEETING_LOCKED)
        val media = engine?.localMedia ?: return
        live.claim(Roles.MIC)
        if (media.startMicrophone() == null) {
            live.release(Roles.MIC)
            note("The microphone would not start.")
        }
    }


    /**
     * The mic button's one action, three states.
     *
     * No microphone here yet -> start one. A live microphone -> mute it,
     * keeping it running. A live, muted microphone -> unmute it. Matches the
     * web client: muting never releases the microphone, only leaving the call
     * does (see [leaveCall]). Release stays a distinct, deliberate act - see
     * [setMicrophoneMuted] - this is only the button's own cycle through it.
     */
    fun toggleMicrophone() = act {
        if (chatOnly) return@act
        if (!_room.value.mediaRunning) return@act noteIfJoinPending()
        val media = engine?.localMedia ?: return@act note(mediaMissing())
        val live = session ?: return@act
        val action = microphoneAction(_room.value.micOn, _room.value.micMuted)
        if (action != MicrophoneAction.Mute && !_room.value.meetingSpeaker) return@act note(MEETING_LOCKED)
        if (action == MicrophoneAction.Start && askRecordingConsent(RecordingConsent.MICROPHONE)) return@act
        when (action) {
            MicrophoneAction.Start -> {
                // The claim goes first, and not for tidiness: the moment a track
                // appears the roster is republished, and a device that published a
                // microphone it had not yet claimed would see one of its own others
                // still holding the role and shut itself straight back off.
                live.claim(Roles.MIC)
                if (media.startMicrophone() == null) {
                    live.release(Roles.MIC)
                    note("The microphone would not start.")
                }
            }
            MicrophoneAction.Mute -> media.setMicrophoneMuted(true)
            MicrophoneAction.Unmute -> media.setMicrophoneMuted(false)
        }
    }

    /**
     * Silence this device's microphone without letting go of it.
     *
     * Deliberately not the same act as [toggleMicrophone] releasing the
     * microphone outright: [toggleMicrophone] never releases it either now,
     * it only mutes and unmutes. This is the lower-level primitive both it
     * and any other caller mute through. Mute keeps the track live and
     * advertises `muted` on the roster, so the room can show who is quiet,
     * and so the slot carrying it keeps progressing for the health ladder to
     * measure.
     */
    fun setMicrophoneMuted(muted: Boolean) = act {
        if (!_room.value.mediaRunning) return@act
        val media = engine?.localMedia ?: return@act
        if (!media.setMicrophoneMuted(muted)) note("There is no microphone running to mute.")
    }

    /** Why a mic, camera or share press found no engine, in the room's own terms. */
    private fun mediaMissing(): String = _room.value.let {
        mediaMissingNote(it.mediaStarting, it.callJoinPending, it.mediaFault)
    }

    /** A press between answering and the engine arriving is not ignored in
     *  silence: the join is remembered, and the person is told it is coming. */
    private fun noteIfJoinPending() {
        if (_room.value.callJoinPending) note(mediaMissing())
    }

    fun toggleCamera() = act {
        if (chatOnly) return@act
        if (!_room.value.mediaRunning) return@act noteIfJoinPending()
        val media = engine?.localMedia ?: return@act note(mediaMissing())
        if (_room.value.cameraOn) {
            revokeLocalRecordingVideo(dev.forgesworn.kithmoot.media.recording.RecordingVideoRole.CAMERA)
            media.stopCamera()
        } else if (!_room.value.meetingSpeaker) {
            note(MEETING_LOCKED)
        } else if (askRecordingConsent(RecordingConsent.CAMERA)) {
            return@act
        } else if (media.startCamera() == null) {
            note("No camera is available here.")
        }
    }

    fun switchCamera() {
        engine?.localMedia?.switchCamera()
    }

    /**
     * Whether the application is in front of the person.
     *
     * Only the background pipeline cares, and it cares for the battery: a
     * segmentation model running on a phone in somebody's pocket is a bill for
     * a picture nobody is looking at. While the app is away and a background is
     * chosen, nothing at all goes out from the camera - not the room, and not a
     * stale composite. See media/effects/BackgroundProcessor.kt.
     */
    fun setAppVisible(visible: Boolean) {
        synchronized(nativeRecordingLock) { appVisible = visible }
        if (!visible) {
            val video = synchronized(nativeRecordingLock) { nativeRecordingOwner?.layout != null }
            if (video) {
                engine?.revokeRecordingVideo(reason = "Recording paused while KithMoot is hidden")
                changeNativeRecordingPause(true)
            }
        }
        if (!visible) {
            _room.update { it.copy(nativeHosting = it.nativeHosting?.paused(),
                joinUrl = if (it.nativeHosting != null) "" else it.joinUrl,
                letInAsks = if (it.nativeHosting != null) emptyList() else it.letInAsks) }
            nativeKeeperEntry?.close()
        }
        if (!visible) stopRoomSharing()
        if (!visible && freshNearbyOpening) { entryJob?.cancel(); return }
        engine?.localMedia?.setAppVisible(visible)
        parkJob?.cancel()
        parkJob = null
        if (!visible && savedRoom?.route?.nearby == true && nearbyParkJob?.isActive != true) {
            nearbyTransport?.close()
            nearbyOwner?.close()
            parkedRoomId = savedRoom?.id
            // Do not cancel this teardown when the app returns quickly. The
            // closed radio must not be left attached to a still-open screen.
            entryJob?.takeIf { it.isActive }?.cancel()
            nearbyParkJob = viewModelScope.launch {
                if (entering.awaitAcquire(ENTRY_GATE_WAIT_MS)) {
                    entering.release()
                    if (_stage.value == Stage.ROOM) leave()
                    _start.first { !it.busy }
                    if (appVisible) resumeParked()
                } else note("Bluetooth is paused. Leave and reopen the room to reconnect.")
            }
        } else if (visible && nearbyParkJob?.isActive != true) resumeParked()
        else if (!visible && savedRoom?.route?.nearby != true) armPark()
    }

    private var nearbyParkJob: Job? = null

    @Volatile private var appVisible: Boolean = true

    private var parkJob: Job? = null
    /** The room [armPark] closed while the app was hidden, reopened on the way back unless something else opens first. */
    @Volatile private var parkedRoomId: String? = null

    /**
     * Hands a room left on screen behind other apps to the closed-app
     * listener once it has sat idle for [PARK_HIDDEN_ROOM_AFTER_MS]; see
     * [shouldParkHiddenRoom]. Anything starting in the room meanwhile starts
     * the wait again.
     */
    private fun armPark() {
        if (chatOnly || _stage.value != Stage.ROOM) return
        parkJob = viewModelScope.launch {
            var idleSince: Long? = null
            while (!appVisible) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (_stage.value != Stage.ROOM) return@launch
                if (!shouldParkHiddenRoom(parkCheck())) idleSince = null
                else if (idleSince == null) idleSince = now
                else if (now - idleSince >= PARK_HIDDEN_ROOM_AFTER_MS) {
                    val id = savedRoom?.id ?: return@launch
                    parkedRoomId = id
                    Log.i(JOIN_LOG, "parked hidden room after idleMs=${now - idleSince}")
                    leave()
                    return@launch
                }
                delay(PARK_CHECK_INTERVAL_MS)
            }
        }
    }

    private fun parkCheck(): ParkCheck {
        val saved = savedRoom
        val room = _room.value
        val app = getApplication<KithMootApplication>()
        val delivery = saved?.let {
            val usesLink = it.relays.any { url -> linkConsents.activeRoute(it.participant, it.id, url) != null }
            DeliveryCandidate(
                roomId = it.id,
                allowsInternet = it.route.internet,
                anonymous = anonymousRoom || it.anonymous,
                quiet = it.policy?.quiet == true || it.quietState != null,
                ended = it.retired || it.movedOn || it.ended(epochSeconds()),
                epochId = (session?.epochState?.value as? dev.forgesworn.kithmoot.session.RoomEpochState.Active)?.trafficRoom,
                needsBunker = usesLink && it.viaAccount &&
                    runCatching { accounts.load()?.method == "bunker" }.getOrDefault(true),
            )
        }
        return ParkCheck(
            delivery = delivery,
            listenerReceiving = dev.forgesworn.kithmoot.service.BackgroundCallListenerService.alive &&
                dev.forgesworn.kithmoot.service.BackgroundDeliverySettings(app).enabled(),
            chatOnly = chatOnly,
            onCall = room.onCall,
            callJoinPending = room.callJoinPending,
            callChanging = room.callChanging,
            mediaStarting = room.mediaStarting,
            screenOn = room.screenOn,
            recording = room.recording != dev.forgesworn.kithmoot.protocol.RecordingView.Off || consentedRecording != null,
            // Not the `opening` job: it outlives the media build it starts, which mediaStarting already covers.
            busy = entering.held.value || _start.value.busy || room.chatSending || room.chatPending ||
                room.workBusy || room.roomUpdate != null,
        )
    }

    /** A notification tap, answer or link came in with the return: it opens instead of the parked room. */
    fun forgetParkedRoom() { parkedRoomId = null }

    /** Back in front: reopens the room [armPark] closed, unless a notification tap or link opened another first. */
    private fun resumeParked() {
        val id = parkedRoomId ?: return
        viewModelScope.launch {
            delay(RESUME_PARKED_AFTER_MS)
            if (parkedRoomId != id || _stage.value != Stage.START) return@launch
            parkedRoomId = null
            Log.i(JOIN_LOG, "reopening parked room")
            reopenRoom(id)
        }
    }

    /**
     * Choose what is drawn behind you, or choose nothing.
     *
     * Applied to the running camera at once and remembered on this device.
     * Passing a null scene turns it off, which is the only route by which this
     * device's camera goes out untouched.
     */
    fun chooseBackground(scene: SeaScene?, fish: Boolean) {
        val choice = BackgroundChoice(scene = scene, fish = fish)
        backgrounds.save(choice)
        _room.update { it.copy(background = choice) }
        engine?.localMedia?.setBackground(choice)
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
        revokeLocalRecordingVideo(dev.forgesworn.kithmoot.media.recording.RecordingVideoRole.CAMERA)
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
    fun startScreenShare(permission: Intent, shareAudio: Boolean = true) {
        if (!_room.value.mediaRunning) return
        if (!_room.value.meetingSpeaker) return note(MEETING_LOCKED)
        val media = engine?.localMedia ?: return note(mediaMissing())
        val scope = sessionScope ?: return
        scope.launch {
            ScreenShareService.start(getApplication())
            val running = withTimeoutOrNull(5_000) { ScreenShareService.running.first { it } }
            if (running != true) {
                ScreenShareService.stop(getApplication())
                return@launch note("Android would not start the screen-sharing notification.")
            }
            val started = withContext(Dispatchers.Default) { synchronized(mediaControlLock) { runCatching {
                check(_room.value.mediaRunning && engine?.localMedia === media) { "The call has ended." }
                media.startScreenShare(permission, shareAudio)
            } } }
            if (started.getOrNull() == null) {
                ScreenShareService.stop(getApplication())
                note("Screen sharing did not start: " + (started.exceptionOrNull()?.message ?: "the capture was refused"))
            }
        }
    }

    fun stopScreenShare() = act {
        revokeLocalRecordingVideo(dev.forgesworn.kithmoot.media.recording.RecordingVideoRole.SCREEN)
        engine?.localMedia?.stopScreenShare()
        ScreenShareService.stop(getApplication())
    }

    fun screenShareDeclined() {
        note("Screen sharing needs Android's permission. Nothing was shared.")
    }

    fun setProfilesEnabled(enabled: Boolean) {
        if ((anonymousRoom || !(_room.value.route.internet)) && enabled) return
        display.edit().putBoolean("publicProfiles", enabled).apply()
        if (!enabled) dev.forgesworn.kithmoot.ui.room.forgetProfilePictures()
        _start.update { it.copy(publicProfiles = enabled, privateChatProfiles = if (enabled) it.privateChatProfiles else emptyMap()) }
        _room.update { it.copy(profilesEnabled = enabled, profiles = if (enabled) it.profiles else emptyMap()) }
    }

    /** A self-view shown as a mirror is what most people expect, and some find
     *  it backwards. Remembered for this device, not the room. */
    fun setMirrorSelf(enabled: Boolean) {
        display.edit().putBoolean(MIRROR_SELF, enabled).apply()
        _start.update { it.copy(mirrorSelf = enabled) }
        _room.update { it.copy(mirrorSelf = enabled) }
    }

    fun refreshCadence() = cadenceAction { record, who, secondary ->
        refreshCadence(record, who, secondary)
    }

    /**
     * One explicit NIP-77 comparison of the current room address. It carries
     * only event IDs and timestamps. A box-only event can be retrieved only by
     * the separate button enabled by this comparison. A second, independently
     * visible action may offer only exact phone-only events that this phone
     * retained in its encrypted, bounded offer archive.
     */
    fun compareRoomHistoryWithBothy() {
        val record = savedRoom ?: return
        val who = identity ?: return
        val relay = pool ?: return
        val scope = sessionScope ?: return
        val state = _room.value.nip77 ?: return
        if (state.busy) return
        _room.update { it.copy(nip77 = state.copy(busy = true, detail = "Comparing event IDs with Bothy. No messages move.")) }
        scope.launch(Dispatchers.IO) {
            try {
                val account = accountSession?.account ?: error("Sign in as this room's account before comparing history.")
                require(record.viaAccount && account.pubkey == who.participant) {
                    "This room is not owned by the signed-in account."
                }
                val url = relayUrls.singleOrNull()?.takeIf {
                    LinkRelayAddress.canonical(it) == it && it in circleRelaySet()
                } ?: error("Connect this room to one verified Link-carried Bothy before comparing history.")
                val epoch = session?.epochKeys() ?: error("This room is no longer open.")
                val address = deriveChatChannel(epoch.id, epoch.key).id
                val until = epochSeconds()
                val since = maxOf(0, until - 30L * 24 * 60 * 60)
                val records = nip77Events.records(account.pubkey, record.id, address, since, until)
                val result = relay.reconcileNip77(
                    url = url,
                    filter = Filter(kinds = listOf(KIND_CHAT), tags = mapOf("#d" to listOf(address)),
                        since = since, until = until, limit = 127),
                    records = records,
                )
                val phoneOnly = result.have.map { it.toHex() }.toSet()
                val offerIds = nip77Offers.available(account.pubkey, record.id, address, since, until, phoneOnly)
                    .map(NostrEvent::id).toSet()
                updateNip77Result(relay, result, Nip77ReconciliationPlan(
                    account = account.pubkey,
                    roomId = record.id,
                    relayUrl = url,
                    address = address,
                    since = since,
                    until = until,
                    fetchIds = result.need.map { it.toHex() }.toSet(),
                    offerIds = offerIds,
                ))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = (error as? java.util.concurrent.ExecutionException)?.cause?.message
                    ?: error.message ?: "Bothy could not compare this room's event IDs."
                if (pool === relay) _room.update { current ->
                    current.copy(nip77 = current.nip77?.copy(busy = false, detail = message))
                }
            }
        }
    }

    private fun updateNip77Result(relay: RelayPool, result: Nip77Reconciliation, plan: Nip77ReconciliationPlan) {
        if (pool !== relay) return
        nip77Plan = plan.takeIf { it.fetchIds.isNotEmpty() || it.offerIds.isNotEmpty() }
        _room.update { current ->
            current.copy(nip77 = current.nip77?.copy(
                busy = false,
                fetchAvailable = plan.fetchIds.isNotEmpty(),
                offerAvailable = plan.offerIds.isNotEmpty(),
                detail = "Comparison complete: Bothy has " + result.need.size + " IDs this phone has not fetched; " +
                    "this phone has " + result.have.size + " IDs not offered to Bothy. No messages moved." +
                    if (plan.fetchIds.isEmpty()) "" else " Fetch is available only for Bothy's listed IDs." +
                    if (plan.offerIds.isEmpty()) "" else " Offer is available only for ${plan.offerIds.size} encrypted event(s) retained on this phone.",
            ))
        }
    }

    /**
     * Retrieves exactly the current comparison's box-only IDs from that same
     * Link-authenticated Bothy. Returned outer events still pass the normal
     * room decryption, credential and replay checks before they reach the UI.
     */
    fun fetchComparedHistoryFromBothy() {
        val plan = nip77Plan ?: return
        val record = savedRoom ?: return
        val who = identity ?: return
        val relay = pool ?: return
        val live = session ?: return
        val scope = sessionScope ?: return
        val state = _room.value.nip77 ?: return
        if (state.busy || !state.fetchAvailable) return
        _room.update { it.copy(nip77 = state.copy(busy = true, fetchAvailable = false, detail = "Requesting only Bothy's compared event IDs. Each message will still be verified locally.")) }
        scope.launch(Dispatchers.IO) {
            try {
                val account = accountSession?.account ?: error("Sign in as this room's account before retrieving history.")
                require(record.viaAccount && account.pubkey == who.participant && plan.account == account.pubkey && plan.roomId == record.id) {
                    "This comparison no longer belongs to the signed-in room account."
                }
                require(pool === relay && relayUrls.singleOrNull() == plan.relayUrl && plan.relayUrl in circleRelaySet()) {
                    "This room's verified Bothy changed. Compare IDs again before retrieving anything."
                }
                val epoch = live.epochKeys()
                require(deriveChatChannel(epoch.id, epoch.key).id == plan.address) {
                    "This room changed its encryption epoch. Compare IDs again before retrieving anything."
                }
                val events = relay.fetchNip77Events(
                    url = plan.relayUrl,
                    filter = Filter(
                        ids = plan.fetchIds.sorted(),
                        kinds = listOf(KIND_CHAT),
                        tags = mapOf("#d" to listOf(plan.address)),
                        since = plan.since,
                        until = plan.until,
                        limit = plan.fetchIds.size,
                    ),
                    ids = plan.fetchIds,
                )
                events.forEach(live::onChatEvent)
                val remaining = plan.fetchIds - events.map(NostrEvent::id).toSet()
                if (pool === relay && nip77Plan == plan) {
                    nip77Plan = plan.copy(fetchIds = remaining).takeIf { it.fetchIds.isNotEmpty() || it.offerIds.isNotEmpty() }
                    _room.update { current -> current.copy(nip77 = current.nip77?.copy(
                        busy = false,
                        fetchAvailable = remaining.isNotEmpty(),
                        offerAvailable = plan.offerIds.isNotEmpty(),
                        detail = "Bothy returned ${events.size} compared record(s). They are shown only if normal local decryption and credential checks accept them." +
                            if (remaining.isEmpty()) "" else " ${remaining.size} compared ID(s) were not returned; compare again before retrying.",
                    )) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = (error as? java.util.concurrent.ExecutionException)?.cause?.message
                    ?: error.message ?: "Bothy could not retrieve the compared event IDs."
                if (pool === relay && nip77Plan == plan) _room.update { current ->
                    current.copy(nip77 = current.nip77?.copy(busy = false, fetchAvailable = true, detail = message))
                }
            }
        }
    }

    /**
     * Offers only the current comparison's phone-only events that remain in
     * the encrypted local archive. The same Link-authenticated box must
     * acknowledge every exact event; this is never background replication.
     */
    fun offerComparedHistoryToBothy() {
        val plan = nip77Plan ?: return
        val record = savedRoom ?: return
        val who = identity ?: return
        val relay = pool ?: return
        val live = session ?: return
        val scope = sessionScope ?: return
        val state = _room.value.nip77 ?: return
        if (state.busy || !state.offerAvailable) return
        _room.update { it.copy(nip77 = state.copy(busy = true, offerAvailable = false,
            detail = "Offering only this comparison's encrypted phone events to Bothy. Nothing goes to a public relay.")) }
        scope.launch(Dispatchers.IO) {
            try {
                val account = accountSession?.account ?: error("Sign in as this room's account before offering history.")
                require(record.viaAccount && account.pubkey == who.participant && plan.account == account.pubkey && plan.roomId == record.id) {
                    "This comparison no longer belongs to the signed-in room account."
                }
                require(pool === relay && relayUrls.singleOrNull() == plan.relayUrl && plan.relayUrl in circleRelaySet()) {
                    "This room's verified Bothy changed. Compare IDs again before offering anything."
                }
                val epoch = live.epochKeys()
                require(deriveChatChannel(epoch.id, epoch.key).id == plan.address) {
                    "This room changed its encryption epoch. Compare IDs again before offering anything."
                }
                val events = nip77Offers.available(account.pubkey, record.id, plan.address, plan.since, plan.until, plan.offerIds)
                require(events.map(NostrEvent::id).toSet() == plan.offerIds) {
                    "The compared phone events are no longer retained here. Compare IDs again before offering anything."
                }
                val offered = relay.offerNip77Events(
                    url = plan.relayUrl,
                    filter = Filter(ids = plan.offerIds.sorted(), kinds = listOf(KIND_CHAT), tags = mapOf("#d" to listOf(plan.address)),
                        since = plan.since, until = plan.until, limit = plan.offerIds.size),
                    events = events,
                )
                if (pool === relay && nip77Plan == plan) {
                    nip77Plan = plan.copy(offerIds = emptySet()).takeIf { it.fetchIds.isNotEmpty() }
                    _room.update { current -> current.copy(nip77 = current.nip77?.copy(
                        busy = false,
                        fetchAvailable = plan.fetchIds.isNotEmpty(),
                        offerAvailable = false,
                        detail = "Bothy acknowledged $offered exact encrypted event(s) from this phone. No public relay was used.",
                    )) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = (error as? java.util.concurrent.ExecutionException)?.cause?.message
                    ?: error.message ?: "Bothy could not accept this custody offer."
                if (pool === relay && nip77Plan == plan) _room.update { current ->
                    current.copy(nip77 = current.nip77?.copy(busy = false, offerAvailable = true, detail = message))
                }
            }
        }
    }

    fun startCadence() = cadenceAction { record, who, secondary ->
        val access = cadenceAccess(record, who, secondary)
        val context = requireNotNull(access.context) { access.reason ?: "Cadence is unavailable." }
        val existing = CadenceSchedule.primary(
            cadenceLeases.all(record.id, who.devicePubkey), context.key, DeadDrop.epochIndexAt(epochSeconds()),
        )
        if (existing != null) {
            refreshCadence(record, who, secondary)
            return@cadenceAction
        }
        require(!_room.value.chatSending && _room.value.pendingChats.none { it.state == dev.forgesworn.kithmoot.session.PendingChatState.SENDING }) {
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
        cadenceClient.stage(who.participant, options, who, epochSeconds(), cadenceLeases).get()
        val view = scheduleView(context, record.id, who.devicePubkey)
        _room.update { it.copy(cadence = view) }
    }

    /**
     * Stage the next generation of the running lease, starting exactly at its
     * end so there is never a second scheduler or a gap. Only ever pressed by
     * the person; a lease that is not renewed ends at its promised boundary.
     */
    fun renewCadence() = cadenceAction { record, who, secondary ->
        val access = cadenceAccess(record, who, secondary)
        val context = requireNotNull(access.context) { access.reason ?: "Cadence is unavailable." }
        val status = cadenceClient.status(who.participant, context.scope, cadenceId(), who, epochSeconds()).get().answer
        val credentialExpiry = who.credential.tagValue("expiration")?.toLongOrNull()
            ?: throw IllegalStateException("The device credential has no expiry.")
        val renewal = if (!status.ready) CadenceRenewal.Refused("Bothy is not ready: ${status.missing.joinToString(", ")}.")
            else CadenceSchedule.renewal(
                cadenceLeases.all(record.id, who.devicePubkey), context.key, status.currentEpoch,
                status.earliestStartEpoch, credentialExpiry / 3600, context.grantExpiresAt / 3600,
            )
        when (renewal) {
            is CadenceRenewal.Refused -> {
                val view = scheduleView(context, record.id, who.devicePubkey).copy(detail = renewal.reason)
                _room.update { it.copy(cadence = view) }
            }
            is CadenceRenewal.Ready -> {
                val options = CadenceLeaseOptions(
                    context.scope, cadenceId(), renewal.leaseId, renewal.generation, context.deviceSlot,
                    status.currentEpoch, renewal.startEpoch, renewal.endEpoch, context.roomKey, context.publicRelays,
                    listOf("local"), epochSeconds(),
                )
                cadenceClient.stage(who.participant, options, who, epochSeconds(), cadenceLeases).get()
                val view = scheduleView(context, record.id, who.devicePubkey)
                _room.update { it.copy(cadence = view) }
            }
        }
    }

    fun stopCadence() = cadenceAction { record, who, secondary ->
        val access = cadenceAccess(record, who, secondary)
        val context = requireNotNull(access.context) { access.reason ?: "Bothy could not stop the schedule." }
        val failure = runCatching { stopCadenceLeases(context, who, record.id) }.exceptionOrNull()
        runCatching { scheduleView(context, record.id, who.devicePubkey) }.onSuccess { view -> _room.update { it.copy(cadence = view) } }
        failure?.let { throw it }
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
        val live = CadenceSchedule.live(cadenceLeases.all(record.id, who.devicePubkey), context.key)
        if (live.isEmpty()) {
            val status = cadenceClient.status(who.participant, context.scope, cadenceId(), who, epochSeconds()).get().answer
            _room.update { it.copy(cadence = CadenceViewState(
                eligible = true,
                state = if (status.ready) "off" else "not-ready",
                detail = if (status.ready) "Bothy is ready to take over this phone's quiet cadence for up to twelve hours."
                    else "Bothy is not ready: ${status.missing.joinToString(", ")}.",
            )) }
            return
        }
        // A renewal leaves two live leases; each is resolved, because the earlier one is the one Bothy is running.
        for (current in live) {
            val resolved = resolveCadence(context, who, current)
            val result = if (resolved === current) {
                cadenceClient.leaseStatus(who.participant, context.scope, who, current, cadenceId(), epochSeconds(), cadenceLeases).get().lease
            } else resolved
            recoverCadenceQueue(context, who, result)
        }
        val view = scheduleView(context, record.id, who.devicePubkey)
        _room.update { it.copy(cadence = view) }
    }

    /** Learn the outcome of a lease whose reply was lost, never reclaiming its counters on a timeout. */
    private fun resolveCadence(context: CadenceContext, who: RoomIdentity, current: StoredCadenceLease): StoredCadenceLease {
        if (current.ownership != CadenceOwnership.CLIENT_EXCLUDED) return current
        return try {
            cadenceClient.retryStage(who.participant, context.scope, who, current, epochSeconds(), cadenceLeases).get().lease
        } catch (_: Exception) {
            cadenceClient.leaseStatus(who.participant, context.scope, who, current, cadenceId(), epochSeconds(), cadenceLeases).get().lease
        }
    }

    /**
     * Stop every live lease, including a staged renewal, each at its own safe
     * boundary. Bothy checks a boundary against its own epoch. A phone clock
     * behind Bothy's, within the two-minute signing allowance, can still read
     * the previous hour, and a boundary from it is one Bothy refuses, so the
     * boundaries come from Bothy's epoch.
     */
    private fun stopCadenceLeases(context: CadenceContext, who: RoomIdentity, room: String) {
        CadenceSchedule.live(cadenceLeases.all(room, who.devicePubkey), context.key).forEach { resolveCadence(context, who, it) }
        val epoch = cadenceClient.status(who.participant, context.scope, cadenceId(), who, epochSeconds()).get().answer.currentEpoch
        val targets = CadenceSchedule.stopTargets(cadenceLeases.all(room, who.devicePubkey), context.key, epoch)
        var failure: Exception? = null
        for ((lease, boundary) in targets) {
            try {
                cadenceClient.stop(who.participant, context.scope, who, lease, cadenceId(), boundary, epochSeconds(), cadenceLeases).get()
            } catch (error: Exception) {
                failure = failure ?: error
            }
        }
        failure?.let { throw it }
    }

    /**
     * Bothy's rekey retires every lease under the old key but reports only its
     * target, so the target is the lease holding this epoch's queued messages.
     */
    private fun epochCadence(record: SavedRoom, who: RoomIdentity, epoch: EpochKeys): StoredCadenceLease? {
        val live = cadenceLeases.all(record.id, who.devicePubkey).filter {
            it.ownership != CadenceOwnership.ENDED &&
                it.plan.trafficRoom == epoch.id && it.plan.roomGeneration == epoch.epoch.toLong() + 1
        }
        val now = DeadDrop.epochIndexAt(epochSeconds())
        return live.firstOrNull { now in it.plan.startEpoch until it.plan.endEpoch } ?: live.minByOrNull { it.plan.startEpoch }
    }

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
            // Written down before the room is marked closed, so a device that
            // stops here still tidies the room away when the app next starts.
            if (notice.closed && notice.destruct) runCatching { savedRooms.update(record.id) { it.withDestruct() } }
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
        if (cadenceLeases.all(record.id, who.devicePubkey).none { it.ownership != CadenceOwnership.ENDED }) return@withLock
        val access = cadenceAccess(record, who, record.secondary)
        val context = access.context ?: throw RoomRecoveryException(access.reason ?: "Cadence authority is unavailable.")
        stopCadenceLeases(context, who, record.id)
    }

    fun removeChatAttachment(hash: String) {
        _room.update { it.copy(chatAttachments = it.chatAttachments.filterNot { file -> file.sha256 == hash }) }
        viewModelScope.launch(Dispatchers.IO) { dev.forgesworn.kithmoot.storage.MediaUploadLedger(getApplication()).due(hash = hash) }
    }

    fun addChatArtwork(reference: ChatArtwork) {
        if (resolveCatalogueArtwork(reference) == null) return
        _room.update { if (it.chatArtwork.size >= 4) it else it.copy(chatArtwork = it.chatArtwork + reference.copy()) }
    }

    fun removeChatArtwork(index: Int) {
        _room.update { it.copy(chatArtwork = it.chatArtwork.filterIndexed { position, _ -> position != index }) }
    }

    /** No external file traffic for an anonymous room. Uploads require the chosen server's explicit consent. */
    fun addChatImage(uri: android.net.Uri, storage: String, consent: Boolean) {
        val live = session ?: return
        val roomId = _room.value.roomId
        val scope = sessionScope ?: return
        if (!_room.value.route.internet) return note("Choose an Internet connection to upload images.")
        if (_room.value.anonymous) return note("Image uploads are unavailable in an anonymous room.")
        if (!consent) return note("Allow shared encrypted storage before uploading.")
        if (_room.value.mediaBusy || _room.value.chatAttachments.size >= 4) return
        val origin = try { mediaStorageOrigin(storage) } catch (error: Exception) { return note(error.message ?: "Choose an HTTPS storage origin.") }
        _room.update { it.copy(mediaBusy = true, chatSendError = null) }
        scope.launch(Dispatchers.IO) {
            val ledger = dev.forgesworn.kithmoot.storage.MediaUploadLedger(getApplication())
            var sealed: SealedMedia? = null
            try {
                val resolver = getApplication<Application>().contentResolver
                val name = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: "image"
                val type = resolver.getType(uri)?.lowercase() ?: "application/octet-stream"
                val bytes = checkNotNull(resolver.openInputStream(uri)).use { it.readNBytes(MAX_MEDIA_SOURCE_BYTES + 1) }
                try { sealed = sealMedia(bytes, name, type) } finally { bytes.fill(0) }
                ensureActive()
                check(session === live) { "The room changed. Choose the image again." }
                val file = checkNotNull(sealed)
                val at = epochSeconds(); val ends = _room.value.endsAt
                check(ends == null || ends > at) { "This room has ended." }
                val key = live.identity.deviceSecretKey
                val auth = mediaAuthorisation("upload", file.hash, origin, key, at, minOf(at + 300, ends ?: Long.MAX_VALUE))
                val deletion = mediaAuthorisation("delete", file.hash, origin, key, at, (ends ?: (at + 31_536_000)) + 2_592_000)
                ledger.begin(file.hash)
                ledger.record(roomId, origin, file.hash, deletion)
                val attachment = uploadMedia(file, origin, auth)
                ensureActive()
                check(session === live && !dev.forgesworn.kithmoot.protocol.conferenceEnded(_room.value.endsAt, epochSeconds())) { "The room changed or ended while uploading. The file will be deleted." }
                _room.update { if (session === live) it.copy(chatAttachments = it.chatAttachments + attachment) else it }
            } catch (cancelled: CancellationException) {
                sealed?.let { ledger.due(hash = it.hash) }; throw cancelled
            } catch (error: Exception) {
                sealed?.let { ledger.due(hash = it.hash) }
                if (session === live) _room.update { it.copy(chatSendError = error.message ?: "The image could not be uploaded.") }
            } finally {
                sealed?.let { it.envelope.fill(0); ledger.finish(it.hash) }
                if (session === live) _room.update { it.copy(mediaBusy = false) }
            }
        }
    }

    fun sendChat(body: String) = sendChat(body, null)

    fun sendChat(body: String, onRetained: () -> Unit) = sendChat(body, null, onRetained)

    private fun sendChat(body: String, reaction: ChatReaction?, onRetained: () -> Unit = {}) {
        val live = session ?: return
        val scope = sessionScope ?: return
        val attachments = if (reaction == null) _room.value.chatAttachments else emptyList()
        val artwork = if (reaction == null) _room.value.chatArtwork else emptyList()
        val text = if (body.isBlank()) (listOf(artworkFallback(artwork)).filter(String::isNotBlank) +
            attachments.map { it.name ?: "Image" }).joinToString("; ").take(MAX_CHAT_TEXT_LENGTH) else body
        if (_room.value.cadence?.busy == true) {
            note("Finish the quiet schedule change before sending.")
            return
        }
        val durable = !_room.value.anonymous && !_room.value.quiet
        // Claim the composer before launching: repeated taps must not sign the
        // same draft while it is still being retained. Durable messages release
        // it once kept locally, so the next draft need not wait for a relay.
        if (_room.value.chatSending) return
        _room.update { it.copy(chatSending = true, chatSendError = null) }
        scope.launch(Dispatchers.IO) {
            var composerReleased = false
            try {
                val retainedOnMain: suspend () -> Unit = {
                    withContext(Dispatchers.Main.immediate) { if (session === live) { _room.update {
                        it.copy(chatAttachments = it.chatAttachments.filterNot { file -> file in attachments },
                            chatArtwork = it.chatArtwork.filterNot { staged -> artwork.any { submitted -> staged === submitted } })
                    }; onRetained()
                        if (durable) {
                            composerReleased = true
                            _room.update { it.copy(chatSending = false) }
                        }
                    } }
                }
                val confirmed = if (durable) live.sendChatDurable(text, reaction, attachments, artwork, retainedOnMain)
                    else live.sendChatConfirmed(text, reaction, attachments, artwork).also { if (it) retainedOnMain() }
                // A durable message that did not go is on the chat as pending, saying why.
                if (!confirmed && !durable) _room.update { if (session === live) it.copy(chatSendError = "No relay confirmed this message.") else it }
            } catch (_: TimeoutCancellationException) {
                if (session === live && !durable) _room.update { it.copy(chatSendError = "No relay confirmed this message.", notice = "No relay confirmed this message. Try again.") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (session === live) {
                    val message = error.message ?: "The message could not be confirmed."
                    _room.update { it.copy(chatSendError = message, notice = "$message Try again.") }
                }
            } finally {
                // A completed older publication must not unlock a newer draft.
                if (!composerReleased && session === live) _room.update { it.copy(chatSending = false) }
                if (durable && session === live) scheduleBackoff(live)
            }
        }
    }

    /**
     * A reply typed on this room's notification, sent as the composer sends:
     * kept on this phone before it is first published, and held to the
     * receiver's few seconds rather than the room's long confirmation wait.
     * Unconfirmed in that time, it stays kept and this room retries it as
     * it would its own. Refused, without sending, while another message is
     * being sent or waits for a retry.
     */
    private suspend fun replyFromNotice(live: RoomSession, text: String): dev.forgesworn.kithmoot.notifications.ReplyOutcome {
        val failed = dev.forgesworn.kithmoot.notifications.ReplyOutcome.FAILED
        val began = withContext(Dispatchers.Main.immediate) {
            val state = _room.value
            if (session !== live || state.chatSending || state.cadence?.busy == true ||
                state.anonymous || state.quiet) false
            else { _room.update { it.copy(chatSending = true, chatSendError = null) }; true }
        }
        if (!began) return failed
        var retained = false
        val kept = dev.forgesworn.kithmoot.notifications.ReplyOutcome.KEPT
        return try {
            val confirmed = withTimeoutOrNull(NOTICE_REPLY_CONFIRM_MS) { live.sendChatDurable(text, null) { retained = true } }
            if (confirmed == true) dev.forgesworn.kithmoot.notifications.ReplyOutcome.SENT
            else if (retained) kept else failed
        } catch (cancelled: CancellationException) {
            if (cancelled is TimeoutCancellationException) { if (retained) kept else failed } else throw cancelled
        } catch (error: Exception) {
            if (session === live) _room.update { it.copy(chatSendError = error.message ?: "The message could not be confirmed.") }
            if (retained) kept else failed
        } finally {
            if (session === live) _room.update { it.copy(chatSending = false) }
            if (!chatOnly) notifications.replied()
        }
    }

    /** [shown] is what the room showed, so a sender's slow clock cannot have it counted again. */
    private fun markBackgroundRead(record: SavedRoom, shown: List<dev.forgesworn.kithmoot.session.ChatMessage> = emptyList()) {
        try {
            dev.forgesworn.kithmoot.storage.BackgroundInboxVault(getApplication(), record.id, record.participant, record.devicePubkey)
                .inbox.markRead(epochSeconds(), shown)
        } catch (_: Exception) {
            // Unreadable storage only costs a repeated unread count, never a message.
        }
        // What the background service showed while the room was closed is read now.
        dev.forgesworn.kithmoot.notifications.MessageNotices.cancel(getApplication(), record.id)
    }

    private fun backgroundSeen(record: SavedRoom): List<String> = runCatching {
        dev.forgesworn.kithmoot.storage.BackgroundInboxVault(getApplication(), record.id, record.participant, record.devicePubkey)
            .inbox.state().seen
    }.getOrDefault(emptyList())

    private var pendingAttempts = 0
    private var pendingBackoff: Job? = null
    private var pendingRetry: Job? = null

    /** Offers what waits, oldest first. [auto] is the back-off's own call, which keeps its place in the sequence. */
    fun retryPendingChat(auto: Boolean = false) {
        val live = session ?: return
        val scope = sessionScope ?: return
        if (pendingRetry?.isActive == true) return
        if (!auto) { pendingAttempts = 0; pendingBackoff?.cancel() }
        _room.update { it.copy(chatSendError = null) }
        pendingRetry = scope.launch(Dispatchers.IO) {
            try {
                live.retryPendingChat()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (session === live) _room.update { it.copy(chatSendError =
                    error.message ?: "A message remains on this phone. Retry when connected.") }
            } finally {
                if (session === live) scheduleBackoff(live)
            }
        }
    }

    /** Try again after 5, 15, 30, then every 60 seconds, while something that can still go is waiting. */
    private fun scheduleBackoff(live: RoomSession) {
        val scope = sessionScope ?: return
        pendingBackoff?.cancel()
        val goes = live.pendingChats.value.any { it.state != dev.forgesworn.kithmoot.session.PendingChatState.MOVED }
        if (!goes) { pendingAttempts = 0; return }
        val seconds = PENDING_RETRY_SECONDS[minOf(pendingAttempts, PENDING_RETRY_SECONDS.lastIndex)]
        pendingAttempts++
        pendingBackoff = scope.launch {
            delay(seconds * 1000L)
            if (session === live) retryPendingChat(auto = true)
        }
    }

    /** Edit: the message goes back to the composer as text. [onText] runs on the main thread, or not at all. */
    fun editPendingChat(id: String, onText: (String) -> Unit) {
        val live = session ?: return
        val scope = sessionScope ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val text = live.editPendingChat(id)
                if (text == null) note("That message has already gone out, or is on its way. It can no longer be edited.")
                else withContext(Dispatchers.Main.immediate) { if (session === live) onText(text) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (session === live) _room.update { it.copy(chatSendError = error.message ?: "The pending message could not be edited.") }
            }
        }
    }

    /** Delete: for a message that never left this phone. */
    fun deletePendingChat(id: String) {
        val live = session ?: return
        val scope = sessionScope ?: return
        scope.launch(Dispatchers.IO) {
            try {
                if (live.deletePendingChat(id)) note("Deleted. Nobody will see that message.")
                else note("That message has already gone out, or is on its way. It cannot be deleted.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (session === live) _room.update { it.copy(chatSendError = error.message ?: "The pending message could not be deleted.") }
            }
        }
    }

    /** For a message a relay may already hold: it leaves the list and nothing is unsent. */
    fun removePendingChat(id: String) {
        val live = session ?: return
        val scope = sessionScope ?: return
        scope.launch(Dispatchers.IO) {
            try {
                if (live.removePendingChat(id)) note("Removed from this list. A relay may already have received it, and people may still see it.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (session === live) _room.update { it.copy(chatSendError = error.message ?: "The pending message could not be removed.") }
            }
        }
    }

    /** Create, retain and signer-seal a two-person room before leaving the introduction room. */
    /** Kind 10050 lists for these authors from these relays, as many as arrive
     *  before the relays finish or [DM_RELAY_LOOKUP_MS] is up. Never throws. */
    private suspend fun lookUpDmRelayLists(authors: List<String>, urls: List<String>): List<NostrEvent> {
        val distinct = urls.mapNotNull { runCatching { canonicalRoomRelayUrl(it) }.getOrNull() }.distinct()
        if (distinct.isEmpty()) return emptyList()
        val scope = CoroutineScope(kotlin.coroutines.coroutineContext)
        val pool = RelayPool(distinct, OkHttpRelaySockets(), scope, writeRelays = emptySet())
        pool.start()
        return try {
            pool.queryAvailable(listOf(Filter(kinds = listOf(KIND_DM_RELAYS), authors = authors)), DM_RELAY_LOOKUP_MS)
        } catch (e: CancellationException) {
            if (e is kotlinx.coroutines.TimeoutCancellationException) emptyList() else throw e
        } catch (_: Exception) {
            emptyList()
        } finally {
            pool.stop()
        }
    }

    /** The relays a new private conversation with [peer] starts on. The other
     *  person's list is asked for only while public profiles are on: asking a
     *  public relay for it names their key there. */
    private suspend fun relaysForConversationWith(self: String, peer: String, fallback: List<String>): List<String> {
        val askAboutPeer = _room.value.profilesEnabled
        val lists = lookUpDmRelayLists(if (askAboutPeer) listOf(self, peer) else listOf(self),
            fallback + accountRelayChoices().filter { it.read }.map { it.url } + PROFILE_RELAYS)
        return relaysForPrivateConversation(latestDmRelayList(lists, self),
            if (askAboutPeer) latestDmRelayList(lists, peer) else emptyList(), fallback)
    }

    fun startPrivateConversation(peer: String) {
        val live = session
        val signer = accountSigner
        val source = savedRoom
        val self = _room.value.selfParticipant
        val currentPeers = _room.value.privateConversationPeers
        if (_stage.value != Stage.ROOM || live == null || source == null) return
        if (!source.route.internet) return note("Choose Internet before starting another private conversation.")
        if (anonymousRoom) return note("Private conversations are unavailable in an anonymous room.")
        if (signer == null || signer.pubkey != self) {
            note("Sign in with the room's account before starting a private conversation.")
            return
        }
        if (peer !in currentPeers) {
            note("That person is no longer present in this room.")
            return
        }
        if (!entering.tryAcquire()) {
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
                // The two people's own DM relay lists, or this room's relays
                // when neither has one; the invitation and the link both name
                // exactly these. See docs/messages.md, "Where it lives".
                val relays = relaysForConversationWith(self, peer, relayUrls.toList())
                val invitation = InvitationPayload(host.invitation, relays, policy)
                val link = encodeInvitationUrl(selectedWebApp.joinBase, host.invitation, relays, policy)

                val roomRelays = invitationRelaysFrom(relays)
                publishGroup(host, secret, relays, roomRelays = roomRelays)
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
                    roomRelays = roomRelays,
                    roomRelaysSigned = roomRelays.isNotEmpty(),
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
                entering.release()
                _room.update { it.copy(privateConversationBusy = false) }
            }
        }
    }

    /** Deliberately open a verified invitation through the account signer that it addresses. */
    fun openPrivateConversation(message: ChatMessage) {
        if (!_room.value.route.internet) return note("Choose Internet before opening a new private invitation.")
        val live = session ?: return
        val signer = accountSigner
        val invite = message.invite ?: return
        val self = _room.value.selfParticipant
        val peer = invitePeer(invite, self, message.participant)
        if (signer == null || signer.pubkey != self || peer == null || message !in _room.value.chat) {
            note("This private invitation is not addressed to the signed-in room account.")
            return
        }
        if (!entering.tryAcquire()) {
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
                    expectedEpoch = admission.epoch,
                    destruct = admission.destruct,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: RoomStorageException) {
                storageFailed()
                note("The private conversation could not be saved.")
            } catch (e: Exception) {
                note(e.message ?: "The private conversation could not be opened.")
            } finally {
                entering.release()
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
        if (anonymousRoom) return@launch note("Paired devices are unavailable in an anonymous room.")
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
        // Eight relays at most, as in every other link: the room's own
        // first, then this device's.
        val linkRelays = RoomRelays.forLink(relayUrls, savedRoom?.sharedRelays.orEmpty())
        _room.update { it.copy(
            pairingLink = roomInvitation?.let { invitation ->
                encodeInvitationPairingLink(
                    base = selectedWebApp.joinBase,
                    invitation = invitation.invitation,
                    relays = linkRelays,
                    policy = invitation.policy,
                    deviceSecretKey = deviceKey,
                    credential = credential,
                )
            } ?: encodePairingLink(
                    base = selectedWebApp.joinBase,
                    secret = secret,
                    relays = linkRelays,
                    deviceSecretKey = deviceKey,
                    credential = credential,
                ),
        ) }
    }

    fun dismissPairingLink() {
        _room.update { it.copy(pairingLink = null) }
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
                    try { publishGroup(nextHost, secret, relayUrls, saved.anonymous, saved.ends, saved.roomRelays.takeIf { saved.roomRelaysSigned && !keepsOwnRelays(saved) }, saved.destruct) }
                    catch (e: GroupInvitationException) { return@withLock note(e.message ?: "The new group link could not be saved.") }
                }
                // The link names the room's own relays first, then the rest of
                // this pool, within the eight a link may carry.
                val linkRelays = RoomRelays.forLink(relayUrls, saved.sharedRelays)
                val nextInvitation = InvitationPayload(nextHost.invitation, linkRelays, saved.policy)
                val url = encodeInvitationUrl(selectedWebApp.joinBase, nextHost.invitation, linkRelays, saved.policy)
                val retirement = encodeInvitationRetirement(oldHost.invitation, oldHost.inviterSecretKey, epochSeconds(), ends = saved.ends)
                val next = try {
                    saved.rotated(nextHost, url, retirement).also(savedRooms::save)
                } catch (_: Exception) { return@withLock note("The new invitation could not be saved. The current link is unchanged.") }
                savedRoom = next
                transport.publish(retirement)
                invitationHostJob?.cancel()
                roomInvitationHost = nextHost
                roomInvitation = nextInvitation
                invitationHostJob = serveInvitation(scope, transport, nextHost, secret) { session?.epochKeys()?.epoch }
                _room.update { it.copy(joinUrl = url, notice = "A fresh link is ready. The old link's retirement will be sent when a relay connects. Existing members stay.") }
            }
        }
    }

    /** A stale render cannot hand out a native invitation after a source commit.
     * Only cheap in-memory guards run here; Home storage reads stay on IO. */
    fun canShareRoomInvitation(expected: NativeHostingState?): Boolean {
        val state = _room.value
        if (!appVisible || !state.canShareInvitation || state.nativeHosting != expected) return false
        if (expected == null) return nativeKeeperEntry == null && nativeKeeperController == null
        val entry = nativeKeeperEntry ?: return false
        val controller = nativeKeeperController ?: return false
        return session != null && entry.binding.pin == expected.binding.pin &&
            state.roomId == expected.binding.room && controller.canShareObservedInvitation(expected)
    }

    /** Uses the native source's complete audience; no legacy host key. */
    fun changeNativeRoomKey(expected: NativeHostingState) = changeNativeMembers(expected, emptyList())

    fun removeNativeRoomMember(expected: NativeHostingState, participant: String) =
        changeNativeMembers(expected, listOf(participant))

    private fun changeNativeMembers(expected: NativeHostingState, removed: List<String>) {
        val gone = removed.toList()
        runNativeCommand(expected, { it.canChangeMembers }) {
            it.rekeyObservedMembers(expected, gone)
            "Room update saved. Hosting state does not confirm delivery to members."
        }
    }

    fun retireNativeInvitation(expected: NativeHostingState) = runNativeCommand(expected,
        { it.canRetireInvitation }) {
        it.retireObservedInvitation(expected)
        "Invitation retired. Existing members can still chat."
    }

    fun resendNativeRetirement(expected: NativeHostingState, id: String) = runNativeCommand(expected,
        { it.canResendRetirement && id in it.retirementOriginals }) {
        it.retryObservedRetirement(expected, id)
        "Notice resend requested. This does not confirm delivery to members."
    }

    fun recoverNativePendingUpdate(expected: NativeHostingState) = runNativeCommand(expected, { it.canRetry }) {
        val outcome = it.retryObservedPending(expected)
        when (outcome.status) {
            NativeHostingStatus.RECOVERING -> "Room update is still pending. Check the connection and remaining retry limits."
            NativeHostingStatus.READY -> "Saved room update recovered. This does not confirm delivery to members."
            else -> "Room hosting is unavailable. Reopen to inspect the saved update."
        }
    }

    private fun runNativeCommand(expected: NativeHostingState, allowed: (NativeHostingState) -> Boolean,
        action: suspend (NativeKeeperController) -> String) {
        val entry = nativeKeeperEntry
        val controller = nativeKeeperController
        val live = session
        fun attached() = entry != null && controller != null && live != null && appVisible &&
            nativeKeeperEntry === entry && nativeKeeperController === controller && session === live
        // Capture before dispatch so switching rooms cannot select another owner.
        viewModelScope.launch {
            while (true) {
                val state = _room.value
                val current = state.nativeHosting
                if (!attached() || state.roomId != expected.binding.room || current == null ||
                    current.binding != expected.binding || current.revision != expected.revision ||
                    current.epoch != expected.epoch || current.lifecycle != expected.lifecycle ||
                    current.pendingOriginals != expected.pendingOriginals ||
                    current.ownerGeneration != expected.ownerGeneration || !allowed(current) || !allowed(expected)) {
                    _room.update { if (attached() && it.roomId == expected.binding.room)
                        it.copy(notice = "Room hosting changed. Open the confirmation again.") else it }
                    return@launch
                }
                if (state.nativeHostingBusy) return@launch
                if (_room.compareAndSet(state, state.copy(nativeHostingBusy = true))) break
            }
            var result: String? = null
            try {
                result = withContext(Dispatchers.IO) { action(requireNotNull(controller)) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { result = "Room update could not complete. Inspect the hosting state before trying again." }
            finally {
                _room.update { if (attached() && it.roomId == expected.binding.room &&
                    it.nativeHosting?.binding == expected.binding)
                    it.copy(nativeHostingBusy = false, notice = result ?: it.notice) else it }
            }
        }
    }

    private suspend fun persistLiveRoom(id: String,
        failure: String = "The room's changed access could not be saved. Check its current invitation before returning.",
        change: (SavedRoom) -> SavedRoom) {
        withContext(Dispatchers.IO) {
            try {
                val saved = savedRooms.update(id, change)
                if (savedRoom?.id == id) savedRoom = saved
            } catch (_: RoomStorageException) {
                note(failure)
            }
        }
    }

    /**
     * A `relays` record from the room's authority has verified and outranks
     * anything this device held. Adds the listed relays to the live
     * connection - no rejoin - and keeps the record with the saved room, so
     * reopening it later uses them too, then tells the person unless they
     * made the room themselves. The record's relays join the room's own,
     * ahead of this device's and never cut; the saved list of this device's
     * own relays is left alone. Mirrors `ingestRoomRelays`/`adoptRoomRelays`
     * in the web client's `app/src/main.ts`. An anonymous room, or one
     * sheltered behind a Bothy, adds only the relays its guard accepts
     * ([roomRelayGuard]) and says how many it left out.
     */
    /**
     * The room's shared name changed: the newest rename that counts, read
     * on the control channel, carried, or made here. It replaces this
     * device's name for the room everywhere - the saved room and the rooms
     * list, the title, chat notifications and the call ringer - and is kept
     * with its order key, so an older link does not put the old name back.
     * Mirrors `adoptSharedRoomName` in the web client's `app/src/main.ts`.
     */
    private fun onRoomNameReceived(roomId: String, shared: RoomNameRecord) {
        if (savedRoom?.id != roomId) return
        viewModelScope.launch(Dispatchers.IO) {
            if (savedRoom?.id != roomId) return@launch
            if (savedRoom?.sharedName?.let { it.id == shared.id && it.at == shared.at && it.name == shared.name } != true) {
                persistLiveRoom(roomId, "The room's new name could not be saved on this phone. It still shows here.") { it.withSharedName(shared) }
                runCatching { savedRooms.list() }.getOrNull()?.let { rooms -> _start.update { it.copy(savedRooms = rooms) } }
            }
            notifications.rename(roomId, shared.name)
            withContext(Dispatchers.Main) {
                _room.update { it.withSharedName(roomId, shared) }
            }
        }
    }

    /** A rename, not a carried copy, read for the first time this visit:
     *  the chat says who renamed the room, once per rename. */
    private fun onRenameRead(roomId: String, rename: RoomNameRecord) {
        _room.update { it.withRenameRead(roomId, rename) }
    }

    /** Rename the room for everybody in it: hidden in a two-person room,
     *  whose title is the other person, and in an anonymous room, which
     *  follows no shared work or names. */
    fun renameRoomForEveryone(name: String) {
        val work = roomWork ?: run {
            _room.update { it.copy(notice = "Wait for the room to finish connecting, then rename it.") }
            return
        }
        val clean = dev.forgesworn.kithmoot.protocol.DisplayName.sanitise(name)
        if (clean == null) { _room.update { it.copy(notice = "A room name cannot be empty.") }; return }
        if (clean == _room.value.name) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val record = work.rename(clean)
                _room.update { if (roomWork === work) it.copy(notice = "Renamed the room to “${record.name}” for everyone.") else it }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                _room.update { if (roomWork === work) it.copy(notice = error.message ?: "The room could not be renamed. Try again.") else it }
            }
        }
    }

    private fun onRoomRelaysReceived(roomId: String, record: RoomRelaysRecord, sentAt: Long) {
        if (savedRoom?.id != roomId) return
        if (savedRoom?.nativeAuthority != null) return
        viewModelScope.launch(Dispatchers.IO) {
            if (savedRoom?.id != roomId) return@launch
            persistLiveRoom(roomId) { it.withRoomRelaysRecord(record) }
            val added = adoptSharedRelays(roomId)
            val room = savedRoom?.takeIf { it.id == roomId } ?: return@launch
            val refused = RoomRelays.guarded(record.relays, roomRelayGuard(room)).refused.size
            if (added.isEmpty() && refused == 0) return@launch
            val ownRoom = room.host(epochSeconds())?.let { it.delegation.isEmpty() && room.authority == Schnorr.publicKeyHex(it.inviterSecretKey) } == true
            val notice = listOfNotNull(
                "This room now also uses ${added.joinToString(", ")}, as its owner asked.".takeIf { added.isNotEmpty() && !ownRoom },
                RoomRelays.refusalNotice(refused, torOnly = room.anonymous),
            ).joinToString(" ").ifEmpty { null }
            withContext(Dispatchers.Main) {
                _room.update {
                    if (it.roomId != roomId) it else it.copy(
                        relaysTotal = relayUrls.size,
                        lane = roomLane(relayUrls, anonymousRoom, ::circleRelaySet),
                        notice = notice ?: it.notice,
                    )
                }
            }
        }
    }

    /** A room whose saved relays are its own choice: an anonymous room, whose
     *  relays must all be onion services, or one sheltered behind a Bothy,
     *  where adding public relays would undo that choice. Link hints never
     *  join its saved relays, and its room relays pass [roomRelayGuard]. */
    private fun keepsOwnRelays(room: SavedRoom): Boolean =
        room.anonymous || linkConsents.all().any { it.roomId == room.id }

    /**
     * Which of the room's own relays this room may use, or null for an
     * ordinary room, which uses them all. An anonymous room takes only what
     * Tor-only mode accepts: v3 onion relays, which it can reach over Tor. A
     * room sheltered behind a Bothy takes only its active Link route and its
     * circle's relays, so its lane stays sheltered. What a guard refuses is
     * left out of the pool, never dialled.
     */
    private fun roomRelayGuard(room: SavedRoom): ((String) -> Boolean)? = when {
        room.anonymous -> { url -> runCatching { TorOnlyRelayUrls.normalise(url) }.isSuccess }
        linkConsents.all().any { it.roomId == room.id } -> {
            val circle = circleRelaySet()
            val guard: (String) -> Boolean = { url ->
                linkConsents.activeRoute(room.participant, room.id, url) != null ||
                    laneOfRelayUrl(url, circle) == Lane.SHELTERED
            }
            guard
        }
        else -> null
    }

    /** This device made the room: it holds the inviter key itself, so what it
     *  says the room's relays are is what the group invitation will say. */
    private fun madeHere(room: SavedRoom): Boolean =
        room.invitation?.invitation?.persistent == true && room.host(epochSeconds())?.delegation?.isEmpty() == true

    /**
     * The room's own relays, as this opening settles them: a signed list from
     * the group invitation wins; else what the saved room already holds; else
     * the link's hints, on first sight. The device that made the room signs
     * what it holds, which is how a room made before invitations carried
     * relays gets them: [keepGroupInvitationAlive] republishes with them.
     */
    private fun learnRoomRelays(room: SavedRoom, learnt: List<String>, signed: Boolean): SavedRoom {
        // An anonymous room's own relays are the onion relays its link named,
        // already in its pool; only its authority's record can add to them.
        if (room.anonymous) return room
        var next = room.withRoomRelays(learnt, signed)
        if (next.roomRelays.isEmpty()) next = next.withRoomRelays(invitationRelaysFrom(linkRelays(next.joinUrl)), signed = false)
        if (next.roomRelays.isNotEmpty() && !next.roomRelaysSigned && madeHere(next)) next = next.withRoomRelays(next.roomRelays, signed = true)
        return next
    }

    private fun linkRelays(link: String): List<String> =
        runCatching { decodeInvitationUrl(link)?.relays ?: decodeJoinUrl(link).relays }.getOrDefault(emptyList())

    /**
     * A member of a group room whose relays did not come from its signed
     * invitation reads that invitation once, in the background, after
     * opening, and adopts the relays it names. This is how members of a room
     * made before invitations carried relays converge on them. It never
     * fails the room: a retirement, a missing invitation or a timeout here
     * changes nothing.
     */
    private fun readRoomRelaysOnce(scope: CoroutineScope, transport: RelayPool, room: SavedRoom) {
        val invitation = room.invitation?.invitation ?: return
        if (!invitation.persistent || room.roomRelaysSigned || room.anonymous) return
        scope.launch(Dispatchers.IO) {
            val admission = try {
                withTimeoutOrNull(ROOM_RELAYS_READ_MS) { requestPersistentAdmission(invitation) { transport.queryStored(it) } }
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                null
            } catch (_: Exception) { null }
            admission?.secret?.fill(0)
            val relays = admission?.relays ?: return@launch
            if (savedRoom?.id != room.id) return@launch
            persistLiveRoom(room.id) { it.withRoomRelays(relays, signed = true) }
            val added = adoptSharedRelays(room.id)
            if (added.isNotEmpty()) withContext(Dispatchers.Main) {
                _room.update {
                    if (it.roomId != room.id) it else it.copy(
                        relaysTotal = relayUrls.size,
                        lane = roomLane(relayUrls, anonymousRoom, ::circleRelaySet),
                    )
                }
            }
        }
    }

    /**
     * Adds to the live pool whichever of the room's shared relays it lacks,
     * after the saved room learnt more of them. No rejoin. An own relay the
     * sixteen-relay cap would now leave out stays connected until the room is
     * next opened: a running pool can add a relay, not drop one. Returns the
     * relays added.
     */
    private fun adoptSharedRelays(roomId: String): List<String> {
        val room = savedRoom?.takeIf { it.id == roomId } ?: return emptyList()
        val transport = pool ?: return emptyList()
        val accepted = RoomRelays.guarded(room.sharedRelays, roomRelayGuard(room)).accepted
        val added = RoomRelays.missing(relayUrls, RoomRelays.atOpen(relayUrls, emptyList(), room = accepted))
        if (added.isEmpty()) return emptyList()
        stopRoomSharing()
        transport.addRelays(added)
        relayUrls = relayUrls + added
        _room.update { it.copy(sharing = it.sharing?.copy(relays = transport.describe().map(::canonicalRelayUrl).sorted(),
            error = "Internet connections changed. Review them before resuming sharing.")) }
        return added
    }

    /** Says something short to the person in the room. Shown once, then cleared. */
    fun showNotice(message: String) = note(message)

    /**
     * Somebody the room does not know asked this device's desk for the room's key, after the
     * room removed somebody (kithmoot#207): a newcomer, or a removed person back under a new key.
     * Shown as a "wants to join" card; nothing is handed over unless this person says yes.
     */
    private fun askToLetIn(stableRoom: String, participant: String) {
        viewModelScope.launch {
            if (savedRoom?.id != stableRoom) return@launch
            val p = participant.lowercase()
            _room.update { state ->
                if (state.letInAsks.any { it.participant == p }) state
                else state.copy(letInAsks = state.letInAsks + LetInAsk(p, letInLabel(p)))
            }
        }
    }

    /** A contact's name when this device has one for them, else their short npub. */
    private fun letInLabel(participant: String): String =
        runCatching { contacts.get(participant)?.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: shortNpub(participant)

    /** The answer to a "wants to join" card: a yes lets them in from this device. */
    fun answerLetIn(participant: String, yes: Boolean) {
        val room = savedRoom ?: return
        val ask = _room.value.letInAsks.firstOrNull { it.participant == participant } ?: return
        _room.update { it.copy(letInAsks = it.letInAsks.filterNot { a -> a.participant == participant }) }
        if (!yes) return
        if (room.nativeAuthority != null) {
            val controller = nativeKeeperController ?: run { note("Reopen the room before approving this participant."); return }
            viewModelScope.launch(Dispatchers.IO) {
                try { controller.approve(participant) }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { _room.update { it.copy(notice = error.message ?: "The approval could not be saved.") } }
            }
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { roomMembers.letIn(room.id, participant) }
            _room.update { it.copy(notice = "You let ${ask.label} in.") }
        }
    }

    fun dismissEpochTrouble() {
        epochTroubleDismissed += _room.value.epochTrouble.size
        _room.update { it.copy(epochTrouble = emptyList()) }
    }

    fun dismissNotice() {
        _room.update { it.copy(notice = null) }
    }

    // --- internals -----------------------------------------------------------

    private fun onLocalTracks(tracks: List<LocalTrack>) {
        _room.update { it.copy(
            micOn = tracks.any { it.microphoneOn },
            micMuted = tracks.any { it.microphoneOn && it.microphoneMuted },
            cameraOn = tracks.any { it.role == Roles.CAMERA },
            screenOn = tracks.any { it.role == Roles.SCREEN },
        ) }
        // The self-heal the web client does in `publishActiveTracks`: a device
        // with something live that is not saying which call it is on is the
        // exact shape of the complaint - a phone streaming to a Mac that still
        // offered to Start one. Adopt the call that is on rather than minting a
        // second. Only while this device is meant to be on a call at all:
        // `leaveCall` clears `mediaRunning` before anything else, so a track
        // emission landing behind a Leave declares nothing.
        if (tracks.isNotEmpty() && _room.value.mediaRunning) adoptRoomCall(pressed = false)
    }

    /**
     * Say which call this device is on: the room's current one if any present
     * device advertises one, else the one this device was last on, else - for
     * a press of Join only - a new one. See [callToDeclare].
     *
     * The choice rule is the head of [RoomSession.calls], which is the web's
     * `calls()[0]` - most people, then oldest - so two clients adopting at the
     * same moment adopt the same call rather than each other's.
     */
    private fun adoptRoomCall(pressed: Boolean) {
        val live = session ?: return
        // Never while a leave is settling. See leaveCall.
        if (leavingCall) {
            Log.i(JOIN_LOG, "call adopt skipped reason=leave-in-flight")
            return
        }
        if (live.currentCall() != null) {
            // Already a member. Keep the screen's mirror of it honest anyway:
            // it is what the control reads as "on the call".
            _room.update { if (session === live) it.copy(onCall = true) else it }
            return
        }
        val existing = live.calls().firstOrNull()?.id
        val remembered = lastCallId
        val id = callToDeclare(existing, remembered, pressed, ::newCallId)
        if (id == null) {
            Log.i(JOIN_LOG, "call adopt skipped reason=no-call-visible")
            return
        }
        val how = when (id) { existing -> "joined"; remembered -> "rejoined"; else -> "started" }
        Log.i(JOIN_LOG, "call $how id=${id.take(8)} by=${if (pressed) "press" else "self-heal"}")
        stopRoomSharing()
        runCatching { live.setCall(CallMembership(id, epochSeconds())) }
            .onSuccess {
                lastCallId = id
                _room.update { if (session === live) it.copy(onCall = true) else it }
            }
            .onFailure { Log.w(JOIN_LOG, "call declaration could not be published") }
    }

    /** A fresh call id, in the web client's format: 16 random bytes as hex. */
    private fun newCallId(): String = Entropy.bytes(16).toHex()

    private fun note(message: String) {
        _room.update { it.copy(notice = message) }
    }

    /**
     * The one moment the battery-optimisation exemption behind "Ring when
     * KithMoot is closed" is ever asked for: after this device's first call
     * ends, never at first launch and never again once declined - see
     * `service/BackgroundRingSettings.takeBatteryAsk` and
     * `service/BatteryOptimisation.kt`. A call just ended is the moment a
     * person has direct evidence the feature exists and works, rather than
     * a cold prompt on an app they have not used yet.
     */
    private fun maybeAskBatteryExemption() {
        val context: android.content.Context = getApplication()
        val settings = dev.forgesworn.kithmoot.service.BackgroundRingSettings(context)
        if (!settings.enabled()) return
        if (!settings.takeBatteryAsk()) return
        note("Ring me rooms can still ring you while KithMoot is closed if Android does not restrict its battery use.")
        dev.forgesworn.kithmoot.service.requestIgnoreBatteryOptimizations(context)
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
                lane = if (relayUrls.isEmpty()) state.lane else roomLane(relayUrls, anonymousRoom) { circle },
                tiles = if (live == null) state.tiles else buildTiles(live.participants.value, state.selfParticipant, state.selfDevice, cardNames, volumesFor(live.participants.value)),
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
        // A forgotten contact forgets everything local tied to them, including
        // how loud this device remembered them being.
        callVolume.forget(p)
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


}

internal fun epochSeconds(): Long = System.currentTimeMillis() / 1000

/**
 * Whether a bookmark tombstone from the account wipes a room this phone holds:
 * only a self-destructing one, which another of the person's devices has
 * tidied away. Removing any other room from the account removes only its
 * bookmark, as it always has.
 */
internal fun wipesOnBookmarkTombstone(saved: SavedRoom?): Boolean = saved?.destruct == true

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
