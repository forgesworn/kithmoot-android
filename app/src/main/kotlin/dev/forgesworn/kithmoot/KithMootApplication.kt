package dev.forgesworn.kithmoot

import android.app.Application
import dev.forgesworn.kithmoot.storage.EncryptedRoomStorage
import dev.forgesworn.kithmoot.storage.RoomRepository

/**
 * Owns one serialised repository for saved room access across activities.
 * Live connections belong to the view model that opened the room.
 */
class KithMootApplication : Application() {
    val savedRooms: RoomRepository by lazy { RoomRepository(EncryptedRoomStorage(this)) }
}
