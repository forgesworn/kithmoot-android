package dev.forgesworn.kithmoot.account

/** Every build carries the VMLS engine, so the vault is coordinated by it. */
fun vaultWitness(): VaultWitness = EngineVaultWitness()
