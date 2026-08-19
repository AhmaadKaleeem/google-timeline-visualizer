package dev.mahlernim.timelinevisualizer.data

import android.content.Context
import android.net.Uri
import androidx.core.content.edit

class TimelineSourceStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): Uri? = preferences.getString(KEY_URI, null)?.let(Uri::parse)

    fun replace(uri: Uri): Boolean = preferences.edit()
        .putString(KEY_URI, uri.toString())
        .commit()

    fun beginImport(uri: Uri): Boolean = preferences.edit()
        .putString(KEY_IMPORT_IN_PROGRESS_URI, uri.toString())
        .commit()

    fun completeImport(uri: Uri) {
        if (importInProgress() == uri) {
            preferences.edit().remove(KEY_IMPORT_IN_PROGRESS_URI).commit()
        }
    }

    fun recoverInterruptedImport(): Uri? {
        val pending = importInProgress() ?: return null
        val remembered = load()
        val interruptedRemembered = remembered?.takeIf { it == pending }
        preferences.edit().apply {
            remove(KEY_IMPORT_IN_PROGRESS_URI)
            if (interruptedRemembered != null) remove(KEY_URI)
        }.commit()
        return interruptedRemembered
    }

    fun clear(): Uri? {
        val previous = load()
        preferences.edit {
            remove(KEY_URI)
            remove(KEY_IMPORT_IN_PROGRESS_URI)
        }
        return previous
    }

    internal fun importInProgress(): Uri? =
        preferences.getString(KEY_IMPORT_IN_PROGRESS_URI, null)?.let(Uri::parse)

    internal fun clearForTest() {
        preferences.edit { clear() }
    }

    companion object {
        private const val PREFERENCES_NAME = "timeline_source"
        private const val KEY_URI = "document_uri_v1"
        private const val KEY_IMPORT_IN_PROGRESS_URI = "import_in_progress_uri_v1"
    }
}
