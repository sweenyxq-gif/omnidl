package com.omnidownloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.omnidownloader.data.userscript.UserscriptEngine
import com.omnidownloader.data.userscript.UserscriptMetadataParser
import com.omnidownloader.data.userscript.UserscriptResolver
import com.omnidownloader.data.userscript.UserscriptStorage
import com.omnidownloader.data.userscript.ExtensionCatalog
import com.omnidownloader.domain.userscript.UserscriptExecutionResult
import com.omnidownloader.domain.userscript.UserscriptMetadata
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UserscriptManagementTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val engine = object : UserscriptEngine {
        override suspend fun execute(metadata: UserscriptMetadata, targetUrl: String) =
            UserscriptExecutionResult(success = false)
    }

    @Before
    @After
    fun clearRegistry() {
        context.getSharedPreferences("userscript_registry", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun installedScriptPersistsAndEnabledStateControlsMatching() {
        val first = UserscriptResolver(engine, UserscriptStorage(context))
        val installed = first.registerScript(validScript())
        assertNotNull(installed)
        assertTrue(first.canHandle("https://downloads.example.test/item/42"))

        val restored = UserscriptResolver(engine, UserscriptStorage(context))
        assertNotNull(restored.getScript(installed!!.id))
        restored.setEnabled(installed.id, false)
        assertFalse(restored.canHandle("https://downloads.example.test/item/42"))

        val restoredDisabled = UserscriptResolver(engine, UserscriptStorage(context))
        assertFalse(restoredDisabled.getScript(installed.id)!!.enabled)
        assertTrue(restoredDisabled.unregisterScript(installed.id))
        assertNull(UserscriptResolver(engine, UserscriptStorage(context)).getScript(installed.id))
    }

    @Test
    fun unsupportedGrantAndMissingUrlRulesAreRejected() {
        val resolver = UserscriptResolver(engine, UserscriptStorage(context))
        assertNull(resolver.registerScript(validScript().replace("// @grant GM_log", "// @grant GM_unsupportedDangerousGrant")))
        assertNull(resolver.registerScript(validScript().replace("// @match https://downloads.example.test/*", "")))
        assertNull(resolver.registerScript(validScript().replace("// ==/UserScript==", "")))
    }

    @Test
    fun matchRulesHonorExclusions() {
        val metadata = UserscriptMetadataParser.parse(
            validScript().replace(
                "// @match https://downloads.example.test/*",
                "// @match https://downloads.example.test/*\n// @exclude https://downloads.example.test/private/*"
            )
        )!!
        assertTrue(UserscriptMetadataParser.matchesUrl(metadata, "https://downloads.example.test/public/file"))
        assertFalse(UserscriptMetadataParser.matchesUrl(metadata, "https://downloads.example.test/private/file"))
    }

    @Test
    fun curatedExtensionsHaveValidInstallableMetadataAndScopedHosts() {
        val resolver = UserscriptResolver(engine, UserscriptStorage(context))
        assertTrue(ExtensionCatalog.scripts.size >= 3)
        ExtensionCatalog.scripts.forEach { extension ->
            assertNotNull(resolver.registerScript(extension.rawScript))
            assertTrue(extension.matches.isNotEmpty())
            assertTrue(extension.connects.isNotEmpty())
            assertFalse(extension.connects.contains("*"))
        }
        assertTrue(resolver.canHandle("https://gitlab.com/acme/tool/-/releases/v1"))
        assertTrue(resolver.canHandle("https://archive.org/details/public-item"))
        assertTrue(resolver.canHandle("https://sourceforge.net/projects/example/files/releases/"))
    }

    @Test
    fun allInOneVideoDownloaderParsesAndInstallsSuccessfully() {
        val resolver = UserscriptResolver(engine, UserscriptStorage(context))
        val scriptFile = java.io.File("../userscripts/all-in-one-video-downloader.user.js")
        if (scriptFile.exists()) {
            val scriptContent = scriptFile.readText()
            val parsed = UserscriptMetadataParser.parse(scriptContent)
            assertNotNull(parsed)
            assertTrue(parsed!!.includes.isNotEmpty())
            assertTrue(parsed.grants.contains("GM_download"))
            val installed = resolver.registerScript(scriptContent)
            assertNotNull(installed)
            assertTrue(resolver.canHandle("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
            assertTrue(resolver.canHandle("https://www.instagram.com/p/C_abc/"))
            assertTrue(resolver.canHandle("https://x.com/user/status/123"))
        }
    }

    private fun validScript() = """
        // ==UserScript==
        // @name Test Resolver
        // @namespace test.omnidl
        // @version 1.2.3
        // @match https://downloads.example.test/*
        // @grant GM_log
        // @connect downloads.example.test
        // @omni-resolver true
        // @omni-api 1
        // ==/UserScript==
        omni.resolve(targetUrl);
    """.trimIndent()
}
