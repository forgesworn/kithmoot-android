package dev.forgesworn.kithmoot.ui

/** The synchronous Home publication boundary; callers do storage IO before committing. */
internal class SavedRoomObservation {
    class Token internal constructor()
    private var current: Token? = null
    private var closed = false

    @Synchronized fun begin(publish: () -> Unit = {}): Token = Token().also { token ->
        if (!closed) {
            current = token
            publish()
        }
    }

    @Synchronized fun commit(token: Token, publish: () -> Unit) {
        if (!closed && current === token) publish()
    }

    @Synchronized fun failed(publish: () -> Unit) {
        if (!closed) {
            current = null
            publish()
        }
    }

    @Synchronized fun close() {
        closed = true
        current = null
    }
}
