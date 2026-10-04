package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * A moderated meeting, and a recording everybody knows about. Mirrors
 * `meeting.ts` and the `meeting`, `recording` and `hand` cases in
 * `control.ts` in the TypeScript reference implementation.
 *
 * A room has no operator, so nothing in the middle can mute anybody. What it
 * does have is an authority: the key pinned in its link. That key signs two
 * records, carried on the `control` channel exactly as the room's relay list
 * is - versioned, newest wins, and any member may repost one, because the
 * signature is what counts and not whoever sent it.
 *
 * **The meeting policy** says whether the room is in meeting mode and who the
 * speakers are. In meeting mode a person not on the list has their
 * microphone, camera and screen share locked by their own app and - the half
 * that does not depend on their app being honest - every other app in the
 * room refuses to play or show what they send anyway.
 *
 * **The recording notice** says a recording is running. Every app shows it
 * for as long as it stands, and asks before joining a call it covers.
 */

/** More speakers than this is not a meeting with a stage, it is a call. */
const val MAX_MEETING_SPEAKERS: Int = 64

/** How often a running recording's notice is posted again. */
const val RECORDING_REPOST_SECONDS: Long = 5L * 60

/** How often the authority posts a meeting policy that is on again, so a
 *  member who arrives hours in still reads it from the control log. */
const val MEETING_REPOST_SECONDS: Long = 30L * 60

/** A notice not reposted for this long is shown as unconfirmed rather than
 *  taken down: the honest failure for a notice about recording is to keep
 *  showing it. */
const val RECORDING_STALE_SECONDS: Long = 3 * RECORDING_REPOST_SECONDS

/** A notice not reposted for this long is no longer shown. */
const val RECORDING_FORGET_SECONDS: Long = 12L * 60 * 60

/** A raised hand older than this has been forgotten by whoever raised it. */
const val HAND_TTL_SECONDS: Long = 15L * 60

/** JavaScript's `Number.MAX_SAFE_INTEGER`: the web client refuses a version above it. */
private const val MAX_SAFE_VERSION: Long = 9_007_199_254_740_991L
private val HEX64 = Regex("[0-9a-f]{64}")
private val RECORDING_ID = Regex("[0-9a-f]{32}")
private val SIG = Regex("[0-9a-f]{128}", RegexOption.IGNORE_CASE)

/** Whether the room is in meeting mode, and who may talk and show video. */
data class MeetingPolicy(val on: Boolean, val speakers: List<String>, val version: Long)

/** A verified policy and the signature it carried, so it can be reposted. */
data class SignedMeetingPolicy(val policy: MeetingPolicy, val sig: String)

/** Whether recording `id` (16 random bytes, lower-case hex) is running. */
data class RecordingNotice(val on: Boolean, val id: String, val version: Long)

data class SignedRecordingNotice(val notice: RecordingNotice, val sig: String)

/** Canonical speaker list: lower-case, deduplicated, sorted. Throws on
 *  anything that is not a participant pubkey, or on more than the cap. */
fun canonicalSpeakers(speakers: List<String>): List<String> {
    val out = speakers.map { it.lowercase() }.toSet().sorted()
    require(out.all { HEX64.matches(it) }) { "a speaker is a 64-character hex pubkey" }
    require(out.size <= MAX_MEETING_SPEAKERS) { "a meeting can have at most $MAX_MEETING_SPEAKERS speakers" }
    return out
}

private fun requireRoomId(roomId: String): String =
    roomId.also { require(it.matches(Regex("[0-9a-f]{64}", RegexOption.IGNORE_CASE))) { "room id must be 64 hex characters" } }.lowercase()

private fun requireVersion(version: Long): Long =
    version.also { require(it in 0..MAX_SAFE_VERSION) { "version must be a non-negative integer" } }

private fun speakersJson(speakers: List<String>): String = speakers.joinToString(",", "[", "]") { "\"$it\"" }

