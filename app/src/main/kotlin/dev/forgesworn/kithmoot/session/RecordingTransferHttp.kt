package dev.forgesworn.kithmoot.session

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Recording bodies can still be draining through a slow proxy after the
 * last socket write. Keep a bounded idle allowance as well as a total limit;
 * a ten-minute call timeout alone leaves OkHttp's ten-second idle defaults.
 * Apply the same policy to an injected TLS client without changing its trust.
 */
internal fun recordingTransferClient(client: OkHttpClient = OkHttpClient()): OkHttpClient = client.newBuilder()
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
    .readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS)
    .callTimeout(10, TimeUnit.MINUTES).build()

internal val recordingTransferHttp = recordingTransferClient()
