package dev.forgesworn.kithmoot.session

import android.content.SharedPreferences
import kotlin.test.*

class MediaStorageChoiceTest {
    @Test fun `ForgeSworn hosts are recognised whatever the case, path or trailing dot`() {
        assertTrue(isForgeSwornHost("https://kithmoot.forgesworn.dev"))
        assertTrue(isForgeSwornHost("https://KITHMOOT.forgesworn.dev/"))
        assertTrue(isForgeSwornHost("https://shelter.forgesworn.dev"))
        assertTrue(isForgeSwornHost("https://forgesworn.dev"))
        assertTrue(isForgeSwornHost("https://kithmoot.app"))
        assertTrue(isForgeSwornHost("https://files.kithmoot.app"))
        assertTrue(isForgeSwornHost("https://kithmoot.forgesworn.dev."))
        assertTrue(isForgeSwornHost("https://FORGESWORN.DEV.:8443/"))
        assertTrue(isForgeSwornHost("  https://kithmoot.forgesworn.dev  "))
    }

    @Test fun `only the host decides, so a ForgeSworn name elsewhere in the address is not one`() {
        assertFalse(isForgeSwornHost("https://notforgesworn.dev"))
        assertFalse(isForgeSwornHost("https://forgesworn.dev.example.com"))
        assertFalse(isForgeSwornHost("https://kithmoot.app.example.com"))
        assertFalse(isForgeSwornHost("https://evil.example/kithmoot.forgesworn.dev"))
        assertFalse(isForgeSwornHost("https://kithmoot.forgesworn.dev@evil.example"))
        assertTrue(isForgeSwornHost("https://evil.example@kithmoot.forgesworn.dev"), "the host after the @ is where the connection goes")
        assertFalse(isForgeSwornHost("https://blossom.yakihonne.com"))
    }

    @Test fun `anything that does not parse is not a ForgeSworn host`() {
        assertFalse(isForgeSwornHost(""))
        assertFalse(isForgeSwornHost("not a url"))
        assertFalse(isForgeSwornHost("forgesworn.dev"))
        assertFalse(isForgeSwornHost("https://"))
        assertFalse(isForgeSwornHost("%%%"))
    }

    @Test fun `the public list never names a ForgeSworn host and every origin is already canonical`() {
        assertTrue(PUBLIC_FILE_SERVERS.isNotEmpty())
        for (server in PUBLIC_FILE_SERVERS) {
            assertFalse(isForgeSwornHost(server.origin), server.origin)
            assertEquals(server.origin, mediaStorageOrigin(server.origin))
            assertTrue(server.operator.isNotBlank())
            assertTrue(server.maxBytes > 0)
            assertTrue(server.checked.matches(Regex("""\d{4}-\d{2}-\d{2}""")), server.checked)
        }
        assertEquals(listOf("https://blossom.yakihonne.com"), PUBLIC_FILE_SERVERS.map { it.origin })
    }

    @Test fun `a public server reads as its host, its operator and its largest file`() {
        assertEquals("blossom.yakihonne.com, run by Yakihonne LLC, files up to 200 MB", PUBLIC_FILE_SERVERS.single().label())
    }

    @Test fun `new consents are written as version 2`() {
        assertEquals(2, MEDIA_STORAGE_DISCLOSURE)
        assertEquals("https://github.com/forgesworn/kithmoot/blob/main/docs/run-your-own.md#files", RUN_YOUR_OWN_FILES_URL)
    }

    @Test fun `a version 1 consent to a ForgeSworn server is not honoured and one to any other server is`() {
        assertNull(rememberedMediaStorage("https://kithmoot.forgesworn.dev", 1))
        assertNull(rememberedMediaStorage("https://KITHMOOT.forgesworn.dev/", 1))
        assertNull(rememberedMediaStorage("https://kithmoot.forgesworn.dev.", 1))
        assertNull(rememberedMediaStorage("https://files.kithmoot.app", 1))
        assertEquals("https://files.example", rememberedMediaStorage("https://files.example", 1))
        assertEquals("https://blossom.yakihonne.com", rememberedMediaStorage("https://blossom.yakihonne.com", 1))
    }

    @Test fun `no consent to a ForgeSworn server is honoured, whichever version wrote it`() {
        assertNull(rememberedMediaStorage("https://kithmoot.forgesworn.dev", 2))
        assertNull(rememberedMediaStorage("https://KITHMOOT.forgesworn.dev/", 2))
        assertEquals("https://files.example", rememberedMediaStorage("https://files.example", 2))
    }

