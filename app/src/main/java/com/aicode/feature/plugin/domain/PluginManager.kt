package com.aicode.feature.plugin.domain

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.skill.Skill
import com.aicode.feature.agent.domain.subagent.AgentDefinition
import com.aicode.feature.plugin.data.ExtractedPackage
import com.aicode.feature.plugin.data.PackageReadResult
import com.aicode.feature.plugin.data.PluginDependencyStore
import com.aicode.feature.plugin.data.PluginInstallTransaction
import com.aicode.feature.plugin.data.PluginPackageReader
import com.aicode.feature.plugin.data.SOURCE_DEVELOPMENT
import com.aicode.feature.plugin.data.SOURCE_PACKAGE
import com.aicode.feature.plugin.data.PluginRecord
import com.aicode.feature.plugin.data.PluginRegistryStore
import com.aicode.feature.plugin.runtime.PluginDependencyPreparer
import com.aicode.feature.plugin.runtime.PluginDependencyResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class PluginStatus {
    OK,

    /** 安装目录或清单文件丢失。 */
    MISSING,

    /** 清单摘要与安装时记录不一致，或贡献文件缺失 / 无法解析。 */
    CORRUPTED,

    /** 清单不再通过校验（例如宿主插件 API 升级后不兼容）。 */
    INVALID
}

/** 已安装插件的运行视图：宿主记录 + 从安装目录读出的清单与贡献。 */
data class InstalledPlugin(
    val record: PluginRecord,
    val status: PluginStatus,
    val manifest: PluginManifest?,
    val content: PluginContent,
    val errors: List<PluginError> = emptyList()
) {
    val id: String get() = record.id

    /** 启用且状态正常、清单可读时，它声明的全部贡献都生效。 */
    val isActive: Boolean
        get() = record.enabled && status == PluginStatus.OK && manifest != null
}

/** 已通过校验、等待使用者确认安装的暂存包。 */
data class PluginPreview(
    val token: String,
    val manifest: PluginManifest,
    val content: PluginContent,
    /** 已安装的同 ID 插件版本；非空表示这次导入会替换它。 */
    val installedVersion: String?,
    /** 被替换安装的来源信息；新装时为 null，供界面判断来源是否变化。 */
    val installedSourceId: String? = null,
    val installedRepository: String? = null,
    val installedOfficial: Boolean? = null
)

sealed interface PluginPreviewResult {
    data class Ready(val preview: PluginPreview) : PluginPreviewResult
    data class Failed(val errors: List<PluginError>) : PluginPreviewResult
}

sealed interface PluginInstallResult {
    data class Installed(val pluginId: String) : PluginInstallResult
    data class Failed(val error: PluginError) : PluginInstallResult
}

/**
 * 插件的安装、启停与贡献聚合。
 *
 * 插件包与数据保存在容器内全局配置目录 `~/.aicode/plugins`（宿主侧为 [ContainerInstaller.aicodeDir]，
 * 由 PRoot 绑定到容器内 `/root/.aicode`），本地容器里的命令可以直接访问插件文件与将来的依赖。
 * 安装记录留在宿主私有目录，不随包进入容器，避免容器内进程改写启停状态。SSH 模式下插件仍只在手机端。
 */
