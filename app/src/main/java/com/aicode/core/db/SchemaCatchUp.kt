package com.aicode.core.db

import androidx.sqlite.db.SupportSQLiteDatabase
import com.aicode.core.util.FileLogger

/**
 * 补齐历史上被改号/重排过的加法迁移列。
 *
 * 1.10.1 曾把 session_parent / provider_proxy / provider_sort_order
 * 编成 41/42/43，随后又在中间插入 thinking_blocks / cache_creation，
 * 把这三项改成 43/44/45。已升到旧 43 的库再跑新 44 会
 * `duplicate column name: proxyEnabled`，同时永远跳过 41/42 的新列。
 */
object SchemaCatchUp {

    data class AdditiveColumn(
        val table: String,
        val name: String,
        val definition: String
    )

    val requiredColumns: List<AdditiveColumn> = listOf(
        AdditiveColumn("agent_messages", "thinkingBlocksJson", "TEXT DEFAULT NULL"),
        AdditiveColumn("llm_call_records", "cacheCreationTokens", "INTEGER NOT NULL DEFAULT 0"),
        AdditiveColumn("chat_sessions", "parentId", "TEXT DEFAULT NULL"),
        AdditiveColumn("chat_sessions", "subagentType", "TEXT DEFAULT NULL"),
        AdditiveColumn("ai_providers", "proxyEnabled", "INTEGER NOT NULL DEFAULT 0"),
        AdditiveColumn("ai_providers", "proxyType", "TEXT NOT NULL DEFAULT 'HTTP'"),
        AdditiveColumn("ai_providers", "proxyHost", "TEXT NOT NULL DEFAULT ''"),
        AdditiveColumn("ai_providers", "proxyPort", "INTEGER NOT NULL DEFAULT 0"),
        AdditiveColumn("ai_providers", "proxyUsername", "TEXT NOT NULL DEFAULT ''"),
        AdditiveColumn("ai_providers", "proxyPassword", "TEXT NOT NULL DEFAULT ''"),
        AdditiveColumn("ai_providers", "sortOrder", "INTEGER NOT NULL DEFAULT 0")
    )

    fun isDuplicateColumnMessage(message: String?): Boolean {
        return message.orEmpty().contains("duplicate column name", ignoreCase = true)
    }

    fun existingColumns(db: SupportSQLiteDatabase, table: String): Set<String> {
        val result = mutableSetOf<String>()
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (nameIndex >= 0) {
                    result.add(cursor.getString(nameIndex))
                }
            }
        }
        return result
    }

    fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean {
        db.query(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
            arrayOf(table)
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    fun ensure(db: SupportSQLiteDatabase) {
        for ((table, columns) in requiredColumns.groupBy { it.table }) {
            if (!tableExists(db, table)) continue
            val existing = existingColumns(db, table)
            for (column in columns) {
                if (column.name in existing) continue
                val sql = "ALTER TABLE `$table` ADD COLUMN `${column.name}` ${column.definition}"
                try {
                    db.execSQL(sql)
                    FileLogger.i("SchemaCatchUp", "Added missing column ${table}.${column.name}")
                } catch (e: Exception) {
                    if (isDuplicateColumnMessage(e.message)) {
                        FileLogger.w(
                            "SchemaCatchUp",
                            "Column already present: ${table}.${column.name}"
                        )
                    } else {
                        throw e
                    }
                }
            }
        }
    }
}
