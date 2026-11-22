package com.aicode.feature.plugin.data.index

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginLogoCachePolicyTest {

    @Test
    fun cacheRemainsFreshUntilTtlExpires() {
        val now = PLUGIN_LOGO_CACHE_TTL_MS * 3

        assertTrue(isPluginLogoCacheFresh(now - PLUGIN_LOGO_CACHE_TTL_MS + 1, now))
        assertFalse(isPluginLogoCacheFresh(now - PLUGIN_LOGO_CACHE_TTL_MS, now))
        assertFalse(isPluginLogoCacheFresh(now - PLUGIN_LOGO_CACHE_TTL_MS - 1, now))
    }
}
