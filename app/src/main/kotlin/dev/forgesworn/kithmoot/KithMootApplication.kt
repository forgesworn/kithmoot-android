package dev.forgesworn.kithmoot

import android.app.Application

/**
 * Exists so the process has a name in a stack trace and somewhere to put
 * anything that must outlive an activity. Deliberately empty otherwise - a room
 * belongs to the view model that opened it, not to the process.
 */
class KithMootApplication : Application()
