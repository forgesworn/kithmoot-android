package dev.forgesworn.kithmoot.ui

import dev.forgesworn.kithmoot.session.FakePreferences
import dev.forgesworn.kithmoot.session.FileDestination
import dev.forgesworn.kithmoot.session.FileServerVia
import dev.forgesworn.kithmoot.session.acceptDefaultFileServer
import dev.forgesworn.kithmoot.session.chooseFileServer
import dev.forgesworn.kithmoot.session.stopFileUploads
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What Settings and Room info say about where files go (spec 9.5 and 9.6). */
class FileChoiceTest {
    private val yaki = "https://blossom.yakihonne.com"

    @Test fun `the Privacy row says where files go`() {
        assertEquals("A public server · blossom.yakihonne.com", fileChoiceOf(FakePreferences(), false).summary())
        val accepted = FakePreferences().also { acceptDefaultFileServer(it, yaki) }
        assertEquals("A public server · blossom.yakihonne.com", fileChoiceOf(accepted, false).summary())
        val own = FakePreferences().also { chooseFileServer(it, "https://files.example") }
        assertEquals("My own server · files.example", fileChoiceOf(own, false).summary())
        val off = FakePreferences().also { stopFileUploads(it) }
        assertEquals("Nowhere", fileChoiceOf(off, false).summary())
        assertEquals("Choose a server", FileChoice(FileDestination.Off(chosen = false)).summary())
    }

    @Test fun `the own server field shows only an address the person typed`() {
        assertEquals("https://files.example", fileChoiceOf(FakePreferences().also { chooseFileServer(it, "https://files.example") }, false).ownServer)
        assertEquals("", fileChoiceOf(FakePreferences().also { chooseFileServer(it, yaki, FileServerVia.TYPED) }, false).ownServer)
        assertEquals("", fileChoiceOf(FakePreferences().also { acceptDefaultFileServer(it, yaki) }, false).ownServer)
    }

    @Test fun `Room info says where files sent in the room go`() {
        assertEquals("Files you send in this room go to blossom.yakihonne.com.", fileChoiceOf(FakePreferences(), false).roomLine())
        assertEquals("Files you send in this room go to files.example.",
            fileChoiceOf(FakePreferences().also { chooseFileServer(it, "https://files.example") }, false).roomLine())
        assertEquals("Files: you’ve turned uploads off on this device.", fileChoiceOf(FakePreferences().also { stopFileUploads(it) }, false).roomLine())
        assertNull(FileChoice(FileDestination.Off(chosen = false)).roomLine())
    }
}