/** `sha256("kithmoot/v1/meeting:<roomId>:<version>:<1 or 0>:<JSON array of the speakers>")`. */
private fun meetingMessage(roomId: String, policy: MeetingPolicy): ByteArray =
    Digests.sha256("kithmoot/v1/meeting:${requireRoomId(roomId)}:${requireVersion(policy.version)}:${if (policy.on) 1 else 0}:${speakersJson(policy.speakers)}".toByteArray(Charsets.UTF_8))

/** `sha256("kithmoot/v1/recording:<roomId>:<version>:<id>:<1 or 0>")`. */
private fun recordingMessage(roomId: String, notice: RecordingNotice): ByteArray {
    require(RECORDING_ID.matches(notice.id)) { "a recording id is 32 lower-case hex characters" }
    return Digests.sha256("kithmoot/v1/recording:${requireRoomId(roomId)}:${requireVersion(notice.version)}:${notice.id}:${if (notice.on) 1 else 0}".toByteArray(Charsets.UTF_8))
}

private fun verifyDigest(message: () -> ByteArray, sig: String, authority: String): Boolean = runCatching {
    val signature = sig.hexToBytes()
    if (signature.size != 64 || !HEX64.matches(authority.lowercase())) return false
    Schnorr.verify(signature, message(), authority.lowercase().hexToBytes())
}.getOrDefault(false)

fun signMeetingPolicy(roomId: String, policy: MeetingPolicy, authoritySecretKey: ByteArray, auxRand: ByteArray = Entropy.bytes(32)): String {
    require(authoritySecretKey.size == 32) { "authority secret key must be 32 bytes" }
    val canonical = policy.copy(speakers = canonicalSpeakers(policy.speakers))
    return Schnorr.sign(meetingMessage(roomId, canonical), authoritySecretKey, auxRand).toHex()
}

/** Never throws: this runs on anything a relay hands over. A list that is
 *  not already canonical is refused rather than mended, so the list a device
 *  verifies is the list it enforces. */
fun verifyMeetingPolicy(roomId: String, policy: MeetingPolicy, sig: String, authority: String): Boolean {
    val canonical = runCatching { canonicalSpeakers(policy.speakers) }.getOrNull() ?: return false
    if (canonical != policy.speakers) return false
    return verifyDigest({ meetingMessage(roomId, policy) }, sig, authority)
}

fun signRecordingNotice(roomId: String, notice: RecordingNotice, authoritySecretKey: ByteArray, auxRand: ByteArray = Entropy.bytes(32)): String {
    require(authoritySecretKey.size == 32) { "authority secret key must be 32 bytes" }
    return Schnorr.sign(recordingMessage(roomId, notice), authoritySecretKey, auxRand).toHex()
}

/** Never throws. */
fun verifyRecordingNotice(roomId: String, notice: RecordingNotice, sig: String, authority: String): Boolean =
    verifyDigest({ recordingMessage(roomId, notice) }, sig, authority)

/**
 * Whether [participant] may be heard and seen under [policy]: the one rule
 * both ends apply. A sender's app locks what this refuses, and a receiver's
 * app will not play it. No policy, or a policy that is off, refuses nothing.
 */
fun meetingAllows(policy: MeetingPolicy?, participant: String): Boolean {
    if (policy == null || !policy.on) return true
    return participant.lowercase() in policy.speakers
}

/**
 * Whether this device keeps a remote device's sound and pictures off, given
 * the participant that owns it ([owner], null when no roster entry places
 * it). A device nobody owns is kept off too while the policy is on: a stage
 * that lets in whoever it cannot place is no stage.
 */
fun meetingGated(policy: MeetingPolicy?, owner: String?): Boolean {
    if (policy == null || !policy.on) return false
    return owner == null || !meetingAllows(policy, owner)
}

/**
 * The policy with [participant] put on or taken off the stage, and a new
 * version: [nowMs], or one past the old version if that is later, so a
 * change always outranks what it changes. Mirrors `withSpeaker` in the web
 * client's `src/meeting.ts`.
 */
