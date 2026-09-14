package com.omnidownloader

import com.omnidownloader.data.update.GitHubUpdateRepository
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionTest {
    private val repository = GitHubUpdateRepository(OkHttpClient())

    @Test fun semanticVersionsAreComparedNumerically() {
        assertTrue(repository.isNewer("0.3.0", "0.2.9"))
        assertTrue(repository.isNewer("1.0.1", "1.0.0"))
        assertFalse(repository.isNewer("0.2.0", "0.2.0"))
        assertFalse(repository.isNewer("0.1.9", "0.2.0"))
    }
}
