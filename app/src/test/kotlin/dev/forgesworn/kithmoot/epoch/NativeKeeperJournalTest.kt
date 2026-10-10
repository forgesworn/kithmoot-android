package dev.forgesworn.kithmoot.epoch

import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.RoomRoute
import dev.forgesworn.kithmoot.session.*
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NativeKeeperJournalTest {
    private class Store : RoomStorage {
        var bytes: ByteArray? = null
        var fail = false
        var ambiguous = false
        var beforeWrite: (() -> Unit)? = null
        override fun read() = bytes?.clone()
        override fun write(value: ByteArray) {
            beforeWrite?.invoke()
            if (fail) error("disk full")
            bytes = value.clone()
            if (ambiguous) error("atomic commit outcome unknown")
        }
        override fun reset() = error("Authority state cannot be reset for fresh credit")
    }
    private class Rig(val route: RoomRoute = RoomRoute.MIXED, val ends: Long? = null, destruct: Boolean = false) {
        var at = 1000L
        val creation = NativeKeeperCreation.fresh(at, ends = ends, destruct = destruct)
        val secret = creation.roomSecret()
        val invitation = creation.invitation()
        val room = deriveRoom(secret)
        val owner = Fixtures.primary(room, 5, 6)
        val member = Fixtures.primary(room, 1, 2)
        val store = Store()
        val binding = binding()
        fun binding(route: RoomRoute = this.route, device: String = owner.devicePubkey,
            relays: List<String> = if (route.internet) listOf("wss://fixture.invalid/") else emptyList()) =
            NativeKeeperBinding(room.roomId, creation.authority, owner.participant, device, route, relays)
        fun create() = NativeKeeperJournal.create(store, binding, creation, owner.credential) { at }
        fun open(binding: NativeKeeperBinding = this.binding) = NativeKeeperJournal.open(store, binding) { at }
        fun request(seed: Int = 31) = encodeLivePersistentRequest(LivePersistentContext(invitation, room.roomId), Fixtures.key(seed), at)
        fun credentials() = listOf(owner.credential, member.credential)
        fun epochRequest(identity: RoomIdentity = member) = encodeEpochRequest(room.roomId, binding.authority,
            room.roomKey, identity.deviceSecretKey, identity.credential, at)
    }
    private fun NativeKeeperJournal.select() = bind { true }

    @Test fun welcomeLostOkReopensTheOriginalWithDebtBackoffAndAcceptance() {
        val r = Rig(); lateinit var first: NativeKeeperJournal.Handoff
        r.create().use { source ->
            source.select(); first = assertNotNull(source.reserveWelcome())
            assertEquals(KIND_GROUP_INVITATION, first.event.kind)
            assertEquals(1, first.attempt); assertTrue(source.canHandoff(first))
            assertNull(source.reserveWelcome())
        }
        r.at += 5
        r.open().use { source ->
            val debt = source.snapshot().internetBytes; source.select()
            val retry = assertNotNull(source.reserveWelcome())
            assertEquals(first.event, retry.event); assertEquals(2, retry.attempt)
            assertEquals(debt * 2, source.snapshot().internetBytes)
            assertFalse(source.canHandoff(first)); assertTrue(source.canHandoff(retry))
            source.offered(retry); assertFalse(source.canHandoff(retry)); assertNull(source.reserveWelcome())
            println("NATIVE_KEEPER_ENTRY_MEASUREMENT welcome-original-bytes=$debt lost-ok-debt=${source.snapshot().internetBytes}")
        }
        r.open().use { source -> source.select(); assertNull(source.reserveWelcome()) }
    }

    @Test fun welcomeRefreshKeepsBaseLifetimeAndRefusesPendingRetiredNearbyAndRollback() {
        val r = Rig(ends = 100_000L, destruct = true)
        r.create().use { source ->
            source.select(); val first = assertNotNull(source.reserveWelcome()); source.offered(first)
            r.at += 6 * 60 * 60
            val fresh = assertNotNull(source.reserveWelcome())
            assertNotEquals(first.event.id, fresh.event.id)
            val body = assertNotNull(decodePersistentInvitation(fresh.event, r.invitation))
            assertContentEquals(r.secret, body.secret); assertEquals(r.ends, body.endsAt); assertTrue(body.destruct)
            val renewedOwner = PrimaryIdentity.create(r.room.roomId, r.at + 3600, r.at,
                participantSecretKey = Fixtures.key(5), deviceSecretKey = Fixtures.key(6))
            source.prepareRekey(listOf(renewedOwner.credential)); assertFalse(source.canHandoff(fresh)); assertNull(source.reserveWelcome())
        }
        r.open().use { source -> source.select(); assertNull(source.reserveWelcome()) }
        val retired = Rig()
        retired.create().use { source -> source.select(); source.prepareRetirement(); assertNull(source.reserveWelcome()) }
        val nearby = Rig(RoomRoute.NEARBY)
        nearby.create().use { source -> source.select(); assertNull(source.reserveWelcome()); assertEquals(0, source.snapshot().internetBytes) }
        val rollback = Rig()
        rollback.create().use { source -> source.select(); val original = assertNotNull(source.reserveWelcome())
            rollback.at--; assertFalse(source.canHandoff(original)); assertFails { source.reserveWelcome() } }
    }

    @Test fun courierMarkerRequiresAnActualEmptyPinnedLedgerAndSurvivesLostWriteReturn() {
        val r = Rig(); val queue = Store()
        val binding = RoomRekeyBinding(r.binding.room, r.binding.authority, r.binding.device, r.binding.meshScope, r.binding.relays, r.route)
        RoomRekeyLedger(queue, binding, { r.at * 1000 }, true).use { ledger ->
            r.create().use { source ->
                assertTrue(source.mayInitialiseReceiver()); r.store.ambiguous = true
                assertFails { source.recordCourierCreated(ledger) }; assertTrue(source.persistenceFailed())
            }
            r.store.ambiguous = false
            r.open().use { source -> assertTrue(source.courierReady()); assertFalse(source.mayInitialiseReceiver()) }
        }
        val root = Json.parseToJsonElement(r.store.bytes!!.toString(Charsets.UTF_8)).jsonObject
        r.store.bytes = JsonObject(root + ("v" to JsonPrimitive(2))).toString().toByteArray()
        val bytes = r.store.bytes!!.clone()
        assertFailsWith<NativeKeeperMigrationRequiredException> { r.open() }
        assertContentEquals(bytes, r.store.bytes)
    }

    @Test fun approvalCardsAreOnlyVerifiedCurrentRequestsAndPersistedApprovalClearsThem() {
        val r = Rig()
        r.create().use { source ->
            source.select(); assertTrue(source.unknownParticipants().isEmpty())
            assertNotNull(source.answerEpoch(r.epochRequest(), RekeyLane.NEARBY))
            assertEquals(listOf(r.member.participant), source.unknownParticipants())
        }
        r.open().use { source ->
            assertEquals(listOf(r.member.participant), source.unknownParticipants()); source.select()
            source.approve(r.member.participant); assertTrue(source.unknownParticipants().isEmpty())
        }
        r.open().use { source -> assertTrue(source.unknownParticipants().isEmpty()); assertTrue(r.member.participant in source.snapshot().members) }
    }

    @Test fun creationTransfersNewAuthorityOnceAndOpenCannotReconstructMissingOrCorruptState() {
        val r = Rig()
        assertFails { r.open() }
        r.create().use { journal ->
            assertTrue(journal.snapshot().suspended)
            assertEquals(listOf(r.owner.participant), journal.snapshot().members)
            assertFails { r.creation.roomSecret() }
            assertFails { r.creation.invitation() }
            assertFails { r.create() }
            assertFails { NativeKeeperJournal.withInactiveOwner(r.binding.owner) { error("must not enter") } }
        }
        r.open().use { journal -> assertTrue(journal.snapshot().suspended); assertEquals(0, journal.epoch().epoch) }
        val original = r.store.bytes!!.clone()
        r.store.bytes = "corrupt".toByteArray()
        assertFails { r.open() }
        assertEquals("corrupt", r.store.bytes!!.toString(Charsets.UTF_8))
        r.store.bytes = original
        r.open().close()
        assertEquals("released", NativeKeeperJournal.withInactiveOwner(r.binding.owner) { "released" })
    }

    @Test fun wrongOwnerCredentialAndPolicyChangesCannotAcquireTheAuthorityOrResetCredit() {
        val r = Rig()
        assertFails { NativeKeeperJournal.create(r.store, r.binding, r.creation, r.member.credential) { r.at } }
        // Rejection before transfer leaves the genuinely fresh creation usable.
        r.create().use { it.select(); it.answer(r.request(), RekeyLane.NEARBY) }
        val bytes = r.store.bytes!!.clone()
        for (changed in listOf(r.binding(device = r.member.devicePubkey), r.binding(route = RoomRoute.NEARBY),
            r.binding(relays = listOf("wss://other.invalid/")))) {
            assertEquals(r.binding.owner, changed.owner)
            assertFails { r.open(changed) }
            assertContentEquals(bytes, r.store.bytes)
        }
        r.open().close()
    }

    @Test fun cachedChallengeSurvivesLostHandoffAndReopenWithOriginalSignatureExpiryAndDebt() {
        val r = Rig(); val request = r.request(); lateinit var first: NativeKeeperJournal.Handoff
        lateinit var before: NativeKeeperJournal.Snapshot
        r.create().use { journal ->
            journal.select(); first = assertNotNull(journal.answer(request, RekeyLane.NEARBY))
            assertTrue(journal.canHandoff(first)); before = journal.snapshot()
        }
        r.at += 5
        r.open().use { journal ->
            assertEquals(before.copy(suspended = true), journal.snapshot())
            assertNull(journal.answer(request, RekeyLane.NEARBY))
            journal.select()
            val retry = assertNotNull(journal.answer(request, RekeyLane.NEARBY))
            assertEquals(first.event, retry.event); assertEquals(2, retry.attempt)
            assertEquals(before.nearbyBytes * 2, journal.snapshot().nearbyBytes)
            assertFalse(journal.canHandoff(first)); assertTrue(journal.canHandoff(retry))
            val decoded = assertNotNull(decodeLivePersistentAnswer(retry.event,
                LivePersistentContext(r.invitation, r.room.roomId), request, Fixtures.key(31), r.at))
            assertEquals(0L, decoded.epochHint)
            journal.offered(retry); assertFalse(journal.canHandoff(retry))
        }
    }

    @Test fun challengeAttemptsCannotBeResetByLaneSwitchOrAnExpiredAnswer() {
        val r = Rig(); val request = r.request(); lateinit var first: NativeKeeperJournal.Handoff
        r.create().use { journal ->
            journal.select(); first = assertNotNull(journal.answer(request, RekeyLane.INTERNET))
            assertFalse(journal.canHandoff(first.copy(lane = RekeyLane.NEARBY)))
            assertNotNull(journal.answer(request, RekeyLane.NEARBY))
            assertNotNull(journal.answer(request, RekeyLane.INTERNET))
            assertNull(journal.answer(request, RekeyLane.NEARBY))
        }
        r.at += 31
        r.open().use { journal ->
            journal.select(); assertNull(journal.answer(request, RekeyLane.INTERNET))
            assertFalse(journal.canHandoff(first))
            assertNotNull(journal.answer(r.request(seed = 32), RekeyLane.INTERNET))
        }
    }

    @Test fun ambiguousAnswerReservationSuspendsSigningAndReopenRetainsOriginalAnswerAndCharge() {
        val r = Rig(); val request = r.request()
        r.create().use { journal ->
            journal.select(); var writes = 0
            r.store.beforeWrite = { writes++; if (writes == 3) r.store.ambiguous = true }
            assertFails { journal.answer(request, RekeyLane.NEARBY) }
            assertTrue(journal.persistenceFailed()); assertFails { journal.prepareRekey(listOf(r.owner.credential)) }
        }
        val original = Json.parseToJsonElement(r.store.bytes!!.toString(Charsets.UTF_8)).jsonObject
            .getValue("answers").jsonArray.single().jsonObject.getValue("answer").jsonObject.let(NostrEvent::fromJson)
        r.store.ambiguous = false; r.store.beforeWrite = null
        r.open().use { journal ->
            val debt = journal.snapshot().nearbyBytes
            assertTrue(debt > 0); journal.select()
            val retry = assertNotNull(journal.answer(request, RekeyLane.NEARBY))
            assertEquals(original, retry.event); assertEquals(2, retry.attempt)
            assertEquals(debt * 2, journal.snapshot().nearbyBytes)
        }
    }

    @Test fun pendingTransitionCommitsBeforeReturnAndAmbiguousCommitNeverSignsAReplacement() {
        val r = Rig(); val request = r.request()
        r.create().use { journal ->
            journal.select(); r.store.ambiguous = true
            assertFails { journal.prepareRekey(listOf(r.owner.credential)) }
            assertTrue(journal.persistenceFailed()); assertFails { journal.answer(request, RekeyLane.INTERNET) }
        }
        val persisted = Json.parseToJsonElement(r.store.bytes!!.toString(Charsets.UTF_8)).jsonObject
        val original = persisted.getValue("pending").jsonObject.getValue("events").jsonArray.single().jsonObject.let(NostrEvent::fromJson)
        r.store.ambiguous = false
        r.open().use { journal ->
            assertEquals(listOf(original), journal.snapshot().pending)
            assertEquals(0, journal.epoch().epoch); journal.select()
            assertFails { journal.prepareRekey(listOf(r.owner.credential)) }
            assertNull(journal.answer(request, RekeyLane.NEARBY))
            val send = assertNotNull(journal.reservePending(original.id, RekeyLane.NEARBY))
            assertEquals(original, send.event); assertTrue(journal.canHandoff(send))
            journal.offered(send); assertFalse(journal.canHandoff(send))
        }
    }

    @Test fun preCommitFailureCannotReturnANoticeOrAlterTheDurableCurrentEpoch() {
        val r = Rig()
        r.create().use { journal ->
            journal.select(); val bytes = r.store.bytes!!.clone(); r.store.fail = true
            assertFails { journal.prepareRekey(listOf(r.owner.credential)) }
            assertContentEquals(bytes, r.store.bytes); assertTrue(journal.persistenceFailed())
        }
        r.store.fail = false
        r.open().use { journal ->
            assertEquals(0, journal.epoch().epoch); assertTrue(journal.snapshot().pending.isEmpty())
            journal.select(); assertEquals(1, journal.prepareRekey(listOf(r.owner.credential)).size)
        }
    }

    @Test fun credentialsNeedPersistedApprovalAndTheOwnerMustHaveASuccessorSeal() {
        val r = Rig()
        r.create().use { journal ->
            journal.select()
            assertFails { journal.prepareRekey(r.credentials()) }
            journal.approve(r.member.participant)
            assertFails { journal.prepareRekey(listOf(r.member.credential)) }
            assertFails { journal.prepareRekey(r.credentials(), removed = listOf(r.owner.participant)) }
        }
        r.open().use { journal ->
            assertTrue(r.member.participant in journal.snapshot().members); journal.select()
            val original = journal.prepareRekey(listOf(r.owner.credential), removed = listOf(r.member.participant)).single()
            assertTrue(Events.verify(original)); assertFails { journal.approve(r.member.participant) }
        }
    }

    @Test fun withdrawalClockRollbackAndNearbyOnlySelectionDenyHandoffWithoutRefund() {
        val r = Rig(RoomRoute.NEARBY); var selected = true; lateinit var send: NativeKeeperJournal.Handoff
        lateinit var before: NativeKeeperJournal.Snapshot
        r.create().use { journal ->
            journal.bind { selected }; assertNull(journal.answer(r.request(), RekeyLane.INTERNET))
            send = assertNotNull(journal.answer(r.request(), RekeyLane.NEARBY)); before = journal.snapshot()
            selected = false; assertFalse(journal.canHandoff(send)); assertTrue(journal.snapshot().suspended)
            selected = true; assertTrue(journal.canHandoff(send))
            r.at--; assertFalse(journal.canHandoff(send)); assertFails { journal.answer(r.request(), RekeyLane.NEARBY) }
        }
        assertFails { r.open() }
        r.at++
        r.open().use { journal -> assertEquals(before.copy(suspended = true), journal.snapshot()) }
    }

    @Test fun budgetAndEightNoticeAttemptsSurviveOwnerReopenAndTheRollingWindow() {
        val r = Rig(RoomRoute.NEARBY); lateinit var original: NostrEvent
        r.create().use { journal ->
            journal.select(); original = journal.prepareRekey(listOf(r.owner.credential)).single()
            repeat(8) { index ->
                val send = assertNotNull(journal.reservePending(original.id, RekeyLane.NEARBY))
                assertEquals(index + 1, send.attempt); journal.offered(send)
            }
            assertNull(journal.reservePending(original.id, RekeyLane.NEARBY))
        }
        r.at += 62 * 60 + 1
        r.open().use { journal ->
            journal.select(); assertNull(journal.reservePending(original.id, RekeyLane.NEARBY))
            // Snapshot records charged debt at its persisted high-water time, not a refund on read.
            assertTrue(journal.snapshot().nearbyBytes > 0)
        }
    }

    @Test fun freshAnswerAndInvalidRequestBudgetsAreSharedAndPersisted() {
        val r = Rig()
        r.create().use { journal ->
            journal.select()
            repeat(16) { assertNotNull(journal.answer(r.request(seed = it + 20), RekeyLane.INTERNET)) }
            assertNull(journal.answer(r.request(seed = 50), RekeyLane.INTERNET))
        }
        r.open().use { journal ->
            journal.select(); assertNull(journal.answer(r.request(seed = 51), RekeyLane.INTERNET))
            r.at += 61; assertNotNull(journal.answer(r.request(seed = 52), RekeyLane.INTERNET))
            // Bad requests cost durable verification reservations too.
            val invalid = r.request(seed = 53).copy(sig = "00".repeat(64))
            repeat(64) { assertNull(journal.answer(invalid, RekeyLane.INTERNET)) }
            val revision = journal.snapshot().revision
            assertNull(journal.answer(r.request(seed = 54), RekeyLane.INTERNET))
            assertEquals(revision, journal.snapshot().revision)
        }
    }

    @Test fun handoffGuardDoesNotWaitForAnAtomicWriteAndOnlyOneDispatcherCanBind() {
        val r = Rig(); val executor = Executors.newSingleThreadExecutor()
        try {
            r.create().use { journal ->
                journal.select(); assertFails { journal.bind { true } }
                val send = assertNotNull(journal.answer(r.request(), RekeyLane.NEARBY))
                val entered = CountDownLatch(1); val release = CountDownLatch(1)
                r.store.beforeWrite = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                val write = executor.submit { journal.approve(r.member.participant) }
                try { assertTrue(entered.await(5, TimeUnit.SECONDS)); assertFalse(journal.canHandoff(send)) }
                finally { release.countDown(); write.get(5, TimeUnit.SECONDS); r.store.beforeWrite = null }
                journal.suspendExports(); assertFalse(journal.canHandoff(send)); assertFails { journal.bind { true } }
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun strictRestoreRejectsUnknownFieldsStringNumbersAndMismatchedPendingSecrets() {
        val r = Rig()
        r.create().use { journal -> journal.select(); journal.prepareRekey(listOf(r.owner.credential)) }
        val original = r.store.bytes!!.clone()
        val root = Json.parseToJsonElement(original.toString(Charsets.UTF_8)).jsonObject
        val mutations = listOf(
            JsonObject(root + ("surprise" to JsonPrimitive(true))),
            JsonObject(root + ("epoch" to JsonPrimitive("0"))),
            JsonObject(root + ("pending" to JsonObject(root.getValue("pending").jsonObject + ("secret" to JsonPrimitive("11".repeat(32)))))),
            JsonObject(root + ("pending" to JsonObject(root.getValue("pending").jsonObject + ("offered" to buildJsonObject { put("NEARBY:${"11".repeat(32)}", 1) })))),
        )
        for (mutation in mutations) {
            r.store.bytes = mutation.toString().toByteArray(Charsets.UTF_8); assertFails { r.open() }
            assertContentEquals(mutation.toString().toByteArray(Charsets.UTF_8), r.store.bytes)
        }
        r.store.bytes = original; r.open().close()
    }

    @Test fun restoredRetirementAndClosureCannotAnswerNewChallengesOrReviveAuthority() {
        for (closed in listOf(false, true)) {
            val r = Rig()
            r.create().use { journal ->
                journal.select()
                if (closed) {
                    val events = journal.prepareRekey(emptyList(), closed = true, destruct = true)
                    assertEquals(2, events.size)
                    assertEquals("{\"v\":1,\"ended\":true,\"destruct\":true}", events.first().content)
                } else journal.prepareRetirement()
            }
            r.open().use { journal ->
                assertEquals(if (closed) KeeperPhase.CLOSED else KeeperPhase.RETIRED, journal.snapshot().phase)
                journal.select(); assertNull(journal.answer(r.request(), RekeyLane.NEARBY))
                assertFails { journal.prepareRekey(listOf(r.owner.credential)) }
                journal.snapshot().pending.forEach { assertNotNull(journal.reservePending(it.id, RekeyLane.NEARBY)) }
            }
        }
    }

    @Test fun anExpiredRoomAndClosedCreationCannotExposeOrExportItsKeys() {
        val r = Rig(ends = 1002)
        val journal = r.create(); journal.select()
        val send = assertNotNull(journal.answer(r.request(), RekeyLane.NEARBY))
        r.at = 1002; assertFalse(journal.canHandoff(send)); assertNull(journal.answer(r.request(), RekeyLane.NEARBY))
        assertFails { journal.prepareRekey(listOf(r.owner.credential)) }
        journal.close(); assertFails { journal.invitation() }; assertFails { journal.epoch() }
        val fresh = NativeKeeperCreation.fresh(1000); fresh.close(); assertFails { fresh.roomSecret() }; assertFails { fresh.invitation() }
    }

    @Test fun verificationReservationFailureCreatesNoAnswerButKeepsItsCharge() {
        val r = Rig(); val request = r.request()
        r.create().use { journal ->
            journal.select(); r.store.ambiguous = true
            assertFails { journal.answer(request, RekeyLane.NEARBY) }
            assertTrue(journal.persistenceFailed())
        }
        val root = Json.parseToJsonElement(r.store.bytes!!.toString(Charsets.UTF_8)).jsonObject
        assertTrue(root.getValue("answers").jsonArray.isEmpty())
        assertEquals(1, root.getValue("spends").jsonArray.size)
        r.store.ambiguous = false
        r.open().use { journal -> journal.select(); assertNotNull(journal.answer(request, RekeyLane.NEARBY)) }
    }

    @Test fun epochDeskRefusesUnknownMembersUntilPersistedApprovalAndNeverReplacesCachedRefusals() {
        val r = Rig(); val request = r.epochRequest(); lateinit var refused: NativeKeeperJournal.Handoff
        r.create().use { journal ->
            journal.select(); refused = assertNotNull(journal.answerEpoch(request, RekeyLane.INTERNET))
            val verdict = decodeEpochGrant(refused.event, r.room.roomId, r.binding.authority,
                r.member.deviceSecretKey, request.id, r.at) as EpochGrant.Refused
            assertEquals("unknown", verdict.reason)
            journal.approve(r.member.participant)
        }
        r.open().use { journal ->
            journal.select(); assertEquals(refused.event, assertNotNull(journal.answerEpoch(request, RekeyLane.INTERNET)).event)
            val fresh = r.epochRequest()
            val grant = assertNotNull(journal.answerEpoch(fresh, RekeyLane.INTERNET))
            val decoded = decodeEpochGrant(grant.event, r.room.roomId, r.binding.authority,
                r.member.deviceSecretKey, fresh.id, r.at) as EpochGrant.Current
            assertEquals(0, decoded.epoch); assertNull(decoded.secret)
            assertEquals(listOf(r.member.participant, r.owner.participant).sorted(), decoded.members)
        }
    }

    @Test fun epochAnswerRetriesExactBytesAcrossReopenAndExpiresWithTheOriginalRequest() {
        val r = Rig(); val request = r.epochRequest(r.owner); lateinit var send: NativeKeeperJournal.Handoff
        lateinit var before: NativeKeeperJournal.Snapshot
        r.create().use { journal -> journal.select(); send = assertNotNull(journal.answerEpoch(request, RekeyLane.NEARBY)); before = journal.snapshot() }
        r.at += 10
        r.open().use { journal ->
            assertNull(journal.answerEpoch(request, RekeyLane.NEARBY)); journal.select()
            val retry = assertNotNull(journal.answerEpoch(request, RekeyLane.INTERNET))
            assertEquals(send.event, retry.event); assertEquals(2, retry.attempt)
            assertEquals(before.nearbyBytes, journal.snapshot().nearbyBytes)
            assertEquals(before.nearbyBytes, journal.snapshot().internetBytes)
            assertFalse(journal.canHandoff(send)); assertTrue(journal.canHandoff(retry))
            r.at = 1090; assertFalse(journal.canHandoff(retry)); assertNull(journal.answerEpoch(request, RekeyLane.INTERNET))
        }
    }

    @Test fun epochDeskChargesInvalidCredentialsAndAdmissionMacAndCannotAnswerDuringPendingWork() {
        val r = Rig()
        r.create().use { journal ->
            journal.select()
            val wrongMac = encodeEpochRequest(r.room.roomId, r.binding.authority, ByteArray(32),
                r.member.deviceSecretKey, r.member.credential, r.at)
            val foreign = Fixtures.primary(Fixtures.room(9), 1, 2)
            val wrongCredential = encodeEpochRequest(r.room.roomId, r.binding.authority, r.room.roomKey,
                foreign.deviceSecretKey, foreign.credential, r.at)
            assertNull(journal.answerEpoch(wrongMac, RekeyLane.INTERNET))
            assertNull(journal.answerEpoch(wrongCredential, RekeyLane.INTERNET))
            assertEquals(2L, journal.snapshot().revision)
            journal.prepareRekey(listOf(r.owner.credential))
            assertNull(journal.answerEpoch(r.epochRequest(r.owner), RekeyLane.INTERNET))
        }
    }

    @Test fun invitationAndEpochDeskShareTheFreshSignatureAndVerificationBudgets() {
        val r = Rig()
        r.create().use { journal ->
            journal.select()
            repeat(8) { index ->
                assertNotNull(journal.answer(r.request(seed = 20 + index), RekeyLane.INTERNET))
                assertNotNull(journal.answerEpoch(r.epochRequest(r.owner), RekeyLane.INTERNET))
            }
            assertNull(journal.answer(r.request(seed = 30), RekeyLane.INTERNET))
            assertNull(journal.answerEpoch(r.epochRequest(r.owner), RekeyLane.INTERNET))
        }
    }

    @Test fun aFullNearbyByteBudgetCannotCauseUnboundedFreshSigningOrLeaveInternetFreshCredit() {
        val r = Rig()
        r.create().use { journal ->
            journal.select(); var refused = 0
            repeat(16) { if (journal.answer(r.request(seed = 20 + it), RekeyLane.NEARBY) == null) refused++ }
            assertTrue(refused > 0, "At least one valid request hits the control-byte ceiling")
            assertNull(journal.answerEpoch(r.epochRequest(r.owner), RekeyLane.INTERNET),
                "Even unoffered signatures consume the shared durable signing quota")
        }
        r.open().use { journal ->
            journal.select(); assertNull(journal.answerEpoch(r.epochRequest(r.owner), RekeyLane.INTERNET))
            r.at += 61; assertNotNull(journal.answerEpoch(r.epochRequest(r.owner), RekeyLane.INTERNET))
        }
    }

    @Test fun protocolPermittedFiveSecondFutureRequestsKeepTheirExactCachedAnswersAfterReopen() {
        val r = Rig()
        val live = encodeLivePersistentRequest(LivePersistentContext(r.invitation, r.room.roomId), Fixtures.key(31), r.at + 5)
        val epoch = encodeEpochRequest(r.room.roomId, r.binding.authority, r.room.roomKey,
            r.owner.deviceSecretKey, r.owner.credential, r.at + 5)
        lateinit var liveAnswer: NativeKeeperJournal.Handoff; lateinit var epochAnswer: NativeKeeperJournal.Handoff
        r.create().use { journal ->
            journal.select(); liveAnswer = assertNotNull(journal.answer(live, RekeyLane.INTERNET))
            epochAnswer = assertNotNull(journal.answerEpoch(epoch, RekeyLane.INTERNET))
        }
        r.open().use { journal ->
            journal.select(); assertEquals(liveAnswer.event, assertNotNull(journal.answer(live, RekeyLane.INTERNET)).event)
            assertEquals(epochAnswer.event, assertNotNull(journal.answerEpoch(epoch, RekeyLane.INTERNET)).event)
        }
    }

    @Test fun ambiguousSigningBudgetCommitCannotReturnAnAnswerOrRefundTheSignatureReservation() {
        val r = Rig(); val request = r.request()
        r.create().use { journal ->
            journal.select(); var writes = 0
            r.store.beforeWrite = { writes++; if (writes == 2) r.store.ambiguous = true }
            assertFails { journal.answer(request, RekeyLane.NEARBY) }; assertTrue(journal.persistenceFailed())
        }
        val root = Json.parseToJsonElement(r.store.bytes!!.decodeToString()).jsonObject
        assertTrue(root.getValue("answers").jsonArray.isEmpty())
        assertEquals(1, root.getValue("spends").jsonArray.count { it.jsonObject.getValue("fresh").jsonPrimitive.boolean })
        r.store.beforeWrite = null; r.store.ambiguous = false
        r.open().use { journal -> journal.select(); assertNotNull(journal.answer(request, RekeyLane.NEARBY)) }
    }

    @Test fun selfDestructRoomClosureCarriesTheSameTerminalPolicyInBothOriginalNotices() {
        val r = Rig(destruct = true)
        r.create().use { journal ->
            journal.select(); val events = journal.prepareRekey(emptyList(), closed = true)
            assertEquals("{\"v\":1,\"ended\":true,\"destruct\":true}", events.first().content)
            val notice = assertNotNull(decodeRekeyEvent(events.last(), r.room.roomId, r.binding.authority, deriveEpoch(RoomEpoch(0, r.secret)),
                r.owner.deviceSecretKey))
            assertTrue(notice.closed); assertTrue(notice.destruct)
        }
        r.open().use { journal -> assertEquals(KeeperPhase.CLOSED, journal.snapshot().phase) }
    }

    @Test fun realReceiverAndLiveSessionMustApplyTheExactOriginalBeforeTheSourceFloorCanAdvance() = runTest {
        val creation = NativeKeeperCreation.fresh(0)
        val secret = creation.roomSecret(); val invitation = creation.invitation(); val room = deriveRoom(secret)
        val owner = Fixtures.primary(room, 5, 6); val member = Fixtures.primary(room, 1, 2)
        val binding = NativeKeeperBinding(room.roomId, creation.authority, owner.participant, owner.devicePubkey,
            RoomRoute.INTERNET, listOf("wss://fixture.invalid/"))
        val store = Store(); val vault = EpochVault(Store()); vault.initialise(room.roomId, binding.authority, secret, 0)
        val relay = FakeRelay()
        val receiver = session(room, owner, relay, authority = binding.authority, epochGate = { event, notice ->
            assertNotNull(vault.follow(room.roomId, notice, event.id, currentTime / 1000)); EpochGateResult.COMMITTED
        })
        val peer = session(room, member, relay, authority = binding.authority, epochGate = { _, _ -> EpochGateResult.COMMITTED })
        var journal = NativeKeeperJournal.create(store, binding, creation, owner.credential) { currentTime / 1000 }
        try {
            receiver.join(); peer.join(); runCurrent(); journal.select(); journal.approve(member.participant)
            val challenge = encodeLivePersistentRequest(LivePersistentContext(invitation, room.roomId), Fixtures.key(31), 0)
            assertNotNull(journal.answer(challenge, RekeyLane.INTERNET))
            val original = journal.prepareRekey(listOf(owner.credential, member.credential)).single()
            assertFails { journal.completePending(vault, receiver) }
            journal.close(); journal = NativeKeeperJournal.open(store, binding) { currentTime / 1000 }
            assertTrue(journal.snapshot().suspended); assertEquals(listOf(original), journal.snapshot().pending)
            journal.select(); assertNull(journal.answer(challenge, RekeyLane.INTERNET))
            val send = assertNotNull(journal.reservePending(original.id, RekeyLane.INTERNET))
            assertTrue(journal.canHandoff(send)); relay.publish(send.event); runCurrent()
            assertEquals(1, receiver.epochKeys().epoch); assertEquals(1, peer.epochKeys().epoch)
            assertFails { journal.completePending(vault, receiver) } // Live success cannot invent a recorded source handoff.
            journal.offered(send)
            val before = journal.snapshot(); journal.close()
            journal = NativeKeeperJournal.open(store, binding) { currentTime / 1000 }
            assertEquals(before.copy(suspended = true), journal.snapshot()); journal.select()
            assertTrue(journal.completePending(vault, receiver)); assertEquals(1, journal.epoch().epoch)
            assertEquals(original.id, journal.snapshot().cause); assertTrue(journal.snapshot().pending.isEmpty())
            val epochRequest = encodeEpochRequest(room.roomId, binding.authority, room.roomKey,
                member.deviceSecretKey, member.credential, 0)
            val epochAnswer = assertNotNull(journal.answerEpoch(epochRequest, RekeyLane.INTERNET))
            val granted = decodeEpochGrant(epochAnswer.event, room.roomId, binding.authority,
                member.deviceSecretKey, epochRequest.id, 0) as EpochGrant.Current
            assertEquals(1, granted.epoch); assertContentEquals(journal.epoch().secret, granted.secret)
            assertNull(journal.answer(challenge, RekeyLane.INTERNET), "An old challenge must not acquire a new signature/hint")
            val fresh = encodeLivePersistentRequest(LivePersistentContext(invitation, room.roomId), Fixtures.key(32), 0)
            val answer = assertNotNull(journal.answer(fresh, RekeyLane.INTERNET))
            assertEquals(1L, assertNotNull(decodeLivePersistentAnswer(answer.event,
                LivePersistentContext(invitation, room.roomId), fresh, Fixtures.key(32), 0)).epochHint)
            receiver.sendChat("Keeper after durable transition"); runCurrent()
            peer.sendChat("Original member replies without rejoin"); runCurrent()
            val bodies = listOf("Keeper after durable transition", "Original member replies without rejoin")
            assertEquals(bodies, receiver.chat.value.map { it.body }); assertEquals(bodies, peer.chat.value.map { it.body })
            println("NATIVE_KEEPER_MEASUREMENT " + buildJsonObject {
                put("case", "original-transition-journal-reopen"); put("noticeId", original.id)
                put("noticeBytes", original.toCompactJson().toByteArray(Charsets.UTF_8).size)
                put("sourceEpochBefore", before.epoch); put("sourceEpochAfter", journal.epoch().epoch)
                put("receiverEpoch", receiver.epochKeys().epoch); put("memberEpoch", peer.epochKeys().epoch)
                put("memberRejoined", false); put("originalInternetOffers", relay.published.count { it.id == original.id })
                put("internetDebtPreserved", before.internetBytes == journal.snapshot().internetBytes - answer.event.toCompactJson().toByteArray(Charsets.UTF_8).size - epochAnswer.event.toCompactJson().toByteArray(Charsets.UTF_8).size)
                put("freshRowsEach", peer.chat.value.size); put("processDeath", false)
            })
        } finally { journal.close(); receiver.leave(); peer.leave(); secret.fill(0) }
    }

    @Test fun replacingTheActualKeeperSessionAfterReceiverActivationResolvesTheOriginalPendingSource() = runTest {
        val creation = NativeKeeperCreation.fresh(0); val secret = creation.roomSecret(); val room = deriveRoom(secret)
        val owner = Fixtures.primary(room, 5, 6); val member = Fixtures.primary(room, 1, 2)
        val binding = NativeKeeperBinding(room.roomId, creation.authority, owner.participant, owner.devicePubkey,
            RoomRoute.INTERNET, listOf("wss://fixture.invalid/"))
        val store = Store(); val receiverStore = Store(); var vault = EpochVault(receiverStore)
        vault.initialise(room.roomId, binding.authority, secret, 0)
        val relay = FakeRelay()
        var keeper = session(room, owner, relay, authority = binding.authority, epochGate = { event, notice ->
            assertNotNull(vault.follow(room.roomId, notice, event.id, 0)); EpochGateResult.COMMITTED
        })
        val peer = session(room, member, relay, authority = binding.authority, epochGate = { _, _ -> EpochGateResult.COMMITTED })
        var journal = NativeKeeperJournal.create(store, binding, creation, owner.credential) { 0 }
        try {
            keeper.join(); peer.join(); runCurrent(); journal.select(); journal.approve(member.participant)
            val original = journal.prepareRekey(listOf(owner.credential, member.credential)).single()
            val send = assertNotNull(journal.reservePending(original.id, RekeyLane.INTERNET))
            relay.publish(original); runCurrent(); journal.offered(send)
            assertEquals(original.id, vault.get(room.roomId)!!.activationCause)
            journal.close(); keeper.leave(); runCurrent()
            // Replace both actual receiver owners. The peer session stays alive and never rejoins.
            vault = EpochVault(receiverStore)
            val committed = vault.get(room.roomId)!!
            keeper = session(room, owner, relay, authority = binding.authority,
                initialEpoch = deriveEpoch(RoomEpoch(committed.currentEpoch, committed.currentSecret)),
                epochGate = { _, _ -> error("A cold owner already at the successor must not invent another receiver commit") })
            keeper.join(); runCurrent()
            journal = NativeKeeperJournal.open(store, binding) { 0 }
            assertTrue(journal.snapshot().suspended); assertEquals(listOf(original), journal.snapshot().pending)
            journal.select()
            // Legacy and mismatched causes cannot be substituted even when the live key agrees.
            val bytes = receiverStore.bytes!!.clone()
            val root = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            for (cause in listOf(JsonNull, JsonPrimitive("11".repeat(32)))) {
                receiverStore.bytes = JsonObject(root + ("rooms" to JsonArray(root.getValue("rooms").jsonArray.map {
                    JsonObject(it.jsonObject + ("activationCause" to cause))
                }))).toString().toByteArray()
                assertFails { journal.completePending(vault, keeper) }
                assertEquals(listOf(original), journal.snapshot().pending)
            }
            receiverStore.bytes = bytes
            assertTrue(journal.completePending(vault, keeper)); assertEquals(1, journal.epoch().epoch)
            keeper.sendChat("New keeper session at the committed epoch"); runCurrent()
            peer.sendChat("Same member answers after keeper recovery"); runCurrent()
            assertEquals(2, keeper.chat.value.size); assertEquals(2, peer.chat.value.size)
            println("NATIVE_KEEPER_MEASUREMENT " + buildJsonObject {
                put("case", "source-and-actual-session-reopen"); put("noticeId", original.id)
                put("sourceEpochAfter", journal.epoch().epoch); put("receiverCause", committed.activationCause)
                put("memberRejoined", false); put("freshRowsEach", peer.chat.value.size); put("processDeath", false)
            })
        } finally { journal.close(); keeper.leave(); peer.leave(); secret.fill(0) }
    }

    @Test fun closureCompletesOnlyAgainstTheActualTerminalCauseAndSuccessorClosedSession() = runTest {
        val creation = NativeKeeperCreation.fresh(0); val secret = creation.roomSecret(); val room = deriveRoom(secret)
        val owner = Fixtures.primary(room, 5, 6)
        val binding = NativeKeeperBinding(room.roomId, creation.authority, owner.participant, owner.devicePubkey,
            RoomRoute.INTERNET, listOf("wss://fixture.invalid/"))
        val store = Store(); val vault = EpochVault(Store()); vault.initialise(room.roomId, binding.authority, secret, 0)
        val relay = FakeRelay()
        val receiver = session(room, owner, relay, authority = binding.authority, epochGate = { event, notice ->
            vault.terminal(room.roomId, 0, notice, event.id, currentTime / 1000); EpochGateResult.COMMITTED
        })
        var journal = NativeKeeperJournal.create(store, binding, creation, owner.credential) { currentTime / 1000 }
        try {
            receiver.join(); runCurrent(); journal.select()
            val events = journal.prepareRekey(emptyList(), closed = true)
            events.forEach { original ->
                val send = assertNotNull(journal.reservePending(original.id, RekeyLane.INTERNET))
                relay.publish(send.event); journal.offered(send); runCurrent()
            }
            assertEquals(RoomEpochState.Closed(1), receiver.epochState.value)
            assertTrue(journal.completePending(vault, receiver)); journal.close()
            journal = NativeKeeperJournal.open(store, binding) { currentTime / 1000 }; journal.select()
            assertEquals(KeeperPhase.CLOSED, journal.snapshot().phase); assertTrue(journal.snapshot().pending.isEmpty())
            assertFails { journal.prepareRekey(listOf(owner.credential)) }
        } finally { journal.close(); receiver.leave(); secret.fill(0) }
    }
}
