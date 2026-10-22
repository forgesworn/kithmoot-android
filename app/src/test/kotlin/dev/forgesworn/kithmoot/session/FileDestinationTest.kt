package dev.forgesworn.kithmoot.session

import kotlin.test.*

/** The web's `file-storage.test.ts` (kithmoot #527), for the cases Android has. */
class FileDestinationTest {
    private val default = PUBLIC_FILE_SERVERS.first().origin
    private val defaultLimit = PUBLIC_FILE_SERVERS.first().maxBytes
    private fun fresh() = FakePreferences()

    // file storage consent

    @Test fun `writes nothing until a person acts`() {
        val prefs = fresh()
        assertNull(storedFileServer(prefs))
        assertTrue(prefs.stored.isEmpty())
        assertEquals("File uploads are off on this device. Turn them on in Settings › Privacy › Where files go.", FILE_STORAGE_REQUIRED)
    }

    @Test fun `refuses an address that is not a plain https origin`() {
        val prefs = fresh()
        for (url in listOf("http://files.example", "https://user:pass@files.example", "https://files.example/path", "")) {
            assertFails { chooseFileServer(prefs, url) }
        }
        assertTrue(prefs.stored.isEmpty())
    }

    @Test fun `remembers a typed server as version 3 and a pressed notice as the default`() {
        val prefs = fresh()
        assertEquals("https://files.example", chooseFileServer(prefs, " https://Files.Example/ "))
        assertEquals(mapOf<String, Any>("mode" to "shared", "disclosure" to 3, "origin" to "https://files.example", "via" to "typed"), prefs.stored)
        assertEquals(default, acceptDefaultFileServer(prefs, default))
        assertEquals(mapOf<String, Any>("mode" to "shared", "disclosure" to 3, "origin" to default, "via" to "default"), prefs.stored)
        assertEquals(StoredFileServer(default, FileServerVia.DEFAULT), storedFileServer(prefs))
    }

    @Test fun `turning uploads off writes the choice down rather than forgetting it`() {
        val prefs = fresh()
        chooseFileServer(prefs, "https://files.example")
        stopFileUploads(prefs)
        assertEquals(mapOf<String, Any>("mode" to "off", "disclosure" to 3), prefs.stored)
        assertNull(storedFileServer(prefs))
        assertEquals(FileDestination.Off(chosen = true), resolveFileDestination(prefs))
    }

    @Test fun `fails closed for malformed or unsupported consent`() {
        val cases = listOf(
            mapOf("mode" to "private", "disclosure" to 1, "origin" to "https://files.example"),
            mapOf("mode" to "shared", "disclosure" to 3, "origin" to "https://files.example"),
            mapOf("mode" to "shared", "disclosure" to 3, "origin" to "https://files.example", "via" to "elsewhere"),
            mapOf("mode" to "shared", "disclosure" to 4, "origin" to "https://files.example", "via" to "typed"),
            mapOf("mode" to "shared", "disclosure" to "2", "origin" to "https://files.example"),
            mapOf("disclosure" to 1, "origin" to "http://files.example"),
            mapOf("disclosure" to 2, "origin" to "https://files.example/path"),
            mapOf("disclosure" to 0, "origin" to "https://files.example"),
            mapOf("origin" to 7, "disclosure" to 2),
        )
        for (case in cases) {
            val prefs = FakePreferences(*case.map { it.key to it.value }.toTypedArray())
            assertNull(storedFileServer(prefs), "$case")
            assertTrue(resolveFileDestination(prefs) is FileDestination.Default, "$case")
        }
    }

    // where a file goes

    @Test fun `an empty store resolves to the first public server, to be accepted by the person`() {
        assertEquals(FileDestination.Default(default, 209_715_200L), resolveFileDestination(fresh()))
    }

    @Test fun `a version 2 consent is a typed server`() {
        val prefs = FakePreferences("origin" to "https://files.example", "disclosure" to 2)
        assertEquals(FileDestination.Server("https://files.example", FileServerVia.TYPED), resolveFileDestination(prefs))
    }

    @Test fun `a version 2 consent to the public server is honoured and gets its size limit`() {
        val prefs = FakePreferences("origin" to default, "disclosure" to 2)
        val destination = resolveFileDestination(prefs)
        assertEquals(FileDestination.Server(default, FileServerVia.TYPED), destination)
        assertEquals(defaultLimit, destinationLimit(destination))
    }

    @Test fun `a version 3 default consent is honoured while it names the default`() {
        val prefs = fresh(); acceptDefaultFileServer(prefs, default)
        assertEquals(FileDestination.Server(default, FileServerVia.DEFAULT), resolveFileDestination(prefs))
    }

    @Test fun `Nowhere resolves to off, chosen`() {
        assertEquals(FileDestination.Off(chosen = true), resolveFileDestination(FakePreferences("mode" to "off")))
    }

    @Test fun `a default consent naming an older default tells the person again`() {
        val prefs = fresh(); acceptDefaultFileServer(prefs, "https://old-default.example")
        assertEquals(FileDestination.Default(default, defaultLimit), resolveFileDestination(prefs))
        // The same consent follows a default that is still the same.
        val stillDefault = listOf(PublicFileServer("https://old-default.example", "Old", 1_000L, "2026-10-01"))
        assertEquals(FileDestination.Server("https://old-default.example", FileServerVia.DEFAULT), resolveFileDestination(prefs, stillDefault))
    }

