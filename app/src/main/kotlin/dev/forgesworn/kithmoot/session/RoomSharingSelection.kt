package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.storage.RoomStorage
import kotlinx.serialization.json.*

/** Encrypted atomic preferences. They describe consent, never live authority.
 * Initialisation/policy intent must finish before any forwarding owner starts.
 * No enabled bit is restored: every room reopen requires explicit Resume. */
internal class RoomSharingSelection(private val storage: RoomStorage,
    private val room: String, private val participant: String, private val device: String) {
    data class Selection(val current: RoomForwardingBinding, val initialised: Boolean,
        val pending: RoomForwardingBinding? = null)

    init { require(listOf(room, participant, device).all { Regex("[0-9a-f]{64}").matches(it) }) }

    fun read(): Selection? {
        val bytes = storage.read() ?: return null
        try {
            require(bytes.size <= MAX_BYTES)
            val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            require(root.getValue("v").jsonPrimitive.int == 1)
            val current = checked(RoomForwardingBinding.fromJson(root.getValue("current")))
            val initialised = root.getValue("initialised").jsonPrimitive.boolean
            val pending = root["pending"]?.let { checked(RoomForwardingBinding.fromJson(it)) }
            require(pending == null || (initialised && pending.pin != current.pin))
            return Selection(current, initialised, pending)
        } finally { bytes.fill(0) }
    }

    /** Returns a suspended ledger only after both journals agree. A failed or
     * ambiguous commit closes it. Retry inspects the committed pin rather than
     * replacing state, granting credit or assuming a write exception undid it. */
    fun prepare(binding: RoomForwardingBinding, ledgerStorage: RoomStorage,
        nowMs: () -> Long = System::currentTimeMillis): RoomForwardingLedger =
        RoomForwardingLedger.prepareSelection(checked(binding)) {
            var state = read() ?: Selection(binding, false).also(::write)
            var ledger: RoomForwardingLedger? = null
            try {
                val bytes = ledgerStorage.read()
                val storedPin = if (bytes == null) null else try {
                    require(bytes.size <= RoomForwardingLedger.MAX_FILE_BYTES)
                    Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject.getValue("pin").jsonPrimitive.content
                } finally { bytes.fill(0) }
                val committed = when (storedPin) {
                    null -> { check(!state.initialised && state.pending == null) { "The sharing journal is missing" }; state.current }
                    state.current.pin -> state.current
                    state.pending?.pin -> checkNotNull(state.pending)
                    else -> error("The sharing journal does not match its selection")
                }
                val opened = RoomForwardingLedger(ledgerStorage, committed, nowMs, createIfMissing = !state.initialised)
                ledger = opened
                state.pending?.let { pending ->
                    opened.changeSelection(pending)
                    state = Selection(pending, true); write(state)
                }
                if (!state.initialised) { state = state.copy(initialised = true); write(state) }
                if (binding.pin != state.current.pin) {
                    write(state.copy(pending = binding))
                    opened.changeSelection(binding)
                    state = Selection(binding, true); write(state)
                }
                opened
            } catch (error: Exception) { ledger?.close(); throw error }
        }

    private fun checked(binding: RoomForwardingBinding) = binding.also {
        require(it.room == room && it.participant == participant && it.device == device) { "Sharing identity does not match" }
    }
    private fun write(selection: Selection) {
        val bytes = buildJsonObject {
            put("v", 1); put("current", selection.current.toJson()); put("initialised", selection.initialised)
            selection.pending?.let { put("pending", it.toJson()) }
        }.toString().toByteArray(Charsets.UTF_8)
        try { require(bytes.size <= MAX_BYTES); storage.write(bytes) } finally { bytes.fill(0) }
    }
    companion object { const val MAX_BYTES = 64 * 1024 }
}
