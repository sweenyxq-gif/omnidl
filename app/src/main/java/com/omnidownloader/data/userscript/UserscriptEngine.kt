package com.omnidownloader.data.userscript

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.omnidownloader.domain.model.SourceType
import com.omnidownloader.domain.resolver.ResolvedItem
import com.omnidownloader.domain.userscript.ScriptNetworkLog
import com.omnidownloader.domain.userscript.UserscriptExecutionResult
import com.omnidownloader.domain.userscript.UserscriptMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URI
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

interface UserscriptEngine {
    suspend fun execute(
        metadata: UserscriptMetadata,
        targetUrl: String
    ): UserscriptExecutionResult
}

@Singleton
class AndroidUserscriptEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sandbox: ResolverNetworkSandbox,
    private val storage: UserscriptStorage
) : UserscriptEngine {

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun execute(
        metadata: UserscriptMetadata,
        targetUrl: String
    ): UserscriptExecutionResult = withContext(Dispatchers.Main) {
        val startTime = System.currentTimeMillis()
        val logs = Collections.synchronizedList(mutableListOf<String>())
        val networkCalls = Collections.synchronizedList(mutableListOf<ScriptNetworkLog>())
        val resolvedItems = Collections.synchronizedList(mutableListOf<ResolvedItem>())
        val completionDeferred = CompletableDeferred<Unit>()
        val addLog: (String) -> Unit = { message ->
            if (logs.size < 200) logs.add(message.take(2_000))
        }

        var webView: WebView? = null
        try {
            webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                settings.blockNetworkLoads = true
                settings.loadsImagesAutomatically = false
                setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true)
            }

            val bridge = object {
                @JavascriptInterface
                fun log(message: String) {
                    addLog(message)
                }

                @JavascriptInterface
                fun resolve(jsonStr: String) {
                    try {
                        val json = JSONObject(jsonStr)
                        val url = json.optString("url").takeIf { it.isNotBlank() } ?: return
                        if (resolvedItems.size >= 50) {
                            addLog("Ignored extra result: scripts may return at most 50 links")
                            return
                        }
                        val scheme = runCatching { URI(url).scheme?.lowercase() }.getOrNull()
                        if (scheme !in setOf("http", "https", "magnet", "ftp", "sftp")) {
                            addLog("Blocked unsupported resolved URL scheme: ${scheme ?: "missing"}")
                            return
                        }
                        val label = json.optString("label").takeIf { it.isNotBlank() } ?: metadata.name
                        val filename = json.optString("filename").takeIf { it.isNotBlank() }
                        val mimeType = json.optString("mimeType").takeIf { it.isNotBlank() }
                        val size = if (json.has("size")) json.optLong("size") else null
                        val quality = json.optString("quality").takeIf { it.isNotBlank() }
                        val typeStr = json.optString("type", "HTTP").uppercase()
                        val type = when (typeStr) {
                            "MAGNET" -> SourceType.MAGNET
                            "HLS" -> SourceType.HLS
                            "DASH" -> SourceType.DASH
                            "TORRENT" -> SourceType.TORRENT_FILE
                            "FTP" -> SourceType.FTP
                            "SFTP" -> SourceType.SFTP
                            else -> SourceType.HTTP
                        }

                        val headers = mutableMapOf<String, String>()
                        json.optJSONObject("headers")?.let { h ->
                            val keys = h.keys()
                            while (keys.hasNext() && headers.size < 32) {
                                val k = keys.next()
                                headers[k] = h.getString(k)
                            }
                        }

                        resolvedItems.add(
                            ResolvedItem(
                                label = label,
                                url = url,
                                filename = filename,
                                type = type,
                                mimeType = mimeType,
                                size = size,
                                quality = quality,
                                headers = headers
                            )
                        )
                        completionDeferred.complete(Unit)
                    } catch (e: Exception) {
                        addLog("Error parsing resolve payload: ${e.message}")
                    }
                }

                @JavascriptInterface
                fun getValue(key: String, def: String): String {
                    if ("GM_getValue" !in metadata.grants) return def
                    return storage.getValue(metadata.id, key, def) ?: def
                }

                @JavascriptInterface
                fun setValue(key: String, value: String) {
                    if ("GM_setValue" !in metadata.grants) return
                    storage.setValue(metadata.id, key, value)
                }

                @JavascriptInterface
                fun deleteValue(key: String) {
                    if ("GM_deleteValue" !in metadata.grants) return
                    storage.deleteValue(metadata.id, key)
                }

                @JavascriptInterface
                fun listValuesJson(): String {
                    if ("GM_listValues" !in metadata.grants) return "[]"
                    val list = storage.listValues(metadata.id)
                    return org.json.JSONArray(list).toString()
                }

                @JavascriptInterface
                @Suppress("UNUSED_PARAMETER")
                fun safeHttpCall(id: String, method: String, url: String, headersJson: String, body: String?): String {
                    if ("GM_xmlhttpRequest" !in metadata.grants) {
                        return JSONObject().put("error", "Network access requires @grant GM_xmlhttpRequest").toString()
                    }
                    val headersMap = mutableMapOf<String, String>()
                    try {
                        val h = JSONObject(headersJson)
                        val keys = h.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            headersMap[k] = h.getString(k)
                        }
                    } catch (e: Exception) {
                        // ignore empty/malformed header json
                    }

                    return runBlocking(Dispatchers.IO) {
                        try {
                            val res = sandbox.executeSafeRequest(
                                metadata = metadata,
                                method = method,
                                url = url,
                                headers = headersMap,
                                body = body
                            ) { netLog ->
                                networkCalls.add(netLog)
                            }
                            JSONObject().apply {
                                put("status", res.statusCode)
                                put("statusText", res.statusText)
                                put("headers", JSONObject(res.headers))
                                put("body", res.bodyText)
                                put("finalUrl", res.finalUrl)
                            }.toString()
                        } catch (e: Exception) {
                            JSONObject().apply {
                                put("error", e.message ?: "Network error")
                            }.toString()
                        }
                    }
                }
            }

            webView.addJavascriptInterface(bridge, "_OmniNative")

            val polyfill = """
                (function() {
                    window.omni = {
                        resolve: function(data) {
                            if (typeof data === 'string') {
                                _OmniNative.resolve(JSON.stringify({ url: data }));
                            } else {
                                _OmniNative.resolve(JSON.stringify(data));
                            }
                        },
                        log: function(msg) {
                            _OmniNative.log(String(msg));
                        },
                        fetch: async function(url, options) {
                            options = options || {};
                            var method = options.method || 'GET';
                            var headers = options.headers || {};
                            var body = options.body || null;
                            var respJson = _OmniNative.safeHttpCall('fetch', method, url, JSON.stringify(headers), body);
                            var resp = JSON.parse(respJson);
                            if (resp.error) throw new Error(resp.error);
                            return {
                                status: resp.status,
                                statusText: resp.statusText,
                                headers: resp.headers,
                                ok: resp.status >= 200 && resp.status < 300,
                                url: resp.finalUrl,
                                text: async function() { return resp.body; },
                                json: async function() { return JSON.parse(resp.body); }
                            };
                        },
                        storage: {
                            get: function(k, d) { return _OmniNative.getValue(k, d || ""); },
                            set: function(k, v) { _OmniNative.setValue(k, String(v)); }
                        }
                    };

                    window.GM_log = window.omni.log;
                    window.unsafeWindow = window;
                    window.GM_getValue = function(k, d) { return _OmniNative.getValue(k, d || ""); };
                    window.GM_setValue = function(k, v) { _OmniNative.setValue(k, String(v)); };
                    window.GM_deleteValue = function(k) { _OmniNative.deleteValue(k); };
                    window.GM_listValues = function() { return JSON.parse(_OmniNative.listValuesJson()); };
                    window.GM_addStyle = function(css) {
                        try {
                            var style = document.createElement('style');
                            style.textContent = css;
                            (document.head || document.documentElement || document.body).appendChild(style);
                        } catch(e) {}
                    };
                    window.GM_openInTab = function(url, options) {
                        window.omni.log("GM_openInTab: " + url);
                        if (typeof url === 'string' && (url.indexOf('http') === 0 || url.indexOf('magnet:') === 0)) {
                            window.omni.resolve({ url: url, label: "Resolved Stream Link", type: "HTTP" });
                        }
                    };
                    window.GM = window.GM || {};
                    window.GM.openInTab = window.GM_openInTab;
                    window.GM_download = function(options, filename) {
                        var target = typeof options === 'string' ? { url: options, name: filename } : options;
                        window.omni.resolve({
                            url: target.url,
                            filename: target.name || filename,
                            label: target.name || "Downloaded Media",
                            type: "HTTP"
                        });
                    };
                    window.GM_setClipboard = function(text) {};
                    window.GM_notification = function(details) { window.omni.log(typeof details === 'string' ? details : (details.text || "")); };
                    window.GM_registerMenuCommand = function() {};
                    window.GM_info = { script: { version: "1.0", name: "Userscript" } };
                    window.GM_xmlhttpRequest = function(details) {
                        try {
                            var method = details.method || 'GET';
                            var url = details.url;
                            var headers = details.headers || {};
                            var data = details.data || null;
                            var respJson = _OmniNative.safeHttpCall('gm_xhr', method, url, JSON.stringify(headers), data);
                            var resp = JSON.parse(respJson);
                            if (resp.error) {
                                if (details.onerror) details.onerror({ error: resp.error });
                            } else {
                                if (details.onload) details.onload({
                                    status: resp.status,
                                    statusText: resp.statusText,
                                    responseText: resp.body,
                                    responseHeaders: resp.headers
                                });
                            }
                        } catch(e) {
                            if (details.onerror) details.onerror({ error: e.message });
                        }
                    };
                })();
            """.trimIndent()

            val runnerCode = """
                $polyfill
                (async function() {
                    try {
                        var targetUrl = ${JSONObject.quote(targetUrl)};
                        ${metadata.rawScript}
                    } catch (err) {
                        omni.log("Runtime Exception: " + err.message);
                    }
                })();
            """.trimIndent()

            webView.evaluateJavascript(runnerCode, null)

            // Wait up to 15 seconds for resolution or completion
            withTimeoutOrNull(15_000) {
                completionDeferred.await()
            }
            // A resolver may emit several links in one run. Give queued JS callbacks a short
            // settling window instead of destroying the WebView after the first result.
            if (resolvedItems.isNotEmpty()) delay(300)

            val elapsed = System.currentTimeMillis() - startTime
            UserscriptExecutionResult(
                success = resolvedItems.isNotEmpty(),
                resolvedItems = resolvedItems.toList(),
                logs = logs.toList(),
                networkCalls = networkCalls.toList(),
                executionTimeMs = elapsed,
                error = if (resolvedItems.isEmpty()) "Script completed without calling omni.resolve()" else null
            )
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            UserscriptExecutionResult(
                success = false,
                resolvedItems = emptyList(),
                logs = logs.toList(),
                networkCalls = networkCalls.toList(),
                executionTimeMs = elapsed,
                error = e.message ?: "Execution failed"
            )
        } finally {
            webView?.destroy()
        }
    }
}
