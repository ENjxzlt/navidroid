package dev.navidroid.data

import android.content.Context
import android.content.SharedPreferences

/** SharedPreferences als [KeyValue] für die Stores im gemeinsamen Modul. */
class PrefsKeyValue(private val prefs: SharedPreferences) : KeyValue {
    constructor(context: Context, name: String) : this(context.getSharedPreferences(name, Context.MODE_PRIVATE))

    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

    override fun edit(block: KeyValue.Editor.() -> Unit) {
        val e = prefs.edit()
        object : KeyValue.Editor {
            override fun putString(key: String, value: String?) { e.putString(key, value) }
            override fun putInt(key: String, value: Int) { e.putInt(key, value) }
            override fun putBoolean(key: String, value: Boolean) { e.putBoolean(key, value) }
            override fun clear() { e.clear() }
        }.block()
        e.apply()
    }
}
