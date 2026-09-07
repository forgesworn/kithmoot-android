package dev.forgesworn.kithmoot.storage

import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.protocol.deriveRoom
import dev.forgesworn.kithmoot.protocol.createRoomInvitation
import dev.forgesworn.kithmoot.protocol.encodeInvitationUrl
import dev.forgesworn.kithmoot.session.PrimaryIdentity

/** A saved room from before v3. Recovery must work even with its relay offline. */
internal fun seedLegacyRoom(app: KithMootApplication, name: String) {
    val secret = Entropy.bytes(32)
    val now = System.currentTimeMillis() / 1000
    val relays = listOf("ws://10.0.2.2:59999")
    val identity = PrimaryIdentity.create(deriveRoom(secret).roomId, now + 86400, now)
    val host = createRoomInvitation(false)
    val url = encodeInvitationUrl("https://kithmoot.forgesworn.dev/j/", host.invitation, relays)
    app.savedRooms.save(SavedRoom.create(secret, identity, url, relays, name, now, host, host.invitation.canonicalInviter))
}
