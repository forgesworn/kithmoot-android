package dev.forgesworn.kithmoot.account

/**
 * Release builds carry no VMLS engine until its independent review (vennel
 * D1), so nothing covered is ever written: there is no unwitnessed mode.
 */
fun vaultWitness(): VaultWitness = NoVaultWitness
