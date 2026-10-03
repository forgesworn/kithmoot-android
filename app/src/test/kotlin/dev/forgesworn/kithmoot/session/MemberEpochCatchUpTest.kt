package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.epoch.EpochPhase
import dev.forgesworn.kithmoot.epoch.EpochVault
import dev.forgesworn.kithmoot.epoch.MemberEpochResponder
import dev.forgesworn.kithmoot.epoch.activeEpochFor
import dev.forgesworn.kithmoot.epoch.asDesk
import dev.forgesworn.kithmoot.protocol.KIND_EPOCH_REQUEST
import dev.forgesworn.kithmoot.protocol.KIND_MEMBER_EPOCH_GRANT
import dev.forgesworn.kithmoot.protocol.KIND_MEMBER_EPOCH_REQUEST
import dev.forgesworn.kithmoot.protocol.KIND_ROSTER
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RekeyNotice
import dev.forgesworn.kithmoot.protocol.Room
import dev.forgesworn.kithmoot.protocol.RoomEpoch
import dev.forgesworn.kithmoot.protocol.createRoomInvitation
import dev.forgesworn.kithmoot.protocol.decodeMemberEpochRequest
import dev.forgesworn.kithmoot.protocol.deriveEpoch
import dev.forgesworn.kithmoot.protocol.encodeInvitationUrl
import dev.forgesworn.kithmoot.protocol.encodeMemberEpochGrant
import dev.forgesworn.kithmoot.protocol.encodeRekeyEvent
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.storage.MemoryStorage
import dev.forgesworn.kithmoot.storage.SavedRoom
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Member-to-member epoch catch-up at the session level: with the authority's device away, a
 * member already at epoch 1 brings a device that missed the rekey up to date, and the device's
 * vault then holds the epoch the background call listener rings under. A removed requester is
 * given nothing, and a grant short of the newest rekey on the relays is not believed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemberEpochCatchUpTest {
    private val authoritySecret = Fixtures.key(41)
    private val authority = Schnorr.publicKeyHex(authoritySecret)
    private val roomSecret = ByteArray(32) { 7 }
    private val stable = Fixtures.room()
    private val epoch1 = RoomEpoch(1, ByteArray(32) { 71 })

    private fun vault(): EpochVault = EpochVault(MemoryStorage(), MemoryStorage()).also {
        it.initialise(stable.roomId, authority, roomSecret, 0)
    }

    /** What `RoomViewModel.commitRoomEpoch` does with a gate call, minus cadence. */
    private fun commit(vault: EpochVault, event: NostrEvent, notice: RekeyNotice, committed: MutableList<RekeyNotice>): EpochGateResult {
        committed += notice
        val durable = requireNotNull(vault.get(stable.roomId))
        if (notice.closed || notice.secret == null) {
            vault.terminal(stable.roomId, durable.currentEpoch, notice, event.id, 0)
            return EpochGateResult.COMMITTED
        }
        if (notice.catchUp) vault.beginCatchUp(stable.roomId, durable.currentEpoch, notice, event.id, null, 0)
        else vault.beginTransition(stable.roomId, durable.currentEpoch, notice, event.id, null, 0)
        vault.activate(stable.roomId, notice.epoch, 0)
        return EpochGateResult.COMMITTED
    }

    /** A member device of another participant, following the room with a real vault and its desk. */
    private fun TestScope.member(relay: FakeRelay, vault: EpochVault, identity: PrimaryIdentity = Fixtures.primary(stable, 5, 6)): RoomSession {
        val committed = mutableListOf<RekeyNotice>()
        val desk = MemberEpochResponder(
            stable.roomId, authority, identity.deviceSecretKey, stable.roomKey, null,
            current = { vault.get(stable.roomId)?.takeIf { it.phase == EpochPhase.ACTIVE && it.currentEpoch > 0 }?.let { RoomEpoch(it.currentEpoch, it.currentSecret) } },
            secretAt = { vault.secretAt(stable.roomId, it) },
            rekeyAt = { vault.rekeyAt(stable.roomId, it) },
            removed = { vault.get(stable.roomId)?.removed.orEmpty() },
            // Every asker here is a member the room knows; the gate itself (kithmoot#207) is
            // MemberEpochResponderTest's business.
            known = { true },
            closed = { vault.get(stable.roomId)?.phase == EpochPhase.CLOSED },
            now = { currentTime / 1000 },
            random = { 0.5 },
        ).asDesk()
        return session(
            stable, identity, relay, authority = authority,
            epochGate = { event, notice -> commit(vault, event, notice, committed) },
            memberEpochDesk = desk,
            onEpochHistory = { secrets, rekeys -> vault.remember(stable.roomId, secrets, rekeys) },
        )
    }

    /** The authority's rekey to epoch 1, sealed only to [sealedTo], committing to its secret. */
    private fun rekeyToOne(sealedTo: String, removed: List<String> = emptyList()) = encodeRekeyEvent(
        stable.roomId, authoritySecret, deriveEpoch(RoomEpoch(0, roomSecret)), epoch1, listOf(sealedTo), removed, 0, commit = true,
    )

    private fun saved(): SavedRoom {
        val who = PrimaryIdentity.create(stable.roomId, 100_000, 0)
        val host = createRoomInvitation()
        val relays = listOf("wss://relay.example")
        return SavedRoom.create(
            roomSecret, who, encodeInvitationUrl("https://kithmoot.example/j/", host.invitation, relays),
            relays, "Workshop", 0, host, host.invitation.canonicalInviter,
        )
    }

    @Test fun `with the authority away, a member at epoch 1 brings a behind device up and its vault rings there`() = runTest {
        val relay = FakeRelay()
        val memberVault = vault()
        val memberIdentity = Fixtures.primary(stable, 5, 6)
        val member = member(relay, memberVault, memberIdentity)
        member.join()
        runCurrent()
        val rekey = rekeyToOne(memberIdentity.devicePubkey)
        relay.publish(rekey)
        runCurrent()
        assertEquals(1, member.epochKeys().epoch)
        assertEquals(rekey.id, memberVault.rekeyAt(stable.roomId, 1)?.id)

        // A phone that rejoined with a new device key: no stored rekey seals to it.
        val behind = Fixtures.primary(stable, 1, 2)
        val behindVault = vault()
        val committed = mutableListOf<RekeyNotice>()
        val live = session(
            stable, behind, relay, authority = authority, expectedEpoch = 1,
            epochGate = { event, notice -> commit(behindVault, event, notice, committed) },
            onEpochHistory = { secrets, rekeys -> behindVault.remember(stable.roomId, secrets, rekeys) },
        )
        live.join()
        runCurrent()
        assertEquals(1, relay.countOfKind(KIND_EPOCH_REQUEST))
        // The first member request waits for the relays to replay the room's rekeys.
        assertEquals(0, relay.countOfKind(KIND_MEMBER_EPOCH_REQUEST))
        advanceTimeBy(1_501)
        runCurrent()
        assertEquals(1, relay.countOfKind(KIND_MEMBER_EPOCH_REQUEST))
        advanceTimeBy(1_500)
        runCurrent()

        assertEquals(1, relay.countOfKind(KIND_MEMBER_EPOCH_GRANT))
        val grant = relay.published.single { it.kind == KIND_MEMBER_EPOCH_GRANT }
        assertTrue(grant.pubkey != memberIdentity.devicePubkey, "a grant is signed by a one-time key")
        assertEquals(1, live.epochKeys().epoch)
        assertEquals(deriveEpoch(epoch1).id, live.epochKeys().id)
        assertIs<RoomEpochState.Active>(live.epochState.value)
        assertTrue(committed.single().catchUp)
        val durable = assertNotNull(behindVault.get(stable.roomId))
        assertEquals(1, durable.currentEpoch)
        // What `BackgroundCallListenerService.watchFor` reads, so ringing follows.
        assertEquals(deriveEpoch(epoch1).id, activeEpochFor(saved(), durable)?.id)
        // The proven chain is kept, so this device can hand it on in turn.
        assertEquals(rekey.id, behindVault.rekeyAt(stable.roomId, 1)?.id)
        assertTrue(relay.published.filter { it.kind == KIND_ROSTER && it.pubkey == behind.devicePubkey }.all { it.tagValue("d") == deriveEpoch(epoch1).id })
        live.sendChat("on the room everybody else is in")
        assertEquals(deriveEpoch(epoch1).id, relay.published.last().tagValue("d"))
        // Once settled, it stops asking.
        val asked = relay.countOfKind(KIND_MEMBER_EPOCH_REQUEST)
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(asked, relay.countOfKind(KIND_MEMBER_EPOCH_REQUEST))
    }

    @Test fun `a removed requester gets nothing from a member`() = runTest {
        val relay = FakeRelay()
        val memberVault = vault()
        val memberIdentity = Fixtures.primary(stable, 5, 6)
        val removedIdentity = Fixtures.primary(stable, 1, 2)
        val member = member(relay, memberVault, memberIdentity)
        member.join()
        runCurrent()
        relay.publish(rekeyToOne(memberIdentity.devicePubkey, removed = listOf(removedIdentity.participant)))
        runCurrent()
        assertEquals(1, member.epochKeys().epoch)
        assertEquals(listOf(removedIdentity.participant), memberVault.get(stable.roomId)?.removed)

        val live = session(
            stable, removedIdentity, relay, authority = authority, expectedEpoch = 1,
            epochGate = { _, _ -> error("nothing may be committed") },
        )
        live.join()
        advanceTimeBy(30_001)
        runCurrent()

        assertTrue(relay.countOfKind(KIND_MEMBER_EPOCH_REQUEST) >= 2, "it kept asking")
        assertEquals(0, relay.countOfKind(KIND_MEMBER_EPOCH_GRANT))
        assertEquals(0, live.epochKeys().epoch)
        assertIs<RoomEpochState.RecoveryNeeded>(live.epochState.value)
    }

    @Test fun `a member grant short of the newest rekey on the relays is not believed`() = runTest {
        val relay = FakeRelay()
        val behind = Fixtures.primary(stable, 1, 2)
        val memberDevice = Fixtures.primary(stable, 5, 6).devicePubkey
        val one = rekeyToOne(memberDevice)
        val two = encodeRekeyEvent(
            stable.roomId, authoritySecret, deriveEpoch(epoch1), RoomEpoch(2, ByteArray(32) { 72 }),
            listOf(memberDevice), listOf(Fixtures.primary(stable, 9, 10).participant), 0, commit = true,
        )
        // A member removed at epoch 2 still holds epoch 1 and the rekey into it, and answers.
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            relay.transport().subscribe(listOf(Filter(kinds = listOf(KIND_MEMBER_EPOCH_REQUEST), tags = mapOf("#d" to listOf(stable.roomId)))))
                .collect { event ->
                    val request = decodeMemberEpochRequest(event, stable.roomId, authority, stable.roomKey, currentTime / 1000) ?: return@collect
                    relay.publish(encodeMemberEpochGrant(stable.roomId, request.device, request.request, listOf(epoch1), listOf(one), currentTime / 1000))
                }
        }
        val live = session(
            stable, behind, relay, authority = authority,
            epochGate = { _, _ -> error("nothing may be committed") },
        )
        live.join()
        runCurrent()
        // The relays show the authority's rekey to epoch 2, which seals nothing to this device.
        relay.publish(two)
        runCurrent()
        advanceTimeBy(30_001)
        runCurrent()

        assertTrue(relay.countOfKind(KIND_MEMBER_EPOCH_GRANT) >= 1, "the stale member answered")
        assertEquals(0, live.epochKeys().epoch)
        assertEquals(2, assertIs<RoomEpochState.RecoveryNeeded>(live.epochState.value).expectedEpoch)
    }

    @Test fun `the authority's own device runs no member desk`() = runTest {
        val relay = FakeRelay()
        val identity = Fixtures.primary(stable, 5, 6)
        var asked = 0
        val desk = object : dev.forgesworn.kithmoot.epoch.MemberEpochDesk {
            override suspend fun onGrant(event: NostrEvent) { asked += 1 }
            override suspend fun onRequest(event: NostrEvent, inStep: Int?) = dev.forgesworn.kithmoot.epoch.MemberDeskDecision.Ignore.also { asked += 1 }
            override suspend fun answer(request: dev.forgesworn.kithmoot.protocol.MemberEpochRequest, inStep: Int?): NostrEvent? = null
        }
        val live = session(stable, identity, relay, authority = authority, epochGate = { _, _ -> EpochGateResult.COMMITTED },
            epochResponder = { null }, memberEpochDesk = desk)
        live.join()
        val behind = Fixtures.primary(stable, 1, 2)
        relay.publish(dev.forgesworn.kithmoot.protocol.encodeMemberEpochRequest(
            stable.roomId, authority, stable.roomKey, behind.deviceSecretKey, behind.credential, 0, currentTime / 1000,
        ))
        runCurrent()
        assertEquals(0, asked)
    }
}
