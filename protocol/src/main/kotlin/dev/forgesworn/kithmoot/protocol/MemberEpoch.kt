package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexEquals
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.normaliseHex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.math.BigInteger
import java.security.MessageDigest

/*
 * Member-to-member epoch catch-up, wire-compatible with `@forgesworn/fold-kit`
 * 0.5.0's `src/member-epoch.ts` (see its `docs/member-epoch-catch-up.md`).
 *
 * Any current member can bring an admitted, non-removed device up to date,
 * and the device trusts that member not at all: the grant carries the
 * authority-signed rekeys from the device's epoch to the current one, each of
 * which decrypts only under the key of the epoch before it (NIP-44's MAC
 * verifies under no other key), and the last is checked against the
 * [epochCommitment] the authority wrote into its body. A last rekey with no
 * commitment is not accepted from a member: that epoch stays the authority's.
 */

/** A device behind the room asking any current member for the epochs it missed. Ephemeral. */
const val KIND_MEMBER_EPOCH_REQUEST = 20_471

/** A member's answer: signed by a one-time key, sealed to the asking device. Ephemeral. */
const val KIND_MEMBER_EPOCH_GRANT = 20_472

/** HKDF info for the key a member epoch request's body is sealed under. */
const val MEMBER_EPOCH_REQUEST_KEY_INFO = "kithmoot/v1/member-epoch-request-key"

/** The most epochs one member grant carries. A device further behind asks the authority. */
const val MAX_MEMBER_EPOCH_CHAIN = 32

/** The largest serialised grant a desk publishes: under the 64 KiB many relays enforce. */
const val MAX_MEMBER_GRANT_BYTES = 60_000

private val HEX64 = Regex("[0-9a-fA-F]{64}")

/** secp256k1's group order, for turning a 48-byte draw into a key the way `@noble/curves` does. */
private val CURVE_ORDER = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)

/**
 * The key a 48-byte draw makes, exactly as `schnorr.utils.randomSecretKey(seed)` (and so
 * nostr-tools' `generateSecretKey`) makes it: `(seed mod (n - 1)) + 1`, big-endian, 32 bytes.
 */
fun secretKeyFromSeed(seed: ByteArray): ByteArray {
    require(seed.size == 48) { "a key seed is 48 bytes" }
    val scalar = BigInteger(1, seed).mod(CURVE_ORDER.subtract(BigInteger.ONE)).add(BigInteger.ONE)
    val raw = scalar.toByteArray()
    val out = ByteArray(32)
    val start = maxOf(0, raw.size - 32)
    System.arraycopy(raw, start, out, 32 - (raw.size - start), raw.size - start)
    return out
}

/** The key a member epoch request is sealed under: the epoch-0 room key, expanded under its own info. */
fun deriveMemberEpochRequestKey(roomKey: ByteArray): ByteArray {
    require(roomKey.size == 32) { "room key must be 32 bytes" }
    return Digests.hkdfSha256(roomKey, null, MEMBER_EPOCH_REQUEST_KEY_INFO.toByteArray(Charsets.UTF_8), 32)
}

data class MemberEpochRequest(val device: String, val participant: String, val request: String, val have: Int)

/** A member grant, verified. */
class MemberEpochGrant(
    /** The epoch handed over and its secret. */
    val epoch: RoomEpoch,
    /** Cumulative: the removed set passed in plus every removal in the chain. */
    removed: List<String>,
    /** Every epoch the grant carried, in order, ending with [epoch]. */
    chain: List<RoomEpoch>,
    /** The authority's rekey into each of [chain], in the same order. */
    rekeys: List<NostrEvent>,
    /** The newest member list (#207) any rekey in the chain carried. */
    members: List<String>? = null,
) {
    val members = members?.toList()
    val removed = removed.toList()
    val chain = chain.toList()
    val rekeys = rekeys.toList()
}

/** Ask any current member for the epochs after [have]. */
fun encodeMemberEpochRequest(
    roomId: String,
    /** The room's authority: bound into the admission proof. */
    authority: String,
    /** The epoch-0 room key: seals the body and makes the admission proof. */
    roomKey: ByteArray,
    deviceSecretKey: ByteArray,
    credential: NostrEvent,
    have: Int,
    now: Long,
    proof: KindredProof? = null,
    nonce: ByteArray = Entropy.bytes(32),
    auxRand: ByteArray = Entropy.bytes(32),
    /** A conference room's end; see [withRoomExpiration]. */
    roomEnds: Long? = null,
): NostrEvent {
    require(deviceSecretKey.size == 32) { "device secret key must be 32 bytes" }
    val room = requireEpochHex(roomId, "room id")
    val peer = requireEpochHex(authority, "authority pubkey")
    require(have in 0..MAX_EPOCH) { "have must be a small non-negative integer" }
    val admission = epochRequestAdmission(roomKey, room, peer, Schnorr.publicKeyHex(deviceSecretKey), now)
    val body = buildJsonObject {
        put("v", 1)
        put("credential", credential.toJson())
        if (proof != null) put("proof", proof.toJson())
        put("admission", admission)
        put("have", have)
    }
    val key = deriveMemberEpochRequestKey(roomKey)
    return try {
        Events.sign(
            deviceSecretKey, KIND_MEMBER_EPOCH_REQUEST, now, withRoomExpiration(listOf(listOf("d", room)), roomEnds),
            Nip44.encrypt(body.toString(), key, nonce), auxRand,
        )
    } finally { key.fill(0) }
}

