package dev.forgesworn.kithmoot.lab

import dev.forgesworn.kithmoot.BuildConfig
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicBoolean

/** The sole activation boundary: debug AND an explicit lab build, then one owned window.
 * Production transport hooks return immediately by default. Nothing is logged or exported. */
internal object MetadataObserver {
    private val lock = Any()
    private var current: MetadataSession? = null
    private var deadlineNanos = 0L
    private var leases = 0
    private var expired = false
    private var pendingAttempts = 0
    private val enabled get() = BuildConfig.DEBUG && BuildConfig.LAB_METADATA_OBSERVER
    data class Spec(val windowId: String, val ownerStage: String, val terms: Map<String,String>, val salt: ByteArray,
        val actor: String, val durationMs: Long, val actions: List<String>, val signerOrigin: String)
    fun arm(spec: Spec): JsonObject = synchronized(lock) {
        check(enabled) { "Lab metadata observation is disabled" }
        check(current == null && leases == 0 && pendingAttempts == 0) { "A metadata window or callback is still owned" }
        val mono = System.nanoTime() / 1_000_000
        val wall = System.currentTimeMillis()
        val session = MetadataSession(spec.windowId, spec.ownerStage, spec.terms, spec.salt, spec.actor,
            spec.durationMs, spec.actions, spec.signerOrigin, { wall + (System.nanoTime()/1_000_000 - mono) }, wall)
        current = session; deadlineNanos = System.nanoTime() + spec.durationMs * 1_000_000; expired = false
        buildJsonObject { put("armed", true); put("windowId", spec.windowId); put("ownerStage", spec.ownerStage); put("manifestDigest", session.manifestDigest) }
    }
    fun snapshot(stage: String, window: String): JsonObject = synchronized(lock) {
        val session = owned(stage, window)
        JsonObject(session.snapshot() + mapOf("activeCallbacks" to JsonPrimitive(leases), "pendingAttempts" to JsonPrimitive(pendingAttempts), "captureBoundaryComplete" to JsonPrimitive(leases == 0 && pendingAttempts == 0), "timestampScope" to JsonPrimitive("application-callback-observation-epoch-anchored-monotonic")))
    }
    fun disarm(stage: String, window: String): JsonObject = synchronized(lock) {
        val session = owned(stage, window)
        val terminal = session.close(); current = null
        JsonObject(terminal + mapOf("activeCallbacks" to JsonPrimitive(leases), "pendingAttempts" to JsonPrimitive(pendingAttempts), "captureBoundaryComplete" to JsonPrimitive(leases == 0 && pendingAttempts == 0), "timestampScope" to JsonPrimitive("application-callback-observation-epoch-anchored-monotonic")))
    }
    private fun owned(stage: String, window: String): MetadataSession {
        check(enabled); return requireNotNull(current).also { check(it.ownerStage == stage && it.windowId == window) }
    }
    /** Lease at callback entry. Carry it through work; NEVER rediscover a window after deferral. */
    fun lease(): Lease? {
        if (!enabled) return null
        return synchronized(lock) {
            val session = current ?: return@synchronized null
            if (System.nanoTime() > deadlineNanos) { if (!expired) { expired = true; session.gap() }; return@synchronized null }
            leases++; Lease(session, deadlineNanos)
        }
    }
    class Lease internal constructor(internal val session: MetadataSession, private val deadline: Long): AutoCloseable {
        private val closed = AtomicBoolean()
        private var deadlineGap = false
        private fun expired(): Boolean {
            val expired = System.nanoTime() > deadline
            if (expired && !deadlineGap) { deadlineGap = true; session.gap() }
            return expired
        }
        @Synchronized fun safe(block: (MetadataSession) -> Unit) {
            if (!closed.get() && !expired()) session.guarded { block(session); expired() }
        }
        @Synchronized fun finish(stream: MetadataSession.Stream?, full: Boolean) {
            if (!closed.get()) session.guarded { stream?.finish(full && !expired()) }
        }
        @Synchronized override fun close() { if (closed.compareAndSet(false,true)) synchronized(lock) { leases-- } }
    }
    /** Calls begun before arm must finish before a new window can be owned. */
    fun attempt(): Attempt? {
        if (!enabled) return null
        return synchronized(lock) { pendingAttempts++; Attempt(lease()) }
    }
    class Attempt internal constructor(val lease: Lease?): AutoCloseable {
        private val closed = AtomicBoolean()
        override fun close() { if (closed.compareAndSet(false,true)) { lease?.close(); synchronized(lock) { pendingAttempts-- } } }
    }
    private fun frame(session: MetadataSession, surface: String, url: String, direction: String, bytes: ByteArray) {
        val origin = MetadataPrimitives.origin(url)
        session.observe(direction, if (surface == "native-inner") "native" else "app-page",
            if (surface == "native-inner") "native-route" else if (origin == session.signerOrigin) "signer-public-relay" else "ordinary-relay",
            origin, url, "websocket", bytes, surface, "application-websocket-frame")
    }
    fun frame(surface: String, url: String, direction: String, text: String) {
        val lease = lease() ?: return
        lease.use { it.safe { session -> frame(session,surface,url,direction,text.toByteArray(Charsets.UTF_8)) } }
    }
    fun binaryFrame(surface: String, url: String, direction: String, bytes: okio.ByteString) {
        val lease = lease() ?: return
        lease.use { it.safe { session -> frame(session,surface,url,direction,bytes.toByteArray()) } }
    }
    fun sendAccepted(url: String, text: String, send: () -> Boolean): Boolean {
        val lease = lease() ?: return send()
        lease.use {
            val accepted = send()
            it.safe { session -> if (accepted) frame(session,"external-websocket",url,"out",text.toByteArray(Charsets.UTF_8))
                else session.lifecycle("external-websocket","enqueue-refused",url) }
            return accepted
        }
    }
    fun nativeSend(url: String, text: String, send: () -> Unit) {
        val attempt = attempt() ?: return send()
        attempt.use { send(); it.lease?.safe { session -> frame(session,"native-inner",url,"out",text.toByteArray(Charsets.UTF_8)) } }
    }
    fun nativeRequest(request: dev.forgesworn.kithmoot.relay.LinkJsonRequest,
        send: () -> dev.forgesworn.kithmoot.relay.LinkJsonResponse): dev.forgesworn.kithmoot.relay.LinkJsonResponse {
        val attempt = attempt() ?: return send()
        val url = "native://link" + request.path
        attempt.use { owned ->
            owned.lease?.safe { session ->
                fun value(bytes: ByteArray, kind: String) = session.observe("out","native","native-route","native://link",null,"http-value",bytes,"native-inner",kind)
                value(request.routeId.toByteArray(Charsets.UTF_8),"native-route-id")
                value(request.method.toByteArray(Charsets.UTF_8),"native-method")
                value(request.path.toByteArray(Charsets.UTF_8),"native-path")
                value(request.authorization.toByteArray(Charsets.UTF_8),"native-authorization")
                value(request.body,"native-body-offered")
            }
            return try { send().also { response -> owned.lease?.safe { session ->
                session.observe("in","native","native-route","native://link",null,"http-value",response.body,"native-inner","native-body-returned")
            } } } catch (failure: Throwable) {
                owned.lease?.safe { it.lifecycle("native-inner","request-failed",url) }; throw failure
            }
        }
    }
    fun lifecycle(surface: String, phase: String, url: String) {
        val lease = lease() ?: return
        lease.use { it.safe { session -> session.lifecycle(surface, phase, url) } }
    }
}
