package com.omnidownloader.data.repository

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
)

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private object Keys {
        val MAX = intPreferencesKey("max_concurrent"); val CONNECTIONS = intPreferencesKey("connections")
        val TREE = stringPreferencesKey("default_tree_uri"); val WIFI = booleanPreferencesKey("wifi_only")
        val RESUME = booleanPreferencesKey("auto_resume"); val RETRIES = intPreferencesKey("retries")
        val THEME = stringPreferencesKey("theme"); val UA = stringPreferencesKey("user_agent")
    }
    val settings: Flow<AppSettings> = context.settingsStore.data.map { p -> AppSettings(p[Keys.MAX] ?: 3, p[Keys.CONNECTIONS] ?: 8, p[Keys.TREE] ?: "", p[Keys.WIFI] ?: false, p[Keys.RESUME] ?: true, p[Keys.RETRIES] ?: 4, p[Keys.THEME] ?: "SYSTEM", p[Keys.UA] ?: "OmniDownloader/0.1 Android") }
    suspend fun setMaxConcurrent(value: Int) = context.settingsStore.edit { it[Keys.MAX] = value.coerceIn(1, 8) }
    suspend fun setConnections(value: Int) = context.settingsStore.edit { it[Keys.CONNECTIONS] = value.coerceIn(1, 16) }
    suspend fun setDefaultTree(uri: String) = context.settingsStore.edit { it[Keys.TREE] = uri }
    suspend fun setWifiOnly(value: Boolean) = context.settingsStore.edit { it[Keys.WIFI] = value }
    suspend fun setAutoResume(value: Boolean) = context.settingsStore.edit { it[Keys.RESUME] = value }
    suspend fun setTheme(value: String) = context.settingsStore.edit { it[Keys.THEME] = value }
}
