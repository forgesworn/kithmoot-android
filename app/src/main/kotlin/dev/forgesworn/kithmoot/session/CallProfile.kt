package dev.forgesworn.kithmoot.session

/**
 * Which call signalling profile this build speaks, and the one switch that
 * decides whether it says so.
 *
 * Profile 2 is the call reliability spec: fixed media slots, a reliable
 * signalling channel with generations, and per-pair health. Profile 1 is what
 * every client shipped before it speaks, and what this one still speaks to any
 * far end that has not said otherwise.
 */

/** The roster's `callProfile` value that means fixed slots, reliable
 *  signalling and pair health. Only the exact number counts. */
const val CALL_PROFILE_2: Int = 2

/**
 * The one switch, and it is off.
 *
 * Off, nothing changes on the wire for anybody: this device's roster entry
 * carries no `callProfile`, so no far end will open a profile-2 pair with it,
 * and this device opens none either. On, it advertises profile 2 and uses it
 * with any pair whose far end advertises it too - both ends, never one.
 *
 * A build-time constant rather than a setting because it must be impossible to
 * reach halfway: a device that advertised the profile and then could not
 * carry it would leave every pair it touched waiting for an offer that was
 * never coming.
 */
const val CALL_PROFILE_2_ENABLED: Boolean = false
