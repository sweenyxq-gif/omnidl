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
import org.junit.Assert.assertEquals
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
        assertNull(resolver.registerScript(validScript().replace("// @omni-resolver true", "")))
        assertNull(resolver.registerScript(validScript().replace("// @omni-api 1", "// @omni-api 99")))
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
    fun browserOnlyUserscriptIsNotActivatedAsAResolver() {
        val resolver = UserscriptResolver(engine, UserscriptStorage(context))
        val scriptContent = context.assets.open("userscripts/all-in-one-video-downloader.user.js")
            .bufferedReader().use { it.readText() }
        val parsed = UserscriptMetadataParser.parse(scriptContent)
        assertNotNull(parsed)
        assertFalse(parsed!!.isOmniResolver)
        assertNull(resolver.registerScript(scriptContent))
    }

    @Test
    fun bundledCatalogOnlyActivatesOmniCompatibleResolvers() {
        val resolver = UserscriptResolver(engine, UserscriptStorage(context), context)
        val bundled = resolver.installedScripts.value.values.filter { it.builtIn }

        assertEquals(18, bundled.size)
        assertTrue(bundled.all { it.isOmniResolver && it.omniApiVersion == 1 })
        assertFalse(bundled.any { it.name.startsWith("All-in-One Video Downloader") })
    }

    @Test
    fun updateMustKeepIdentityAndPreservesEnabledState() {
        val resolver = UserscriptResolver(engine, UserscriptStorage(context))
        val installed = resolver.registerScript(validScript())!!
        resolver.setEnabled(installed.id, false)

        val updated = resolver.replaceScript(installed.id, validScript().replace("1.2.3", "1.3.0"))
        assertEquals("1.3.0", updated?.version)
        assertFalse(updated!!.enabled)
        assertNull(
            resolver.replaceScript(
                installed.id,
                validScript().replace("// @name Test Resolver", "// @name Different Resolver")
            )
        )
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
