package dev.forgesworn.kithmoot.account

import android.content.Context

/**
 * Every build carries the VMLS engine, so every build gets the "Restore witness"
 * screen: a coordinated MLS vault whose personas reach their box through
 * their own Link engines.
 */
fun restoreWitness(context: Context): RestoreWitness? {
    val core = EngineCore.of(context)
    return RestoreWitness(core.vault, core.links, core.quiet, core.scope)
}
