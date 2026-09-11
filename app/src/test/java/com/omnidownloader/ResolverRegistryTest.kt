package com.omnidownloader

import com.omnidownloader.download.resolver.*
import org.junit.Assert.*
import org.junit.Test

class ResolverRegistryTest {
    @Test fun selectsOnlyMatchingResolver() {
        val registry = ResolverRegistry(listOf(DirectLinkResolver()))
        assertEquals("direct", registry.select("https://example.com/file")?.id)
        assertNull(registry.select("magnet:?xt=urn:btih:x"))
    }
}
