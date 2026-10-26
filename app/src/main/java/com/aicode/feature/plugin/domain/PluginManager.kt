package com.aicode.feature.plugin.domain

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.skill.Skill
import com.aicode.feature.agent.domain.subagent.AgentDefinition
import com.aicode.feature.plugin.data.ExtractedPackage
import com.aicode.feature.plugin.data.PackageReadResult
import com.aicode.feature.plugin.data.PluginPackageReader
import com.aicode.feature.plugin.data.PluginRecord
import com.aicode.feature.plugin.data.PluginRegistryStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.InputStream
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

    val granted: Set<PluginPermission> =
        record.grants.mapNotNull { PluginPermission.fromId(it) }.toSet()

    /** 清单要求必须授权、但目前尚未授权的能力。 */
    val missingRequired: Set<PluginPermission>
        get() = manifest?.requiredPermissions.orEmpty() - granted

    /** 启用且状态正常且必要能力齐全，其贡献才会生效。 */
    val isActive: Boolean
        get() = record.enabled && status == PluginStatus.OK && manifest != null && missingRequired.isEmpty()
}

/** 已通过校验、等待使用者确认安装的暂存包。 */
data class PluginPreview(
    val token: String,
    val manifest: PluginManifest,
    val content: PluginContent,
    /** 已安装的同 ID 插件版本；非空表示这次导入会替换它。 */
    val installedVersion: String?,
    /** 与已安装内容的包摘要完全相同。 */
    val identical: Boolean
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
 * 插件的安装、启停、授权与贡献聚合。
 *
 * 插件包与授权记录都保存在手机端 `filesDir/plugins`，该目录没有绑定进 PRoot 容器，也不经
 * [com.aicode.feature.workspace.domain.FileAccessProvider]，不随 SSH 模式迁到远端。
 * 本构建只加载声明式贡献（技能、子代理），不执行任何插件代码。
 */
@Singleton
class PluginManager internal constructor(
    rootDir: File,
    private val hostApi: SemVer,
    private val clock: () -> Long
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        File(context.filesDir, "plugins"),
        PluginApi.VERSION,
        System::currentTimeMillis
    )

    private val installedDir = File(rootDir, "installed")
    private val stagingDir = File(rootDir, "staging")
    private val store = PluginRegistryStore(File(rootDir, "registry.json"))
    private val reader = PluginPackageReader()

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

    private val staged = ConcurrentHashMap<String, StagedPackage>()

    /** 首次调用时从磁盘读取安装记录；会做文件 IO，调用方不要放在主线程。 */
    fun ensureLoaded() {
        if (loaded) return
        synchronized(loadLock) {
            if (loaded) return
            // 进程重启后不可能存在进行中的安装，暂存目录里的都是残留
            stagingDir.deleteRecursively()
            val records = store.load()
            publish(records.map { loadInstalled(it) })
            sweepOrphans(records.map { it.id }.toSet())
            loaded = true
            _isLoaded.value = true
        }
    }

    /** 当前生效的插件技能：已启用、必要能力齐全且授予了 `skills.register`。 */
    fun activeSkills(): List<Skill> {
        ensureLoaded()
        return _plugins.value
            .filter { it.isActive && PluginPermission.SKILLS_REGISTER in it.granted }
            .flatMap { plugin -> plugin.content.skills.map { it.skill } }
    }

    /** 当前生效的插件子代理定义，条件同 [activeSkills]，对应 `agents.register`。 */
    fun activeAgents(): List<AgentDefinition> {
        ensureLoaded()
        return _plugins.value
            .filter { it.isActive && PluginPermission.AGENTS_REGISTER in it.granted }
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

    /** 读取并校验 [input] 中的插件包，通过后暂存等待 [install]。同一时刻只保留一个待确认的暂存包。 */
    suspend fun preview(input: InputStream): PluginPreviewResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            discardAll()
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
        val existing = _plugins.value.firstOrNull { it.id == manifest.id }
        return PluginPreviewResult.Ready(
            PluginPreview(
                token = token,
                manifest = manifest,
                content = content,
                installedVersion = existing?.record?.version,
                identical = existing?.record?.archiveSha256 == pkg.archiveSha256
            )
        )
    }

    private fun rejected(pkg: ExtractedPackage, errors: List<PluginError>): PluginPreviewResult {
        pkg.dir.deleteRecursively()
        return PluginPreviewResult.Failed(errors)
    }

    /** 放弃一个待确认的暂存包。 */
    suspend fun discard(token: String) = withContext(Dispatchers.IO) {
        mutex.withLock { staged.remove(token)?.pkg?.dir?.deleteRecursively() }
        Unit
    }

    private fun discardAll() {
        staged.values.forEach { it.pkg.dir.deleteRecursively() }
        staged.clear()
    }

    /**
     * 安装暂存包。[granted] 是使用者在确认界面勾选的能力：必须覆盖全部必要能力，且不能超出清单申请的范围。
     * 同 ID 已安装时整体替换，授权按本次确认重新设定，不继承旧授权。
     */
    suspend fun install(token: String, granted: Set<PluginPermission>): PluginInstallResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureLoaded()
                val pending = staged[token]
                    ?: return@withLock PluginInstallResult.Failed(PluginError(PluginErrorCode.PREVIEW_EXPIRED))
                val manifest = pending.manifest
                val declared = manifest.permissions.map { it.permission }.toSet()
                val missing = manifest.requiredPermissions - granted
                if (missing.isNotEmpty() || !declared.containsAll(granted)) {
                    return@withLock PluginInstallResult.Failed(
                        PluginError(PluginErrorCode.PERMISSION_NOT_GRANTED, missing.joinToString { it.id })
                    )
                }
                try {
                    commitInstall(token, pending, granted)
                    PluginInstallResult.Installed(manifest.id)
                } catch (e: IOException) {
                    FileLogger.e(TAG, "安装插件失败: ${manifest.id}", e)
                    PluginInstallResult.Failed(PluginError(PluginErrorCode.STORAGE_FAILED, e.javaClass.simpleName))
                }
            }
        }

    private fun commitInstall(token: String, pending: StagedPackage, granted: Set<PluginPermission>) {
        val manifest = pending.manifest
        installedDir.mkdirs()
        val target = File(installedDir, manifest.id)
        val backup = File(installedDir, "${manifest.id}$REPLACED_SUFFIX")
        backup.deleteRecursively()

        val record = PluginRecord(
            id = manifest.id,
            version = manifest.version.toString(),
            enabled = true,
            installedAt = clock(),
            origin = ORIGIN_LOCAL,
            archiveSha256 = pending.pkg.archiveSha256,
            manifestSha256 = pending.pkg.manifestSha256,
            grants = granted.map { it.id }.sorted()
        )

        var movedOld = false
        var movedNew = false
        try {
            if (target.exists()) {
                moveDirectory(target, backup)
                movedOld = true
            }
            moveDirectory(pending.pkg.dir, target)
            movedNew = true
            val others = _plugins.value.filter { it.id != manifest.id }
            store.save(others.map { it.record } + record)
            staged.remove(token)
            backup.deleteRecursively()
            publish(others + loadInstalled(record))
        } catch (e: IOException) {
            // 记录没写成功：把新目录放回暂存区（使用者可以直接重试），旧版本放回原位
            if (movedNew && !target.renameTo(pending.pkg.dir)) target.deleteRecursively()
            if (movedOld) backup.renameTo(target)
            throw e
        }
    }

    suspend fun uninstall(id: String): Boolean = mutateRecords { current ->
        if (current.none { it.id == id }) return@mutateRecords null
        current.filter { it.id != id }.also { next ->
            store.save(next.map { it.record })
            File(installedDir, id).deleteRecursively()
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean): Boolean = updateRecord(id) { record, _ ->
        record.copy(enabled = enabled)
    }

    /** 授予或撤销单项能力。清单没有申请过的能力不会被授予。 */
    suspend fun setGranted(id: String, permission: PluginPermission, granted: Boolean): Boolean =
        updateRecord(id) { record, plugin ->
            if (plugin.manifest?.requestFor(permission) == null) return@updateRecord record
            val grants = record.grants.toMutableSet()
            if (granted) grants += permission.id else grants -= permission.id
            record.copy(grants = grants.sorted())
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

    private fun loadInstalled(record: PluginRecord): InstalledPlugin {
        fun broken(status: PluginStatus, errors: List<PluginError> = emptyList()) =
            InstalledPlugin(record, status, manifest = null, content = PluginContent(), errors = errors)

        val dir = File(installedDir, record.id)
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
        return when (val content = PluginContentLoader.load(dir, manifest)) {
            is ContentLoadResult.Failed -> InstalledPlugin(record, PluginStatus.CORRUPTED, manifest, PluginContent(), content.errors)
            is ContentLoadResult.Ok -> InstalledPlugin(record, PluginStatus.OK, manifest, content.content)
        }
    }

    /** 没有安装记录的目录不具备任何授权，直接清掉（多半是安装中途崩溃留下的）。 */
    private fun sweepOrphans(knownIds: Set<String>) {
        installedDir.listFiles().orEmpty()
            .filter { it.name !in knownIds }
            .forEach { it.deleteRecursively() }
    }

    private fun moveDirectory(from: File, to: File) {
        if (!from.renameTo(to)) throw IOException("无法移动 ${from.name} 到 ${to.name}")
    }

    companion object {
        private const val TAG = "PluginManager"
        private const val REPLACED_SUFFIX = ".replaced"
        const val ORIGIN_LOCAL = "local"
    }
}
