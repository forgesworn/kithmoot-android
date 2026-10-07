package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.relay.LinkTransportRuntime
import dev.forgesworn.kithmoot.relay.LinkTransportSession
import dev.forgesworn.kithmoot.relay.LinkTransportState
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking

/** The foreground tick reads the witness afresh at a modest interval, not only while a retiring duty stands (D1 C2). */
class RestoreWitnessTickTest {
    private val principal = "dev.forgesworn.kithmoot"
    private val random = SecureRandom()
    private var clock = 1_793_577_600L

    private fun bytes(n: Int) = ByteArray(n).also(random::nextBytes)

    @Test fun `the banner is read from the witness afresh every ten minutes, and from memory between`() = runBlocking<Unit> {
        val server = FakeWitnessServer(bytes(32))
        val stores = MemoryCoordinatedStores()
        val vault = MlsVault.coordinated(VaultCoordination(stores, WitnessChannels { _, _, _ -> server.channel }, FakeVaultWitness()), now = { clock })
        var key = bytes(32)
        while (runCatching { Schnorr.publicKey(key) }.isFailure) key = bytes(32)
        val persona = Schnorr.publicKey(key).toHex()
        val subject = bytes(32)
        val genesis = (vault.beginCoordination(persona, subject, server.key) as VaultResult.Ok).value
        server.enrol(genesis.subject, genesis.initialDigest)
        assertEquals(CoordinationStatus.Active, vault.coordinationStatus(persona, check = true))
        val links = PersonaLinks(object : LinkTransportRuntime {
            override fun start(state: LinkTransportState): LinkTransportSession = error("The tick opens no Link session")
        }, quiet = { false })
        val tick = RestoreWitness(vault, links, AtomicBoolean(false), CoroutineScope(Job()), now = { clock })

        val start = server.requests
        tick.foregroundTick(persona)
        assertEquals(CoordinationStatus.Active, tick.banner.value)
        assertEquals(start + 1, server.requests, "the first tick asks the witness")

        // The witness retires the subject; a tick soon after answers from memory.
        server.subjects.getValue(genesis.subject).retired = true
        clock += 60
        tick.foregroundTick(persona)
        assertEquals(CoordinationStatus.Active, tick.banner.value)
        assertEquals(start + 1, server.requests)

        clock += RestoreWitness.WITNESS_READ_INTERVAL_SECONDS
        tick.foregroundTick(persona)
        assertIs<CoordinationStatus.Fenced>(tick.banner.value)
    }
}
