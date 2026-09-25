package com.chronicle.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "chronicle_prefs")

enum class ThemeMode { SYSTEM, PAPER, INK }

data class ChroniclePrefs(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dailyNudgeEnabled: Boolean = false,
    /** Minutes after midnight for the daily writing nudge. */
    val dailyNudgeMinutes: Int = 20 * 60,
)

class PrefsRepository(private val context: Context) {

    val prefs: Flow<ChroniclePrefs> = context.dataStore.data.map { p ->
        ChroniclePrefs(
            themeMode = p[KEY_THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            dailyNudgeEnabled = p[KEY_NUDGE_ENABLED] ?: false,
            dailyNudgeMinutes = p[KEY_NUDGE_MINUTES] ?: (20 * 60),
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[KEY_THEME] = mode.name }
    }

    suspend fun setDailyNudge(enabled: Boolean) {
        context.dataStore.edit { it[KEY_NUDGE_ENABLED] = enabled }
    }

    suspend fun setDailyNudgeMinutes(minutes: Int) {
        context.dataStore.edit { it[KEY_NUDGE_MINUTES] = minutes }
    }

    private companion object {
        val KEY_THEME = stringPreferencesKey("theme_mode")
        val KEY_NUDGE_ENABLED = booleanPreferencesKey("daily_nudge_enabled")
        val KEY_NUDGE_MINUTES = intPreferencesKey("daily_nudge_minutes")
    }
}
