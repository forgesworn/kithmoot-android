package dev.forgesworn.kithmoot.media.recording

/** Only a public node authorisation identity; never a room or file key. */
data class RecordingStorageChoice(val draft: String, val room: String, val origin: String, val publicKey: String)
