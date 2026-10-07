package dev.forgesworn.kithmoot.account

import android.content.Context

/**
 * Debug builds carry the one coordinated vault (P3-03b-3), so an account change
 * bumps its durable session epoch. The epoch alone moves the generation: no
 * separate app generation is kept.
 */
fun vaultSessionEnd(context: Context): VaultSessionEnd = vaultSessionEnd { EngineCore.of(context).vault }

internal fun vaultSessionEnd(vault: () -> MlsVault): VaultSessionEnd = VaultSessionEnd { vault().bump() }
