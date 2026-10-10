package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.storage.RoomStorageException
import java.security.SecureRandom
import java.util.Base64
import java.lang.reflect.Proxy
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** A paired Link route. It deliberately has no Nostr account, room or consent data. */
data class StoredLinkRoute(
    val routeId: String,
    val card: ByteArray,
    val pairedRouteSecret: ByteArray,
    val cardSerial: ULong,
    val cardVerifiedAt: ULong,
) {
    init {
        require(routeId.matches(ROUTE_ID))
        require(card.size in 1..4096)
        require(pairedRouteSecret.size == 32)
    }

    internal fun copyForUse() = copy(card = card.copyOf(), pairedRouteSecret = pairedRouteSecret.copyOf())

    private companion object { val ROUTE_ID = Regex("[A-Za-z0-9._:-]{1,128}") }
}

data class LinkTransportState(val transportSeed: ByteArray, val routes: List<StoredLinkRoute>) {
    init {
        require(transportSeed.size == 32)
        require(routes.size <= MAX_ROUTES)
        require(routes.distinctBy { it.routeId }.size == routes.size)
    }

    internal fun copyForUse() = LinkTransportState(transportSeed.copyOf(), routes.map { it.copyForUse() })
    private companion object { const val MAX_ROUTES = 32 }
}

/**
 * Owns only the native Link identity and paired route credentials.  Account/room
 * consent is intentionally stored elsewhere, so losing a consent cannot reveal
 * a route credential and retaining a route cannot activate it.
 */
class LinkTransportVault(private val storage: RoomStorage, private val random: SecureRandom = SecureRandom()) {
    @Synchronized fun state(): LinkTransportState = read().copyForUse()

    /** Startup inspection must not initialise an unused transport identity. */
    @Synchronized fun routeIds(): Set<String> {
        val current = readExisting() ?: return emptySet()
        return try { current.routes.mapTo(mutableSetOf()) { it.routeId } }
        finally {
            current.transportSeed.fill(0)
            current.routes.forEach { it.card.fill(0); it.pairedRouteSecret.fill(0) }
        }
    }

    @Synchronized fun upsert(route: StoredLinkRoute): LinkTransportState {
        val current = read()
        val next = LinkTransportState(current.transportSeed, (current.routes.filterNot { it.routeId == route.routeId } + route).also {
            require(it.size <= 32) { "Too many paired Link routes" }
        })
        write(next)
        return next.copyForUse()
    }

    @Synchronized fun remove(routeId: String): LinkTransportState {
        val current = read()
        val next = LinkTransportState(current.transportSeed, current.routes.filterNot { it.routeId == routeId })
        write(next)
        return next.copyForUse()
    }

    private fun read(): LinkTransportState = guarded {
        readExisting() ?: LinkTransportState(ByteArray(32).also(random::nextBytes), emptyList()).also(::write)
    }

    private fun readExisting(): LinkTransportState? = guarded {
        val bytes = storage.read() ?: return@guarded null
        try {
            require(bytes.size <= 256 * 1024)
            val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            require(root.getValue("version").jsonPrimitive.long == 1L)
            val seed = decode(root.getValue("transportSeed").jsonPrimitive.content, 32)
            val routes = root.getValue("routes").jsonArray.map { item ->
                val route = item.jsonObject
                StoredLinkRoute(
                    route.getValue("routeId").jsonPrimitive.content,
                    decode(route.getValue("card").jsonPrimitive.content, 1..4096),
                    decode(route.getValue("pairedRouteSecret").jsonPrimitive.content, 32),
                    route.getValue("cardSerial").jsonPrimitive.content.toULong(),
                    route.getValue("cardVerifiedAt").jsonPrimitive.content.toULong(),
                )
            }
            LinkTransportState(seed, routes)
        } finally { bytes.fill(0) }
    }

    private fun write(state: LinkTransportState) = guarded {
        val bytes = buildJsonObject {
            put("version", 1)
            put("transportSeed", encode(state.transportSeed))
            put("routes", buildJsonArray {
                state.routes.forEach { route -> add(buildJsonObject {
                    put("routeId", route.routeId)
                    put("card", encode(route.card))
                    put("pairedRouteSecret", encode(route.pairedRouteSecret))
                    put("cardSerial", route.cardSerial.toString())
                    put("cardVerifiedAt", route.cardVerifiedAt.toString())
                }) }
            })
        }.toString().toByteArray(Charsets.UTF_8)
        try { storage.write(bytes) } finally { bytes.fill(0) }
    }

