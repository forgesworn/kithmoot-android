package dev.forgesworn.kithmoot.protocol

/** Private Bothy room authority. The event is intercepted and never enters room history. */
const val KIND_CIRCLE_EVENT_GRANT: Int = 24242
const val MAX_CIRCLE_GRANT_LIFETIME_SECONDS: Long = 400L * 24 * 60 * 60

enum class CircleGrantStatus(val wire: String) { ACTIVE("active"), REVOKED("revoked") }

data class CircleGrantTerms(
    val server: String,
    val room: String,
    val persona: String,
    val device: String,
    val grantId: String,
    val expiration: Long,
) {
    init {
        require(SERVER.matches(server)) { "The Bothy relay URL is not canonical." }
        require(HEX64.matches(room) && HEX64.matches(persona) && HEX64.matches(device))
        require(HEX32.matches(grantId))
        require(expiration > 0)
    }

    fun tags(status: CircleGrantStatus): List<List<String>> = listOf(
        listOf("t", "event-grant"),
        listOf("server", server),
        listOf("d", room),
        listOf("p", persona),
        listOf("device", device),
        listOf("grant", grantId),
        listOf("read", "1460"),
        listOf("read", "1462"),
        listOf("read", "20461"),
        listOf("write", "1460"),
        listOf("write", "20461"),
        listOf("expiration", expiration.toString()),
        listOf("status", status.wire),
    )

    private companion object {
        val HEX64 = Regex("[0-9a-f]{64}")
        val HEX32 = Regex("[0-9a-f]{32}")
        val SERVER = Regex("ws://[a-z2-7]{52}/events")
    }
}
