package dev.forgesworn.kithmoot

import android.app.Application
import dev.forgesworn.kithmoot.account.AccountStore
import dev.forgesworn.kithmoot.storage.ContactBook
import dev.forgesworn.kithmoot.storage.EncryptedRoomStorage
import dev.forgesworn.kithmoot.storage.RoomRepository

/**
 * Owns one serialised repository for saved room access across activities.
 * Live connections belong to the view model that opened the room.
 */
class KithMootApplication : Application() {
    val savedRooms: RoomRepository by lazy { RoomRepository(EncryptedRoomStorage(this)) }

    /** The Nostr account this phone is signed in as, in its own vault. */
    val accounts: AccountStore by lazy { AccountStore(EncryptedRoomStorage(this, "kithmoot.account.v1")) }

    /** The people this phone holds a contact card for, in their own vault. */
    val contacts: ContactBook by lazy { ContactBook(EncryptedRoomStorage(this, "kithmoot.contacts.v1")) }
}
