package dev.forgesworn.kithmoot.account

import android.content.Context
import dev.forgesworn.kithmoot.relay.ReflectiveLinkTransportRuntime
import dev.forgesworn.kithmoot.storage.AndroidMlsVaultStores
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Debug builds carry the VMLS engine, so they get the "Restore witness"
 * screen: a coordinated MLS vault whose personas reach their box through
 * their own Link engines.
 */
fun restoreWitness(context: Context): RestoreWitness? {
    val quiet = AtomicBoolean(false)
    val links = PersonaLinks(ReflectiveLinkTransportRuntime(), quiet = quiet::get)
    val vault = MlsVault.coordinated(VaultCoordination(AndroidMlsVaultStores(context), links))
    return RestoreWitness(vault, links, quiet, CoroutineScope(SupervisorJob() + Dispatchers.IO))
}
