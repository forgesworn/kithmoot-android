// Local no-op stand-ins for Google's `com.google.android.datatransport` API.
//
// MediaPipe's tasks-core (the background-replacement segmenter, see
// media/effects/PersonSegmenter.kt) links against these types from its own
// compiled RemoteLoggingClient class to report usage statistics to Google.
// app/build.gradle.kts excludes the real `com.google.android.datatransport:*`
// artifacts, so these classes exist purely so that MediaPipe's bytecode still
// resolves and constructs without a NoClassDefFoundError. Every method here
// is a deliberate no-op: nothing is ever sent anywhere. See
// docs/effects-no-telemetry.md for the full account and
// VerifyNoDatatransportTelemetryTask in app/build.gradle.kts for the guard that
// keeps it that way.
//
// Do not add real behaviour to this package. If a future MediaPipe upgrade
// needs a method or class this file does not provide, the build fails with a
// clear NoSuchMethodError/NoClassDefFoundError rather than silently phoning
// home again -- extend the stub, don't restore the real dependency.
//
// ML Kit barcode scanning used these stand-ins too until the QR scanner moved
// to the vendored ZXing reader (docs/third-party/zxing.md). The members only
// ML Kit called (`Event.ofTelemetry`, `CCTDestination.getSupportedEncodings`) are still
// no-ops and can go once nothing references them.
package com.google.android.datatransport

/** Stands in for `com.google.android.datatransport.Event`. */
class Event private constructor() {
    companion object {
        @JvmStatic
        fun ofData(data: Any?): Event = Event()

        // Was used by ML Kit's telemetry client, not by MediaPipe. See the
        // file header.
        @JvmStatic
        fun ofTelemetry(data: Any?): Event = Event()
    }
}

/** Stands in for `com.google.android.datatransport.Encoding`. */
class Encoding private constructor() {
    companion object {
        @JvmStatic
        fun of(name: String): Encoding = Encoding()
    }
}

/** Stands in for `com.google.android.datatransport.Transformer`. */
fun interface Transformer {
    fun apply(input: Any?): Any?
}

/** Stands in for `com.google.android.datatransport.Transport`. `send` is a no-op. */
interface Transport {
    fun send(event: Event)
}

/** Stands in for `com.google.android.datatransport.TransportFactory`. */
interface TransportFactory {
    fun getTransport(name: String, payloadType: Class<*>, encoding: Encoding, transformer: Transformer): Transport
}

internal object NoOpTransport : Transport {
    override fun send(event: Event) {
        // Deliberately does nothing: see file header.
    }
}

internal object NoOpTransportFactory : TransportFactory {
    override fun getTransport(name: String, payloadType: Class<*>, encoding: Encoding, transformer: Transformer): Transport =
        NoOpTransport
}
