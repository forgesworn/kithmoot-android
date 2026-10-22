package dev.forgesworn.kithmoot.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A file its server no longer has reads the same as on the web's card (kithmoot #530). */
class GoneFileNoteTest {
    @Test fun `a 404 or a 410 says the file is no longer on its host`() {
        val url = "https://kithmoot.forgesworn.dev/blossom/" + "a".repeat(64)
        for (code in listOf(404, 410)) assertEquals("This file is no longer on kithmoot.forgesworn.dev.", goneFileNote(url, code))
    }

    @Test fun `any other answer keeps its own words`() {
        for (code in listOf(200, 401, 403, 500, 502)) assertNull(goneFileNote("https://blossom.example/x", code))
    }

    @Test fun `a URL with no host still gets a sentence`() {
        assertEquals("This file is no longer on its server.", goneFileNote("not a url", 410))
    }
}
