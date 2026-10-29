package dev.forgesworn.kithmoot.lab

import dev.forgesworn.kithmoot.BuildConfig
import okhttp3.*
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer

/** Initial logical HTTP attempt, matching browser CDP scope, NOT sent bytes or bandwidth.
 * Application interceptor precedes cache/connect. EventListeners and inherited clones stay intact. */
internal object MetadataHttp {
    fun builder(): OkHttpClient.Builder = install(OkHttpClient.Builder())
    fun client(): OkHttpClient = builder().build()
    fun install(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        if (BuildConfig.DEBUG && BuildConfig.LAB_METADATA_OBSERVER && builder.interceptors().none { it is Capture }) builder.addInterceptor(Capture())
        return builder
    }
    private class Capture : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val attempt = MetadataObserver.attempt() ?: return chain.proceed(chain.request())
            val lease = attempt.lease
            if (lease == null) return try { chain.proceed(chain.request()) } finally { attempt.close() }
            val request = chain.request()
            val url = request.url.toString()
            val handshake = request.header("Upgrade")?.equals("websocket",true) == true
            val surface = if (handshake) "websocket-handshake-extra" else "external-http-attempt"
            lease.safe { session ->
                val origin = MetadataPrimitives.origin(url)
                fun record(value: String, kind: String) = session.observe("out", "app-page", "other", origin, null,
                    "http-value", value.toByteArray(Charsets.UTF_8), surface, kind)
                record(url,"request-url")
                for (i in 0 until request.headers.size) { record(request.headers.name(i),"request-header-name"); record(request.headers.value(i),"request-header-value") }
            }
            val body = request.body
            var writes = 0
            val wrapped = if (body == null || handshake || body.isDuplex()) request else request.newBuilder().method(request.method, object : RequestBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun isOneShot() = body.isOneShot()
                override fun isDuplex() = body.isDuplex()
                override fun writeTo(sink: BufferedSink) {
                    writes++
                    var stream: MetadataSession.Stream? = null
                    lease.safe { stream = it.stream("out", url, "external-http-body-actual", "request-body-write") }
                    val tee = object : ForwardingSink(sink) {
                        override fun write(source: Buffer, byteCount: Long) {
                            // Count only chunks the real sink accepted. If a chunk partly fails,
                            // its unknown prefix is NOT invented; finish(false) invalidates that value.
                            var remaining = byteCount
                            while (remaining > 0) {
                                val count = minOf(8192L, remaining)
                                var bytes: ByteArray? = null
                                lease.safe { val copy = Buffer(); source.copyTo(copy,0,count); bytes = copy.readByteArray() }
                                try { super.write(source,count); bytes?.let { captured -> lease.safe { stream?.write(captured) } } }
                                finally { bytes?.fill(0) }
                                remaining -= count
                            }
                        }
                    }.buffer()
                    var complete = false
                    try { body.writeTo(tee); tee.emit(); complete = true }
                    finally { lease.finish(stream,complete) }
                }
            }).build()
            if (body?.isDuplex() == true) lease.safe { it.gap() } // Leave async duplex behavior completely untouched.
            try {
                val response = chain.proceed(wrapped)
                lease.safe {
                    if (writes > 1 || response.priorResponse != null || response.request.url != request.url) it.gap()
                    it.lifecycle(surface, if (response.networkResponse != null) "network-response" else if (response.cacheResponse != null) "cache-hit" else "response-origin-unproven",url)
                }
                // Keep the exact original response/body and its cancellation/lazy-consumption behavior.
                // Incoming HTTP response bodies are outside this observer's shared outgoing-value scope.
                return response
            } catch (failure: Throwable) {
                // A transport fault is not an observer fault. Preserve its exact instance.
                lease.safe { it.lifecycle(surface,"transport-failed",url) }; throw failure
            } finally {
                if (body != null && writes == 0 && !body.isDuplex()) lease.safe { it.gap(); it.lifecycle(surface,"body-not-written",url) }
                // Outgoing-attempt scope ends at response headers or the original transport failure.
                // Response-body consumption is deliberately outside this observer.
                attempt.close()
            }
        }
    }
}
