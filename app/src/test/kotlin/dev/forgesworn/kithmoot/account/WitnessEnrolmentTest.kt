package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.relay.StoredLinkRoute
import java.security.SecureRandom
import java.util.Base64
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The keeper's enrolment of a persona (P3-03b-2 PR 5, B3): the writer minted
 * before pairing, the pairing kept in the persona's file, genesis pinned to
 * the paired box, and the enrol line the keeper runs.
 */
class WitnessEnrolmentTest {
    private val random = SecureRandom()
    private lateinit var stores: MemoryCoordinatedStores
    private lateinit var server: FakeWitnessServer
    private lateinit var witness: FakeVaultWitness
    private lateinit var vault: MlsVault
    private val persona = "ab".repeat(32)
    /** The seed each pairing was handed, as the persona's Link engine would get it. */
    private val seeds = mutableListOf<ByteArray>()

    @BeforeTest fun setup() {
        stores = MemoryCoordinatedStores()
        server = FakeWitnessServer(BOX)
        witness = FakeVaultWitness()
        vault = vault()
    }

    private fun vault() = MlsVault.coordinated(VaultCoordination(stores, WitnessChannels { _, _, _ -> server.channel }, witness), now = { NOW })

    private suspend fun pair(v: MlsVault = vault): VaultResult<Unit> = v.pairWitness(persona) { seed ->
        seeds += seed.copyOf()
        StoredLinkRoute("witness-test", CARD.copyOf(), ByteArray(32).also(random::nextBytes), 1uL, NOW.toULong())
    }

    // ---- the writer's identity ----

    @Test fun `a writer's node id is the Ed25519 public key of its seed (RFC 8032 test 1)`() {
        val seed = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60".hexToBytes()
        assertEquals("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", WriterIdentity.nodeId(seed).toHex())
    }

    @Test fun `the box's node id comes from the paired card, and a tampered card gives none`() {
        val route = StoredLinkRoute("witness-test", CARD.copyOf(), ByteArray(32), 1uL, NOW.toULong())
        assertEquals(BOX.toHex(), WriterIdentity.boxNodeId(route)!!.toHex())
        val tampered = CARD.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        assertNull(WriterIdentity.boxNodeId(route.copy(card = tampered)))
    }

    // ---- the steps ----

    @Test fun `prepare mints the writer before any pairing, and keeps it`() = runBlocking<Unit> {
        assertEquals(WitnessEnrolment.None, vault.witnessEnrolment(persona))
        vault.prepareCoordination(persona)
        val prepared = assertIs<WitnessEnrolment.Prepared>(vault.witnessEnrolment(persona))
        assertTrue(prepared.writer.matches(Regex("[0-9a-f]{64}")))
        vault().prepareCoordination(persona)
        assertEquals(prepared, vault().witnessEnrolment(persona))
    }

    @Test fun `pairing hands the writer's own seed to the engine and keeps the route`() = runBlocking<Unit> {
        assertEquals(VaultResult.Ok(Unit), pair())
        val paired = assertIs<WitnessEnrolment.Paired>(vault().witnessEnrolment(persona))
        assertEquals(BOX.toHex(), paired.box)
        assertEquals(WriterIdentity.nodeId(seeds.single()).toHex(), paired.writer)
    }

    @Test fun `a failed pairing changes nothing`() = runBlocking<Unit> {
        vault.prepareCoordination(persona)
        val before = vault.witnessEnrolment(persona)
        assertFailsWith<IllegalStateException> { vault.pairWitness(persona) { throw IllegalStateException("pairing refused") } }
        assertEquals(before, vault().witnessEnrolment(persona))
    }

    @Test fun `genesis pins the paired box and the enrol line names this writer`() = runBlocking<Unit> {
        pair()
        val writer = (vault.witnessEnrolment(persona) as WitnessEnrolment.Paired).writer
        val genesis = (vault.beginCoordination(persona, SUBJECT) as VaultResult.Ok).value
        val enrolled = assertIs<WitnessEnrolment.Enrolled>(vault().witnessEnrolment(persona))
        assertEquals(
            "bothyd witness enrol --subject ${SUBJECT.toHex()} --installation ${genesis.installation} " +
                "--writer $writer --initial-digest ${genesis.initialDigest}",
            enrolled.line,
        )
        assertEquals(BOX.toHex(), enrolled.box)
        // The marker carries only what the box already knows.
        assertTrue(String(stores.marker()!!).contains(writer))
        assertFalse(String(stores.marker()!!).contains(persona))
    }

