package dev.forgesworn.kithmoot

import android.app.Application
import dev.forgesworn.kithmoot.account.AccountStore
import dev.forgesworn.kithmoot.storage.ContactBook
import dev.forgesworn.kithmoot.storage.EncryptedRoomStorage
import dev.forgesworn.kithmoot.storage.RoomRepository
import dev.forgesworn.kithmoot.relay.LinkTransportVault
import dev.forgesworn.kithmoot.relay.LinkTransportManager
import dev.forgesworn.kithmoot.relay.ReflectiveLinkTransportRuntime
import dev.forgesworn.kithmoot.relay.LinkConsentVault

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

    /** Link credentials have their own encrypted vault, distinct from rooms and accounts. */
    val linkTransport: LinkTransportVault by lazy { LinkTransportVault(EncryptedRoomStorage(this, "kithmoot.link-transport.v1")) }

    /** Account-and-room permissions are deliberately separate from Link route credentials. */
    val linkConsents: LinkConsentVault by lazy { LinkConsentVault(EncryptedRoomStorage(this, "kithmoot.link-consent.v1")) }

    /** One engine owner for the whole process; room consent selects any usable route later. */
    val linkEngine: LinkTransportManager by lazy { LinkTransportManager(linkTransport, ReflectiveLinkTransportRuntime()) }
}
