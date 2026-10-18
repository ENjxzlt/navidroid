package dev.navidroid.data

/** Einfache Einstellungs-Ablage: Android über SharedPreferences, Linux über eine JSON-Datei. */
interface KeyValue {
    fun getString(key: String): String?
    fun getInt(key: String, default: Int): Int
    fun getBoolean(key: String, default: Boolean): Boolean
    fun edit(block: Editor.() -> Unit)

    interface Editor {
        fun putString(key: String, value: String?)
        fun putInt(key: String, value: Int)
        fun putBoolean(key: String, value: Boolean)
        fun clear()
    }
}