    private fun decode(value: String, size: Int): ByteArray = decode(value, size..size)
    private fun decode(value: String, sizes: IntRange): ByteArray = Base64.getUrlDecoder().decode(value).also {
        require(it.size in sizes)
    }
    private fun encode(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    private inline fun <T> guarded(block: () -> T): T = try { block() } catch (e: Exception) {
        if (e is RoomStorageException) throw e
        throw RoomStorageException(e)
    }
}

/** Small seam around generated bindings: tests never need a native library. */
interface LinkTransportRuntime {
    fun start(state: LinkTransportState): LinkTransportSession
}

interface LinkTransportSession {
    fun open(url: String, routeId: String, listener: RelaySocketListener): LinkTransportSocket
    fun request(request: LinkJsonRequest): LinkJsonResponse
    fun pair(routeId: String, card: ByteArray, pairingSecret: ByteArray, expiresAt: ULong): StoredLinkRoute
    fun upsert(route: StoredLinkRoute)
    fun retire(routeId: String)
    fun finalize(routeId: String)
    fun remove(routeId: String)
    fun stop()
}

/** Opaque JSON crossing the reviewed native bridge. Secrets are deliberately absent from toString. */
data class LinkJsonRequest(
    val routeId: String,
    val method: String,
    val path: String,
    val authorization: String,
    val body: ByteArray,
) {
    override fun toString() = "LinkJsonRequest(method=$method, body=${body.size} bytes)"
}

data class LinkPathState(
    val status: String,
    val relay: String?,
    val direct: String?,
    val cause: String,
)

data class LinkJsonResponse(
    val status: Int,
    val body: ByteArray,
    val path: LinkPathState,
    /** True only for Bothy's deliberate refusal on a restore-witness route: a
     *  403 carrying `vmls-witness: refused`. Any other 403 means unavailable. */
    val witnessRefused: Boolean = false,
) {
    override fun toString() = "LinkJsonResponse(status=$status, body=${body.size} bytes, path=$path, witnessRefused=$witnessRefused)"
}

fun interface LinkJsonTransport {
    fun request(request: LinkJsonRequest): java.util.concurrent.CompletableFuture<LinkJsonResponse>
}

interface LinkTransportSocket {
    fun send(text: String)
    fun disconnect()
    fun dispose()
}

/**
 * Calls the reviewed UniFFI binding when it is present in build/link-bridge.
 * Reflection is intentional: hosted builds can verify the Kotlin app without
 * downloading the private release artefact, while a prepared production build
 * gets the same generated API and JNI library that the verifier approved.
 */
class ReflectiveLinkTransportRuntime : LinkTransportRuntime {
    override fun start(state: LinkTransportState): LinkTransportSession {
        val engineClass = load("LinkEngine")
        val routeClass = load("LinkRoute")
        val configClass = load("LinkConfig")
        val routes = state.routes.map { route -> constructRecord(routeClass,
            route.routeId, route.card.copyOf(), route.pairedRouteSecret.copyOf(),
            route.cardSerial.toLong(), route.cardVerifiedAt.toLong(),
        ) }
        val config = configClass.constructors.single().newInstance(state.transportSeed.copyOf(), emptyList<String>(), false, routes)
        val companion = requireNotNull(engineClass.getField("Companion").get(null))
        val engine = requireNotNull(invokeReflected(companion.javaClass.methods.single { it.name == "start" && it.parameterCount == 1 }, companion, config))
        return ReflectiveLinkTransportSession(engine, routeClass)
    }

    private fun load(name: String): Class<*> = try {
        Class.forName("dev.forgesworn.link.ffi.$name")
    } catch (e: ClassNotFoundException) {
        throw IllegalStateException("The verified Link Android bridge has not been prepared", e)
    }
}

private class ReflectiveLinkTransportSession(private val engine: Any, private val routeClass: Class<*>) : LinkTransportSession {
    private val listenerClass = Class.forName("dev.forgesworn.link.ffi.LinkSocketListener")

    override fun open(url: String, routeId: String, listener: RelaySocketListener): LinkTransportSocket {
        val callback = Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { _, method, args ->
            when (method.name) {
                "onOpen" -> listener.onOpen()
                "onText" -> listener.onMessage(args!![0] as String)
                "onClosed" -> listener.onClosed(args!![0] as String)
            }
            null
        }
        val socket = requireNotNull(invokeReflected(engine.javaClass.methods.single { it.name == "openSocket" && it.parameterCount == 3 }, engine, url, routeId, callback))
        return ReflectiveLinkTransportSocket(socket)
    }