    @Test fun `genesis is refused before pairing, and for a key other than the paired box's`() = runBlocking<Unit> {
        vault.prepareCoordination(persona)
        assertEquals(VaultResult.Refused(VaultRefusal.Unauthorised), vault.beginCoordination(persona, SUBJECT))
        pair()
        assertEquals(VaultResult.Refused(VaultRefusal.Unauthorised), vault.beginCoordination(persona, SUBJECT, ByteArray(32) { 7 }))
        assertIs<WitnessEnrolment.Paired>(vault.witnessEnrolment(persona))
    }

    @Test fun `pairing again after genesis is refused`() = runBlocking<Unit> {
        pair()
        vault.beginCoordination(persona, SUBJECT)
        assertEquals(VaultResult.Refused(VaultRefusal.Unauthorised), pair())
        assertEquals(1, seeds.size)
    }

    @Test fun `an interrupted genesis is retried with the same writer and pairing, but a fresh installation`() = runBlocking<Unit> {
        pair()
        val writer = (vault.witnessEnrolment(persona) as WitnessEnrolment.Paired).writer
        stores.failNextCoordinatedWrite = true
        assertFailsWith<MlsVaultUnavailableException> { vault.beginCoordination(persona, SUBJECT) }
        val v = vault()
        assertEquals(WitnessEnrolment.Paired(writer, BOX.toHex()), v.witnessEnrolment(persona))
        // Its subject was tombstoned; a fresh one enrols under a fresh installation.
        assertEquals(VaultResult.Refused(VaultRefusal.Unauthorised), v.beginCoordination(persona, SUBJECT))
        val again = (v.beginCoordination(persona, OTHER_SUBJECT) as VaultResult.Ok).value
        val line = (v.witnessEnrolment(persona) as WitnessEnrolment.Enrolled).line!!
        assertTrue("--writer $writer " in line)
        assertTrue("--installation ${again.installation} " in line)
    }

    @Test fun `after a fence and the keeper's retire, enrolment starts afresh with a new writer`() = runBlocking<Unit> {
        pair()
        val writer = (vault.witnessEnrolment(persona) as WitnessEnrolment.Paired).writer
        vault.beginCoordination(persona, SUBJECT)
        server.mode = FakeWitnessServer.Mode.Down
        vault.clear(persona)
        val fenced = assertIs<WitnessEnrolment.Fenced>(vault().witnessEnrolment(persona))
        assertEquals(SUBJECT.toHex(), fenced.subject)
        assertEquals("bothyd witness retire --subject ${SUBJECT.toHex()}", PersonaCoordination.retireLine(fenced.subject!!))
        assertEquals(VaultResult.Ok(Unit), vault().keeperConfirmsRetired(persona, SUBJECT.toHex()))
        assertEquals(WitnessEnrolment.None, vault().witnessEnrolment(persona))
        pair(vault())
        val fresh = assertIs<WitnessEnrolment.Paired>(vault().witnessEnrolment(persona))
        assertNotEquals(writer, fresh.writer)
    }

    /**
     * A profile and Keystore restored or copied from after pairing but before
     * the genesis state write carries an enrolled writer (and, before the
     * marker, its installation) into a new subject. The box refuses that enrol
     * line, so this phone is only ever refused; replacing it leaves nothing of
     * the old writer or installation.
     */
    @Test fun `a genesis the box never enrolled is replaced by a new writer and installation`() = runBlocking<Unit> {
        pair()
        val writer = (vault.witnessEnrolment(persona) as WitnessEnrolment.Paired).writer
        val first = (vault.beginCoordination(persona, SUBJECT) as VaultResult.Ok).value
        // The keeper's enrol was refused: the box never knows the subject.
        assertEquals(CoordinationStatus.Pending(refused = true), vault().coordinationStatus(persona))
        vault().clear(persona)
        val fenced = assertIs<WitnessEnrolment.Fenced>(vault().witnessEnrolment(persona))
        assertEquals(SUBJECT.toHex(), fenced.subject)
        // Nothing to retire at the box, and no retiring duty holds the keeper's word back.
        assertEquals(VaultResult.Ok(Unit), vault().keeperConfirmsRetired(persona, SUBJECT.toHex()))
        pair(vault())
        val fresh = assertIs<WitnessEnrolment.Paired>(vault().witnessEnrolment(persona))
        assertNotEquals(writer, fresh.writer)
        val again = (vault().beginCoordination(persona, OTHER_SUBJECT) as VaultResult.Ok).value
        assertNotEquals(first.installation, again.installation)
        server.enrol(OTHER_SUBJECT.toHex(), again.initialDigest)
        assertEquals(CoordinationStatus.Active, vault().coordinationStatus(persona))
    }

