package com.aicode.feature.settings.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkVerifierTest {

    @Test
    fun higherVersionCode_isNewer() {
        assertTrue(isNewerInstall(1185, "1.13.1", 1184, "1.13.0"))
        assertTrue(isNewerInstall(1185, null, 1184, null))
    }

    @Test
    fun lowerVersionCode_isNotNewer() {
        assertFalse(isNewerInstall(1183, "1.13.0", 1184, "1.13.0"))
    }

    @Test
    fun sameVersionCode_releaseOverRc_isNewer() {
        assertTrue(isNewerInstall(1184, "1.13.0", 1184, "1.13.0-rc2"))
    }

    @Test
    fun sameVersionCode_sameVersionName_isNotNewer() {
        assertFalse(isNewerInstall(1184, "1.13.0", 1184, "1.13.0"))
    }

    @Test
    fun sameVersionCode_lowerVersionName_isNotNewer() {
        assertFalse(isNewerInstall(1184, "1.13.0-rc2", 1184, "1.13.0"))
    }

    @Test
    fun sameVersionCode_missingVersionName_isNotNewer() {
        assertFalse(isNewerInstall(1184, null, 1184, "1.13.0-rc2"))
        assertFalse(isNewerInstall(1184, "1.13.0", 1184, " "))
    }
}