    override fun request(request: LinkJsonRequest): LinkJsonResponse {
        val requestClass = Class.forName("dev.forgesworn.link.ffi.LinkHttpRequest")
        val nativeRequest = constructRecord(
            requestClass,
            request.routeId,
            request.method,
            request.path,
            request.authorization,
            request.body.copyOf(),
        )
        return linkJsonResponse(requireNotNull(invoke("requestJson", nativeRequest)))
    }

    override fun pair(routeId: String, card: ByteArray, pairingSecret: ByteArray, expiresAt: ULong): StoredLinkRoute {
        val bundleClass = Class.forName("dev.forgesworn.link.ffi.LinkPairingBundle")
        val bundle = constructRecord(bundleClass, routeId, card.copyOf(), pairingSecret.copyOf(), expiresAt.toLong())
        val route = requireNotNull(invokeReflected(engine.javaClass.methods.single { it.name == "pairRoute" && it.parameterCount == 1 }, engine, bundle))
        fun value(name: String): Any = requireNotNull(route.javaClass.methods.single {
            (it.name == name || it.name.startsWith("$name-")) && it.parameterCount == 0
        }.let { invokeReflected(it, route) })
        fun unsigned(name: String): ULong = when (val raw = value(name)) {
            is ULong -> raw
            is Long -> raw.toULong()
            else -> throw IllegalStateException("The Link bridge returned an invalid $name")
        }
        return StoredLinkRoute(
            value("getRouteId") as String,
            value("getCard") as ByteArray,
            value("getPairedRouteSecret") as ByteArray,
            unsigned("getCardSerial"),
            unsigned("getCardVerifiedAt"),
        )
    }

