package dev.forgesworn.kithmoot.media.privacy

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Base64
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.forgesworn.kithmoot.session.AttachmentCleaner
import dev.forgesworn.kithmoot.session.AttachmentCleaningFailed
import dev.forgesworn.kithmoot.session.CleanedAttachment
import dev.forgesworn.kithmoot.session.MAX_CLEAN_ATTACHMENT_BYTES
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

/** Runs the pinned shared cleaner in an offline renderer. It receives one private source and a result port,
 * never a Java object, room identity, account key, provider URI or network connection. */
class LocalAttachmentCleaner(context: Context) : AttachmentCleaner {
    private val context = context.applicationContext

    override suspend fun clean(source: File, destination: File, name: String, type: String?): CleanedAttachment =
        mutex.withLock {
            try { withTimeout(90_000) { withContext(Dispatchers.Main.immediate) { rewrite(source, destination, name, type) } } }
            catch (_: TimeoutCancellationException) { throw AttachmentCleaningFailed() }
        }

    @SuppressLint("SetJavaScriptEnabled") // Only the pinned bundled script runs; every resource is intercepted below.
    private suspend fun rewrite(source: File, destination: File, name: String, type: String?): CleanedAttachment {
        val origin = "https://${UUID.randomUUID()}.metadata.kithmoot.invalid"
        val html = """<!doctype html><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'self'; connect-src 'self'; img-src blob: data:; object-src 'none'; base-uri 'none'; form-action 'none'"><script src="/cleaner.js" defer></script>"""
        val result = CompletableDeferred<CleanedAttachment>()
        val output = destination.outputStream()
        var expected = 0L
        var written = 0L
        var resultName = ""
        var resultType = ""
        var began = false
        var launched = false
        var ports: Array<WebMessagePort>? = null
        val view = try { WebView(context) } catch (_: Exception) { output.close(); throw AttachmentCleaningFailed() }
        try {
            view.settings.apply {
                javaScriptEnabled = true
                blockNetworkLoads = true
                allowFileAccess = false
                allowContentAccess = false
                domStorageEnabled = false
                databaseEnabled = false
                setGeolocationEnabled(false)
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            view.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                    request.url.toString() != "$origin/" || launched
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val url = request.url.toString()
                    val allowed = request.method == "GET" && url in setOf("$origin/", "$origin/cleaner.js", "$origin/source")
                    if (!allowed) return response("text/plain", ByteArrayInputStream(byteArrayOf()), 403)
                    return try {
                        if (url == "$origin/") response("text/html", ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
                        else if (url.endsWith("/cleaner.js")) response("application/javascript", context.assets.open("attachment-cleaner/cleaner.js"))
                        else response("application/octet-stream", source.inputStream())
                    } catch (_: Exception) { response("text/plain", ByteArrayInputStream(byteArrayOf()), 404) }
                }
                override fun onPageFinished(web: WebView, url: String) {
                    if (launched || url != "$origin/") return
                    launched = true
                    val pair = web.createWebMessageChannel().also { ports = it }
                    pair[0].setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
                        override fun onMessage(port: WebMessagePort, message: WebMessage) {
                            if (result.isCompleted) return
                            try {
                                val raw = message.data ?: throw AttachmentCleaningFailed()
                                require(raw.length <= 50_000)
                                val value = JSONObject(raw)
                                when (value.getString("status")) {
                                    "begin" -> {
                                        require(!began)
                                        expected = value.getLong("bytes")
                                        require(expected in 1..MAX_CLEAN_ATTACHMENT_BYTES)
                                        resultName = value.getString("name").also { require(it.length <= 4096) }
                                        resultType = value.getString("type").also { require(it.length <= 255) }
                                        began = true
                                    }
                                    "chunk" -> {
                                        require(began && value.getLong("offset") == written)
                                        val bytes = Base64.decode(value.getString("data"), Base64.NO_WRAP)
                                        try {
                                            require(bytes.size in 1..32768 && written + bytes.size <= expected)
                                            output.write(bytes)
                                            written += bytes.size
                                        } finally { bytes.fill(0) }
                                        port.postMessage(WebMessage("ack"))
                                    }
                                    "complete" -> {
                                        require(began && written == expected)
                                        output.flush()
                                        result.complete(CleanedAttachment(resultName, resultType, written))
                                    }
                                    else -> throw AttachmentCleaningFailed()
                                }
                            } catch (_: Exception) { result.completeExceptionally(AttachmentCleaningFailed()) }
                        }
                    })
                    val request = JSONObject().put("name", name).put("type", type ?: "").toString()
                    web.postWebMessage(WebMessage(request, arrayOf(pair[1])), Uri.parse(origin))
                }
            }
                view.loadUrl("$origin/")
            return result.await()
        } finally {
            result.cancel()
            ports?.forEach { runCatching { it.close() } }
            view.stopLoading()
            view.destroy()
            output.close()
        }
    }

    companion object {
        private val mutex = Mutex()
        private fun response(mime: String, body: java.io.InputStream, status: Int = 200) =
            WebResourceResponse(mime, "UTF-8", status, if (status == 200) "OK" else "Refused",
                mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"), body)
    }
}
