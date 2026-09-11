package com.omnidownloader.download.http

import java.io.File
import java.io.OutputStream
import java.security.MessageDigest

object SegmentMerger {
    fun merge(files: List<File>, output: OutputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.forEach { file -> file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read); output.write(buffer, 0, read) }
        } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun verifySha256(actual: String, expected: String?) = expected.isNullOrBlank() || actual.equals(expected.trim(), true)
}
