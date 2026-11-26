package dev.forgesworn.kithmoot.board

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.UUID

/** The longest message either side of the port sends, in characters (KithMoot `ROOM_BOARD_PORT_MAX_MESSAGE`). */
const val ROOM_BOARD_PORT_MAX_MESSAGE: Int = 48_000

/**
 * The room board page: KithMoot's keyless board (`src/room-board-port.ts`) and Girnel's room view over it, pinned
 * and bundled under `assets/room-board` (see `docs/third-party/room-board.md`), in a renderer of its own.
 *
 * The page holds no key, credential, relay address or account. It reaches this app through one transferred
 * message port, never a Java object, and holds the board while the app keeps it: [listener] is told every message
 * the page sends and answers through [post] (KithMoot `docs/room-board-port.md`). It is served on a fresh random
 * HTTPS origin from its pinned files alone; any other request is refused here, network loads, navigation and file
 * or content access are off, and what it keeps in DOM storage is deleted with it.
 *
 * Main thread only, as a WebView is.
 */
class RoomBoardPage(screen: Context, private val listener: Listener) {
    interface Listener {
        /** One message from the page, at most [ROOM_BOARD_PORT_MAX_MESSAGE] characters. On the main thread. */
        fun onMessage(message: String)
        /** The page's renderer went away: everything it held that the app's copy lacks is gone with it. */
        fun onGone()
    }

    private val context = screen.applicationContext
    val origin = "https://${UUID.randomUUID()}.board.kithmoot.invalid"
    private val files: Set<String> = pinnedFiles(this.context)
    private var port: WebMessagePort? = null
    private var ports: Array<WebMessagePort>? = null
    private val waiting = ArrayDeque<String>()
    private var launched = false
    private var destroyed = false
    private val refusals = mutableListOf<String>()

    /** The paths the page asked for and was refused, newest last: none, in a page that works as pinned. */
    val refused: List<String> get() = synchronized(refusals) { refusals.toList() }

    /** The page's context: the screen it is made on, whose window what the page opens over itself (a list to choose
     *  from, a date picker) needs. Given the application instead, the page runs and keeps the board as well, and
     *  those cannot open. */
    private val host = MutableContextWrapper(screen)

    /** What the app shows when the board is on screen. Kept for as long as the page is. */
    @SuppressLint("SetJavaScriptEnabled") // Only the pinned bundled scripts run; every resource is intercepted below.
    val view: WebView = WebView(host).apply {
        settings.apply {
            javaScriptEnabled = true
            blockNetworkLoads = true
            allowFileAccess = false
            allowContentAccess = false
            // Girnel keeps its theme, text size and half-typed text there. The origin is this page's alone and is
            // deleted with it, so nothing outlives the page.
            domStorageEnabled = true
            databaseEnabled = false
            setGeolocationEnabled(false)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse = serve(request)
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (!destroyed) listener.onGone()
                destroy()
                return true
            }
            override fun onPageFinished(web: WebView, url: String) {
                if (launched || destroyed || url != "$origin/") return
                launched = true
                val pair = web.createWebMessageChannel().also { ports = it }
                pair[0].setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
                    override fun onMessage(port: WebMessagePort, message: WebMessage) {
                        val raw = message.data ?: return
                        if (destroyed || raw.length > ROOM_BOARD_PORT_MAX_MESSAGE) return
                        listener.onMessage(raw)
                    }
                })
                web.postWebMessage(WebMessage("", arrayOf(pair[1])), Uri.parse(origin))
                port = pair[0]
                while (waiting.isNotEmpty()) pair[0].postMessage(WebMessage(waiting.removeFirst()))
            }
        }
    }

    /** Load the page. The board opens once the app answers its `ready`. */
    fun open() { if (!destroyed) view.loadUrl("$origin/") }

    /** One message to the page. Held until the page has its port; dropped once the page has gone. */
    fun post(message: String) {
        if (destroyed) return
        require(message.length <= ROOM_BOARD_PORT_MAX_MESSAGE) { "This board message is too long to send" }
        port?.postMessage(WebMessage(message)) ?: waiting.addLast(message)
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        waiting.clear()
        ports?.forEach { runCatching { it.close() } }
        runCatching { view.stopLoading() }
        runCatching { WebStorage.getInstance().deleteOrigin(origin) }
        runCatching { view.destroy() }
    }

    private fun serve(request: WebResourceRequest): WebResourceResponse {
        val url = request.url
        val path = url.path ?: ""
        if (request.method != "GET" || "${url.scheme}://${url.authority}" != origin || url.query != null || url.fragment != null) {
            synchronized(refusals) { if (refusals.size < 100) refusals += url.toString().take(200) }
            return refused()
        }
        return try {
            when {
                path == "/" -> response("text/html", ByteArrayInputStream(HOST_PAGE.toByteArray(Charsets.UTF_8)), HOST_POLICY)
                path == "/board.js" -> response("text/javascript", context.assets.open("room-board/board.js"), HOST_POLICY)
                path.removePrefix("/") in files -> response(mimeOf(path), context.assets.open("room-board$path"), GIRNEL_POLICY)
                else -> { synchronized(refusals) { if (refusals.size < 100) refusals += path }; refused() }
            }
        } catch (_: Exception) { response("text/plain", ByteArrayInputStream(byteArrayOf()), HOST_POLICY, 404) }
    }

    companion object {
        /** The page itself: the board's script, and Girnel's room view in a same-origin frame. */
        private const val HOST_PAGE = """<!doctype html><html lang="en-GB"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><meta name="color-scheme" content="light dark"><title>Board</title><style>html,body,iframe{margin:0;border:0;padding:0;width:100%;height:100%;display:block}</style><iframe id="view" title="Board" allow="clipboard-write" src="/j/girnel/embedded/room.html"></iframe><script src="/board.js"></script></html>"""
        private const val HOST_POLICY = "default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; frame-src 'self'; connect-src 'none'; img-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
        /** KithMoot's own policy for Girnel's pages (deploy/Caddyfile.kithmoot), with no network. */
        private const val GIRNEL_POLICY = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self'; worker-src 'self'; font-src 'self'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'self'"

        /** The Girnel files the pinned page names, as `j/girnel/...`: the only ones served. */
        private fun pinnedFiles(context: Context): Set<String> {
            val proof = JSONObject(context.assets.open("room-board/provenance.json").use { it.readBytes().toString(Charsets.UTF_8) })
            val files = proof.getJSONObject("girnel").getJSONObject("files")
            return files.keys().asSequence().filter { it.startsWith("j/girnel/") && !it.contains("..") }.toSet()
        }

        private fun mimeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
            "html" -> "text/html"
            "js", "mjs" -> "text/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "woff2" -> "font/woff2"
            "woff" -> "font/woff"
            "wasm" -> "application/wasm"
            else -> "application/octet-stream"
        }

        private fun refused() = response("text/plain", ByteArrayInputStream(byteArrayOf()), HOST_POLICY, 403)

        private fun response(mime: String, body: InputStream, policy: String, status: Int = 200) =
            WebResourceResponse(mime, "UTF-8", status, if (status == 200) "OK" else "Refused",
                mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff", "Referrer-Policy" to "no-referrer",
                    "Content-Security-Policy" to policy), body)
    }
}
