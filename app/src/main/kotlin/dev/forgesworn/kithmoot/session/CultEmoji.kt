package dev.forgesworn.kithmoot.session

val CULT_EMOJIS = listOf(":600:" to "600 billion", ":600_spin:" to "600 spin", ":600_rainbow:" to "600 rainbow",
    ":600_moon:" to "600 to the moon", ":600_fire:" to "600 fire", ":600_facepalm:" to "600 facepalm",
    ":600_handshake:" to "600 handshake", ":600_laser:" to "600 laser eyes")
fun isCultEmoji(value: String): Boolean = CULT_EMOJIS.any { it.first == value }
