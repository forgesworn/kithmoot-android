package dev.forgesworn.kithmoot.account

import android.content.Context
import dev.forgesworn.kithmoot.KithMootApplication

/**
 * Debug builds carry the one coordinated vault (P3-03b-3), so an account change
 * bumps its durable session epoch. The epoch alone moves the generation: no
 * separate app generation is kept.
 */
fun vaultSessionEnd(context: Context): VaultSessionEnd {
    val bump = vaultSessionEnd { EngineCore.of(context).vault }
    return VaultSessionEnd {
        // Bumped first, so the ask withdrawn below cannot be answered into this session.
        try { bump.end() } finally { (context.applicationContext as KithMootApplication).vmlsBoxes?.sessionEnded() }
    }
}

internal fun vaultSessionEnd(vault: () -> MlsVault): VaultSessionEnd = VaultSessionEnd { vault().bump() }