/**
 * Null for anything malformed, stale, from a device that cannot prove which participant it
 * speaks for, or that cannot prove admission. The checks run in the TypeScript order.
 */
fun decodeMemberEpochRequest(
    event: NostrEvent,
    roomId: String,
    authority: String,
    roomKey: ByteArray,
    now: Long,
    policy: RoomPolicy? = null,
    maxAgeSeconds: Long = EPOCH_MAX_AGE_SECONDS,
): MemberEpochRequest? = runCatching {
    if (event.kind != KIND_MEMBER_EPOCH_REQUEST || !Events.verify(event) || !epochFresh(event.createdAt, now, maxAgeSeconds)) return null
    val room = requireEpochHex(roomId, "room id")
    if (event.tagValue("d")?.hexEquals(room) != true) return null
    val key = deriveMemberEpochRequestKey(roomKey)
    val body = try { Json.parseToJsonElement(Nip44.decrypt(event.content, key)).jsonObject } finally { key.fill(0) }
    if (body["v"].exactInt() != 1) return null
    val credentialJson = body["credential"] as? JsonObject ?: return null
    val have = body["have"].exactInt()?.takeIf { it in 0..MAX_EPOCH } ?: return null
    val credential = NostrEvent.fromJson(credentialJson)
    val verdict = verifyDeviceCredential(credential, room, now) as? CredentialCheck.Valid ?: return null
    if (!verdict.device.hexEquals(event.pubkey)) return null
    val presented = (body["admission"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    val expected = epochRequestAdmission(roomKey, room, requireEpochHex(authority, "authority pubkey"), verdict.device, event.createdAt)
    if (!MessageDigest.isEqual(presented.normaliseHex().toByteArray(Charsets.US_ASCII), expected.toByteArray(Charsets.US_ASCII))) return null
    if (policy != null) {
        val proof = (body["proof"] as? JsonObject)?.let(KindredProof::fromJson)
        if (!evaluateAccess(policy, verdict.participant, proof, now, room).admitted) return null
    }
    MemberEpochRequest(verdict.device, verdict.participant, event.id, have)
}.getOrNull()

/** An event in nostr-tools' field order (`id, pubkey, created_at, kind, tags, content, sig`), as a grant inlines it. */
private fun plainEvent(event: NostrEvent): JsonObject = buildJsonObject {
    put("id", event.id)
    put("pubkey", event.pubkey)
    put("created_at", event.createdAt)
    put("kind", event.kind)
    put("tags", tagsToJson(event.tags))
    put("content", event.content)
    put("sig", event.sig)
}

/**
 * Answer one member epoch request with the epochs it missed.
 *
 * Signed, and sealed to the asking device, by a key made for this grant alone from
 * [signerSeed] and then dropped, never by the answering member's device key: nothing about a
 * grant is trusted on its signer, so a device key would add only a public line from that device
 * to the room id in the `d` tag. A desk tells its own grants apart by event id.
 */
fun encodeMemberEpochGrant(
    roomId: String,
    /** The asking device. */
    device: String,
    request: String,
    /** Epochs have+1 .. current, in order. */
    epochs: List<RoomEpoch>,
    /** The authority's rekey event for each of [epochs], in the same order. */
    rekeys: List<NostrEvent>,
    now: Long,
    signerSeed: ByteArray = Entropy.bytes(48),
    nonce: ByteArray = Entropy.bytes(32),
    auxRand: ByteArray = Entropy.bytes(32),
    /** A conference room's end; see [withRoomExpiration]. */
    roomEnds: Long? = null,
): NostrEvent {
    val room = requireEpochHex(roomId, "room id")
    val recipient = requireEpochHex(device, "device pubkey")
    val requestId = requireEpochHex(request, "request id")
    require(epochs.size in 1..MAX_MEMBER_EPOCH_CHAIN) { "a member grant carries 1 to 32 epochs" }
    require(rekeys.size == epochs.size) { "one rekey per epoch" }
    epochs.forEachIndexed { i, e ->
        if (i > 0) require(e.epoch == epochs[i - 1].epoch + 1) { "epochs must be consecutive" }
        require(e.epoch >= 1) { "a member grant never carries epoch 0" }
    }
    val signer = secretKeyFromSeed(signerSeed)
    val body = buildJsonObject {
        put("v", 1)
        put("request", requestId)
        put("epoch", epochs.last().epoch)
        put("secrets", buildJsonArray { epochs.forEach { add(JsonPrimitive(base64UrlEncode(it.secret))) } })
        put("rekeys", JsonArray(rekeys.map(::plainEvent)))
    }
    val key = Nip44.conversationKey(signer, recipient.hexToBytes())
    return try {
        Events.sign(
            signer, KIND_MEMBER_EPOCH_GRANT, now,
            withRoomExpiration(listOf(listOf("d", room), listOf("p", recipient)), roomEnds),
            Nip44.encrypt(body.toString(), key, nonce), auxRand,
        )
    } finally {
        key.fill(0)
        signer.fill(0)
    }
}

/**
 * Verify a member's answer to one of this device's member requests. Null for anything that
 * does not check out; see fold-kit's `docs/member-epoch-catch-up.md` "Verification rules":
 *
 * 1. kind 20472, a valid signature, fresh, `d` this room, `p` this device, opens with this
 *    device's key, and `request` one of [requests];
 * 2. as many secrets as rekeys, 1 to 32 of them, and `epoch` = current + that many;
 * 3. no lower than [expected], the newest epoch an authority-signed rekey has been seen for;
 * 4. each rekey authority-signed for this room and its epoch, opening under the key before it,
 *    neither closing the room nor removing [participant], and the last carrying a commitment
 *    that matches its secret.
 */
fun decodeMemberEpochGrant(
    event: NostrEvent,
    roomId: String,
    authority: String,
    /** The asking device's key. */
    deviceSecretKey: ByteArray,
    /** The ids of this device's own outstanding member requests. */
    requests: Collection<String>,
    /** Where this device is: the chain starts from this epoch's key. */
    currentEpoch: Int,
    currentKey: ByteArray,
    /** The participant this device speaks for: a chain that removes it is refused. */
    participant: String,
    now: Long,
    /** The cumulative removed set this device already knows. */
    removed: Collection<String> = emptyList(),
    /** The highest epoch this device has seen an authority-signed rekey for. */
    expected: Int? = null,
    maxAgeSeconds: Long = EPOCH_MAX_AGE_SECONDS,
): MemberEpochGrant? = runCatching {
    require(deviceSecretKey.size == 32)
    if (event.kind != KIND_MEMBER_EPOCH_GRANT || !Events.verify(event) || !epochFresh(event.createdAt, now, maxAgeSeconds)) return null
    val room = requireEpochHex(roomId, "room id")
    if (event.tagValue("d")?.hexEquals(room) != true) return null
    val device = Schnorr.publicKeyHex(deviceSecretKey)
    if (event.tagValue("p")?.hexEquals(device) != true) return null
    val key = Nip44.conversationKey(deviceSecretKey, event.pubkey.hexToBytes())
    val body = try { Json.parseToJsonElement(Nip44.decrypt(event.content, key)).jsonObject } finally { key.fill(0) }
    if (body["v"].exactInt() != 1) return null
    val request = (body["request"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(HEX64::matches) ?: return null
    if (requests.none { it.hexEquals(request) }) return null
    val secrets = body["secrets"] as? JsonArray ?: return null
    val rekeys = body["rekeys"] as? JsonArray ?: return null
    val length = secrets.size
    if (length < 1 || length > MAX_MEMBER_EPOCH_CHAIN || rekeys.size != length) return null
    val top = currentEpoch + length
    if (body["epoch"].exactInt() != top || top > MAX_EPOCH) return null
    if (expected != null && top < expected) return null
    val self = participant.normaliseHex()
    val cumulative = removed.map(String::normaliseHex).toMutableSet()
    var previousEpoch = currentEpoch
    var previousKey = currentKey
    val chain = mutableListOf<RoomEpoch>()
    val events = mutableListOf<NostrEvent>()
    var members: List<String>? = null
    for (i in 0 until length) {
        val rekey = NostrEvent.fromJson(rekeys[i].jsonObject)
        val evidence = readRekeyEvidence(rekey, room, authority, previousEpoch, previousKey) ?: return null
        if (evidence.closed) return null
        if (self in evidence.removed) return null
        cumulative += evidence.removed
        if (evidence.members != null) members = evidence.members
        val raw = (secrets[i] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if ('=' in raw) return null
        val secret = base64UrlDecode(raw)
        if (secret.size != 32) return null
        // Every secret but the last is proven by the next rekey opening under it, on the next
        // turn of this loop. The last has no next rekey: only the commitment can vouch for it.
        if (i == length - 1) {
            val commit = evidence.commit ?: return null
            val wanted = epochCommitment(room, evidence.epoch, secret)
            if (!MessageDigest.isEqual(commit.toByteArray(Charsets.US_ASCII), wanted.toByteArray(Charsets.US_ASCII))) return null
        }
        val next = RoomEpoch(evidence.epoch, secret)
        chain += next
        events += rekey
        val keys = deriveEpoch(next)
        previousEpoch = keys.epoch
        previousKey = keys.key
        secret.fill(0)
    }
    MemberEpochGrant(chain.last(), cumulative.sorted(), chain, events, members)
}.getOrNull()