    override fun upsert(route: StoredLinkRoute) {
        invoke("upsertRoute", constructRecord(routeClass,
            route.routeId, route.card.copyOf(), route.pairedRouteSecret.copyOf(), route.cardSerial.toLong(), route.cardVerifiedAt.toLong(),
        ))
    }
    override fun retire(routeId: String) { invoke("retireRoute", routeId) }
    override fun finalize(routeId: String) { invoke("finalizeRoute", routeId) }
    override fun remove(routeId: String) { invoke("removeRoute", routeId) }
    override fun stop() {
        try { invoke("stop") } finally { (engine as? AutoCloseable)?.close() }
    }
    private fun invoke(name: String, vararg args: Any?) = engine.javaClass.methods.single {
        it.name == name && it.parameterCount == args.size
    }.let { invokeReflected(it, engine, *args) }
}

/** Maps the bridge's `LinkHttpResponse` record. */
internal fun linkJsonResponse(response: Any): LinkJsonResponse {
    val path = requireNotNull(recordValue(response, "getPath"))
    val status = when (val raw = recordValue(response, "getStatus")) {
        is UShort -> raw.toInt()
        is Short -> raw.toUShort().toInt()
        is Int -> raw
        else -> throw IllegalStateException("The Link bridge returned an invalid HTTP status")
    }
    check(status in 100..599) { "The Link bridge returned an invalid HTTP status" }
    return LinkJsonResponse(
        status,
        (recordValue(response, "getBody") as? ByteArray)?.copyOf()
            ?: throw IllegalStateException("The Link bridge returned an invalid response body"),
        LinkPathState(
            recordValue(path, "getStatus") as? String
                ?: throw IllegalStateException("The Link bridge returned an invalid path status"),
            recordValue(path, "getRelay") as? String,
            recordValue(path, "getDirect") as? String,
            recordValue(path, "getCause") as? String
                ?: throw IllegalStateException("The Link bridge returned an invalid path cause"),
        ),
        recordValue(response, "getWitnessRefused") as? Boolean
            ?: throw IllegalStateException("The Link bridge returned an invalid witness refusal flag"),
    )
}

private fun recordValue(record: Any, name: String): Any? = record.javaClass.methods.single {
    (it.name == name || it.name.startsWith("$name-")) && it.parameterCount == 0
}.let { invokeReflected(it, record) }

/** Reflection is only an optional-binding boundary; preserve the native cause for recovery and support. */
private fun invokeReflected(method: Method, receiver: Any, vararg args: Any?): Any? = try {
    method.invoke(receiver, *args)
} catch (error: InvocationTargetException) {
    when (val cause = error.targetException) {
        is Exception -> throw cause
        is Error -> throw cause
        else -> throw IllegalStateException("The Link bridge call failed", cause)
    }
}

/** UniFFI's Kotlin records with unsigned fields gain one JVM marker parameter. */
private fun constructRecord(type: Class<*>, vararg fields: Any?): Any {
    val constructor = type.constructors.single()
    val arguments = when (constructor.parameterCount) {
        fields.size -> fields
        fields.size + 1 -> fields.copyOf(fields.size + 1)
        else -> throw IllegalStateException("The Link bridge returned an incompatible ${type.simpleName} constructor")
    }
    return constructor.newInstance(*arguments)
}

private class ReflectiveLinkTransportSocket(private val socket: Any) : LinkTransportSocket {
    override fun send(text: String) { invoke("sendText", text) }
    override fun disconnect() { invoke("disconnect") }
    override fun dispose() { (socket as? AutoCloseable)?.close() }
    private fun invoke(name: String, vararg args: Any?) = socket.javaClass.methods.single {
        it.name == name && it.parameterCount == args.size
    }.let { invokeReflected(it, socket, *args) }
}

/**
 * Application-scoped Link engine owner. Native calls happen on its dedicated
 * worker, so RelayPool never blocks its caller while a Link path is negotiated.
 */
class LinkTransportManager(
    private val vault: LinkTransportVault,
    private val runtime: LinkTransportRuntime,
) : LinkRelaySocketFactory, LinkJsonTransport, AutoCloseable {
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "kithmoot-link").apply { isDaemon = true }
    }
    @Volatile private var closed = false
    private var session: LinkTransportSession? = null

    override fun open(url: String, routeId: String, listener: RelaySocketListener): RelaySocket {
        val socket = PendingLinkSocket(listener, worker)
        worker.execute {
            try {
                check(!closed) { "Link transport has stopped" }
                check(vault.state().routes.any { it.routeId == routeId }) { "Unknown Link route" }
                socket.attach(engine().open(url, routeId, socket))
            } catch (e: Exception) {
                socket.fail(e.message ?: "Link connection failed")
            }
        }
        return socket
    }

    fun upsert(route: StoredLinkRoute) {
        vault.upsert(route)
        worker.execute { if (!closed) session?.upsert(route.copyForUse()) }
    }

    /** Send one bounded request on the same serial worker and pinned route as relay traffic. */
    override fun request(request: LinkJsonRequest): java.util.concurrent.CompletableFuture<LinkJsonResponse> {
        val result = java.util.concurrent.CompletableFuture<LinkJsonResponse>()
        worker.execute {
            try {
                check(!closed) { "Link transport has stopped" }
                check(vault.state().routes.any { it.routeId == request.routeId }) { "Unknown Link route" }
                result.complete(engine().request(request.copy(body = request.body.copyOf())))
            } catch (e: Exception) {
                result.completeExceptionally(e)
            }
        }
        return result
    }

    fun remove(routeId: String) {
        vault.remove(routeId)
        worker.execute { if (!closed) session?.remove(routeId) }
    }

    /** Retire the server route before removing the matching local credential. */
    fun retire(routeId: String): java.util.concurrent.CompletableFuture<Unit> {
        val result = java.util.concurrent.CompletableFuture<Unit>()
        worker.execute {
            try {
                check(!closed) { "Link transport has stopped" }
                check(vault.state().routes.any { it.routeId == routeId }) { "Unknown Link route" }
                engine().retire(routeId)
                result.complete(Unit)
            } catch (e: Exception) {
                result.completeExceptionally(e)
            }
        }
        return result
    }

    /** Remove server transport after durable logical retirement. */
    fun finalize(routeId: String): java.util.concurrent.CompletableFuture<Unit> {
        val result = java.util.concurrent.CompletableFuture<Unit>()
        worker.execute {
            try {
                check(!closed) { "Link transport has stopped" }
                check(vault.state().routes.any { it.routeId == routeId }) { "Unknown Link route" }
                engine().finalize(routeId)
                result.complete(Unit)
            } catch (e: Exception) {
                result.completeExceptionally(e)
            }
        }
        return result
    }

    /** Used at startup to discard transport credentials that have no consent record. */
    fun routeIds(): Set<String> = vault.routeIds()

    /** Pairing is native and blocking, so return its completion without ever blocking the UI thread. */
    fun pair(card: ByteArray, pairingSecret: ByteArray, expiresAt: Long): java.util.concurrent.CompletableFuture<StoredLinkRoute> {
        require(card.isNotEmpty() && pairingSecret.size == 16 && expiresAt >= 0)
        val result = java.util.concurrent.CompletableFuture<StoredLinkRoute>()
        val routeId = "route-" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also(SecureRandom()::nextBytes))
        worker.execute {
            try {
                check(!closed) { "Link transport has stopped" }
                val route = engine().pair(routeId, card, pairingSecret, expiresAt.toULong())
                vault.upsert(route)
                result.complete(route.copyForUse())
            } catch (e: Exception) {
                result.completeExceptionally(e)
            } finally {
                pairingSecret.fill(0)
            }
        }
        return result
    }

    override fun close() {
        if (closed) return
        closed = true
        worker.execute { session?.stop(); session = null; worker.shutdown() }
    }

    private fun engine(): LinkTransportSession = session ?: runtime.start(vault.state()).also { session = it }
}

