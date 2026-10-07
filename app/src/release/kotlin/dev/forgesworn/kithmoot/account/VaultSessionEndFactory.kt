package dev.forgesworn.kithmoot.account

import android.content.Context

/** Release builds carry no vault until the VMLS engine's independent review (vennel D1): nothing to end. */
@Suppress("UNUSED_PARAMETER")
fun vaultSessionEnd(context: Context): VaultSessionEnd = VaultSessionEnd.None
