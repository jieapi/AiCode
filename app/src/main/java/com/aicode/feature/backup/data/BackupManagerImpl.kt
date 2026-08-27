package com.aicode.feature.backup.data

import android.content.Context
import com.aicode.core.util.GitIgnoreMatcher
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.dao.TodoItemDao
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.data.local.entity.TodoItemEntity
import com.aicode.feature.agent.domain.mcp.McpConfigRepository
import com.aicode.feature.agent.domain.mcp.McpManager
import com.aicode.feature.agent.domain.permission.PermissionRulesRepository
import com.aicode.feature.backup.domain.AgentMessageDto
import com.aicode.feature.backup.domain.BackupCrypto
import com.aicode.feature.backup.domain.BackupDecryptionException
import com.aicode.feature.backup.domain.BackupManager
import com.aicode.feature.backup.domain.BackupMetadata
import com.aicode.feature.backup.domain.BackupOptions
import com.aicode.feature.backup.domain.BackupSnapshot
import com.aicode.feature.backup.domain.ChatSessionDto
import com.aicode.feature.backup.domain.ImportPreview
import com.aicode.feature.backup.domain.ProviderDto
import com.aicode.feature.backup.domain.RemoteConnectionDto
import com.aicode.feature.backup.domain.RemoteMountDto
import com.aicode.feature.backup.domain.RestoreStats
import com.aicode.feature.backup.domain.TodoItemDto
import com.aicode.feature.backup.domain.WorkspaceBackupMeta
import com.aicode.feature.backup.domain.toMetadata
import com.aicode.feature.settings.data.local.dao.AIProviderDao
import com.aicode.feature.settings.data.local.entity.AIProviderEntity
import com.aicode.feature.settings.data.repository.CompactionModelSettingsRepository
import com.aicode.feature.settings.data.repository.AgentSoundSettingsRepository
import com.aicode.feature.settings.data.repository.KeepaliveSettingsRepository
import com.aicode.feature.settings.data.repository.LogSettingsRepository
import com.aicode.feature.settings.data.repository.SyncSettingsRepository
import com.aicode.feature.settings.data.repository.ThemeSettingsRepository
import com.aicode.feature.settings.data.repository.VisionModelSettingsRepository
import com.aicode.feature.workspace.data.local.dao.RemoteConnectionDao
import com.aicode.feature.workspace.data.local.entity.RemoteConnectionEntity
import com.aicode.feature.workspace.data.local.entity.RemoteMountEntity
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.model.RemoteProtocol
import com.aicode.feature.workspace.domain.model.Workspace
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupManagerImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val aiProviderDao: AIProviderDao,
    private val remoteConnectionDao: RemoteConnectionDao,
    private val chatSessionDao: ChatSessionDao,
    private val agentMessageDao: AgentMessageDao,
    private val todoItemDao: TodoItemDao,
    private val mcpConfigRepository: McpConfigRepository,
    private val mcpManager: McpManager,
    private val permissionRulesRepository: PermissionRulesRepository,
    private val themeSettingsRepository: ThemeSettingsRepository,
    private val keepaliveSettingsRepository: KeepaliveSettingsRepository,
    private val agentSoundSettingsRepository: AgentSoundSettingsRepository,
    private val logSettingsRepository: LogSettingsRepository,
    private val visionModelSettingsRepository: VisionModelSettingsRepository,
    private val compactionModelSettingsRepository: CompactionModelSettingsRepository,
    private val syncSettingsRepository: SyncSettingsRepository,
    private val workspaceRepository: WorkspaceRepository
) : BackupManager {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private fun currentSchemaVersion(): Int = AgentDatabase.SCHEMA_VERSION

    private fun appVersionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    }.getOrDefault("")

    override suspend fun export(password: CharArray?, options: BackupOptions, output: OutputStream) {
        withContext(Dispatchers.IO) {
            val temp = createTempFile()
            try {
                writeTarGz(temp, options)
                val pw = password?.takeIf { it.isNotEmpty() }
                FileInputStream(temp).use { input ->
                    if (pw != null) {
                        BackupCrypto.encryptStream(input, output, pw)
                    } else {
                        input.copyTo(output)
                    }
                }
            } finally {
                temp.delete()
            }
        }
    }

    override suspend fun exportSession(sessionId: String, output: OutputStream) {
        withContext(Dispatchers.IO) {
            val session = chatSessionDao.getById(sessionId) ?: error("Session not found: $sessionId")
            val temp = createTempFile()
            try {
                FileOutputStream(temp).use { fos ->
                    GzipCompressorOutputStream(fos).use { gz ->
                        TarArchiveOutputStream(gz).use { tar ->
                            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
                            writeMetadataEntry(tar, BackupMetadata(
                                schemaVersion = currentSchemaVersion(),
                                appVersion = appVersionName(),
                                createdAt = System.currentTimeMillis()
                            ))
                            writeJsonlFileEntry(tar, FILE_SESSIONS) { writer ->
                                writer.writeLine(json.encodeToString(ChatSessionDto.serializer(), session.toDto()))
                            }
                            writeJsonlFileEntry(tar, FILE_MESSAGES) { writer ->
                                var lastTs = 0L
                                var lastId = ""
                                while (true) {
                                    val batch = agentMessageDao.getPageBySessionAfter(sessionId, lastTs, lastId, PAGE_SIZE)
                                    if (batch.isEmpty()) break
                                    batch.forEach { writer.writeLine(json.encodeToString(AgentMessageDto.serializer(), it.toDto())) }
                                    lastTs = batch.last().timestamp
                                    lastId = batch.last().id
                                }
                            }
                            writeJsonlFileEntry(tar, FILE_TODOS) { writer ->
                                var lastTs = 0L
                                var lastId = ""
                                while (true) {
                                    val batch = todoItemDao.getBySessionPageAfter(sessionId, lastTs, lastId, PAGE_SIZE)
                                    if (batch.isEmpty()) break
                                    batch.forEach { writer.writeLine(json.encodeToString(TodoItemDto.serializer(), it.toDto())) }
                                    lastTs = batch.last().createdAt
                                    lastId = batch.last().id
                                }
                            }
                        }
                    }
                }
                FileInputStream(temp).use { it.copyTo(output) }
            } finally {
                temp.delete()
            }
        }
    }

    override suspend fun import(
        input: InputStream,
        password: CharArray?,
        selectedWorkspaces: Set<String>?
    ): Result<RestoreStats> {
        val pw = password?.takeIf { it.isNotEmpty() }
        return withContext(Dispatchers.IO) {
            runCatching {
                openTar(input, pw).use { source ->
                    restoreFromTar(source.tar, selectedWorkspaces)
                }
            }.recoverCatching { e ->
                when (e) {
                    is BackupDecryptionException -> throw e
                    is IllegalStateException -> throw e
                    else -> throw IllegalArgumentException(
                        if (pw != null) {
                            "备份文件已损坏，或口令与备份文件不匹配"
                        } else {
                            "不是有效的 AiCode 备份文件；如果这是加密备份，请输入导出口令"
                        },
                        e
                    )
                }
            }
        }
    }

    override suspend fun previewImport(input: InputStream, password: CharArray?): Result<ImportPreview> {
        val pw = password?.takeIf { it.isNotEmpty() }
        return withContext(Dispatchers.IO) {
            runCatching {
                openTar(input, pw).use { source ->
                    val tar = source.tar
                    var workspaces: List<WorkspaceBackupMeta> = emptyList()
                    var entry = tar.nextEntry
                    while (entry != null) {
                        if (entry.name == FILE_METADATA) {
                            val plain = tar.readBytes()
                            val metadata = json.decodeFromString(BackupMetadata.serializer(), String(plain, Charsets.UTF_8))
                            checkVersion(metadata.schemaVersion)
                            workspaces = metadata.workspaces
                            // 导出时 metadata.json 为首个条目，读到即可停，避免遍历大段 jsonl
                            break
                        }
                        entry = tar.nextEntry
                    }
                    ImportPreview(workspaces)
                }
            }.recoverCatching { e ->
                when (e) {
                    is BackupDecryptionException -> throw e
                    is IllegalStateException -> throw e
                    else -> throw IllegalArgumentException(
                        if (pw != null) {
                            "备份文件已损坏，或口令与备份文件不匹配"
                        } else {
                            "不是有效的 AiCode 备份文件；如果这是加密备份，请输入导出口令"
                        },
                        e
                    )
                }
            }
        }
    }

    /** 打开 tar 流：先按需解密到临时文件，再解压；调用方负责 [TarSource.close]。 */
    private fun openTar(input: InputStream, pw: CharArray?): TarSource {
        if (pw == null) {
            val p = BufferedInputStream(input)
            val gz = GzipCompressorInputStream(p)
            return TarSource(TarArchiveInputStream(gz), null)
        }
        val temp = createTempFile()
        try {
            BufferedInputStream(input).use { src ->
                FileOutputStream(temp).use { dst -> BackupCrypto.decryptStream(src, dst, pw) }
            }
            val p = FileInputStream(temp)
            val gz = GzipCompressorInputStream(p)
            return TarSource(TarArchiveInputStream(gz), temp)
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
    }

    // ── 导出辅助 ──────────────────────────────────────────────

    private suspend fun writeTarGz(file: File, options: BackupOptions) {
        FileOutputStream(file).use { fos ->
            GzipCompressorOutputStream(fos).use { gz ->
                TarArchiveOutputStream(gz).use { tar ->
                    tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
                    writeMetadataEntry(tar, buildMetadata(options))
                    if (options.workspaceFiles) {
                        writeWorkspaceEntries(tar)
                    }
                    if (options.chatHistory) {
                        writeJsonlFileEntry(tar, FILE_SESSIONS) { writer ->
                            var lastTs = 0L
                            var lastId = ""
                            while (true) {
                                val batch = chatSessionDao.getPageAfter(lastTs, lastId, PAGE_SIZE)
                                if (batch.isEmpty()) break
                                batch.forEach { writer.writeLine(json.encodeToString(ChatSessionDto.serializer(), it.toDto())) }
                                lastTs = batch.last().updatedAt
                                lastId = batch.last().id
                            }
                        }
                        writeJsonlFileEntry(tar, FILE_MESSAGES) { writer ->
                            var lastTs = 0L
                            var lastId = ""
                            while (true) {
                                val batch = agentMessageDao.getPageAfter(lastTs, lastId, PAGE_SIZE)
                                if (batch.isEmpty()) break
                                batch.forEach { writer.writeLine(json.encodeToString(AgentMessageDto.serializer(), it.toDto())) }
                                lastTs = batch.last().timestamp
                                lastId = batch.last().id
                            }
                        }
                        writeJsonlFileEntry(tar, FILE_TODOS) { writer ->
                            var lastTs = 0L
                            var lastId = ""
                            while (true) {
                                val batch = todoItemDao.getPageAfter(lastTs, lastId, PAGE_SIZE)
                                if (batch.isEmpty()) break
                                batch.forEach { writer.writeLine(json.encodeToString(TodoItemDto.serializer(), it.toDto())) }
                                lastTs = batch.last().createdAt
                                lastId = batch.last().id
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun buildMetadata(options: BackupOptions): BackupMetadata = BackupMetadata(
        schemaVersion = currentSchemaVersion(),
        appVersion = appVersionName(),
        createdAt = System.currentTimeMillis(),
        providers = if (options.providers) aiProviderDao.getAllProvidersOnce().map { it.toDto() } else emptyList(),
        remoteConnections = if (options.remoteConnections) remoteConnectionDao.getAllConnectionsOnce().map { it.toDto() } else emptyList(),
        remoteMounts = if (options.remoteConnections) remoteConnectionDao.getAllMountsOnce().map { it.toDto() } else emptyList(),
        mcpServers = if (options.mcpServers) mcpConfigRepository.getGlobalServers() else emptyList(),
        globalPermissionRules = if (options.permissionRules) permissionRulesRepository.getGlobalRulesOnce() else emptyList(),
        themeMode = if (options.appSettings) themeSettingsRepository.snapshot() else null,
        keepaliveEnabled = if (options.appSettings) keepaliveSettingsRepository.snapshot() else false,
        agentSoundEnabled = if (options.appSettings) agentSoundSettingsRepository.snapshot() else false,
        logLevel = if (options.appSettings) logSettingsRepository.snapshot() else null,
        visionProviderId = if (options.appSettings) visionModelSettingsRepository.getVisionProviderId() else "",
        visionModel = if (options.appSettings) visionModelSettingsRepository.getVisionModel() else "",
        compactionProviderId = if (options.appSettings) compactionModelSettingsRepository.getCompactionProviderId() else "",
        compactionModel = if (options.appSettings) compactionModelSettingsRepository.getCompactionModel() else "",
        syncSettings = if (options.appSettings) syncSettingsRepository.snapshot() else null,
        workspaces = if (options.workspaceFiles) collectWorkspaceMetas() else emptyList()
    )

    private fun writeMetadataEntry(tar: TarArchiveOutputStream, metadata: BackupMetadata) {
        val content = json.encodeToString(BackupMetadata.serializer(), metadata).toByteArray(Charsets.UTF_8)
        writeTarEntry(tar, FILE_METADATA, content)
    }

    private fun writeTarEntry(tar: TarArchiveOutputStream, name: String, content: ByteArray) {
        val entry = TarArchiveEntry(name).apply { size = content.size.toLong() }
        tar.putArchiveEntry(entry)
        tar.write(content)
        tar.closeArchiveEntry()
    }

    /**
     * 先将 jsonl 写入一个临时文件，获取确切的 [File.length] 设置 TarArchiveEntry.size，
     * 然后流式拷入 TarArchiveOutputStream，避免在 Header 中 size 设为 0 导致写入越界异常。
     */
    private suspend fun writeJsonlFileEntry(
        tar: TarArchiveOutputStream,
        entryName: String,
        block: suspend (JsonlWriter) -> Unit
    ) {
        val tmp = createTempFile()
        try {
            FileOutputStream(tmp).use { fos ->
                val writer = JsonlWriter(fos)
                block(writer)
                writer.flush()
            }
            val entry = TarArchiveEntry(entryName).apply { size = tmp.length() }
            tar.putArchiveEntry(entry)
            FileInputStream(tmp).use { fis -> fis.copyTo(tar) }
            tar.closeArchiveEntry()
        } finally {
            tmp.delete()
        }
    }

    // ── 工作区文件备份 ──────────────────────────────────────────

    /** 第一遍：统计每个本地工作区将备份的文件数（写 metadata 用）。 */
    private fun collectWorkspaceMetas(): List<WorkspaceBackupMeta> =
        workspaceRepository.workspaces.value.mapNotNull { ws ->
            var count = 0
            walkWorkspaceFiles(ws) { _, _ -> count++ }
            if (count > 0) WorkspaceBackupMeta(ws.name, count) else null
        }

    /** 第二遍：把各本地工作区文件写入 tar（`workspaces/<name>/<相对路径>`）。 */
    private fun writeWorkspaceEntries(tar: TarArchiveOutputStream) {
        workspaceRepository.workspaces.value.forEach { ws ->
            walkWorkspaceFiles(ws) { file, parts ->
                writeTarFileEntry(tar, "workspaces/${ws.name}/${parts.joinToString("/")}", file)
            }
        }
    }

    /**
     * 递归遍历本地工作区，按「.gitignore（锚定）+ 同步忽略清单」排除文件；
     * `.git` 目录强制包含（其内部不参与忽略判断）；符号链接跳过（防循环）。
     */
    private fun walkWorkspaceFiles(
        workspace: Workspace,
        onFile: (File, List<String>) -> Unit
    ) {
        val root = File(workspace.path)
        if (!root.isDirectory) return  // 远程工作区或不存在：本地无文件可备份
        val customIgnores = syncSettingsRepository.ignoredPatterns.value
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val gitignorePatterns = parseGitIgnore(File(root, ".gitignore"))

        fun walk(dir: File, relParts: List<String>) {
            dir.listFiles()?.forEach { f ->
                val parts = relParts + f.name
                if (java.nio.file.Files.isSymbolicLink(f.toPath())) return@forEach
                if (parts.first() != ".git" &&
                    (customIgnores.any { it in parts } ||
                        GitIgnoreMatcher.isIgnored(gitignorePatterns, parts, anchored = true))
                ) {
                    return@forEach
                }
                if (f.isDirectory) walk(f, parts) else onFile(f, parts)
            }
        }
        walk(root, emptyList())
    }

    /** 解析工作区根 .gitignore：去空行/注释/结尾斜杠。 */
    private fun parseGitIgnore(file: File): List<String> =
        if (!file.isFile) emptyList()
        else file.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.trim().removeSuffix("/") }

    private fun writeTarFileEntry(tar: TarArchiveOutputStream, name: String, file: File) {
        val entry = TarArchiveEntry(name).apply { size = file.length() }
        tar.putArchiveEntry(entry)
        FileInputStream(file).use { it.copyTo(tar) }
        tar.closeArchiveEntry()
    }

    // ── 导入辅助 ──────────────────────────────────────────────

    private suspend fun restoreFromTar(tar: TarArchiveInputStream, selectedWorkspaces: Set<String>?): RestoreStats {
        var metadata: BackupMetadata? = null
        var stats = RestoreStats()
        var entry = tar.nextEntry
        while (entry != null) {
            when (entry.name) {
                FILE_LEGACY_SNAPSHOT -> {
                    val plain = tar.readBytes()
                    val snapshot = json.decodeFromString(BackupSnapshot.serializer(), String(plain, Charsets.UTF_8))
                    checkVersion(snapshot.schemaVersion)
                    return restoreLegacy(snapshot)
                }
                FILE_METADATA -> {
                    val plain = tar.readBytes()
                    metadata = json.decodeFromString(BackupMetadata.serializer(), String(plain, Charsets.UTF_8))
                    checkVersion(metadata.schemaVersion)
                }
                FILE_SESSIONS -> {
                    val currentWorkspacePath = workspaceRepository.currentPath()
                    stats += RestoreStats(chatSessions = restoreJsonl(tar, ChatSessionDto.serializer()) { dtos ->
                        chatSessionDao.upsertAll(dtos.map { it.copy(workspacePath = currentWorkspacePath).toEntity() })
                    })
                }
                FILE_MESSAGES -> {
                    stats += RestoreStats(agentMessages = restoreJsonl(tar, AgentMessageDto.serializer()) { dtos ->
                        agentMessageDao.insertAll(dtos.map { it.toEntity() })
                    })
                }
                FILE_TODOS -> {
                    stats += RestoreStats(todoItems = restoreJsonl(tar, TodoItemDto.serializer()) { dtos ->
                        todoItemDao.upsertAll(dtos.map { it.toEntity() })
                    })
                }
                else -> {
                    if (entry.name.startsWith(WORKSPACE_PREFIX)) {
                        stats += restoreWorkspaceEntry(tar, entry.name, selectedWorkspaces)
                    }
                }
            }
            entry = tar.nextEntry
        }
        val meta = metadata ?: error("不是有效的 AiCode 备份文件：缺少 metadata.json")
        return stats + restoreMeta(meta)
    }

    /** 逐行解析 jsonl 条目，每 [PAGE_SIZE] 条回调一次批量插入；返回该文件的总条数。 */
    private suspend fun <T> restoreJsonl(tar: TarArchiveInputStream, serializer: KSerializer<T>, insert: suspend (List<T>) -> Unit): Int {
        val buffer = ByteArray(64 * 1024)
        val line = ByteArrayOutputStream(16 * 1024)
        val batch = ArrayList<T>(PAGE_SIZE)
        var count = 0
        while (true) {
            val n = tar.read(buffer)
            if (n < 0) break
            for (i in 0 until n) {
                if (buffer[i] == '\n'.code.toByte()) {
                    if (line.size() > 0) {
                        batch.add(json.decodeFromString(serializer, line.toString(Charsets.UTF_8)))
                        line.reset()
                        if (batch.size >= PAGE_SIZE) {
                            count += batch.size
                            insert(batch.toList())
                            batch.clear()
                        }
                    } else {
                        line.reset()
                    }
                } else {
                    line.write(buffer[i].toInt())
                }
            }
        }
        if (line.size() > 0) {
            batch.add(json.decodeFromString(serializer, line.toString(Charsets.UTF_8)))
        }
        if (batch.isNotEmpty()) {
            count += batch.size
            insert(batch.toList())
        }
        return count
    }

    private fun checkVersion(schemaVersion: Int) {
        if (schemaVersion > currentSchemaVersion()) {
            error("备份的数据库版本 v$schemaVersion 高于本应用 v${currentSchemaVersion()}，请升级应用")
        }
    }

    /** 旧格式（单文件 snapshot.json 完整快照）还原。 */
    private suspend fun restoreLegacy(snapshot: BackupSnapshot): RestoreStats {
        var stats = restoreMeta(snapshot.toMetadata())
        if (snapshot.chatSessions.isNotEmpty()) {
            val currentWorkspacePath = workspaceRepository.currentPath()
            chatSessionDao.upsertAll(snapshot.chatSessions.map { it.copy(workspacePath = currentWorkspacePath).toEntity() })
        }
        if (snapshot.agentMessages.isNotEmpty()) {
            agentMessageDao.insertAll(snapshot.agentMessages.map { it.toEntity() })
        }
        if (snapshot.todoItems.isNotEmpty()) {
            todoItemDao.upsertAll(snapshot.todoItems.map { it.toEntity() })
        }
        return stats + RestoreStats(
            chatSessions = snapshot.chatSessions.size,
            agentMessages = snapshot.agentMessages.size,
            todoItems = snapshot.todoItems.size
        )
    }

    /** 元数据段还原（小表 + 应用设置），新旧格式共用。 */
    private suspend fun restoreMeta(meta: BackupMetadata): RestoreStats {
        if (meta.providers.isNotEmpty()) {
            aiProviderDao.insertAllProviders(meta.providers.map { it.toEntity() })
        }
        if (meta.remoteConnections.isNotEmpty()) {
            remoteConnectionDao.insertAllConnections(meta.remoteConnections.map { it.toEntity() })
        }
        if (meta.remoteMounts.isNotEmpty()) {
            remoteConnectionDao.insertAllMounts(meta.remoteMounts.map { it.toEntity() })
        }
        if (meta.mcpServers.isNotEmpty()) {
            mcpConfigRepository.setGlobalServers(meta.mcpServers)
            mcpManager.reload()
        }
        if (meta.globalPermissionRules.isNotEmpty()) {
            permissionRulesRepository.setGlobalRules(meta.globalPermissionRules)
        }
        meta.themeMode?.let { themeSettingsRepository.restore(it) }
        keepaliveSettingsRepository.restore(meta.keepaliveEnabled)
        agentSoundSettingsRepository.restore(meta.agentSoundEnabled)
        logSettingsRepository.restore(meta.logLevel)
        if (meta.visionProviderId.isNotBlank() || meta.visionModel.isNotBlank()) {
            visionModelSettingsRepository.setVisionModel(meta.visionProviderId, meta.visionModel)
        }
        if (meta.compactionProviderId.isNotBlank() || meta.compactionModel.isNotBlank()) {
            compactionModelSettingsRepository.setCompactionModel(meta.compactionProviderId, meta.compactionModel)
        }
        meta.syncSettings?.let { syncSettingsRepository.restore(it) }

        return RestoreStats(
            providers = meta.providers.size,
            remoteConnections = meta.remoteConnections.size,
            remoteMounts = meta.remoteMounts.size,
            mcpServers = meta.mcpServers.size,
            globalPermissionRules = meta.globalPermissionRules.size
        )
    }

    /**
     * 还原单个工作区文件条目：`workspaces/<name>/<相对路径>`。
     * 仅处理勾选的工作区（[selectedWorkspaces] 非 null 时）；本地无同名工作区时自动创建，
     * 避免新设备/重装后导入的工作区文件因找不到目标而丢失。
     */
    private suspend fun restoreWorkspaceEntry(
        tar: TarArchiveInputStream,
        entryName: String,
        selectedWorkspaces: Set<String>?
    ): RestoreStats {
        val segments = entryName.split("/", limit = 3)
        if (segments.size != 3) return RestoreStats()
        val wsName = segments[1]
        if (selectedWorkspaces != null && wsName !in selectedWorkspaces) return RestoreStats()
        var ws = workspaceRepository.workspaces.value.firstOrNull { it.name == wsName }
        if (ws == null) {
            ws = workspaceRepository.createWorkspace(wsName) ?: return RestoreStats()
        }
        val wsDir = File(ws.path)
        if (!isPathInsideWorkspace(wsDir, segments[2])) return RestoreStats()
        val target = File(wsDir, segments[2])
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { out -> tar.copyTo(out) }
        return RestoreStats(workspaceFiles = 1)
    }

    /** 防路径穿越：工作区必须是本地目录，且相对路径规范化后仍位于其内。 */
    private fun isPathInsideWorkspace(wsDir: File, relPath: String): Boolean {
        if (!wsDir.isDirectory) return false
        val root = wsDir.canonicalPath
        val target = File(wsDir, relPath).canonicalPath
        return target == root || target.startsWith(root + File.separator)
    }

    private fun createTempFile(): File = File.createTempFile("backup", ".tmp", context.cacheDir)

    // ── Entity ↔ DTO 转换 ──────────────────────────────────────

    private fun AIProviderEntity.toDto() = ProviderDto(
        id, name, type, apiKey, baseUrl, defaultModel, models, selectedModel, isEnabled, useFullUrl, useResponseApi,
        anthropicCacheBreakpoints, openaiChatCacheKey, balanceScriptPath, balanceRefreshInterval, sortOrder
    )

    private fun ProviderDto.toEntity() = AIProviderEntity(
        id, name, type, apiKey, baseUrl, defaultModel, models, selectedModel, isEnabled, useFullUrl, useResponseApi,
        anthropicCacheBreakpoints ?: true, openaiChatCacheKey ?: false, balanceScriptPath ?: "", balanceRefreshInterval ?: 5,
        sortOrder = sortOrder ?: 0
    )

    private fun RemoteConnectionEntity.toDto() = RemoteConnectionDto(
        id, name, protocol.name, host, port, username, authType, authData, passphrase
    )

    private fun RemoteConnectionDto.toEntity() = RemoteConnectionEntity(
        id, name, RemoteProtocol.valueOf(protocol), host, port, username, authType, authData, passphrase
    )

    private fun RemoteMountEntity.toDto() = RemoteMountDto(id, connectionId, remotePath, localMountPath, isActive, autoConnect)
    private fun RemoteMountDto.toEntity() = RemoteMountEntity(id, connectionId, remotePath, localMountPath, isActive, autoConnect)

    private fun ChatSessionEntity.toDto() = ChatSessionDto(
        id = id, title = title, createdAt = createdAt, updatedAt = updatedAt, workspacePath = workspacePath,
        mode = mode, reasoningEffort = reasoningEffort, providerId = providerId, model = model, isPinned = isPinned
    )

    private fun ChatSessionDto.toEntity() = ChatSessionEntity(
        id = id, title = title, createdAt = createdAt, updatedAt = updatedAt, workspacePath = workspacePath,
        mode = mode, reasoningEffort = reasoningEffort, providerId = providerId, model = model, isPinned = isPinned
    )

    private fun AgentMessageEntity.toDto() = AgentMessageDto(
        id, sessionId, role, content, timestamp, toolCallsJson, toolCallId, toolName, toolArgs,
        isError, reasoning, signature, attachmentsJson, isCompacted, isContextSummary, isCompactionMarker
    )

    private fun AgentMessageDto.toEntity() = AgentMessageEntity(
        id, sessionId, role, content, timestamp, toolCallsJson, toolCallId, toolName, toolArgs,
        isError, reasoning, signature, attachmentsJson, isCompacted, isContextSummary, isCompactionMarker
    )

    private fun TodoItemEntity.toDto() = TodoItemDto(id, sessionId, subject, description, status, priority, order, createdAt, updatedAt)
    private fun TodoItemDto.toEntity() = TodoItemEntity(id, sessionId, subject, description, status, priority, order, createdAt, updatedAt)

    private companion object {
        const val PAGE_SIZE = 500
        const val FILE_METADATA = "metadata.json"
        const val FILE_SESSIONS = "chatSessions.jsonl"
        const val FILE_MESSAGES = "messages.jsonl"
        const val FILE_TODOS = "todoItems.jsonl"
        const val FILE_LEGACY_SNAPSHOT = "snapshot.json"
        const val WORKSPACE_PREFIX = "workspaces/"
    }
}

/** 带内部缓冲的 jsonl 写入器：行缓冲满 64KB 时刷入 tar 流，避免逐行写系统调用。 */
private class JsonlWriter(private val out: OutputStream) {
    private val buffer = ByteArrayOutputStream(64 * 1024)

    fun writeLine(line: String) {
        buffer.write(line.toByteArray(Charsets.UTF_8))
        buffer.write('\n'.code)
        if (buffer.size() >= 64 * 1024) flush()
    }

    fun flush() {
        buffer.writeTo(out)
        buffer.reset()
    }
}

/** 打开的 tar 流封装：加密导入时附带解密用的临时文件，关闭时一并清理。 */
private class TarSource(
    val tar: TarArchiveInputStream,
    private val temp: File?
) : AutoCloseable {
    override fun close() {
        runCatching { tar.close() }
        temp?.delete()
    }
}
