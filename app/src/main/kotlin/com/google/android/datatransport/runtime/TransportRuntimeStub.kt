// Local no-op stand-ins for `com.google.android.datatransport.runtime`.
// See DatatransportStubs.kt (in the parent package) for why this package
// exists at all: it satisfies MediaPipe's compiled RemoteLoggingClient
// bytecode without pulling in Google's real telemetry runtime.
package com.google.android.datatransport.runtime

import android.content.Context
import com.google.android.datatransport.NoOpTransportFactory
import com.google.android.datatransport.TransportFactory

/** Stands in for `com.google.android.datatransport.runtime.Destination`. */
interface Destination

/** Stands in for `com.google.android.datatransport.runtime.TransportRuntime`. */
class TransportRuntime private constructor() {
    fun newFactory(destination: Destination): TransportFactory = NoOpTransportFactory

    companion object {
        private val instance = TransportRuntime()

        @JvmStatic
        fun initialize(context: Context) {
            // Deliberately does nothing: there is no real transport to set up.
        }

        @JvmStatic
        fun getInstance(): TransportRuntime = instance
    }
}
