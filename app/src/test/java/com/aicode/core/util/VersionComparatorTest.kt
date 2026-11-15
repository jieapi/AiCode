package com.aicode.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionComparatorTest {

    // ---- parseVersionTag ----

    @Test
    fun parseVersionTag_stripsVPrefix() {
        assertEquals("1.7.0", parseVersionTag("v1.7.0"))
        assertEquals("1.7.0-rc1", parseVersionTag("v1.7.0-rc1"))
    }

    @Test
    fun parseVersionTag_withoutVPrefix() {
        assertEquals("1.7.0", parseVersionTag("1.7.0"))
        assertEquals("1.7.0-dev.2", parseVersionTag("1.7.0-dev.2"))
    }

    @Test
    fun parseVersionTag_trimsWhitespace() {
        assertEquals("1.7.0", parseVersionTag("  v1.7.0  "))
    }

    @Test
    fun parseVersionTag_returnsNullForBlank() {
        assertNull(parseVersionTag(""))
        assertNull(parseVersionTag("   "))
    }

    @Test
    fun parseVersionTag_takesSegmentBeforeSpace() {
        assertEquals("1.7.0", parseVersionTag("1.7.0 extra metadata"))
    }

    // ---- splitVersion ----

    @Test
    fun splitVersion_plainRelease() {
        val (base, pre) = splitVersion("1.7.0")
        assertEquals("1.7.0", base)
        assertEquals("", pre)
    }

    @Test
    fun splitVersion_preRelease() {
        val (base, pre) = splitVersion("1.7.0-rc1")
        assertEquals("1.7.0", base)
        assertEquals("rc1", pre)
    }

    @Test
    fun splitVersion_stripsBuildHash() {
        val (base, pre) = splitVersion("1.7.0-dev.2+g04bc2fa")
        assertEquals("1.7.0", base)
        assertEquals("dev.2", pre)
    }

    // ---- compareVersions ----

    @Test
    fun compareVersions_equal() {
        assertEquals(0, compareVersions("1.7.0", "1.7.0"))
        assertEquals(0, compareVersions("1.7.0-rc1", "1.7.0-rc1"))
    }

    @Test
    fun compareVersions_higherBaseVersion() {
        assertTrue(compareVersions("1.7.0", "1.6.0") > 0)
        assertTrue(compareVersions("2.0.0", "1.9.9") > 0)
    }

    @Test
    fun compareVersions_lowerBaseVersion() {
        assertTrue(compareVersions("1.6.0", "1.7.0") < 0)
        assertTrue(compareVersions("1.9.9", "2.0.0") < 0)
    }

    @Test
    fun compareVersions_releaseVsPreRelease() {
        // 正式版 > 预发布版
        assertTrue(compareVersions("1.7.0", "1.7.0-rc1") > 0)
        assertTrue(compareVersions("1.7.0-rc1", "1.7.0") < 0)
    }

    @Test
    fun compareVersions_preReleaseOrdering() {
        // rc2 > rc1
        assertTrue(compareVersions("1.7.0-rc2", "1.7.0-rc1") > 0)
        assertTrue(compareVersions("1.7.0-rc1", "1.7.0-rc2") < 0)
    }

    @Test
    fun compareVersions_devVsRc() {
        // "dev" < "rc" (lexicographic)
        assertTrue(compareVersions("1.7.0-rc1", "1.7.0-dev.2+g04bc2fa") > 0)
    }

    @Test
    fun compareVersions_differentSegmentCount() {
        // 1.7 > 1.7.0 because 1.7 maps to [1,7], 1.7.0 maps to [1,7,0]
        // getOrElse fills missing with 0, so they're equal
        assertEquals(0, compareVersions("1.7", "1.7.0"))
    }

    // ---- isUpToDate ----

    @Test
    fun sameVersion_isUpToDate() {
        assertTrue(isUpToDate("1.7.0", "1.7.0"))
        assertTrue(isUpToDate("1.7.0-rc1", "1.7.0-rc1"))
    }

    @Test
    fun currentIsRc_latestIsRelease_needsUpdate() {
        assertFalse(isUpToDate("1.7.0", "1.7.0-rc1"))
    }

    @Test
    fun currentIsDevBuild_latestIsRc_needsUpdate() {
        assertFalse(isUpToDate("1.7.0-rc1", "1.7.0-dev.2+g04bc2fa"))
    }

    @Test
    fun currentIsOlder_needsUpdate() {
        assertFalse(isUpToDate("1.7.0", "1.6.0"))
    }

    @Test
    fun currentIsNewer_isUpToDate() {
        assertTrue(isUpToDate("1.7.0", "1.8.0-dev"))
    }

    @Test
    fun latestEqualsCurrentWithBuildHash_isUpToDate() {
        // 1.7.0 == 1.7.0+g04bc2fa (build hash stripped)
        assertTrue(isUpToDate("1.7.0", "1.7.0+g04bc2fa"))
    }
}
