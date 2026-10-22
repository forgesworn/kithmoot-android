package dev.forgesworn.kithmoot.session

import android.content.SharedPreferences

/** Just enough of the preferences for the two values MediaComposer keeps. */
internal class FakePreferences(vararg initial: Pair<String, Any>) : SharedPreferences {
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

internal class FakeEditor(private val target: MutableMap<String, Any>) : SharedPreferences.Editor {
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
