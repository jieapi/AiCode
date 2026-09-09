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
        // 整段迁移包在事务里：任一条语句失败整体回滚，避免留半迁移状态
        db.beginTransaction()
        try {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS migration_history (" +
                        "version INTEGER PRIMARY KEY, " +
                        "script_name TEXT, " +
                        "executed_at INTEGER)"
            )
            for (sql in sqlStatements) {
                if (sql.isNotBlank()) {
                    try {
                        db.execSQL(sql)
                    } catch (e: Exception) {
                        // 历史迁移脚本存在重复编号/重复列（如 44 曾拆成两个文件），
                        // 重复加列对已有库是常见情况，跳过并记录，避免整个迁移链崩溃。
                        if (e.message?.contains("duplicate column name") == true) {
                            FileLogger.w("MigrationLoader", "Skip $scriptName: column already exists, sql: $sql")
                        } else {
                            throw e
                        }
                    }
                }
            }
            db.execSQL(
                "INSERT INTO migration_history (version, script_name, executed_at) VALUES (?, ?, ?)",
                arrayOf<Any>(version, scriptName, System.currentTimeMillis())
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
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
            
            val statements = SqlScriptSplitter.split(sqlContent)
            
            migrations.add(FileMigration(version, fileName, statements))
        }
        
        return migrations.toTypedArray()
    }
}
