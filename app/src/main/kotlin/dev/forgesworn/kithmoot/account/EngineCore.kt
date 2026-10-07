package dev.forgesworn.kithmoot.account

import android.content.Context
import dev.forgesworn.kithmoot.relay.ReflectiveLinkTransportRuntime
import dev.forgesworn.kithmoot.storage.AndroidMlsVaultStores
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The engine's one coordinated vault per process, shared by the
 * restore witness and VMLS rooms (P3-03b-3): two vaults over the same stores
 * would each hold a persona's lock and witness alone. [quiet] is true while
 * a Tor-only room is open, and stops witness and VMLS traffic alike (C7).
 */
class EngineCore private constructor(val vault: MlsVault, val links: PersonaLinks, val quiet: AtomicBoolean, val scope: CoroutineScope) {
    companion object {
        @Volatile private var built: EngineCore? = null

        @Synchronized fun of(context: Context): EngineCore = built ?: run {
            val quiet = AtomicBoolean(false)
            val links = PersonaLinks(ReflectiveLinkTransportRuntime(), quiet = quiet::get)
            val vault = MlsVault.coordinated(VaultCoordination(AndroidMlsVaultStores(context.applicationContext), links))
            EngineCore(vault, links, quiet, CoroutineScope(SupervisorJob() + Dispatchers.IO)).also { built = it }
        }
    }
}
