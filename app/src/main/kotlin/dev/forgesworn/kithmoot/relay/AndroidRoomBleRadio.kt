package dev.forgesworn.kithmoot.relay

import android.content.Context
import dev.forgesworn.meshble.MeshBleRadio
import dev.forgesworn.meshble.MeshBleRequest
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import java.util.Base64

/** The actual shared BLE engine, with no Capacitor, relay or account dependency. */
internal class AndroidRoomBleRadio(context: Context, listener: (RoomBleEvent) -> Unit) : RoomBleRadio {
    private var config: RoomBleConfig? = null
    private val radio = MeshBleRadio(context.applicationContext) { name, value ->
        when (name) {
            "frame" -> {
                val data = value.opt("data") as? String
                val from = value.opt("from") as? String
                if (data != null && from != null && data.length <= (RoomMeshWire.MAX_BYTES + 2) / 3 * 4 &&
                    from.isNotBlank() && from.length <= 256) {
                    try { listener(RoomBleEvent.Frame(Base64.getDecoder().decode(data), from)) }
                    catch (_: IllegalArgumentException) { /* Malformed base64 has no room authority. */ }
                }
            }
            "status" -> {
                // beginStart first emits a stopped/unscoped snapshot. It is not
                // a failure of the newly opened room. Closed engines suppress
                // callbacks; the link also rejects their owner generation.
                if (config?.scope == value.optString("room")) listener(RoomBleEvent.Status(
                    running = value.optBoolean("running"),
                    peers = value.optInt("writablePeers") + value.optInt("notifiablePeers"),
                    error = (value.opt("lastError") as? String)?.take(256),
                ))
            }
        }
    }

    override fun start(config: RoomBleConfig) {
        this.config = config
        request(startOptions(config), radio::start)
    }

    override fun offer(bytes: ByteArray, to: String?): Int {
        val options = JSONObject().put("data", Base64.getEncoder().encodeToString(bytes))
        if (to != null) options.put("peer", to)
        return request(options, if (to == null) radio::broadcast else radio::send).optInt("queuedPeers")
    }

    override fun close() { radio.close() }

    companion object {
        internal fun startOptions(config: RoomBleConfig): JSONObject = JSONObject()
            .put("room", config.scope).put("selfId", config.selfId).put("serviceUuid", config.serviceUuid.toString())
            .put("hops", 0).put("foregroundService", false)
            // The 20 KiB room frame expands to base64 plus the bounded envelope.
            .put("maxEnvelopeBytes", 32 * 1024)

        private fun request(options: JSONObject, call: (MeshBleRequest) -> Unit): JSONObject {
            var result: Result<JSONObject>? = null
            call(MeshBleRequest(options, object : MeshBleRequest.Completion {
                override fun resolve(value: JSONObject) { result = Result.success(value) }
                override fun reject(message: String, code: String?, cause: Exception?) {
                    result = Result.failure(IllegalStateException(code ?: message, cause))
                }
            }))
            // The pinned engine's start/send/stop API completes synchronously.
            // Fail closed if an upgraded engine changes that ownership contract.
            return checkNotNull(result) { "BLE request did not complete synchronously" }.getOrThrow()
        }
    }
}

/** Construction is inert. start must follow explicit UI action and permissions.
 * No foreground service is started by this initial foreground-only binding. */
fun createAndroidRoomMeshLink(context: Context): NativeRoomMeshLink =
    NativeRoomMeshLink({ listener -> AndroidRoomBleRadio(context, listener) }, Dispatchers.Main)
