package dev.forgesworn.kithmoot.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.forgesworn.kithmoot.protocol.DisplayName
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Android uses ICU regexes; JVM vector tests cannot catch unsupported flags. */
@RunWith(AndroidJUnit4::class)
class DisplayNameAndroidTest {
    @Test fun hostile_and_visible_names_are_sanitised_on_android() {
        assertEquals("Robin admin", DisplayName.sanitise("\u202eRobin\n\u200badmin\ue000\ud800"))
        assertEquals("Robin admin", DisplayName.sanitise("Robin\u00a0\u2028\ufeffadmin"))
        assertEquals("雪 العربية 😀", DisplayName.sanitise(" 雪\u3000العربية 😀 "))
        assertEquals("😀".repeat(32), DisplayName.sanitise("😀".repeat(40)))
    }
}