    @Test fun `clearing during an interrupted enrolment supersedes it rather than fencing a missing file`() = runBlocking<Unit> {
        pair()
        stores.failNextCoordinatedWrite = true
        assertFailsWith<MlsVaultUnavailableException> { vault.beginCoordination(persona, SUBJECT) }
        vault().clear(persona)
        assertEquals(WitnessEnrolment.None, vault().witnessEnrolment(persona))
        assertEquals(CoordinationStatus.NotEnrolled, vault().coordinationStatus(persona))
        // Its subject stays a tombstone, never reused.
        pair(vault())
        assertEquals(VaultResult.Refused(VaultRefusal.Unauthorised), vault().beginCoordination(persona, SUBJECT))
    }

    // ---- the banner never enrols by looking ----

    @Test fun `asking whether a persona is known, and the foreground duty, create nothing`() = runBlocking<Unit> {
        assertFalse(vault.coordinationKnown(persona))
        assertTrue(vault.runRetiringDuties().isEmpty())
        assertTrue(stores.names().isEmpty())
        vault.prepareCoordination(persona)
        assertTrue(vault().coordinationKnown(persona))
        assertFalse(vault().coordinationKnown("cd".repeat(32)))
    }

    @Test fun `the banner asks for the persona's own file and leaves a corrupt index alone`() = runBlocking<Unit> {
        vault.prepareCoordination(persona)
        val index = stores.names().single { it.endsWith(MlsVault.INDEX) }
        stores.corrupt(index)
        val before = stores.sealed(index)!!.value.copyOf()
        val v = vault()
        assertTrue(v.coordinationKnown(persona))
        assertFalse(v.coordinationKnown("cd".repeat(32)))
        assertTrue(before.contentEquals(stores.sealed(index)!!.value))
    }

    @Test fun `the banner speaks only for a persona the witness does not confirm`() {
        assertNull(witnessBanner(null))
        assertNull(witnessBanner(CoordinationStatus.NotEnrolled))
        assertNull(witnessBanner(CoordinationStatus.Active))
        val pending = witnessBanner(CoordinationStatus.Pending(refused = false))!!
        assertEquals("Your vault is waiting for your box.", pending.message)
        assertEquals("Changes to your vault wait until your box confirms them.", pending.detail)
        assertEquals("Your box refused this phone. Its keeper should check the enrolment.", witnessBanner(CoordinationStatus.Pending(refused = true))!!.detail)
        assertEquals("Your vault on this phone is fenced.", witnessBanner(CoordinationStatus.Fenced("cleared", null))!!.message)
    }

    companion object {
        const val NOW = 1_795_305_600L
        /** A Link card signed by [BOX], valid at [NOW] (the BothyPairing fixture). */
        val CARD: ByteArray = Base64.getUrlDecoder().decode(
            "RlNMMQE7QxbPz6sql5G7uJvBXiGyBTGqAHYfK7YUH2oj1INlFwAAAABrAjBEAAAAAGsKGYAAAAAAAAAACQEBABd3c3M6Ly9yZWxheS5leGFtcGxlLm9yZxVcqlwx4xd2X9Xx3ZAT0uArQvPdhySJcVf9dgdBpG98V-80Zx2UVa5AU_NqyOizaBZx9jFaO_5pZ6Mi1iLFeAY",
        )
        val BOX: ByteArray = "3b4316cfcfab2a9791bbb89bc15e21b20531aa00761f2bb6141f6a23d4836517".hexToBytes()
        val SUBJECT = ByteArray(32) { 1 }
        val OTHER_SUBJECT = ByteArray(32) { 2 }
    }
}
