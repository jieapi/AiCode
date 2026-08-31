package com.aicode.core.db

import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aicode.core.util.FileLogger

class FileMigration(
    val version: Int,
    val scriptName: String,
    val sqlStatements: List<String>
) : Migration(version - 1, version) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS migration_history (" +
                    "version INTEGER PRIMARY KEY, " +
                    "script_name TEXT, " +
                    "executed_at INTEGER)"
        )
        for (sql in sqlStatements) {
            if (sql.isBlank()) continue
            try {
                db.execSQL(sql)
            } catch (e: Exception) {
                // 旧包把 proxy/sort 编成 42/43，新包改成 44/45 后会重复 ADD COLUMN。
                if (SchemaCatchUp.isDuplicateColumnMessage(e.message)) {
                    FileLogger.w(
                        "MigrationLoader",
                        "Skip already-applied column in $scriptName: $sql"
                    )
                } else {
                    throw e
                }
            }
        }
        SchemaCatchUp.ensure(db)
        db.execSQL(
            "INSERT OR REPLACE INTO migration_history (version, script_name, executed_at) VALUES (?, ?, ?)",
            arrayOf<Any>(version, scriptName, System.currentTimeMillis())
        )
        FileLogger.i("MigrationLoader", "Applied migration: $scriptName")
    }
}

object MigrationLoader {
    fun loadMigrations(context: Context): Array<Migration> {
        val assetManager = context.assets
        val migrationsDir = "migrations"
        val files = runCatching { assetManager.list(migrationsDir) }.getOrNull() ?: emptyArray()

        val migrations = mutableListOf<Migration>()

        // File format: {version}_{description}.sql, e.g., "7_add_workspace_path.sql"
        for (fileName in files) {
            if (!fileName.endsWith(".sql")) continue

            val versionStr = fileName.substringBefore('_')
            val version = versionStr.toIntOrNull() ?: continue

            val sqlContent = runCatching {
                assetManager.open("$migrationsDir/$fileName").bufferedReader().use { it.readText() }
            }.getOrNull() ?: continue

            val statements = sqlContent.split(";")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            migrations.add(FileMigration(version, fileName, statements))
        }

        return migrations.toTypedArray()
    }
}
