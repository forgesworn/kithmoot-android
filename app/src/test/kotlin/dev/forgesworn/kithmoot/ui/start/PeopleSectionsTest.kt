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

    @Test fun `an inviter is named by Signet, then their card, then a known profile, else by key`() {
        assertEquals("Ada", invitationSender(state, key(2)))
        assertEquals("Priya", invitationSender(state, key(3)))
        assertEquals("Zed", invitationSender(state, key(4)))
        assertEquals(shortNpub(key(7)), invitationSender(state, key(7)))
        assertEquals(shortNpub(key(6)), invitationSender(state.copy(contactCards = listOf(card(6, " "))), key(6)))
    }

    @Test fun `somebody blocked on this phone leaves both lists and cannot be reached`() {
        val here = state.copy(blockedHere = listOf(dev.forgesworn.kithmoot.account.DeviceBlock(key(2), 1), dev.forgesworn.kithmoot.account.DeviceBlock(key(5), 2)))
        val sections = peopleSections(here)
        assertFalse(sections.contacts.any { it.pubkey == key(2) })
        assertFalse(sections.following.any { it.pubkey == key(5) })
        assertFalse(canReach(here, Person(key(5), null, follow = true)))
        assertTrue(canReach(state, Person(key(5), null, follow = true)))
    }

    @Test fun `contacts merge Signet and cards by key, A to Z, Signet's name first, blocked left out`() {
        val contacts = peopleSections(state).contacts
        assertEquals(listOf(key(2), key(3), key(1)), contacts.map { it.pubkey })
        assertEquals(Person(key(2), "Ada", signet = true, card = true), contacts[0])
        assertTrue(contacts.last().follow)
    }

    @Test fun `following is A to Z by profile name, the unnamed last by key, without contacts or the blocked`() {
        assertEquals(listOf(key(4), key(5)), peopleSections(state).following.map { it.pubkey })
        val more = state.copy(follows = listOf(key(7), key(6), key(5), key(8)),
            followProfiles = state.followProfiles + mapOf(key(6) to PublicProfile("amy", null, 1, "f"), key(8) to PublicProfile("Bea", null, 1, "g")))
        assertEquals(listOf(key(6), key(8), key(5), key(7)), peopleSections(more).following.map { it.pubkey })
    }

    @Test fun `sublines say tier and checked for Signet, Contact card for a card, nothing for a follow`() {
        assertEquals("Kith · checked", personSubline(Person(key(1), "R", signet = true, tier = "Kith", checked = true)))
        assertEquals("Ken", personSubline(Person(key(1), "R", signet = true, tier = "Ken")))
        assertEquals("checked", personSubline(Person(key(1), "R", signet = true, checked = true)))
        assertEquals("Signet", personSubline(Person(key(1), "R", signet = true, card = true)))
        assertEquals("Contact card", personSubline(Person(key(3), "P", card = true)))
        // A follow's Nostr address is never shown unverified, and nothing here verifies one.
        assertNull(personSubline(Person(key(4), null, follow = true)))
    }

    @Test fun `a row never shows a full npub, only a name or a short key`() {
        val following = peopleSections(state).following
        assertEquals("Zed", personLabel(following[0], state.followProfiles))
        assertEquals(shortNpub(key(5)), personLabel(following[1], state.followProfiles))
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
