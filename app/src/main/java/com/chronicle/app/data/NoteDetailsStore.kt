package com.chronicle.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.noteDetails by preferencesDataStore(name = "chronicle_note_details")

/** Small attribution sidecar; journal contents and the Room schema stay unchanged. */
class NoteDetailsStore(private val context: Context) {
    val labels = context.noteDetails.data.map { prefs ->
        prefs.asMap().mapNotNull { (key, value) ->
            if (key.name.startsWith("engine_")) key.name.removePrefix("engine_").toLongOrNull()
                ?.let { it to value.toString() } else null
        }.toMap()
    }

    suspend fun save(id: Long, label: String) {
        context.noteDetails.edit { it[stringPreferencesKey("engine_$id")] = label }
    }

    val cleanups = context.noteDetails.data.map { prefs ->
        prefs.asMap().mapNotNull { (key, value) ->
            if (!key.name.startsWith("cleanup_")) return@mapNotNull null
            val id = key.name.removePrefix("cleanup_").toLongOrNull() ?: return@mapNotNull null
            runCatching {
                val json = JSONObject(value.toString())
                id to NoteCleanupRevision(json.getString("original"), json.getString("applied"), json.getString("status"))
            }.getOrNull()
        }.toMap()
    }

    suspend fun saveCleanup(id: Long, revision: NoteCleanupRevision) {
        context.noteDetails.edit {
            it[stringPreferencesKey("cleanup_$id")] = JSONObject()
                .put("original", revision.original).put("applied", revision.applied)
                .put("status", revision.status).toString()
        }
    }

    suspend fun clearCleanup(id: Long) {
        context.noteDetails.edit { it.remove(stringPreferencesKey("cleanup_$id")) }
    }
}

data class NoteCleanupRevision(val original: String, val applied: String, val status: String)
