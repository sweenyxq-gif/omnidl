package com.omnidownloader.data.repository

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsStore by preferencesDataStore("settings")

data class AppSettings(
    val maxConcurrent: Int = 3,
    val connections: Int = 8,
    val defaultTreeUri: String = "",
    val wifiOnly: Boolean = false,
    val autoResume: Boolean = true,
    val retries: Int = 4,
    val theme: String = "SYSTEM",
    val userAgent: String = "OmniDownloader/0.1 Android",
    val ecoMode: Boolean = false,
    val automaticUpdateChecks: Boolean = true,
)

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private object Keys {
        val MAX = intPreferencesKey("max_concurrent"); val CONNECTIONS = intPreferencesKey("connections")
        val TREE = stringPreferencesKey("default_tree_uri"); val WIFI = booleanPreferencesKey("wifi_only")
        val RESUME = booleanPreferencesKey("auto_resume"); val RETRIES = intPreferencesKey("retries")
        val THEME = stringPreferencesKey("theme"); val UA = stringPreferencesKey("user_agent")
        val ECO = booleanPreferencesKey("eco_mode")
        val AUTO_UPDATES = booleanPreferencesKey("automatic_update_checks")
    }
    val settings: Flow<AppSettings> = context.settingsStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { p ->
            AppSettings(
                maxConcurrent = (p[Keys.MAX] ?: 3).coerceIn(1, 8),
                connections = (p[Keys.CONNECTIONS] ?: 8).coerceIn(1, 16),
                defaultTreeUri = p[Keys.TREE].orEmpty(),
                wifiOnly = p[Keys.WIFI] ?: false,
                autoResume = p[Keys.RESUME] ?: true,
                retries = (p[Keys.RETRIES] ?: 4).coerceIn(0, 10),
                theme = p[Keys.THEME].takeIf { it in setOf("SYSTEM", "LIGHT", "DARK") } ?: "SYSTEM",
                userAgent = p[Keys.UA] ?: "OmniDL Android",
                ecoMode = p[Keys.ECO] ?: false,
                automaticUpdateChecks = p[Keys.AUTO_UPDATES] ?: true,
            )
        }
    suspend fun setMaxConcurrent(value: Int) = context.settingsStore.edit { it[Keys.MAX] = value.coerceIn(1, 8) }
    suspend fun setConnections(value: Int) = context.settingsStore.edit { it[Keys.CONNECTIONS] = value.coerceIn(1, 16) }
    suspend fun setDefaultTree(uri: String) = context.settingsStore.edit { it[Keys.TREE] = uri }
    suspend fun setWifiOnly(value: Boolean) = context.settingsStore.edit { it[Keys.WIFI] = value }
    suspend fun setAutoResume(value: Boolean) = context.settingsStore.edit { it[Keys.RESUME] = value }
    suspend fun setRetries(value: Int) = context.settingsStore.edit { it[Keys.RETRIES] = value.coerceIn(0, 10) }
    suspend fun setTheme(value: String) = context.settingsStore.edit { it[Keys.THEME] = value }
    suspend fun setEcoMode(value: Boolean) = context.settingsStore.edit { it[Keys.ECO] = value }
    suspend fun setAutomaticUpdateChecks(value: Boolean) = context.settingsStore.edit { it[Keys.AUTO_UPDATES] = value }
}
