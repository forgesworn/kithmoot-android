package dev.forgesworn.kithmoot.ui.start

import dev.forgesworn.kithmoot.account.ContactInboxView
import dev.forgesworn.kithmoot.account.SignetContact
import dev.forgesworn.kithmoot.account.SignetDirectoryView
import dev.forgesworn.kithmoot.account.shortNpub
import dev.forgesworn.kithmoot.ui.ContactRow
import dev.forgesworn.kithmoot.ui.StartState
import dev.forgesworn.kithmoot.ui.room.PublicProfile
import kotlin.test.*

class PeopleSectionsTest {
    private fun key(n: Int) = "%02x".format(n).repeat(32)
    private fun card(n: Int, name: String?) = ContactRow(key(n), "npub-$n", name, emptyList(), 0)

    private val state = StartState(
        signetContacts = SignetDirectoryView("ready", listOf(SignetContact(key(1), "rowan"), SignetContact(key(2), "Ada"),
            SignetContact(key(9), "Blocked")), blocked = setOf(key(9))),
        contactCards = listOf(card(2, "Ada from her card"), card(3, "Priya")),
        follows = listOf(key(5), key(1), key(4), key(9)),
        followProfiles = mapOf(key(4) to PublicProfile("Zed", null, 1, "e")),
        contactInbox = ContactInboxView(ready = true),
    )

    @Test fun `contacts merge Signet and cards by key, A to Z, Signet's name first, blocked left out`() {
        val contacts = peopleSections(state).contacts
        assertEquals(listOf(key(2), key(3), key(1)), contacts.map { it.pubkey })
        assertEquals(Person(key(2), "Ada", signet = true, card = true), contacts[0])
        assertTrue(contacts.last().follow)
    }

    @Test fun `following keeps the list's order without contacts or the blocked`() {
        assertEquals(listOf(key(5), key(4)), peopleSections(state).following.map { it.pubkey })
    }

    @Test fun `a row never shows a full npub, only a name or a short key`() {
        val following = peopleSections(state).following
        assertEquals("Zed", personLabel(following[1], state.followProfiles))
        assertEquals(shortNpub(key(5)), personLabel(following[0], state.followProfiles))
    }

    @Test fun `search matches names and keys in both sections`() {
        assertEquals(listOf(key(3)), peopleSections(state, "pri").contacts.map { it.pubkey })
        assertEquals(listOf(key(4)), peopleSections(state, "zed").following.map { it.pubkey })
        assertTrue(peopleSections(state, "nobody here").let { it.contacts.isEmpty() && it.following.isEmpty() })
    }

    @Test fun `a Signet contact or a follow can be reached, a card alone cannot`() {
        val sections = peopleSections(state)
        assertTrue(canReach(state, sections.contacts.first { it.pubkey == key(1) }))
        assertTrue(canReach(state, sections.following.first()))
        assertFalse(canReach(state, sections.contacts.first { it.pubkey == key(3) }))
        assertFalse(canReach(state.copy(contactInbox = ContactInboxView(ready = false)), sections.following.first()))
        assertFalse(canReach(state.copy(signetContacts = state.signetContacts.copy(status = "revoked")), sections.contacts.first { it.pubkey == key(2) }))
    }
}
