package dev.forgesworn.kithmoot.account

/** Debug builds carry the VMLS engine, so the vault is coordinated by it. */
fun vaultWitness(): VaultWitness = EngineVaultWitness()
