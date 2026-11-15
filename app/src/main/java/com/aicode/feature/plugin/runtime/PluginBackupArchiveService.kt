package com.aicode.feature.plugin.runtime

import com.aicode.core.util.FileLogger
import com.aicode.feature.plugin.data.PluginBackupPermissionRepository
import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.PluginHook
import com.aicode.feature.plugin.domain.PluginHookEvents
import com.aicode.feature.plugin.domain.PluginHookModes
import com.aicode.feature.plugin.domain.PluginHostPaths
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginManifestParser
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.PathHomeResolver
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 备份包里的插件扩展区段。
 *
 * 导出前宿主调用插件的 `backup.export.before`，插件把额外数据写入自己的暂存目录
 * （`data://backup-export`）；宿主把该目录打包为 `plugins/<pluginId>/…`。
 * 导入时宿主把该区段解到 `data://backup-import`，还原完主数据后再调用
 * `backup.import.finished`，由插件把数据写回真实路径。
 *
 * 只有启用、声明 `contributes.backup`、获得使用者授权并声明对应 hook 的插件参与；
 * 暂存目录在每次操作后清理，失败只记日志，不影响备份或还原本身。
 */
@Singleton
class PluginBackupArchiveService @Inject constructor(
    private val pluginManager: PluginManager,
    private val runtime: PluginRuntimeManager,
    private val permissionRepository: PluginBackupPermissionRepository,
    private val fileAccess: FileAccessProvider,
    private val homeResolver: PathHomeResolver
) {

    data class Section(val pluginId: String, val directory: String)

    /** 让选中的插件写入导出暂存目录；返回需要打包的区段。 */
    suspend fun prepareExport(pluginIds: Set<String>): List<Section> = withContext(Dispatchers.IO) {
        if (pluginIds.isEmpty()) return@withContext emptyList()
        try {
            pluginManager.ensureLoaded()
            val granted = permissionRepository.grants.first().keys
            val sections = mutableListOf<Section>()
            for (plugin in pluginManager.plugins.value) {
                if (!plugin.isActive || plugin.content.backup == null ||
                    plugin.id !in pluginIds || plugin.id !in granted
                ) {
                    continue
                }
                val hooks = plugin.content.hooks.filter {
                    it.event == PluginHookEvents.BACKUP_EXPORT_BEFORE && it.mode == PluginHookModes.OBSERVE
                }
                if (hooks.isEmpty()) continue
                val directory = stagingDir(plugin.id, EXPORT_DIR)
                try {
                    fileAccess.deleteRecursively(directory)
                    fileAccess.mkdirs(directory)
                    val payload = buildJsonObject {
                        put("dir", "${PluginHostPaths.DATA_SCHEME}$EXPORT_DIR")
                    }
                    hooks.forEach { invoke(plugin, it, payload) }
                    sections += Section(plugin.id, directory)
                } catch (e: CancellationException) {
                    cleanupDirectory(directory)
                    throw e
                } catch (e: Exception) {
                    FileLogger.w(TAG, "插件导出暂存失败: ${plugin.id}", e)
                    cleanupDirectory(directory)
                }
            }
            sections
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "准备插件导出区段失败", e)
            emptyList()
        }
    }

    fun listExportFiles(section: Section): List<String> =
        runCatching { fileAccess.listFilesRecursive(section.directory, MAX_DEPTH) }.getOrDefault(emptyList())

    fun readExportFile(section: Section, relativePath: String, maxBytes: Long): ByteArray =
        fileAccess.readBytes(section.directory + "/" + relativePath, maxBytes)

    /**
     * 为备份包中的某个插件区段准备导入暂存目录；插件未安装、停用、未声明备份或未授权时返回 null，
     * 由调用方丢弃该区段。
     */
    suspend fun importTarget(pluginId: String): String? = withContext(Dispatchers.IO) {
        if (!PluginManifestParser.isValidPluginId(pluginId)) return@withContext null
        try {
            pluginManager.ensureLoaded()
            val plugin = pluginManager.plugins.value.firstOrNull {
                it.id == pluginId && it.isActive && it.content.backup != null
            } ?: return@withContext null
            if (!permissionRepository.isGranted(pluginId)) return@withContext null
            val directory = stagingDir(plugin.id, IMPORT_DIR)
            fileAccess.deleteRecursively(directory)
            fileAccess.mkdirs(directory)
            directory
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "准备插件导入暂存目录失败: $pluginId", e)
            null
        }
    }

    fun writeImportFile(directory: String, relativePath: String, bytes: ByteArray): Boolean = try {
        fileAccess.writeBytes(directory + "/" + relativePath, bytes, overwrite = true)
        true
    } catch (e: Exception) {
        FileLogger.w(TAG, "写入插件导入区段失败: $relativePath", e)
        false
    }

    /** 主数据还原完成后通知有区段的插件恢复自己的数据，并清理暂存目录。 */
    suspend fun finishImport(pluginIds: Set<String>, stats: JsonObject) = withContext(Dispatchers.IO) {
        if (pluginIds.isEmpty()) return@withContext
        try {
            pluginManager.ensureLoaded()
            val granted = permissionRepository.grants.first().keys
            for (plugin in pluginManager.plugins.value) {
                if (plugin.id !in pluginIds) continue
                val directory = stagingDir(plugin.id, IMPORT_DIR)
                try {
                    val files = runCatching {
                        fileAccess.listFilesRecursive(directory, MAX_DEPTH)
                    }.getOrDefault(emptyList())
                    val hooks = plugin.content.hooks.filter {
                        it.event == PluginHookEvents.BACKUP_IMPORT_FINISHED && it.mode == PluginHookModes.OBSERVE
                    }
                    if (plugin.isActive && plugin.content.backup != null && plugin.id in granted &&
                        files.isNotEmpty() && hooks.isNotEmpty()
                    ) {
                        val payload = buildJsonObject {
                            put("dir", "${PluginHostPaths.DATA_SCHEME}$IMPORT_DIR")
                            put("files", files.size)
                            put("stats", stats)
                        }
                        hooks.forEach { invoke(plugin, it, payload) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    FileLogger.w(TAG, "插件导入恢复失败: ${plugin.id}", e)
                } finally {
                    cleanupDirectory(directory)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "通知插件导入区段失败", e)
        }
    }

    fun cleanupExport(sections: List<Section>) {
        sections.forEach { cleanupDirectory(it.directory) }
    }

    fun cleanupImport(pluginIds: Set<String>) {
        pluginIds.forEach { cleanupDirectory(stagingDir(it, IMPORT_DIR)) }
    }

    private suspend fun invoke(plugin: InstalledPlugin, hook: PluginHook, payload: JsonObject) {
        val result = runtime.invoke(
            pluginId = plugin.id,
            version = plugin.record.version,
            runtime = hook.runtime,
            handler = hook.handler,
            args = payload,
            method = "hook.invoke",
            timeoutMs = HOOK_TIMEOUT_MS
        )
        writeLogs(plugin, hook, result)
    }

    private fun writeLogs(plugin: InstalledPlugin, hook: PluginHook, result: JsonElement) {
        val logs = (result as? JsonObject)?.get("logs") as? JsonArray ?: return
        logs.take(MAX_LOG_LINES).forEach { item ->
            val text = (item as? JsonPrimitive)?.contentOrNull?.take(MAX_LOG_CHARS) ?: return@forEach
            FileLogger.i(TAG, "[${plugin.id}/${hook.localId}] $text")
        }
    }

    private fun stagingDir(pluginId: String, name: String): String =
        PluginHostPaths.dataRoot(homeResolver, pluginId).trimEnd('/') + "/$name"

    private fun cleanupDirectory(directory: String) {
        runCatching { fileAccess.deleteRecursively(directory) }
    }

    /** 归档区段路径安全：相对路径、无空段或 `..`、深度有限。 */
    fun isValidSectionPath(path: String): Boolean = PluginManifestParser.isSafeRelativePath(path)

    private companion object {
        const val TAG = "PluginBackup"
        const val EXPORT_DIR = "backup-export"
        const val IMPORT_DIR = "backup-import"
        const val HOOK_TIMEOUT_MS = 60_000L
        const val MAX_DEPTH = 8
        const val MAX_LOG_LINES = 20
        const val MAX_LOG_CHARS = 500
    }
}