@Singleton
class PluginManager internal constructor(
    private val rootDir: File,
    private val hostApi: SemVer,
    private val clock: () -> Long,
    private val registryFile: File = File(rootDir, "registry.json"),
    private val legacyRootDir: File? = null,
    private val dependencyPreparer: PluginDependencyPreparer? = null
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        installer: ContainerInstaller,
        dependencyPreparer: PluginDependencyPreparer
    ) : this(
        File(installer.aicodeDir, PLUGINS_DIR),
        PluginApi.VERSION,
        System::currentTimeMillis,
        File(context.filesDir, REGISTRY_FILE),
        File(context.filesDir, PLUGINS_DIR),
        dependencyPreparer
    )

    private val installedDir = File(rootDir, "installed")
    private val stagingDir = File(rootDir, "staging")
    private val dataRoot = File(rootDir, "data")
    private val store = PluginRegistryStore(registryFile)
    private val reader = PluginPackageReader()
    private val dependencyStore = PluginDependencyStore(rootDir)

    private val mutex = Mutex()
    private val loadLock = Any()

    @Volatile
    private var loaded = false

    private val _plugins = MutableStateFlow<List<InstalledPlugin>>(emptyList())
    val plugins: StateFlow<List<InstalledPlugin>> = _plugins.asStateFlow()

    private val _isLoaded = MutableStateFlow(false)

    /** 安装记录是否已从磁盘读完。读完之前 [plugins] 为空不代表没有插件，界面据此避免闪一下空状态。 */
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    private val revisionCounter = AtomicLong(0)

    /** 插件集合每变化一次递增，供提示词缓存等据此失效。 */
    val revision: Long get() = revisionCounter.get()

    private class StagedPackage(
        val pkg: ExtractedPackage,
        val manifest: PluginManifest,
        val content: PluginContent
    )

    private class LoadedRegistry(
        val records: List<PluginRecord>,
        val failed: Boolean
    )

    private val staged = ConcurrentHashMap<String, StagedPackage>()

    /** 首次调用时从磁盘读取安装记录；会做文件 IO，调用方不要放在主线程。 */
    fun ensureLoaded() {
        if (loaded) return
        synchronized(loadLock) {
            if (loaded) return
            migrateLegacyRoot()
            // 进程重启后不可能存在进行中的安装，暂存目录里的都是残留
            cleanup { deleteTree(stagingDir) }
            dependencyStore.cleanupStaging()
            val registry = loadRecords()
            val recovered = !registry.failed && recoverInterruptedInstall(registry.records)
            publish(registry.records.map { loadInstalled(it) })
            migrateLegacyDependencies()
            // registry 或事务恢复失败时保留目录，避免把待恢复的备份当成孤儿。
            if (recovered) sweepOrphans(registry.records.map { it.id }.toSet())
            loaded = true
            _isLoaded.value = true
        }
    }

    /** 重新读取安装记录与清单；只更新界面快照，不重启已启用的插件运行时。 */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            val loaded = loadRecords()
            val recovered = !loaded.failed && recoverInterruptedInstall(loaded.records)
            publish(loaded.records.map { loadInstalled(it) })
            migrateLegacyDependencies()
            if (recovered) sweepOrphans(loaded.records.map { it.id }.toSet())
        }
    }

    /** 当前生效的插件技能：插件启用即可用。 */
    fun activeSkills(): List<Skill> {
        ensureLoaded()
        return _plugins.value
            .filter { it.isActive }
            .flatMap { plugin -> plugin.content.skills.map { it.skill } }
    }

    /** 全部已读出的插件技能，不论插件是否启用。供设置页全量展示使用。 */
    fun allSkills(): List<Skill> {
        ensureLoaded()
        return _plugins.value.flatMap { plugin -> plugin.content.skills.map { it.skill } }
    }

    /** 当前生效的插件子代理定义，条件同 [activeSkills]。 */
    fun activeAgents(): List<AgentDefinition> {
        ensureLoaded()
        return _plugins.value
            .filter { it.isActive }
            .flatMap { plugin -> plugin.content.agents.map { it.definition } }
    }

    /**
     * 全部已读出的插件子代理定义，不论插件是否启用。已经派出去的子会话要靠它还原自己的提示词与工具集，
     * 停用只该拦住新的派发。
     */
    fun allAgents(): List<AgentDefinition> {
        ensureLoaded()
        return _plugins.value.flatMap { plugin -> plugin.content.agents.map { it.definition } }
    }

    /** 当前生效的插件工作区工具：插件启用即可用。 */
    fun activeWorkspaceTools(): List<PluginWorkspaceTool> {
        ensureLoaded()
        return _plugins.value
            .filter { it.isActive }
            .flatMap { it.content.workspaceTools }
    }

    /** 全部插件声明的工作区工具，供全部工具库展示添加。 */
    fun allWorkspaceTools(): List<PluginWorkspaceTool> {
        ensureLoaded()
        return _plugins.value.flatMap { it.content.workspaceTools }
    }

    /** 插件专属数据目录，对应桥里的 `data://` 根。 */
    fun dataDirFor(pluginId: String): File = File(dataRoot, pluginId)

    /** 开发者侧载目录，容器内对应 `~/.aicode/plugin-dev`。 */
    val devDir: File
        get() = File(rootDir.parentFile ?: rootDir, DEV_DIR).apply { mkdirs() }

    /**
     * 在本地容器、插件安装目录里准备依赖；长操作不持锁，失败时保留未准备状态。
     * 没有运行时的纯声明式插件与没有依赖声明的运行时直接视为无需准备。
     */
    suspend fun prepareDependencies(id: String): PluginDependencyResult = withContext(Dispatchers.IO) {
        ensureLoaded()
        val plugin = mutex.withLock { _plugins.value.firstOrNull { it.id == id } }
            ?: return@withContext PluginDependencyResult.Failed("插件不存在")
        val runtime = plugin.content.runtime
            ?: return@withContext PluginDependencyResult.Failed("插件没有运行时，无需依赖")
        val preparer = dependencyPreparer
            ?: return@withContext PluginDependencyResult.Failed("依赖准备不可用")
        val fingerprint = dependencyStore.fingerprint(runtime)
        val result = preparer.prepare(runtime)
        val cacheSaved = if (result is PluginDependencyResult.Ready) {
            runCatching {
                val output = dependencyStore.dependencyOutput(runtime.installDir)
                if (!dependencyStore.isReady(id, fingerprint) && output.exists()) {
                    dependencyStore.capture(runtime, fingerprint)
                }
                if (dependencyStore.isReady(id, fingerprint)) {
                    dependencyStore.link(runtime.installDir, fingerprint, id)
                }
                dependencyStore.isReady(id, fingerprint)
            }.getOrDefault(false)
        } else false
        val prepared = result
        val saved = mutateRecords { current ->
            val installed = current.firstOrNull { it.id == id } ?: return@mutateRecords null
            if (!sameInstallation(installed.record, plugin.record)) return@mutateRecords null
            current.map { installed ->
                if (installed.id != id) installed else {
                    val record = when (prepared) {
                        is PluginDependencyResult.Ready -> installed.record.copy(
                            dependenciesReady = true,
                            dependenciesHash = prepared.hash,
                            dependencyFingerprint = fingerprint.takeIf { cacheSaved }
                        )
                        else -> installed.record.copy(
                            dependenciesReady = false,
                            dependenciesHash = null,
                            dependencyFingerprint = null
                        )
                    }
                    installed.copy(record = record)
                }
            }.also { next -> store.save(next.map { it.record }) }
        }
        if (saved) prepared else PluginDependencyResult.Failed(
            "Plugin changed or dependency state could not be saved",
            code = "STATE_NOT_SAVED"
        )
    }

    private fun preserveExistingDependencies(
        existing: InstalledPlugin?,
        pending: StagedPackage
    ): String? {
        val oldRuntime = existing?.content?.runtime ?: return null
        val newRuntime = pending.content.runtime ?: return null
        val newFingerprint = runCatching { dependencyStore.fingerprint(newRuntime) }.getOrNull() ?: return null
        val recorded = existing.record.dependencyFingerprint
        if (recorded == newFingerprint && dependencyStore.isReady(existing.id, recorded)) return recorded
        val oldOutput = dependencyStore.dependencyOutput(oldRuntime.installDir)
        if (!oldOutput.exists() || java.nio.file.Files.isSymbolicLink(oldOutput.toPath())) return null
        val oldFingerprint = runCatching { dependencyStore.fingerprint(oldRuntime) }.getOrNull() ?: return null
        if (oldFingerprint != newFingerprint) return null
        return runCatching {
            if (!dependencyStore.isReady(existing.id, oldFingerprint)) {
                dependencyStore.capture(oldRuntime, oldFingerprint)
            }
            oldFingerprint.takeIf { dependencyStore.isReady(existing.id, it) }
        }.getOrNull()
    }

    private fun sameInstallation(current: PluginRecord, prepared: PluginRecord): Boolean =
        if (prepared.installRevision != null) current.installRevision == prepared.installRevision
        else current.installRevision == null && current.installedAt == prepared.installedAt &&
            current.archiveSha256 == prepared.archiveSha256 && current.manifestSha256 == prepared.manifestSha256

    /** 当前生效的插件 AI 工具：插件启用即可用。 */
    fun activeTools(): List<PluginTool> {
        ensureLoaded()
        return _plugins.value
            .filter { it.isActive }
            .flatMap { it.content.tools }
    }

    /** 全部插件声明的 AI 工具，不论插件是否启用；供设置页与调试展示。 */
    fun allTools(): List<PluginTool> {
        ensureLoaded()
        return _plugins.value.flatMap { it.content.tools }
    }

    /** 当前生效的插件生命周期 hook：插件启用即可用。 */
    fun activeHooks(): List<PluginHook> {
        ensureLoaded()
        return _plugins.value
            .filter { it.isActive }
            .flatMap { it.content.hooks }
    }

    /** 全部插件声明的生命周期 hook，不论插件是否启用或已授权；供设置页与调试展示。 */
    fun allHooks(): List<PluginHook> {
        ensureLoaded()
        return _plugins.value.flatMap { it.content.hooks }
    }

    /** 当前生效的插件 MCP server：插件启用即可用。 */
    fun activeMcpServers(): List<PluginMcpServer> {
        ensureLoaded()
        return _plugins.value
            .filter { it.isActive }
            .flatMap { it.content.mcp }
    }

    /** 全部插件声明的 MCP server，不论插件是否启用；供设置页展示。 */
    fun allMcpServers(): List<PluginMcpServer> {
        ensureLoaded()
        return _plugins.value.flatMap { it.content.mcp }
    }

    /** 读取并校验 [input] 中的插件包，通过后暂存等待 [install]。同一时刻只保留一个待确认的暂存包。 */
    suspend fun preview(input: InputStream): PluginPreviewResult = withContext(Dispatchers.IO) {
        try {
            mutex.withLock {
                ensureLoaded()
                discardAll()
            }
        } catch (e: IOException) {
            FileLogger.w(TAG, "清理插件暂存包失败", e)
            return@withContext PluginPreviewResult.Failed(listOf(PluginError(PluginErrorCode.STORAGE_FAILED)))
        }
        val token = UUID.randomUUID().toString()
        val dir = File(stagingDir, token)
        when (val read = reader.read(input, dir)) {
            is PackageReadResult.Failed -> PluginPreviewResult.Failed(listOf(read.error))
            is PackageReadResult.Ok -> previewExtracted(token, read.pkg)
        }
    }

    private fun previewExtracted(token: String, pkg: ExtractedPackage): PluginPreviewResult {
        val manifest = when (val parsed = PluginManifestParser.parse(pkg.manifestText, hostApi)) {
            is ManifestParseResult.Failure -> return rejected(pkg, parsed.errors)
            is ManifestParseResult.Success -> parsed.manifest
        }
        val content = when (val loadedContent = PluginContentLoader.load(pkg.dir, manifest)) {
            is ContentLoadResult.Failed -> return rejected(pkg, loadedContent.errors)
            is ContentLoadResult.Ok -> loadedContent.content
        }
        staged[token] = StagedPackage(pkg, manifest, content)
        val existing = _plugins.value.firstOrNull { it.id == manifest.id }?.record
        return PluginPreviewResult.Ready(
            PluginPreview(
                token = token,
                manifest = manifest,
                content = content,
                installedVersion = existing?.version,
                installedSourceId = existing?.sourceId,
                installedRepository = existing?.repository,
                installedOfficial = existing?.official
            )
        )
    }

    private fun rejected(pkg: ExtractedPackage, errors: List<PluginError>): PluginPreviewResult {
        val cleaned = cleanup { deleteTree(pkg.dir) }
        return PluginPreviewResult.Failed(
            if (cleaned) errors else errors + PluginError(PluginErrorCode.STORAGE_FAILED)
        )
    }

    /** 放弃一个待确认的暂存包。 */
    suspend fun discard(token: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val pending = staged[token] ?: return@withLock false
            if (!cleanup { deleteTree(pending.pkg.dir) }) return@withLock false
            staged.remove(token)
            true
        }
    }

    private fun discardAll() {
        staged.entries.forEach { (token, pending) ->
            deleteTree(pending.pkg.dir)
            staged.remove(token)
        }
    }

    /**
     * 安装暂存包。插件清单里的贡献随启用生效，安装不再逐项授权。
     * 同 ID 已安装时整体替换并沿用原启用状态，来源记录为本次安装的来源。
     */
    suspend fun install(
        token: String,
        origin: PluginInstallOrigin = PluginInstallOrigin()
    ): PluginInstallResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureLoaded()
                val pending = staged[token]
                    ?: return@withLock PluginInstallResult.Failed(PluginError(PluginErrorCode.PREVIEW_EXPIRED))
                val manifest = pending.manifest
                try {
                    commitInstall(token, pending, origin)
                    PluginInstallResult.Installed(manifest.id)
                } catch (e: IOException) {
                    FileLogger.e(TAG, "安装插件失败: ${manifest.id}", e)
                    PluginInstallResult.Failed(PluginError(PluginErrorCode.STORAGE_FAILED, e.javaClass.simpleName))
                }
            }
        }

    private fun commitInstall(token: String, pending: StagedPackage, origin: PluginInstallOrigin) {
        val manifest = pending.manifest
        if (!recoverInterruptedInstall(_plugins.value.map { it.record })) {
            throw IOException("上次安装事务尚未恢复")
        }
        installedDir.mkdirs()
        val target = File(installedDir, manifest.id)
        val backup = File(installedDir, "${manifest.id}$REPLACED_SUFFIX")
        deleteTree(backup)

        val existing = _plugins.value.firstOrNull { it.id == manifest.id }
        if (existing == null) deleteTree(dataDirFor(manifest.id))
        val installRevision = UUID.randomUUID().toString()
        val preservedDependency = preserveExistingDependencies(existing, pending)
        val record = PluginRecord(
            id = manifest.id,
            version = manifest.version.toString(),
            enabled = existing?.record?.enabled ?: true,
            installedAt = clock(),
            origin = origin.kind,
            archiveSha256 = pending.pkg.archiveSha256,
            manifestSha256 = pending.pkg.manifestSha256,
            sourceId = origin.sourceId,
            sourceName = origin.sourceName,
            indexUrl = origin.indexUrl,
            repository = origin.repository,
            official = origin.official,
            dependenciesReady = preservedDependency != null,
            dependenciesHash = preservedDependency,
            dependencyFingerprint = preservedDependency,
            sourceKind = SOURCE_PACKAGE,
            devDirName = null,
            installRevision = installRevision
        )

        val transaction = PluginInstallTransaction(manifest.id, installRevision, entryExists(target))
        store.saveInstallTransaction(transaction)
        var movedOld = false
        var movedNew = false
        val others = _plugins.value.filter { it.id != manifest.id }
        val installed: InstalledPlugin
        try {
            if (transaction.hadPrevious) {
                moveDirectory(target, backup)
                movedOld = true
            }
            moveDirectory(pending.pkg.dir, target)
            movedNew = true
            if (preservedDependency != null) {
                dependencyStore.link(target, preservedDependency, manifest.id)
            }
            installed = loadInstalled(record)
            store.save(others.map { it.record } + record)
        } catch (e: IOException) {
            // 只在 registry 提交前回滚；恢复失败时保留私有事务与备份供下次重试。
            try {
                if (movedNew) moveDirectory(target, pending.pkg.dir)
                if (movedOld) moveDirectory(backup, target)
                store.clearInstallTransaction()
            } catch (rollback: IOException) {
                e.addSuppressed(rollback)
            }
            throw e
        }
        staged.remove(token)
        publish(others + installed)
        cleanup {
            deleteTree(backup)
            store.clearInstallTransaction()
        }
    }

    suspend fun uninstall(id: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            val current = _plugins.value
            if (current.none { it.id == id }) return@withLock false
            val next = current.filter { it.id != id }
            if (!cleanup { store.save(next.map { it.record }) }) return@withLock false
            publish(next)
            val installedDeleted = cleanup { deleteTree(File(installedDir, id)) }
            val dataDeleted = cleanup { deleteTree(dataDirFor(id)) }
            val dependenciesDeleted = cleanup { dependencyStore.deleteForPlugin(id) }
            installedDeleted && dataDeleted && dependenciesDeleted
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean): Boolean = updateRecord(id) { record, _ ->
        record.copy(enabled = enabled)
    }

    private suspend fun updateRecord(
        id: String,
        transform: (PluginRecord, InstalledPlugin) -> PluginRecord
    ): Boolean = mutateRecords { current ->
        val target = current.firstOrNull { it.id == id } ?: return@mutateRecords null
        val updated = transform(target.record, target)
        if (updated == target.record) return@mutateRecords current
        current.map { if (it.id == id) it.copy(record = updated) else it }.also { next ->
            store.save(next.map { it.record })
        }
    }

    /** 串行执行一次变更；[block] 返回新的插件列表，返回 null 表示目标不存在。IO 失败返回 false。 */
    private suspend fun mutateRecords(block: (List<InstalledPlugin>) -> List<InstalledPlugin>?): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureLoaded()
                try {
                    val next = block(_plugins.value) ?: return@withLock false
                    if (next != _plugins.value) publish(next)
                    true
                } catch (e: IOException) {
                    FileLogger.e(TAG, "更新插件记录失败", e)
                    false
                }
            }
        }

    private fun publish(next: List<InstalledPlugin>) {
        _plugins.value = next.sortedBy { it.id }
        revisionCounter.incrementAndGet()
    }

    /** 把已安装的同 ID 插件绑定到开发目录；代码修改后由 [reloadDevelopment] 直接重读该目录。 */
    suspend fun bindDevelopment(id: String, dirName: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            val current = _plugins.value.firstOrNull { it.id == id } ?: return@withLock false
            val dir = developmentDir(dirName)
            val manifest = readDevelopmentManifest(dir) ?: return@withLock false
            if (manifest.id != id) return@withLock false
            val updated = current.record.copy(
                version = manifest.version.toString(),
                sourceKind = SOURCE_DEVELOPMENT,
                devDirName = dirName,
                manifestSha256 = PluginPackageReader.sha256Hex(File(dir, PluginPackageReader.MANIFEST_FILE).readBytes()),
                archiveSha256 = directoryFingerprint(dir),
                installRevision = UUID.randomUUID().toString()
            )
            val loaded = loadDevelopment(updated, dir)
            if (loaded.status != PluginStatus.OK) return@withLock false
            val effectiveRecord = loaded.record
            store.save(_plugins.value.filter { it.id != id }.map { it.record } + effectiveRecord)
            publish(_plugins.value.filter { it.id != id } + loaded)
            true
        }
    }

    /** 开发目录保存后直接重读清单与贡献，不重新打包或触碰正式安装目录。 */
    suspend fun reloadDevelopment(dirName: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            val dir = developmentDir(dirName)
            val manifest = readDevelopmentManifest(dir) ?: return@withLock false
            val current = _plugins.value.firstOrNull {
                it.record.sourceKind == SOURCE_DEVELOPMENT && it.record.devDirName == dirName
            } ?: return@withLock false
            val updated = current.record.copy(
                version = manifest.version.toString(),
                manifestSha256 = PluginPackageReader.sha256Hex(File(dir, PluginPackageReader.MANIFEST_FILE).readBytes()),
                archiveSha256 = directoryFingerprint(dir),
                dependenciesReady = current.record.dependenciesReady,
                dependenciesHash = current.record.dependenciesHash,
                dependencyFingerprint = current.record.dependencyFingerprint,
                installRevision = UUID.randomUUID().toString()
            )
            val loaded = loadDevelopment(updated, dir)
            if (loaded.status != PluginStatus.OK) return@withLock false
            val effectiveRecord = loaded.record
            store.save(_plugins.value.filter { it.id != current.id }.map { it.record } + effectiveRecord)
            publish(_plugins.value.filter { it.id != current.id } + loaded)
            true
        }
    }

    private fun loadDevelopment(record: PluginRecord, dir: File): InstalledPlugin {
        val manifest = readDevelopmentManifest(dir)
            ?: return InstalledPlugin(record, PluginStatus.MISSING, null, PluginContent())
        return when (val content = PluginContentLoader.load(dir, manifest, record.installRevision)) {
            is ContentLoadResult.Failed -> InstalledPlugin(record, PluginStatus.CORRUPTED, manifest, PluginContent(), content.errors)
            is ContentLoadResult.Ok -> InstalledPlugin(
                refreshDependencyState(record, content.content),
                PluginStatus.OK,
                manifest,
                content.content
            )
        }
    }

    private fun developmentDir(dirName: String): File {
        val root = devDir.canonicalFile
        val dir = File(root, dirName).canonicalFile
        if (!dir.toPath().startsWith(root.toPath())) throw IOException("开发目录越界")
        return dir
    }

    private fun readDevelopmentManifest(dir: File): PluginManifest? {
        val file = File(dir, PluginPackageReader.MANIFEST_FILE)
        if (!file.isFile || file.length() > PluginManifestParser.MAX_MANIFEST_BYTES) return null
        return (PluginManifestParser.parse(file.readText(), hostApi) as? ManifestParseResult.Success)?.manifest
    }

    private fun directoryFingerprint(dir: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        dir.walkTopDown()
            .filter { it.isFile && it.relativeTo(dir).invariantSeparatorsPath.split('/').none { part -> part in setOf(".git", "node_modules", ".venv", "__pycache__", ".idea") } }
            .sortedBy { it.relativeTo(dir).invariantSeparatorsPath }
            .forEach { file ->
                digest.update(file.relativeTo(dir).invariantSeparatorsPath.toByteArray(Charsets.UTF_8))
                digest.update(file.readBytes())
            }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun loadInstalled(record: PluginRecord): InstalledPlugin {
        fun broken(status: PluginStatus, errors: List<PluginError> = emptyList()) =
            InstalledPlugin(record, status, manifest = null, content = PluginContent(), errors = errors)

        val dir = if (record.sourceKind == SOURCE_DEVELOPMENT && record.devDirName != null) {
            runCatching { developmentDir(record.devDirName) }.getOrNull() ?: File(devDir, "__invalid__")
        } else File(installedDir, record.id)
        val manifestFile = File(dir, PluginPackageReader.MANIFEST_FILE)
        if (!manifestFile.isFile) return broken(PluginStatus.MISSING)
        if (manifestFile.length() > PluginManifestParser.MAX_MANIFEST_BYTES) return broken(PluginStatus.CORRUPTED)

        val bytes = manifestFile.readBytes()
        if (PluginPackageReader.sha256Hex(bytes) != record.manifestSha256) return broken(PluginStatus.CORRUPTED)

        val manifest = when (val parsed = PluginManifestParser.parse(String(bytes, Charsets.UTF_8), hostApi)) {
            is ManifestParseResult.Failure -> return broken(PluginStatus.INVALID, parsed.errors)
            is ManifestParseResult.Success -> parsed.manifest
        }
        if (manifest.id != record.id || manifest.version.toString() != record.version) {
            return broken(PluginStatus.CORRUPTED)
        }
        return when (val content = PluginContentLoader.load(dir, manifest, record.installRevision)) {
            is ContentLoadResult.Failed -> InstalledPlugin(record, PluginStatus.CORRUPTED, manifest, PluginContent(), content.errors)
            is ContentLoadResult.Ok -> InstalledPlugin(
                refreshDependencyState(record, content.content),
                PluginStatus.OK,
                manifest,
                content.content
            )
        }
    }

    /** 依赖文件被改动后，准备状态失效；这里只修正视图，不写盘。 */
    private fun refreshDependencyState(record: PluginRecord, content: PluginContent): PluginRecord {
        val runtime = content.runtime ?: return record.copy(dependenciesReady = true)
        val dependency = runtime.dependencies ?: return record.copy(dependenciesReady = true)
        val hash = runCatching {
            PluginPackageReader.sha256Hex(dependency.file.readBytes())
        }.getOrNull() ?: return record.copy(dependenciesReady = false)
        val fingerprint = runCatching { dependencyStore.fingerprint(runtime) }.getOrNull()
        val ready = fingerprint != null && dependencyStore.isReady(record.id, fingerprint)
        val legacyReady = record.dependenciesReady && record.dependencyFingerprint == null && record.dependenciesHash == hash
        return if (ready || legacyReady) {
            record.copy(dependenciesReady = true, dependencyFingerprint = fingerprint.takeIf { ready })
        } else {
            record.copy(dependenciesReady = false)
        }
    }

    /** 将旧版安装目录内的依赖迁移到独立缓存；迁移失败时保留旧目录，等待下次启动重试。 */
    private fun migrateLegacyDependencies() {
        val current = _plugins.value
        val updated = current.map { plugin ->
            val runtime = plugin.content.runtime ?: return@map plugin
            val output = dependencyStore.dependencyOutput(runtime.installDir)
            if (!output.exists() || java.nio.file.Files.isSymbolicLink(output.toPath())) return@map plugin
            val fingerprint = runCatching { dependencyStore.fingerprint(runtime) }.getOrNull()
                ?: return@map plugin
            val ready = runCatching {
                if (!dependencyStore.isReady(plugin.id, fingerprint)) {
                    dependencyStore.capture(runtime, fingerprint)
                }
                dependencyStore.link(runtime.installDir, fingerprint, plugin.id)
                dependencyStore.isReady(plugin.id, fingerprint)
            }.getOrDefault(false)
            if (!ready) plugin else plugin.copy(
                record = plugin.record.copy(
                    dependenciesReady = true,
                    dependenciesHash = runtime.dependencies?.let { PluginPackageReader.sha256Hex(it.file.readBytes()) },
                    dependencyFingerprint = fingerprint
                ),
                content = loadInstalled(plugin.record.copy(
                    dependenciesReady = true,
                    dependenciesHash = runtime.dependencies?.let { PluginPackageReader.sha256Hex(it.file.readBytes()) },
                    dependencyFingerprint = fingerprint
                )).content
            )
        }
        if (updated != current) {
            runCatching { store.save(updated.map { it.record }) }
                .onSuccess { publish(updated) }
                .onFailure { FileLogger.w(TAG, "迁移插件依赖记录失败", it) }
        }
    }

    /**
     * 旧版本把插件放在 `filesDir/plugins`，安装记录也在包目录内。迁移后包进容器，记录留宿主私有。
     * 迁移失败时保留旧目录，下次启动重试；这里不删除尚未确认搬走的数据。
     */
    private fun migrateLegacyRoot() {
        val legacy = legacyRootDir ?: return
        // renameTo 要求目标父目录存在，插件可能先于容器初始化被访问。
        rootDir.parentFile?.mkdirs()
        if (!rootDir.exists() && legacy.isDirectory) {
            if (!legacy.renameTo(rootDir)) {
                FileLogger.w(TAG, "插件目录迁移失败，继续使用旧目录: ${legacy.absolutePath}")
                return
            }
        }
        val insideRegistry = File(rootDir, "registry.json")
        val source = when {
            insideRegistry.isFile -> insideRegistry
            File(legacy, "registry.json").isFile -> File(legacy, "registry.json")
            else -> null
        }
        if (!registryFile.exists() && source != null) {
            registryFile.parentFile?.mkdirs()
            if (!source.renameTo(registryFile)) {
                // 目标目录不可写或跨挂载点时 rename 会失败；先保证记录还能读到，再等下次清理。
                runCatching { source.copyTo(registryFile, overwrite = true) }
                    .onFailure { FileLogger.w(TAG, "插件安装记录迁移失败: ${source.absolutePath}", it) }
            }
        }
        // 迁移后包目录不该再留一份记录，否则容器内进程可以改写它。
        if (registryFile.exists() && insideRegistry.isFile) insideRegistry.delete()
        if (legacy.isDirectory && legacy.listFiles().isNullOrEmpty()) legacy.delete()
    }

    /**
     * 记录尚未搬到宿主私有目录时（迁移未完成），退回到包目录里的副本读取，
     * 避免把已安装插件当成孤儿清掉。
     */
    private fun loadRecords(): LoadedRegistry {
        if (registryFile.isFile) {
            val records = store.load()
            val failed = store.loadFailed || hasCorruptRegistryBackup() || hasLegacyCorruptBackup()
            return LoadedRegistry(records, failed)
        }
        val inside = File(rootDir, "registry.json")
        if (!inside.isFile) {
            return LoadedRegistry(emptyList(), failed = hasCorruptRegistryBackup() || hasLegacyCorruptBackup())
        }
        val legacyStore = PluginRegistryStore(inside)
        return LoadedRegistry(
            legacyStore.load(),
            legacyStore.loadFailed || hasCorruptRegistryBackup() || hasLegacyCorruptBackup()
        )
    }

    /** 上一次读取失败会留下备份；此时 registry 文件已不存在，但安装目录不能当孤儿清理。 */
    private fun hasCorruptRegistryBackup(): Boolean =
        registryFile.parentFile?.listFiles().orEmpty().any {
            it.name.startsWith("${registryFile.name}.corrupt-")
        }

    private fun hasLegacyCorruptBackup(): Boolean {
        val backupPrefix = "registry.json.corrupt-"
        return rootDir.listFiles().orEmpty().any { it.name.startsWith(backupPrefix) }
    }

    /**
     * 安装/升级在“旧目录已移走、新目录尚未就位或 registry 尚未落盘”之间崩溃时，
     * 按 registry 仍指向的版本恢复，避免下一次 sweep 把备份当孤儿删掉。
     */
    private fun recoverInterruptedInstall(records: List<PluginRecord>): Boolean = cleanup {
        val transaction = store.loadInstallTransaction()
        if (transaction != null) {
            val target = File(installedDir, transaction.id)
            val backup = File(installedDir, "${transaction.id}$REPLACED_SUFFIX")
            val committed = records.any {
                it.id == transaction.id && it.installRevision == transaction.revision
            }
            when {
                committed -> deleteTree(backup)
                transaction.hadPrevious && entryExists(backup) -> {
                    deleteTree(target)
                    moveDirectory(backup, target)
                }
                !transaction.hadPrevious -> deleteTree(target)
                !entryExists(target) -> throw IOException("安装事务的旧目录已丢失: ${transaction.id}")
            }
            store.clearInstallTransaction()
        }
        // 兼容尚未写事务记录的旧安装：清单相同时优先保留 registry 对应的备份。
        records.forEach { record ->
            val target = File(installedDir, record.id)
            val backup = File(installedDir, "${record.id}$REPLACED_SUFFIX")
            if (!Files.isDirectory(backup.toPath(), NOFOLLOW_LINKS)) return@forEach
            when {
                !entryExists(target) -> moveDirectory(backup, target)
                manifestMatches(backup, record) -> {
                    deleteTree(target)
                    moveDirectory(backup, target)
                }
                manifestMatches(target, record) -> deleteTree(backup)
                else -> throw IOException("无法确定中断安装的旧目录: ${record.id}")
            }
        }
    }

    private fun manifestMatches(dir: File, record: PluginRecord): Boolean {
        val manifestFile = File(dir, PluginPackageReader.MANIFEST_FILE)
        if (!manifestFile.isFile || manifestFile.length() > PluginManifestParser.MAX_MANIFEST_BYTES) {
            return false
        }
        val bytes = runCatching { manifestFile.readBytes() }.getOrNull() ?: return false
        if (PluginPackageReader.sha256Hex(bytes) != record.manifestSha256) return false
        val parsed = PluginManifestParser.parse(String(bytes, Charsets.UTF_8), hostApi)
        val manifest = (parsed as? ManifestParseResult.Success)?.manifest ?: return false
        return manifest.id == record.id && manifest.version.toString() == record.version
    }

    /** 没有安装记录的目录不具备任何授权，直接清掉（多半是安装中途崩溃留下的）。 */
    private fun sweepOrphans(knownIds: Set<String>) {
        cleanup {
            if (Files.isSymbolicLink(installedDir.toPath())) {
                deleteTree(installedDir)
            } else if (entryExists(installedDir)) {
                Files.newDirectoryStream(installedDir.toPath()).use { entries ->
                    entries.filter { it.fileName.toString() !in knownIds }.forEach { deleteTree(it.toFile()) }
                }
            }
        }
    }

    private fun entryExists(file: File): Boolean = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        true
    } catch (_: NoSuchFileException) {
        false
    }

    private fun checkCleanupParents(file: File) {
        val root = rootDir.toPath().toAbsolutePath().normalize()
        val path = file.toPath().toAbsolutePath().normalize()
        if (!path.startsWith(root)) throw IOException("清理路径不在插件目录内")
        var parent = path.parent
        while (parent != null && parent.startsWith(root)) {
            if (Files.isSymbolicLink(parent)) throw IOException("清理路径的父目录是符号链接: $parent")
            parent = parent.parent
        }
    }

    private fun deleteTree(file: File) {
        checkCleanupParents(file)
        if (!entryExists(file)) return
        // walkFileTree 默认不跟随链接，目录链接（包括悬空链接）只作为文件删除。
        Files.walkFileTree(file.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private inline fun cleanup(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (e: IOException) {
        FileLogger.w(TAG, "插件存储操作失败", e)
        false
    }

    private fun moveDirectory(from: File, to: File) {
        checkCleanupParents(from)
        checkCleanupParents(to)
        if (!from.renameTo(to)) throw IOException("无法移动 ${from.name} 到 ${to.name}")
    }

    companion object {
        private const val TAG = "PluginManager"
        private const val REPLACED_SUFFIX = ".replaced"
        private const val PLUGINS_DIR = "plugins"
        private const val DEV_DIR = "plugin-dev"
        private const val REGISTRY_FILE = "plugin-registry.json"
    }
}
