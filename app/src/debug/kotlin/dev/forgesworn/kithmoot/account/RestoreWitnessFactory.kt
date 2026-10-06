package dev.forgesworn.kithmoot.account

import android.content.Context

/**
 * Debug builds carry the VMLS engine, so they get the "Restore witness"
 * screen: a coordinated MLS vault whose personas reach their box through
 * their own Link engines.
 */
fun restoreWitness(context: Context): RestoreWitness? {
    val core = EngineCore.of(context)
    return RestoreWitness(core.vault, core.links, core.quiet, core.scope)
}
