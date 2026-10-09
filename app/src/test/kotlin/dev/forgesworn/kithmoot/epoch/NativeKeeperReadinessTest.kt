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
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NativeKeeperReadinessTest {
    private class Store : RoomStorage {
        var bytes: ByteArray? = null
        override fun read() = bytes?.clone()
        override fun write(value: ByteArray) { bytes = value.clone() }
        override fun reset() = error("Authority must not be reset")
        fun json() = Json.parseToJsonElement(bytes!!.decodeToString()).jsonObject
        fun replace(value: JsonObject) { bytes = value.toString().toByteArray() }
    }
    private class Rig(val test: TestScope) : AutoCloseable {
        val creation = NativeKeeperCreation.fresh(test.currentTime / 1000)
        val secret = creation.roomSecret()
        val invitation = creation.invitation()
        val room = deriveRoom(secret)
        val owner = Fixtures.primary(room, 5, 6)
        val binding = NativeKeeperBinding(room.roomId, creation.authority, owner.participant, owner.devicePubkey,
            RoomRoute.INTERNET, listOf("wss://fixture.invalid/"))
        val sourceStore = Store(); val receiverStore = Store()
        var vault = EpochVault(receiverStore).also { it.initialise(room.roomId, binding.authority, secret, test.currentTime / 1000) }
        val relay = FakeRelay()
        var live = newSession()
        var source = NativeKeeperJournal.create(sourceStore, binding, creation, owner.credential) { test.currentTime / 1000 }
        fun newSession(): RoomSession {
            val committed = vault.get(room.roomId)!!
            return test.session(room, owner, relay, authority = binding.authority,
                initialEpoch = deriveEpoch(RoomEpoch(committed.currentEpoch, committed.currentSecret)), initialRemoved = committed.removed,
                epochGate = { event, notice ->
                    if (notice.closed) vault.terminal(room.roomId, notice.epoch - 1, notice, event.id, test.currentTime / 1000)
                    else assertNotNull(vault.follow(room.roomId, notice, event.id, test.currentTime / 1000))
                    EpochGateResult.COMMITTED
                })
        }
        fun reopenSource() {
            source.close()
            source = NativeKeeperJournal.open(sourceStore, binding) { test.currentTime / 1000 }
        }
        suspend fun rekey(closed: Boolean = false): List<NostrEvent> {
            live.holdKeeperTransition(room.roomId, binding.authority, owner.participant, owner.devicePubkey)
            val events = source.prepareRekey(if (closed) emptyList() else listOf(owner.credential), closed = closed)
            live.applyKeeperRekey(events.single { it.kind == KIND_ROOM_REKEY }, owner.participant, owner.devicePubkey)
            for (event in events) {
                val offer = assertNotNull(source.reservePending(event.id, RekeyLane.INTERNET))
                assertTrue(source.canHandoff(offer)); relay.publish(event); source.offered(offer)
            }
            assertTrue(source.completePending(vault, live))
            return events
        }
        override fun close() { source.close(); live.leave(); secret.fill(0); invitation.bearer.fill(0) }
    }

    @Test fun retirementPreservesEpochCauseAcrossActualSourceReceiverAndSessionReopen() = runTest {
        Rig(this).use { r ->
            r.live.join(); runCurrent(); r.source.bind { true }
            assertEquals(KeeperPhase.ACTIVE, r.source.verifyReceiver(r.vault, r.live))
            val root = r.rekey().single(); runCurrent()
            assertEquals(root.id, r.source.snapshot().epochCause)
            val retirement = r.source.prepareRetirement()
            assertFails { r.source.verifyReceiver(r.vault, r.live) }
            val offer = assertNotNull(r.source.reservePending(retirement.id, RekeyLane.INTERNET))
            r.relay.publish(offer.event); r.source.offered(offer)
            assertTrue(r.source.completePending(r.vault, r.live))
            assertEquals(retirement.id, r.source.snapshot().cause)
            assertEquals(root.id, r.source.snapshot().epochCause)
            r.live.leave(); r.vault = EpochVault(r.receiverStore); r.live = r.newSession()
            r.live.join(); runCurrent(); r.reopenSource()
            assertTrue(r.source.snapshot().suspended)
            assertEquals(KeeperPhase.RETIRED, r.source.verifyReceiver(r.vault, r.live))
            assertEquals(root.id, r.vault.get(r.room.roomId)!!.activationCause)
            r.source.bind { true }
            val request = encodeEpochRequest(r.room.roomId, r.binding.authority, r.room.roomKey,
                r.owner.deviceSecretKey, r.owner.credential, currentTime / 1000)
            val answer = assertNotNull(r.source.answerEpoch(request, RekeyLane.INTERNET))
            assertTrue(r.source.canHandoff(answer))
            println("NATIVE_KEEPER_READINESS_MEASUREMENT " + buildJsonObject {
                put("case", "rekey-retirement-actual-owner-reopen"); put("epoch", 1); put("phase", "RETIRED")
                put("epochCause", root.id); put("lifecycleCause", retirement.id)
                put("receiverCause", r.vault.get(r.room.roomId)!!.activationCause); put("sourceWasSuspended", true)
                put("actualSessionReplaced", true); put("processDeath", false)
            })
        }
    }

    @Test fun missingOrForgedEpochCauseAndOlderSourceSchemaCannotInventReadiness() = runTest {
        Rig(this).use { r ->
            r.live.join(); runCurrent(); r.source.bind { true }; r.rekey(); r.source.close()
            val original = r.sourceStore.json()
            val mutations = listOf(
                JsonObject(original - "epochCause"),
                JsonObject(original + ("epochCause" to JsonNull)),
                JsonObject((original - setOf("epochCause", "terminalPredecessor")) + ("v" to JsonPrimitive(1))),
            )
            for (mutation in mutations) {
                r.sourceStore.replace(mutation); val bytes = r.sourceStore.bytes!!.clone()
                if (mutation.getValue("v").jsonPrimitive.int == 1)
                    assertFailsWith<NativeKeeperMigrationRequiredException> { NativeKeeperJournal.open(r.sourceStore, r.binding) { currentTime / 1000 } }
                else assertFails { NativeKeeperJournal.open(r.sourceStore, r.binding) { currentTime / 1000 } }
                assertContentEquals(bytes, r.sourceStore.bytes)
            }
            r.sourceStore.replace(JsonObject(original + ("epochCause" to JsonPrimitive("11".repeat(32)))))
            NativeKeeperJournal.open(r.sourceStore, r.binding) { currentTime / 1000 }.use { forged ->
                val before = r.sourceStore.bytes!!.clone()
                assertFails { forged.verifyReceiver(r.vault, r.live) }; assertTrue(forged.snapshot().suspended)
                assertContentEquals(before, r.sourceStore.bytes)
            }
            r.sourceStore.replace(original); r.source = NativeKeeperJournal.open(r.sourceStore, r.binding) { currentTime / 1000 }
            assertEquals(KeeperPhase.ACTIVE, r.source.verifyReceiver(r.vault, r.live))
        }
    }

    @Test fun exactReceiverKeyAndOwnerAreRequiredAndAnOngoingLocalBarrierCannotBecomeReady() = runTest {
        Rig(this).use { r ->
            r.live.join(); runCurrent()
            val receiver = r.receiverStore.json()
            fun mutate(field: String, value: JsonElement) = JsonObject(receiver + ("rooms" to JsonArray(
                receiver.getValue("rooms").jsonArray.map { JsonObject(it.jsonObject + (field to value)) })))
            // Storage schema and actual key decoding still run; no fake readiness Boolean.
            for ((field, value) in listOf("authority" to JsonPrimitive("11".repeat(32)),
                "activationCause" to JsonPrimitive("11".repeat(32)))) {
                r.receiverStore.replace(mutate(field, value)); assertFails { r.source.verifyReceiver(r.vault, r.live) }
            }
            r.receiverStore.replace(receiver)
            r.live.holdKeeperTransition(r.room.roomId, r.binding.authority, r.owner.participant, r.owner.devicePubkey)
            assertFails { r.source.verifyReceiver(r.vault, r.live) }
            assertTrue(r.source.snapshot().suspended)
            r.source.bind { true }; val pending = r.source.prepareRekey(listOf(r.owner.credential)).single()
            assertFails { r.source.verifyReceiver(r.vault, r.live) }
            assertEquals(listOf(pending), r.source.snapshot().pending)
        }
    }

    @Test fun committedClosureReopenChecksTheExactPredecessorKeyAndTerminalCauseWithoutGrantingHosting() = runTest {
        Rig(this).use { r ->
            r.live.join(); runCurrent(); r.source.bind { true }; r.rekey(); runCurrent()
            val previous = r.vault.get(r.room.roomId)!!
            val closing = r.rekey(closed = true).single { it.kind == KIND_ROOM_REKEY }; runCurrent()
            assertEquals(RoomEpochState.Closed(2), r.live.epochState.value)
            r.reopenSource(); r.vault = EpochVault(r.receiverStore)
            assertEquals(KeeperPhase.CLOSED, r.source.verifyReceiver(r.vault))
            assertEquals(KeeperPhase.CLOSED, r.source.verifyReceiver(r.vault, r.live))
            r.source.bind { true }
            assertFails { r.source.approve(r.owner.participant) }
            assertFails { r.source.prepareRekey(listOf(r.owner.credential)) }
            val receiver = r.receiverStore.json()
            for ((field, value) in listOf("terminalCause" to JsonPrimitive("11".repeat(32)),
                "currentSecret" to JsonPrimitive(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 22 })))) {
                r.receiverStore.replace(JsonObject(receiver + ("rooms" to JsonArray(receiver.getValue("rooms").jsonArray.map {
                    JsonObject(it.jsonObject + (field to value))
                }))))
                assertFails { r.source.verifyReceiver(r.vault) }
            }
            r.receiverStore.replace(receiver)
            val stored = r.vault.get(r.room.roomId)!!
            assertEquals(previous.currentEpoch, stored.currentEpoch); assertContentEquals(previous.currentSecret, stored.currentSecret)
            assertEquals(closing.id, stored.terminalCause)
            println("NATIVE_KEEPER_READINESS_MEASUREMENT " + buildJsonObject {
                put("case", "terminal-predecessor-cold-verification"); put("sourceEpoch", 2); put("receiverEpoch", 1)
                put("terminalCause", closing.id); put("predecessorKeyMatches", true); put("newHostingAllowed", false)
                put("processDeath", false)
            })
        }
    }
}
