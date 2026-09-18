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
// VerifyNoMediapipeTelemetryTask in app/build.gradle.kts for the guard that
// keeps it that way.
//
// Do not add real behaviour to this package. If a future MediaPipe upgrade
// needs a method or class this file does not provide, the build fails with a
// clear NoSuchMethodError/NoClassDefFoundError rather than silently phoning
// home again -- extend the stub, don't restore the real dependency.
//
// This package turned out not to be MediaPipe-only: `play-services-mlkit-
// barcode-scanning` (the QR sign-in/pairing decoder) also depends on the
// real `com.google.android.datatransport`, for its own usage-telemetry
// client (decompiled classes `com.google.android.gms.internal.mlkit_vision_
// barcode.zzwt`..`zzwx`). Android dexing puts every dependency's classes in
// one flat namespace, so these stubs and a real datatransport cannot both be
// on the classpath under the same fully qualified names -- either every
// caller gets the real classes (MediaPipe leaks telemetry again) or every
// caller gets these no-ops. `app/build.gradle.kts` excludes the group from
// both dependencies for that reason. See docs/effects-no-telemetry.md.
package com.google.android.datatransport

/** Stands in for `com.google.android.datatransport.Event`. */
class Event private constructor() {
    companion object {
        @JvmStatic
        fun ofData(data: Any?): Event = Event()

        // Used by ML Kit's telemetry client, not by MediaPipe. See the file
        // header for why it lives here regardless.
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
