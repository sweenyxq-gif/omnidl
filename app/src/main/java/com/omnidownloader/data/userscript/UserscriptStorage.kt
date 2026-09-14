package com.omnidownloader.data.userscript

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import com.omnidownloader.domain.userscript.UserscriptMetadata
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserscriptStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val registry by lazy { context.getSharedPreferences("userscript_registry", Context.MODE_PRIVATE) }

    fun loadScripts(): List<UserscriptMetadata> {
        val saved = registry.getString("scripts", "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(saved)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val raw = item.getString("rawScript")
                    UserscriptMetadataParser.parse(raw)?.let {
                        add(it.copy(enabled = item.optBoolean("enabled", true)))
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveScripts(scripts: Collection<UserscriptMetadata>) {
        val array = JSONArray()
        scripts.filterNot { it.builtIn }.forEach { script ->
            array.put(JSONObject().apply {
                put("rawScript", script.rawScript)
                put("enabled", script.enabled)
            })
        }
        registry.edit().putString("scripts", array.toString()).apply()
    }

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
