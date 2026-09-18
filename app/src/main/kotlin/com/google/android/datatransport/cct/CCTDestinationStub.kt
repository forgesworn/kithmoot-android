// Local no-op stand-in for `com.google.android.datatransport.cct.CCTDestination`.
// See DatatransportStubs.kt for why this package exists at all.
package com.google.android.datatransport.cct

import com.google.android.datatransport.runtime.Destination

/** Stands in for `com.google.android.datatransport.cct.CCTDestination`. */
class CCTDestination private constructor() : Destination {
    // ML Kit's telemetry client (not MediaPipe's) calls this to pick an
    // encoding. An empty set makes every `contains(...)` check false, which
    // just steers it down one harmless branch -- it never throws.
    fun getSupportedEncodings(): Set<com.google.android.datatransport.Encoding> = emptySet()

    companion object {
        // MediaPipe's RemoteLoggingClient reads this as a plain static field
        // (`CCTDestination.INSTANCE`), not through a getter, so it must be
        // `@JvmField` rather than a companion `val`.
        @JvmField
        val INSTANCE: CCTDestination = CCTDestination()
    }
}
