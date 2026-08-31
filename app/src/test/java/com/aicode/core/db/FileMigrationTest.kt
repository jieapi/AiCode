package com.aicode.core.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileMigrationTest {

    @Test
    fun fileMigration_propertiesAndVersion() {
        val statements = listOf(
            "CREATE TABLE test (id INTEGER PRIMARY KEY)",
            "ALTER TABLE test ADD COLUMN name TEXT"
        )
        val migration = FileMigration(
            version = 8,
            scriptName = "8_add_remote_servers.sql",
            sqlStatements = statements
        )

        // FileMigration 继承 Room Migration(startVersion, endVersion)
        // version 8 对应从 7 升级到 8
        assertEquals(7, migration.startVersion)
        assertEquals(8, migration.endVersion)
        assertEquals(8, migration.version)
        assertEquals("8_add_remote_servers.sql", migration.scriptName)
        assertEquals(2, migration.sqlStatements.size)
        assertEquals("CREATE TABLE test (id INTEGER PRIMARY KEY)", migration.sqlStatements[0])
    }

    @Test
    fun sqlSplitting_filtersEmptyStatements() {
        val rawSqlContent = """
            CREATE TABLE IF NOT EXISTS sample (
                id TEXT PRIMARY KEY,
                created_at INTEGER
            );
            
            -- 注释
            ALTER TABLE sample ADD COLUMN data TEXT;
            
            ; ;
        """.trimIndent()

        val statements = rawSqlContent.split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        assertEquals(2, statements.size)
        assertEquals(
            "CREATE TABLE IF NOT EXISTS sample (\n    id TEXT PRIMARY KEY,\n    created_at INTEGER\n)",
            statements[0]
        )
        assertEquals("-- 注释\nALTER TABLE sample ADD COLUMN data TEXT", statements[1])
    }

    @Test
    fun duplicateColumnMessage_matchesSqliteError() {
        assertTrue(
            SchemaCatchUp.isDuplicateColumnMessage(
                "duplicate column name: proxyEnabled (code 1 SQLITE_ERROR)"
            )
        )
        assertTrue(
            SchemaCatchUp.isDuplicateColumnMessage(
                "SQLiteException: Duplicate column name: sortOrder"
            )
        )
        assertFalse(SchemaCatchUp.isDuplicateColumnMessage("no such table: ai_providers"))
        assertFalse(SchemaCatchUp.isDuplicateColumnMessage(null))
    }

    @Test
    fun requiredColumns_coverRenumberedMigrations() {
        val names = SchemaCatchUp.requiredColumns.map { "${it.table}.${it.name}" }.toSet()
        assertTrue(names.contains("agent_messages.thinkingBlocksJson"))
        assertTrue(names.contains("llm_call_records.cacheCreationTokens"))
        assertTrue(names.contains("chat_sessions.parentId"))
        assertTrue(names.contains("ai_providers.proxyEnabled"))
        assertTrue(names.contains("ai_providers.sortOrder"))
    }
}
