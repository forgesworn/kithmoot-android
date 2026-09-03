package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.hexEquals
import dev.forgesworn.kithmoot.crypto.normaliseHex

/**
 * A room epoch, seen from a client that cannot follow one.
 *
 * Rotation retires a link and removes nobody: everybody admitted holds the
 * room key, so the only way to remove a member is to move the room to a key
 * they are not given. The authority - the root inviter, the one key a member
 * believes for this - publishes a rekey naming the next epoch, with the
 * successor secret sealed once per device that stays.
 *
 * **This client cannot follow one.** Nothing here opens the sealed copy or
 * moves the session to the new key; that is the reference implementation's
 * `src/epoch.ts` and a job still to do here. What this file exists for is the
 * failure that would otherwise happen instead: a room that has moved on
 * simply goes quiet, because the roster and the chat are now published under
 * an id this client is not subscribed to, and a room that goes quiet reads as
 * an application that is broken rather than as a room that has moved.
 *
 * So the one thing this does is notice, and it needs no key to do it. The
 * rekey event is tagged with the ROOM id rather than the epoch id - the room
 * id never moves, because a device credential binds to it - so a client that
 * has fallen several epochs behind still sees every later rekey and can still
 * say so.
 */
const val KIND_ROOM_REKEY: Int = 1462

/** The highest epoch the wire format allows, matching `MAX_EPOCH`. */
const val MAX_EPOCH: Int = 1_000_000

/** An epoch tag: at least 1, no leading zero, no more than seven digits. */
private val EPOCH_TAG = Regex("^[1-9][0-9]{0,6}$")

/**
 * Which epoch this rekey moves the room to, or null if it is not one.
 *
 * Key-free by design: a client that has fallen behind no longer holds the key
 * the rekey is encrypted to, and it is exactly that client which most needs to
 * know. Everything checkable without a key is checked - the kind, the signing
 * key, the room, the shape of the epoch, and the signature - and nothing else
 * is claimed.
 *
 * The authority is required rather than optional. Every member of a room holds
 * the room key and could otherwise publish something that made every Android
 * client in it announce that the room had moved.
 *
 * Never throws: this runs inside a subscription callback.
 */
fun peekRekeyEpoch(event: NostrEvent, roomId: String, authority: String): Int? = runCatching {
    if (event.kind != KIND_ROOM_REKEY) return null
    if (!event.pubkey.hexEquals(authority)) return null
    val room = event.tagValue("d")?.normaliseHex() ?: return null
    if (!room.hexEquals(roomId)) return null
    val tag = event.tagValue("epoch") ?: return null
    if (!EPOCH_TAG.matches(tag)) return null
    val epoch = tag.toIntOrNull() ?: return null
    if (epoch > MAX_EPOCH) return null
    if (!Events.verify(event)) return null
    epoch
}.getOrNull()
