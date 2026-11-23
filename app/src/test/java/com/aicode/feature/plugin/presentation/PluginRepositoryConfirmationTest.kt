package com.aicode.feature.plugin.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PluginRepositoryConfirmationTest {

    @Test
    fun shortGitHubRepositoryMatchesFullAddress() {
        assertEquals(
            normalizeRepositoryInput("https://github.com/wxichen/aicode-plugins"),
            normalizeRepositoryInput("wxichen/aicode-plugins")
        )
    }

    @Test
    fun differentRepositoryDoesNotMatch() {
        assertNotEquals(
            normalizeRepositoryInput("wxichen/aicode-plugins"),
            normalizeRepositoryInput("wxichen/other-plugin")
        )
    }
}
