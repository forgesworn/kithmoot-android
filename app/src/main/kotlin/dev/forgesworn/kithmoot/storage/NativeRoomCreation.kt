package dev.forgesworn.kithmoot.storage

import android.content.Context
import dev.forgesworn.kithmoot.epoch.*
import dev.forgesworn.kithmoot.protocol.*
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** IO-only creation/recovery. No routes, publication or root key in its encrypted intent. */
internal class NativeRoomCreation(
    private val storage: RoomStorage,
    private val rooms: RoomRepository,
    private val createSource: (NativeKeeperBinding, NativeKeeperCreation, NostrEvent) -> NativeKeeperJournal,
    private val openSource: (NativeKeeperBinding) -> NativeKeeperJournal,
    private val owner: String = ALIAS,
) : AutoCloseable {
    private data class Intent(val at: Long, val draft: SavedRoom, val reference: JsonObject, val credential: NostrEvent) {
        val binding get() = NativeKeeperReference.decode(reference)
    }
    private val lock = ReentrantLock()
    private val lease = Any()
    private var closed = false
    private var failed = false
    init { check(owners.putIfAbsent(owner, lease) == null) { "Native room creation already has an owner" } }

    /** Persists local identity before transferring the sole signer into its independent source. */
    fun begin(creation: NativeKeeperCreation, draft: SavedRoom, credential: NostrEvent): SavedRoom = lock.withLock {
        try {
            attempt {
                check(read() == null) { "An unfinished native room needs recovery first" }
                check(rooms.get(draft.id) == null) { "This room is already saved" }
                val binding = NativeKeeperBinding(draft.id, requireNotNull(draft.authority), draft.participant,
                    draft.devicePubkey, draft.route, if (draft.route.internet) draft.relays else emptyList())
                val invitation = requireNotNull(draft.invitation).invitation
                val intent = Intent(creation.createdAt, draft, NativeKeeperReference.encode(binding, deriveInvitationId(invitation)), credential)
                validate(intent)
                val originalInvitation = creation.invitation()
                val originalSecret = creation.roomSecret()
                val savedSecret = draft.secret
                try {
                    require(creation.room == binding.room && creation.authority == binding.authority &&
                        originalInvitation == invitation && originalSecret.contentEquals(savedSecret))
                    val welcome = requireNotNull(decodePersistentInvitation(creation.welcome(), invitation))
                    try { require(welcome.secret.contentEquals(savedSecret) && welcome.endsAt == draft.ends && welcome.destruct == draft.destruct) }
                    finally { welcome.secret.fill(0) }
                } finally { originalInvitation.bearer.fill(0); originalSecret.fill(0); savedSecret.fill(0) }
                write(intent)
                createSource(binding, creation, credential).use { finish(intent, it) }
            }
        } finally { creation.close() }
    }

    /** Missing/corrupt source refuses and leaves the intent; never signs a replacement. */
    fun recover(): SavedRoom? = lock.withLock {
        attempt {
            val intent = read() ?: return@attempt null
            openSource(intent.binding).use { finish(intent, it) }
        }
    }

    /** UI-safe unfinished metadata, without reading or claiming the source. */
    fun pending(): SavedRoomSummary? = lock.withLock { attempt { read()?.draft?.summary()?.copy(canShareInvite = false) } }

    /** Call inside use: that owner's lease spans inspection and suspendable
     * cleanup, without holding this thread lock across suspension. */
    fun requireSavedReset() = lock.withLock {
        attempt {
            check(read() == null) { "Recover the unfinished native room before resetting saved rooms." }
        }
    }

    private fun finish(intent: Intent, source: NativeKeeperJournal): SavedRoom {
        require(source.binding.pin == intent.binding.pin && source.snapshot().suspended)
        val existing = rooms.get(intent.draft.id)
        val saved = if (existing == null) {
            intent.draft.withNativeAuthority(source).also(rooms::saveNew)
        } else {
            require(existing.json["nativeAuthority"] == intent.reference && "host" !in existing.json) {
                "Saved room conflicts with unfinished native creation"
            }
            existing.verifyNativeAuthority(source)
            existing // A lost save return must not roll back later epoch/lifecycle metadata.
        }
        require(saved.json["nativeAuthority"] == intent.reference)
        saved.verifyNativeAuthority(source)
        storage.reset() // Last: failure requires reopen; it does not undo either committed store.
        return saved
    }

    private fun validate(intent: Intent) {
        val draft = intent.draft
        require(intent.at >= 0 && intent.at == draft.openedAt)
        require("host" !in draft.json && draft.nativeAuthority == null && !draft.anonymous && !draft.secondary &&
            !draft.retired && !draft.movedOn && draft.policy?.quiet != true)
        // Validate owner/policy/invitation using the same rules as a real restored reference.
        SavedRoom.decode(JsonObject(draft.json + ("nativeAuthority" to intent.reference)))
        val check = verifyDeviceCredential(intent.credential, draft.id, intent.at)
        require(check is CredentialCheck.Valid && check.participant == draft.participant && check.device == draft.devicePubkey)
        if (draft.viaAccount) require(draft.json.getValue("identity").jsonObject["credential"] == intent.credential.toJson())
    }

    private fun read(): Intent? {
        val bytes = storage.read() ?: return null
        try {
            require(bytes.size <= MAX_BYTES)
            val json = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            require(json.keys == setOf("v", "at", "room", "reference", "credential"))
            val version = json.getValue("v").jsonPrimitive
            val at = json.getValue("at").jsonPrimitive
            require(!version.isString && version.intOrNull == 1 && !at.isString)
            val event = json.getValue("credential").jsonObject
            require(event.keys == setOf("id", "pubkey", "sig", "kind", "created_at", "tags", "content"))
            require(!event.getValue("kind").jsonPrimitive.isString && !event.getValue("created_at").jsonPrimitive.isString)
            for (key in listOf("id", "pubkey", "sig", "content")) require(event.getValue(key).jsonPrimitive.isString)
            require(event.getValue("tags").jsonArray.all { tag -> tag.jsonArray.all { it.jsonPrimitive.isString } })
            return Intent(requireNotNull(at.longOrNull), SavedRoom.decode(json.getValue("room").jsonObject),
                json.getValue("reference").jsonObject, NostrEvent.fromJson(event)).also(::validate)
        } finally { bytes.fill(0) }
    }

    private fun write(intent: Intent) {
        val bytes = buildJsonObject {
            put("v", 1); put("at", intent.at); put("room", intent.draft.json)
            put("reference", intent.reference); put("credential", intent.credential.toJson())
        }.toString().toByteArray(Charsets.UTF_8)
        try { require(bytes.size <= MAX_BYTES); storage.write(bytes) }
        finally { bytes.fill(0) }
    }

    private inline fun <T> attempt(action: () -> T): T {
        check(!closed && !failed) { "Native creation needs a new owner to inspect durable state" }
        try { return action() }
        catch (error: Exception) { failed = true; throw error }
    }

    override fun close() {
        lock.withLock { if (!closed) { closed = true; owners.remove(owner, lease) } }
    }

    companion object {
        private const val ALIAS = "kithmoot.native-creation.v1"
        private const val MAX_BYTES = 128 * 1024
        private val owners = ConcurrentHashMap<String, Any>()
        fun open(context: Context, rooms: RoomRepository) = NativeRoomCreation(
            EncryptedRoomStorage(context, ALIAS, MAX_BYTES), rooms,
            { binding, creation, credential -> NativeKeeperVault(context, binding).create(creation, credential) },
            { binding -> NativeKeeperVault(context, binding).open() },
        )
    }
}
