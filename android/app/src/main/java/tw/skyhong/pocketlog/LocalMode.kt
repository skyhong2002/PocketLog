package tw.skyhong.pocketlog

import android.content.Context

/**
 * Local-only mode: no server connection. App sessions, edits, reflections and reviews stay in
 * [LocalJournal] on this phone. Screen context, models and other server features are unavailable.
 */
class LocalMode(context: Context) {
    private val prefs = context.getSharedPreferences("local_mode", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { check(prefs.edit().putBoolean("enabled", value).commit()) }
}
