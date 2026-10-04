package com.aicode.feature.backup.domain

import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupMetadataTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun chatBackupDoesNotIncludeAppSettings() {
        val metadata = BackupMetadata(schemaVersion = 55, createdAt = 0)
        val restored = json.decodeFromString<BackupMetadata>(json.encodeToString(BackupMetadata.serializer(), metadata))
        assertFalse(restored.includesAppSettings)
    }

    @Test
    fun legacyMetadataInfersSettingsFromThemeMode() {
        assertTrue(json.decodeFromString<BackupMetadata>(
            """{"schemaVersion":55,"createdAt":0,"themeMode":"DARK"}"""
        ).includesAppSettings)
        assertFalse(json.decodeFromString<BackupMetadata>(
            """{"schemaVersion":55,"createdAt":0,"themeMode":null}"""
        ).includesAppSettings)
    }

    @Test
    fun explicitSettingsScopeOverridesLegacyInference() {
        assertFalse(json.decodeFromString<BackupMetadata>(
            """{"schemaVersion":55,"createdAt":0,"themeMode":"DARK","includesAppSettings":false}"""
        ).includesAppSettings)
    }

    @Test
    fun legacySnapshotPreservesSettingsScope() {
        assertFalse(BackupSnapshot(schemaVersion = 55, createdAt = 0).toMetadata().includesAppSettings)
        assertTrue(BackupSnapshot(schemaVersion = 55, createdAt = 0, themeMode = "AUTO").toMetadata().includesAppSettings)
    }
}
