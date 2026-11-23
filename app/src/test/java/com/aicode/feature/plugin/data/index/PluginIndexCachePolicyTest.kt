package com.aicode.feature.plugin.data.index

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginIndexCachePolicyTest {

    @Test
    fun cacheRemainsFreshForOneHour() {
        val now = PLUGIN_INDEX_CACHE_TTL_MS * 3

        assertTrue(isPluginIndexCacheFresh(now - PLUGIN_INDEX_CACHE_TTL_MS + 1, now))
        assertFalse(isPluginIndexCacheFresh(now - PLUGIN_INDEX_CACHE_TTL_MS, now))
        assertFalse(isPluginIndexCacheFresh(0, now))
    }
}