fun withSpeaker(policy: MeetingPolicy, participant: String, speaking: Boolean, nowMs: Long): MeetingPolicy {
    val others = policy.speakers.filter { it != participant.lowercase() }
    val speakers = canonicalSpeakers(if (speaking) others + participant else others)
    return MeetingPolicy(policy.on, speakers, maxOf(nowMs, policy.version + 1))
}

/** The policy switched on or off, and a new version. The speakers stay. */
fun withMeetingMode(policy: MeetingPolicy, on: Boolean, nowMs: Long): MeetingPolicy =
    MeetingPolicy(on, policy.speakers, maxOf(nowMs, policy.version + 1))

/** What a recording notice tells the person looking at the room. */
sealed interface RecordingView {
    data object Off : RecordingView
    /** Running, and reposted recently. */
    data class On(val id: String, val since: Long) : RecordingView
    /** Said to be running, but not reposted for a while. Still shown. */
    data class Unconfirmed(val id: String, val since: Long, val lastHeard: Long) : RecordingView
}

/** What to show, given the newest verified notice, when this device first
 *  saw it running ([since]), when it last saw it posted ([lastHeard]), and
 *  [now] - all unix seconds. */
fun recordingView(notice: RecordingNotice?, since: Long, lastHeard: Long, now: Long): RecordingView {
    if (notice == null || !notice.on || now - lastHeard > RECORDING_FORGET_SECONDS) return RecordingView.Off
    if (now - lastHeard > RECORDING_STALE_SECONDS) return RecordingView.Unconfirmed(notice.id, since, lastHeard)
    return RecordingView.On(notice.id, since)
}

/** The chat body carrying a `meeting` control op. */
fun encodeMeetingOp(signed: SignedMeetingPolicy): String = signed.policy.let {
    "{\"op\":\"meeting\",\"on\":${it.on},\"speakers\":${speakersJson(it.speakers)},\"version\":${it.version},\"sig\":\"${signed.sig}\"}"
}

/** The chat body carrying a `recording` control op. */
fun encodeRecordingOp(signed: SignedRecordingNotice): String = signed.notice.let {
    "{\"op\":\"recording\",\"on\":${it.on},\"id\":\"${it.id}\",\"version\":${it.version},\"sig\":\"${signed.sig}\"}"
}

/** The chat body carrying a `hand` control op. It names nobody: the hand is
 *  the sender's. */
fun encodeHandOp(up: Boolean): String = "{\"op\":\"hand\",\"up\":$up}"

private fun controlObject(body: String, op: String): JsonObject? {
    val obj = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
    return obj.takeIf { (it["op"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content == op }
}

private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

private fun JsonObject.version(): Long? =
    (this["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it in 0..MAX_SAFE_VERSION }

private fun JsonObject.sig(): String? =
    (this["sig"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { SIG.matches(it) }?.lowercase()

/**
 * Decodes a `meeting` control op, or null when the text is not one, or is
 * one that could never be valid. The speakers are kept as sent: a list that
 * is not canonical is refused by [verifyMeetingPolicy], not mended here.
 */
fun decodeMeetingOp(body: String): SignedMeetingPolicy? = runCatching {
    val obj = controlObject(body, "meeting") ?: return null
    val on = obj.bool("on") ?: return null
    val array = obj["speakers"] as? JsonArray ?: return null
    val speakers = array.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }
    canonicalSpeakers(speakers)
    val version = obj.version() ?: return null
    val sig = obj.sig() ?: return null
    SignedMeetingPolicy(MeetingPolicy(on, speakers, version), sig)
}.getOrNull()

/** Decodes a `recording` control op, or null. The id must already be lower case. */
fun decodeRecordingOp(body: String): SignedRecordingNotice? {
    val obj = controlObject(body, "recording") ?: return null
    val on = obj.bool("on") ?: return null
    val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { RECORDING_ID.matches(it) } ?: return null
    val version = obj.version() ?: return null
    val sig = obj.sig() ?: return null
    return SignedRecordingNotice(RecordingNotice(on, id, version), sig)
}

/** Decodes a `hand` control op to whether the hand is up, or null. */
fun decodeHandOp(body: String): Boolean? = controlObject(body, "hand")?.bool("up")
