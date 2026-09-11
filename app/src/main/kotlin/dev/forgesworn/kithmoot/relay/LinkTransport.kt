package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.storage.RoomStorageException
import java.security.SecureRandom
import java.util.Base64
import java.lang.reflect.Proxy
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
        val bytes = storage.read() ?: return@guarded LinkTransportState(ByteArray(32).also(random::nextBytes), emptyList()).also(::write)
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
    fun pair(routeId: String, card: ByteArray, pairingSecret: ByteArray, expiresAt: ULong): StoredLinkRoute
    fun upsert(route: StoredLinkRoute)
    fun remove(routeId: String)
    fun stop()
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
        val routes = state.routes.map { route -> routeClass.constructors.single().newInstance(
            route.routeId, route.card.copyOf(), route.pairedRouteSecret.copyOf(),
            route.cardSerial.toLong(), route.cardVerifiedAt.toLong(),
        ) }
        val config = configClass.constructors.single().newInstance(state.transportSeed.copyOf(), emptyList<String>(), false, routes)
        val companion = requireNotNull(engineClass.getField("Companion").get(null))
        val engine = requireNotNull(companion.javaClass.methods.single { it.name == "start" && it.parameterCount == 1 }.invoke(companion, config))
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
        val socket = requireNotNull(engine.javaClass.methods.single { it.name == "openSocket" && it.parameterCount == 3 }
            .invoke(engine, url, routeId, callback))
        return ReflectiveLinkTransportSocket(socket)
    }

    override fun pair(routeId: String, card: ByteArray, pairingSecret: ByteArray, expiresAt: ULong): StoredLinkRoute {
        val bundleClass = Class.forName("dev.forgesworn.link.ffi.LinkPairingBundle")
        val bundle = bundleClass.constructors.single().newInstance(routeId, card.copyOf(), pairingSecret.copyOf(), expiresAt.toLong())
        val route = requireNotNull(engine.javaClass.methods.single { it.name == "pairRoute" && it.parameterCount == 1 }.invoke(engine, bundle))
        fun value(name: String): Any = requireNotNull(route.javaClass.methods.single { it.name == name && it.parameterCount == 0 }.invoke(route))
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
        invoke("upsertRoute", routeClass.constructors.single().newInstance(
            route.routeId, route.card.copyOf(), route.pairedRouteSecret.copyOf(), route.cardSerial.toLong(), route.cardVerifiedAt.toLong(),
        ))
    }
    override fun remove(routeId: String) { invoke("removeRoute", routeId) }
    override fun stop() {
        try { invoke("stop") } finally { (engine as? AutoCloseable)?.close() }
    }
    private fun invoke(name: String, vararg args: Any?) = engine.javaClass.methods.single {
        it.name == name && it.parameterCount == args.size
    }.invoke(engine, *args)
}

private class ReflectiveLinkTransportSocket(private val socket: Any) : LinkTransportSocket {
    override fun send(text: String) { invoke("sendText", text) }
    override fun disconnect() { invoke("disconnect") }
    override fun dispose() { (socket as? AutoCloseable)?.close() }
    private fun invoke(name: String, vararg args: Any?) = socket.javaClass.methods.single {
        it.name == name && it.parameterCount == args.size
    }.invoke(socket, *args)
}

/**
 * Application-scoped Link engine owner. Native calls happen on its dedicated
 * worker, so RelayPool never blocks its caller while a Link path is negotiated.
 */
class LinkTransportManager(
    private val vault: LinkTransportVault,
    private val runtime: LinkTransportRuntime,
) : LinkRelaySocketFactory, AutoCloseable {
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "kithmoot-link").apply { isDaemon = true }
    }
    @Volatile private var closed = false
    private var session: LinkTransportSession? = null

    override fun open(url: String, routeId: String, listener: RelaySocketListener): RelaySocket {
        val socket = PendingLinkSocket(listener)
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

    fun remove(routeId: String) {
        vault.remove(routeId)
        worker.execute { if (!closed) session?.remove(routeId) }
    }

    /** Used at startup to discard transport credentials that have no consent record. */
    fun routeIds(): Set<String> = vault.state().routes.mapTo(mutableSetOf()) { it.routeId }

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

/** Buffers the caller's close until native socket creation completes. */
private class PendingLinkSocket(private val listener: RelaySocketListener) : RelaySocket, RelaySocketListener {
    private var native: LinkTransportSocket? = null
    private var closed = false
    private var notified = false

    @Synchronized override fun send(text: String) { if (!closed) native?.send(text) }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        native?.let { it.disconnect(); it.dispose() }
    }
    @Synchronized fun attach(socket: LinkTransportSocket) {
        native = socket
        if (closed) { socket.disconnect(); socket.dispose() }
    }
    @Synchronized fun fail(reason: String) = closeOnce(reason)
    @Synchronized override fun onOpen() { if (!closed) listener.onOpen() }
    @Synchronized override fun onMessage(text: String) { if (!closed) listener.onMessage(text) }
    @Synchronized override fun onClosed(reason: String) { closeOnce(reason) }
    private fun closeOnce(reason: String) {
        if (notified) return
        closed = true
        notified = true
        native?.dispose()
        listener.onClosed(reason)
    }
}
