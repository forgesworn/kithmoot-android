package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.decodeRosterEvent
import dev.forgesworn.kithmoot.protocol.deriveRoom
import dev.forgesworn.kithmoot.protocol.encodeRosterEvent
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A second device works from a credential and its own key, and never holds the
 * participant master key.
 *
 * That is the property that makes putting your phone in a room a reasonable
 * thing to do. A phone can be lost; what a lost phone can do is bounded by the
 * one room its credential names and the expiry it was given, and never includes
 * losing the identity itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SecondaryDeviceTest {

    @Test
    fun `a secondary identity has nowhere to put a participant key`() {
        val room = Fixtures.room()
        val owner = Fixtures.primary(room, 1, 2)
        val phone = Fixtures.secondary(room, owner, 30)

        // Structural, not behavioural: there is no field on the type that could
        // hold it, so no future change can quietly start storing one without
        // this failing.
        val instanceFields = SecondaryIdentity::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()
        assertEquals(setOf("deviceSecretKey", "credential", "participant", "devicePubkey"), instanceFields)
        assertTrue(phone.participant == owner.participant)
        assertTrue(!phone.deviceSecretKey.contentEquals(owner.deviceSecretKey))
    }

    @Test
    fun `a secondary device publishes a roster entry others accept`() {
        val room = Fixtures.room()
        val owner = Fixtures.primary(room, 1, 2)
        val phone = Fixtures.secondary(room, owner, 30)

        val entry = dev.forgesworn.kithmoot.protocol.RosterEntry(
            participant = phone.participant,
            device = phone.devicePubkey,
            credential = phone.credential,
            updatedAt = 10,
        )
        val event = encodeRosterEvent(entry, room.roomId, room.roomKey, phone.deviceSecretKey)

        // A third party, holding only the room key, accepts it - so the phone is
        // a full member of the room on the strength of the credential alone.
        val decoded = decodeRosterEvent(event, room.roomId, room.roomKey, now = 20)
        assertEquals(owner.participant, decoded?.participant)
        assertEquals(phone.devicePubkey, decoded?.device)
    }

    @Test
    fun `a credential for another device is refused`() {
        val room = Fixtures.room()
        val owner = Fixtures.primary(room, 1, 2)
        val credentialForSomeoneElse = owner.enrol(
            devicePubkey = Schnorr.publicKeyHex(Fixtures.key(50)),
            roomId = room.roomId,
            expiresAt = Fixtures.CREDENTIAL_EXPIRY,
            createdAt = 0,
        )

        assertNull(
            SecondaryIdentity.adopt(credentialForSomeoneElse, Fixtures.key(60), room.roomId, now = 0),
            "a device must not take up a credential that names a different device",
        )
    }

    @Test
    fun `a credential for another room is refused`() {
        val room = Fixtures.room()
        val elsewhere = deriveRoom(ByteArray(32) { 99 })
        val owner = Fixtures.primary(room, 1, 2)
        val credential = owner.enrol(
            devicePubkey = Schnorr.publicKeyHex(Fixtures.key(30)),
            roomId = room.roomId,
            expiresAt = Fixtures.CREDENTIAL_EXPIRY,
            createdAt = 0,
        )

        assertNull(SecondaryIdentity.adopt(credential, Fixtures.key(30), elsewhere.roomId, now = 0))
    }

    @Test
    fun `an expired credential is refused`() {
        val room = Fixtures.room()
        val owner = Fixtures.primary(room, 1, 2)
        val credential = owner.enrol(
            devicePubkey = Schnorr.publicKeyHex(Fixtures.key(30)),
            roomId = room.roomId,
            expiresAt = 100,
            createdAt = 0,
        )

        assertNull(SecondaryIdentity.adopt(credential, Fixtures.key(30), room.roomId, now = 500))
    }

    @Test
    fun `both of one person's devices join as one participant`() = runTest {
        val room = Fixtures.room()
        val relay = FakeRelay()
        val owner = Fixtures.primary(room, 1, 2)
        val phone = Fixtures.secondary(room, owner, 30)
        val stranger = Fixtures.primary(room, 70, 71)

        val laptopSession = session(room, owner, relay)
        val phoneSession = session(room, phone, relay, seed = 11)
        val strangerSession = session(room, stranger, relay, seed = 13)

        laptopSession.join()
        advanceTimeBy(2_000)
        runCurrent()
        phoneSession.join()
        advanceTimeBy(2_000)
        runCurrent()
        strangerSession.join()
        advanceTimeBy(5_000)
        runCurrent()

        // Two people, three devices. The stranger sees two people.
        assertEquals(2, strangerSession.participants.value.size)
        val owners = strangerSession.participants.value.single { it.participant == owner.participant }
        assertEquals(2, owners.deviceCount)

        // And connects to all three remote devices, because media is per device
        // even though presence is per person.
        assertEquals(
            setOf(owner.devicePubkey, phone.devicePubkey),
            strangerSession.remoteDevices.value,
        )
    }

    @Test
    fun `a device never opens a connection to its owner's other devices`() = runTest {
        val room = Fixtures.room()
        val relay = FakeRelay()
        val owner = Fixtures.primary(room, 1, 2)
        val phone = Fixtures.secondary(room, owner, 30)
        val stranger = Fixtures.primary(room, 70, 71)

        val laptopSession = session(room, owner, relay)
        val phoneSession = session(room, phone, relay, seed = 11)
        val strangerSession = session(room, stranger, relay, seed = 13)
        laptopSession.join()
        advanceTimeBy(2_000)
        runCurrent()
        phoneSession.join()
        advanceTimeBy(2_000)
        runCurrent()
        strangerSession.join()
        advanceTimeBy(5_000)
        runCurrent()

        // Sending a person their own face across the room and back is bandwidth
        // spent on nothing.
        assertEquals(setOf(stranger.devicePubkey), laptopSession.remoteDevices.value)
        assertEquals(setOf(stranger.devicePubkey), phoneSession.remoteDevices.value)
    }
}
