package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.NostrEvent
import kotlinx.coroutines.runBlocking

/** Enrolment for tests that hold the key here, where the round trip is nothing. */
fun PrimaryIdentity.enrolNow(devicePubkey: String, roomId: String, expiresAt: Long, createdAt: Long): NostrEvent =
    runBlocking { enrol(devicePubkey, roomId, expiresAt, createdAt) }
