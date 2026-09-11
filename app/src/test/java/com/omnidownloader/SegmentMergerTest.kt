package com.omnidownloader

import com.omnidownloader.download.http.SegmentMerger
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.io.path.createTempDirectory

class SegmentMergerTest {
    @Test fun mergesInOrderAndVerifiesChecksum() {
        val dir = createTempDirectory("omni-test").toFile(); val first = File(dir, "0").apply { writeText("hello ") }; val second = File(dir, "1").apply { writeText("world") }
        val output = ByteArrayOutputStream(); val hash = SegmentMerger.merge(listOf(first, second), output)
        assertEquals("hello world", output.toString(Charsets.UTF_8.name()))
        assertTrue(SegmentMerger.verifySha256(hash, "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"))
        assertFalse(SegmentMerger.verifySha256(hash, "bad")); dir.deleteRecursively()
    }
}
