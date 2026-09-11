package com.omnidownloader.data.userscript

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserscriptStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private fun getPrefs(scriptId: String): SharedPreferences {
        val safeName = "userscript_store_" + scriptId.replace(Regex("[^a-zA-Z0-9_]"), "_")
        return context.getSharedPreferences(safeName, Context.MODE_PRIVATE)
    }

    fun getValue(scriptId: String, key: String, defaultValue: String? = null): String? {
        return getPrefs(scriptId).getString(key, defaultValue)
    }

    fun setValue(scriptId: String, key: String, value: String) {
        getPrefs(scriptId).edit().putString(key, value).apply()
    }

    fun deleteValue(scriptId: String, key: String) {
        getPrefs(scriptId).edit().remove(key).apply()
    }

    fun listValues(scriptId: String): List<String> {
        return getPrefs(scriptId).all.keys.toList()
    }

    fun clear(scriptId: String) {
        getPrefs(scriptId).edit().clear().apply()
    }
}