    @Test fun `a typed consent never falls through when the default changes`() {
        val prefs = fresh(); chooseFileServer(prefs, "https://mine.example")
        val other = listOf(PublicFileServer("https://other.example", "Other", 1_000L, "2026-10-01"))
        assertEquals(FileDestination.Server("https://mine.example", FileServerVia.TYPED), resolveFileDestination(prefs, other))
    }

    @Test fun `a consent of any version to a ForgeSworn host is not honoured, and is retired`() {
        for (version in listOf(1, 2, 3)) {
            val prefs = FakePreferences("mode" to "shared", "disclosure" to version, "origin" to "https://kithmoot.forgesworn.dev", "via" to "typed")
            assertTrue(resolveFileDestination(prefs) is FileDestination.Default, "version $version")
            assertTrue(retireForgeSwornStorage(prefs), "version $version")
            assertTrue(prefs.stored.isEmpty(), "version $version")
        }
    }

    @Test fun `refuses to choose a ForgeSworn host, and stores nothing`() {
        val prefs = fresh()
        assertEquals(FORGESWORN_STORAGE_CLOSED, assertFailsWith<IllegalArgumentException> { chooseFileServer(prefs, "https://kithmoot.forgesworn.dev") }.message)
        assertTrue(prefs.stored.isEmpty())
    }

    @Test fun `has no destination when there is no default to offer`() {
        assertEquals(FileDestination.Off(chosen = false), resolveFileDestination(fresh(), emptyList()))
    }

    @Test fun `formats a limit as whole megabytes`() {
        assertEquals("200 MB", formatLimit(209_715_200L))
        assertEquals("1.5 MB", formatLimit(1_572_864L))
    }

    @Test fun `the retired device meets the notice, accepts it, and is told again when the default changes`() {
        val prefs = FakePreferences("origin" to "https://kithmoot.forgesworn.dev", "disclosure" to 2)
        assertTrue(retireForgeSwornStorage(prefs))
        val first = resolveFileDestination(prefs)
        assertEquals(FileDestination.Default(default, defaultLimit), first)
        acceptDefaultFileServer(prefs, (first as FileDestination.Default).origin)
        assertEquals(FileDestination.Server(default, FileServerVia.DEFAULT), resolveFileDestination(prefs))
        val moved = listOf(PublicFileServer("https://next.example", "Next Ltd", 5_000L, "2026-11-01"))
        assertEquals(FileDestination.Default("https://next.example", 5_000L), resolveFileDestination(prefs, moved))
    }

    // words

    @Test fun `the notice says where files go and who runs the server, in the web's words`() {
        assertEquals("Files you send are stored on blossom.yakihonne.com", fileNoticeTitle(default))
        assertEquals("It’s a public server run by Yakihonne. Files are encrypted on this device first, so the server can’t open them, but anyone in this chat can. " +
            "The server sees your IP address, a key made for this device, when files are sent and opened, and how big they are. It decides how long to keep them.",
            fileNoticeBody(default))
        assertTrue(fileNoticeBody("https://files.deployer.example").startsWith("It’s the server chosen by whoever runs this copy of KithMoot."))
        assertEquals("Yakihonne", plainOperator("Yakihonne LLC"))
        assertEquals("Example", plainOperator("Example, Ltd."))
    }

    @Test fun `uploads off says why, with the action`() {
        assertEquals("File uploads are off on this device." to "Turn on file uploads", fileOffLine(FileDestination.Off(chosen = true)))
        assertEquals("This app has no file server set. Choose one to send files." to "Choose a server", fileOffLine(FileDestination.Off(chosen = false)))
    }

    @Test fun `a malformed off choice still reads as Nowhere, as the web's`() {
        // The web's fileUploadsChosenOff ignores the version too: pinned, not accidental.
        assertEquals(FileDestination.Off(chosen = true), resolveFileDestination(FakePreferences("mode" to "off", "disclosure" to 1, "origin" to "https://files.example")))
    }

    @Test fun `upload failures in the web's words, and only for what is recognised`() {
        val host = "blossom.yakihonne.com"
        fun words(error: Throwable, own: Boolean = false, limit: String? = "200 MB") = describeUploadFailure(error, host, "cat.png", "2.4 MB", limit, own)
        assertEquals("$host didn’t take cat.png. Check your connection and try again.", words(java.net.SocketTimeoutException("timeout")))
        assertEquals("$host takes files up to 200 MB. cat.png is 2.4 MB.", words(IllegalStateException("Storage refused this upload (413).")))
        assertEquals("$host won’t take a file as big as cat.png (2.4 MB).", words(IllegalStateException("Storage refused this upload (413)."), limit = null))
        assertEquals("$host won’t store cat.png. Choose another server.", words(IllegalStateException("Storage refused this upload (415).")))
        assertEquals("$host didn’t let this device upload. If it’s Wildbloom Node, let KithMoot in again in Settings.", words(IllegalStateException("Storage refused this upload (403)."), own = true))
        assertEquals("$host didn’t take cat.png. Check your connection and try again.", words(IllegalStateException("Storage refused this upload (403).")))
        assertEquals("$host didn’t take cat.png. Check your connection and try again.", words(IllegalStateException("Storage refused this upload (502).")))
        assertNull(words(IllegalStateException("The room changed or ended while uploading. The file will be deleted.")))
    }

    @Test fun `a file's size reads as the tray says it`() {
        assertEquals("300 B", formatFileSize(300))
        assertEquals("512 KB", formatFileSize(524_288))
        assertEquals("2.4 MB", formatFileSize(2_516_582))
        assertEquals("200 MB", formatFileSize(209_715_200))
    }
}