/** Defers early callbacks until attach and never calls either side under its state lock. */
internal class PendingLinkSocket(
    private val listener: RelaySocketListener,
    private val worker: java.util.concurrent.Executor,
) : RelaySocket, RelaySocketListener {
    private sealed interface Action {
        data object Open : Action
        data class Message(val text: String) : Action
        data class Closed(val reason: String) : Action
    }
    private val lock = Any()
    private val actions = ArrayDeque<Action>()
    private var native: LinkTransportSocket? = null
    private var closed = false
    private var notified = false
    private var draining = false

    private companion object {
        const val MAX_PENDING_ACTIONS = 128
        const val MAX_PENDING_CHARS = 1024 * 1024
    }

    override fun send(text: String) {
        val socket = synchronized(lock) { if (closed) null else native }
        socket?.send(text)
    }

    override fun close() {
        val socket = synchronized(lock) {
            if (closed) return
            closed = true
            actions.removeAll { it == Action.Open || it is Action.Message }
            native
        }
        socket?.disconnect()
        socket?.dispose()
    }

    fun attach(socket: LinkTransportSocket) {
        val dispose = synchronized(lock) { native = socket; closed }
        if (dispose) { socket.disconnect(); socket.dispose() }
        else scheduleDrain()
    }

    fun fail(reason: String) = closeOnce(reason)
    override fun onOpen() = enqueue { if (!closed) actions.addLast(Action.Open) }
    override fun onMessage(text: String) {
        val overflow = synchronized(lock) {
            if (closed) return
            val queuedChars = actions.sumOf { (it as? Action.Message)?.text?.length ?: 0 }
            if (actions.size >= MAX_PENDING_ACTIONS || text.length > MAX_PENDING_CHARS - queuedChars) true
            else { actions.addLast(Action.Message(text)); false }
        }
        if (overflow) closeOnce("Link receive queue is full") else scheduleDrain()
    }
    override fun onClosed(reason: String) = closeOnce(reason)

    private fun closeOnce(reason: String) {
        val socket = synchronized(lock) {
            if (notified) return
            closed = true
            notified = true
            actions.removeAll { it == Action.Open || it is Action.Message }
            actions.addLast(Action.Closed(reason))
            native
        }
        socket?.dispose()
        scheduleDrain()
    }

    private fun enqueue(change: () -> Unit) {
        synchronized(lock) { change() }
        scheduleDrain()
    }

    private fun scheduleDrain() {
        val start = synchronized(lock) {
            if (!draining && actions.isNotEmpty() && canRun(actions.first())) {
                draining = true
                true
            } else false
        }
        if (start) {
            try { worker.execute(::drain) }
            catch (_: java.util.concurrent.RejectedExecutionException) {
                // The manager is stopping. Never call back into RelayPool from a native callback.
                val socket = synchronized(lock) {
                    draining = false
                    closed = true
                    notified = true
                    actions.clear()
                    native
                }
                socket?.dispose()
            }
        }
    }

    private fun canRun(action: Action): Boolean = native != null || action is Action.Closed

    private fun drain() {
        while (true) {
            val next = synchronized(lock) {
                val head = actions.firstOrNull()
                if (head == null || !canRun(head)) { draining = false; return }
                actions.removeFirst()
            }
            val action = next
            try {
                when (action) {
                    Action.Open -> listener.onOpen()
                    is Action.Message -> listener.onMessage(action.text)
                    is Action.Closed -> listener.onClosed(action.reason)
                }
            } catch (error: Exception) {
                closeOnce(error.message ?: "Link socket failed")
            }
        }
    }
}
