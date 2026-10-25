package dev.forgesworn.kithmoot.media

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** No URL, hostname, username or credential reaches a log line (design point 12). */
class PublicRelayLogTest {
    private val viewModel = File("src/main/kotlin/dev/forgesworn/kithmoot/ui/RoomViewModel.kt").readText()
    /** Every `${...}` and `$name` template in a line, braces matched by depth. */
    private fun interpolations(line: String): List<String> {
        val found = ArrayList<String>()
        var i = 0
        while (i < line.length) {
            if (line[i] == '$' && i + 1 < line.length && line[i + 1] == '{') {
                var depth = 1
                var j = i + 2
                while (j < line.length && depth > 0) { if (line[j] == '{') depth++ else if (line[j] == '}') depth--; j++ }
                found += line.substring(i + 2, j - 1)
                i = j
            } else if (line[i] == '$' && i + 1 < line.length && (line[i + 1].isLetter() || line[i + 1] == '_')) {
                var j = i + 1
                while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '_')) j++
                found += line.substring(i + 1, j)
                i = j
            } else i++
        }
        return found
    }

    @Test fun theServerCountLineCarriesCountsOnly() {
        val line = viewModel.lines().single { it.contains("Log.i(JOIN_LOG, \"ice resolved") }
        assertEquals(
            listOf("ice.size", "ice.count { server -> server.urls.any { it.startsWith(\"turn\") } }", "hideIp",
                "android.os.SystemClock.elapsedRealtime() - begun", "spent[0]", "spent[1]", "spent[2]"),
            interpolations(line),
        )
    }

    @Test fun nothingAboutARelayIsLoggedOnTheNewPaths() {
        val files = listOf("CallIceSelector.kt", "PinnedPublicRelay.kt", "PublicCallRelays.kt", "PublicRelayCall.kt", "PublicRelayCheck.kt",
            "PublicRelayLogin.kt", "PublicAddress.kt", "PublicCallRelayStore.kt").map { File("src/main/kotlin/dev/forgesworn/kithmoot/media/$it") } +
            File("src/main/kotlin/dev/forgesworn/kithmoot/ui/settings/PublicCallRelaysPanel.kt")
        for (file in files) {
            assertTrue(file.exists(), file.path)
            val text = file.readText()
            assertFalse(Regex("""\bLog\.[a-z]+\(|println\(|printStackTrace\(""").containsMatchIn(text), file.path)
        }
    }

    @Test fun theViewModelsRelayLogLinesInterpolateNoRelayDetail() {
        val forbidden = Regex("(?i)url|host|user|credential|password|login|relay\\b|servers\\b|pinned|advert")
        val lines = viewModel.lines().filter { it.contains("Log.") && (it.contains("relay", true) || it.contains("ice ", true)) }
        assertTrue(lines.isNotEmpty())
        for (line in lines) for (part in interpolations(line)) {
            // `server.urls` is only counted, never printed.
            if (part.startsWith("ice.count")) continue
            assertFalse(forbidden.containsMatchIn(part), "$part in $line")
        }
    }
}