    @Test fun `a ForgeSworn server cannot be chosen, and any other HTTPS server can`() {
        for (origin in listOf("https://kithmoot.forgesworn.dev", "https://shelter.forgesworn.dev/", "https://files.kithmoot.app")) {
            assertEquals(FORGESWORN_STORAGE_CLOSED, assertFailsWith<IllegalArgumentException> { chosenMediaStorage(origin) }.message)
        }
        assertEquals("https://blossom.yakihonne.com", chosenMediaStorage("https://blossom.yakihonne.com/"))
        assertEquals("KithMoot no longer sends files to ForgeSworn’s storage.", FORGESWORN_STORAGE_CLOSED)
    }

    @Test fun `no consent, an unknown version or an invalid origin is never honoured`() {
        for (origin in listOf("https://files.example", "https://kithmoot.forgesworn.dev")) {
            assertNull(rememberedMediaStorage(origin, 0))
            assertNull(rememberedMediaStorage(origin, 3))
            assertNull(rememberedMediaStorage(origin, -1))
        }
        assertNull(rememberedMediaStorage(null, 2))
        assertNull(rememberedMediaStorage("http://files.example", 2))
        assertNull(rememberedMediaStorage("not a url", 2))
        assertNull(rememberedMediaStorage("https://files.example/path", 2))
    }

    @Test fun `a consent to a ForgeSworn server is retired once, whichever version wrote it, and nothing else is touched`() {
        for (version in listOf(1, 2)) {
            val prefs = FakePreferences("origin" to "https://kithmoot.forgesworn.dev", "disclosure" to version, "other" to "kept")
            assertTrue(retireForgeSwornStorage(prefs))
            assertNull(prefs.getString("origin", null))
            assertEquals(0, prefs.getInt("disclosure", 0))
            assertEquals("kept", prefs.getString("other", null))
            assertFalse(retireForgeSwornStorage(prefs), "the second call has nothing left to retire")
        }
    }

    @Test fun `a choice of another server is left alone, and an unagreed ForgeSworn one goes quietly`() {
        val other = FakePreferences("origin" to "https://files.example", "disclosure" to 1)
        assertFalse(retireForgeSwornStorage(other))
        assertEquals("https://files.example", other.getString("origin", null))
        assertEquals(1, other.getInt("disclosure", 0))

        val unaccepted = FakePreferences("origin" to "https://kithmoot.forgesworn.dev", "disclosure" to 0)
        assertFalse(retireForgeSwornStorage(unaccepted), "nothing had been agreed, so nothing to say")
        assertNull(unaccepted.getString("origin", null))

        assertFalse(retireForgeSwornStorage(FakePreferences()))
        assertFalse(retireForgeSwornStorage(FakePreferences("disclosure" to 1)))
    }

    @Test fun `a retired pair is not honoured on the next read either`() {
        val prefs = FakePreferences("origin" to "https://kithmoot.forgesworn.dev", "disclosure" to 1)
        retireForgeSwornStorage(prefs)
        assertNull(rememberedMediaStorage(prefs.getString("origin", null), prefs.getInt("disclosure", 0)))
    }
}

/** Just enough of the preferences for the two values MediaComposer keeps. */
private class FakePreferences(vararg initial: Pair<String, Any>) : SharedPreferences {
    val stored = mutableMapOf<String, Any>().apply { putAll(initial) }

    override fun getAll(): MutableMap<String, *> = stored.toMutableMap()
    override fun getString(key: String, defValue: String?): String? = stored[key] as String? ?: defValue
    override fun getInt(key: String, defValue: Int): Int = stored[key] as Int? ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getLong(key: String, defValue: Long): Long = defValue
    override fun getFloat(key: String, defValue: Float): Float = defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = defValue
    override fun contains(key: String): Boolean = key in stored
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun edit(): SharedPreferences.Editor = FakeEditor(stored)
}

private class FakeEditor(private val target: MutableMap<String, Any>) : SharedPreferences.Editor {
    private val pending = mutableListOf<() -> Unit>()
    private fun later(change: () -> Unit): SharedPreferences.Editor { pending += change; return this }

    override fun putString(key: String, value: String?) = later { if (value == null) target.remove(key) else target[key] = value }
    override fun putInt(key: String, value: Int) = later { target[key] = value }
    override fun putLong(key: String, value: Long) = later { target[key] = value }
    override fun putFloat(key: String, value: Float) = later { target[key] = value }
    override fun putBoolean(key: String, value: Boolean) = later { target[key] = value }
    override fun putStringSet(key: String, values: MutableSet<String>?) = later { if (values == null) target.remove(key) else target[key] = values }
    override fun remove(key: String) = later { target.remove(key) }
    override fun clear() = later { target.clear() }
    override fun commit(): Boolean { apply(); return true }
    override fun apply() { pending.forEach { it() }; pending.clear() }
}
