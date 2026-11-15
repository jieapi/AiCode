package com.aicode.feature.agent.domain.permission

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.core.watch.FileChangeBatch
import com.aicode.core.watch.FileChangeHub
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 工具授权规则的持久化。全局规则存 app 私有目录 (`filesDir/aicode/permissions.json`)，
 * 项目级规则存工作区目录 (`.aicode/permissions.json`)，方便团队通过 git 共享。
 *
 * 文件格式为紧凑的 `Tool(pattern)` 风格，示例：
 * ```json
 * {
 *   "permissions": {
 *     "allow": ["Bash(git pull)", "writeFile"],
 *     "deny": ["Bash(rm -rf /)"]
 *   }
 * }
 * ```
 *
 * 安全要点：全局规则存在 app 私有目录，AI 无法篡改；项目级规则存在工作区内，
 * 可被 AI 修改，但作为项目级声明式配置这是有意为之（可 git 追踪/回滚）。
 *
 * 并发模式参考 [McpConfigRepository]：Mutex 保护文件 IO + MutableStateFlow 缓存。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class PermissionRulesRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val workspaceRepository: WorkspaceRepository,
    private val projectAicodeRoot: ProjectAicodeRoot,
    private val fileChangeHub: FileChangeHub,
    private val pathMapper: WorkspacePathMapper
) {
    private companion object {
        const val TAG = "PermissionRules"
        const val PERMISSIONS_FILE = "permissions.json"
        /** 项目级配置目录名，容器内即 `~/workspace/.aicode`。 */
        const val AICODE_DIR_NAME = ".aicode"
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    }

    /** 全局权限文件：`filesDir/aicode/permissions.json`，与 mcp.json 同级。 */
    private val globalFile: File
        get() = File(File(context.filesDir, "aicode"), PERMISSIONS_FILE)

    /** 当前工作区的项目级权限文件：`workspacePath/.aicode/permissions.json`。 */
    private fun projectFileForPath(workspacePath: String): File =
        File(projectAicodeRoot.forPath(workspacePath), PERMISSIONS_FILE)

    // ── 内存缓存与响应式流 ──────────────────────────────────────

    private val globalState = MutableStateFlow<List<PermissionRule>?>(null)
    private val projectStates = ConcurrentHashMap<String, MutableStateFlow<List<PermissionRule>?>>()
    private val mutex = Mutex()

    // ── 外部修改监听：容器内/手工直接编辑 permissions.json 后刷新缓存，UI 与评估即时生效 ──

    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 启动配置文件监听：外部修改后按磁盘现状刷新缓存。App 启动调用一次（幂等）。
     * 订阅驱动：[FileChangeHub] 监听两个权限文件所在目录并合并成批上报，不再自建轮询。
     * 保持常驻订阅是因为 AI 权限评估随时要读到最新规则（不像纯 UI 列表可以暂停刷新）。
     */
    fun startWatching() {
        watchScope.launch {
            merge(
                fileChangeHub.watchAicode(),
                fileChangeHub.watchWorkspace("${FileChangeHub.CONTAINER_ROOT}/$AICODE_DIR_NAME")
            ).collect { batch -> refreshFromDisk(batch) }
        }
    }

    /** 只处理命中的文件；内容确实变化才更新缓存（touch 不算）。 */
    private suspend fun refreshFromDisk(batch: FileChangeBatch) {
        val globalPath = globalFile.absolutePath
        if (batch.changes.any { it.hostPath == globalPath }) {
            val rules = loadFromFile(globalFile)
            if (rules != (globalState.value ?: emptyList<PermissionRule>())) {
                globalState.value = rules
                FileLogger.i(TAG, "检测到全局权限配置变化，已刷新")
            }
        }
        val path = workspaceRepository.currentPath()
        if (batch.changes.any { it.hostPath == projectFileForPath(path).absolutePath }) {
            val state = getProjectState(path)
            // 缓存尚未加载时不处理：首次加载由 [ensureProjectLoaded] 完成，工作区切换不算外部变更。
            if (state.value != null) {
                val rules = loadFromFile(projectFileForPath(path))
                if (rules != state.value) {
                    state.value = rules
                    FileLogger.i(TAG, "检测到项目权限配置变化，已刷新")
                }
            }
        }
    }

    private fun getProjectState(workspacePath: String): MutableStateFlow<List<PermissionRule>?> =
        getProjectState(captureProject(workspacePath))

    private fun getProjectState(project: ProjectContext): MutableStateFlow<List<PermissionRule>?> =
        projectStates.getOrPut(project.file.absolutePath) { MutableStateFlow(null) }

    // ── 懒加载 ──────────────────────────────────────────────────

    private suspend fun ensureGlobalLoaded() {
        if (globalState.value != null) return
        mutex.withLock {
            if (globalState.value != null) return
            globalState.value = loadFromFile(globalFile)
        }
    }

    private suspend fun ensureProjectLoaded(workspacePath: String) =
        ensureProjectLoaded(captureProject(workspacePath))

    private suspend fun ensureProjectLoaded(project: ProjectContext) {
        val state = getProjectState(project)
        if (state.value != null) return
        mutex.withLock {
            if (state.value != null) return
            state.value = loadFromResolvedFile(project.file)
        }
    }

    private suspend fun loadFromFile(file: File): List<PermissionRule> =
        withContext(Dispatchers.IO) {
            loadFromResolvedFile(pathMapper.resolveHostFile(file))
        }

    private suspend fun loadFromResolvedFile(file: File): List<PermissionRule> =
        withContext(Dispatchers.IO) {
            if (!file.isFile) return@withContext emptyList()
            runCatching {
                JSON.decodeFromString<PermissionFile>(file.readText()).toRuleList()
            }.getOrElse {
                FileLogger.w(TAG, "读取 ${file.path} 失败: ${it.message}")
                emptyList()
            }
        }

    private fun writeToFile(file: File, rules: List<PermissionRule>) =
        writeToResolvedFile(pathMapper.resolveHostFile(file), rules)

    private fun writeToResolvedFile(file: File, rules: List<PermissionRule>) {
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Cannot create permissions directory: ${parent.absolutePath}")
        }
        file.writeText(JSON.encodeToString(PermissionFile.serializer(), rules.toPermissionFile()))
    }

    // ── 公共 API ────────────────────────────────────────────────

    class ProjectContext internal constructor(internal val file: File)

    /** 在挂起前固定模式、服务器与工作区映射后的宿主文件，后续 IO 不再读取当前项目。 */
    fun captureProject(workspacePath: String): ProjectContext =
        ProjectContext(pathMapper.resolveHostFile(projectFileForPath(workspacePath)))

    suspend fun loadEffectiveForProject(project: ProjectContext): List<PermissionRule> {
        ensureGlobalLoaded()
        val global = globalState.value ?: emptyList()
        ensureProjectLoaded(project)
        val rules = getProjectState(project).value ?: emptyList()
        return rules + global
    }

    suspend fun addToProject(project: ProjectContext, rule: PermissionRule) {
        ensureProjectLoaded(project)
        mutex.withLock {
            val state = getProjectState(project)
            val rules = (state.value ?: emptyList()).toMutableList()
            if (rule !in rules) rules.add(rule)
            withContext(Dispatchers.IO) { writeToResolvedFile(project.file, rules) }
            state.value = rules
        }
        FileLogger.i(TAG, "记忆授权规则[PROJECT]: ${rule.toolName} ${rule.pattern}")
    }

    /** 当前选中的项目名；无选中时为 null（此时项目级规则不可用，仅全局生效）。 */
    fun currentProjectName(): String? = workspaceRepository.current.value?.name

    /** 当前项目名流，跟随工作区切换自动更新，供 UI 订阅。 */
    val currentProjectNameFlow: Flow<String?> = workspaceRepository.current.map { it?.name }

    /**
     * 当前项目规则流：跟随 [workspaceRepository.current] 切换自动重新加载对应项目规则；
     * 无选中工作区时发空列表。供 UI 订阅，避免一次性快照在初始化未完成时读到 null。
     */
    val currentProjectRulesFlow: Flow<List<PermissionRule>> =
        workspaceRepository.current.flatMapLatest { ws ->
            if (ws == null) flowOf(emptyList()) else projectRulesFlow(ws.name)
        }

    /** 全局规则流，供管理界面观察。 */
    val globalRulesFlow: Flow<List<PermissionRule>> = flow {
        ensureGlobalLoaded()
        emitAll(globalState.filterNotNull())
    }

    /** 一次性读取全部全局规则（备份用）。 */
    suspend fun getGlobalRulesOnce(): List<PermissionRule> {
        ensureGlobalLoaded()
        return globalState.value ?: emptyList()
    }

    /** 全量替换全局规则（备份导入用），原子写文件并更新缓存。 */
    suspend fun setGlobalRules(rules: List<PermissionRule>) {
        mutex.withLock {
            withContext(Dispatchers.IO) { writeToFile(globalFile, rules) }
            globalState.value = rules
        }
    }

    /** 指定项目的规则流，供管理界面观察。 */
    fun projectRulesFlow(projectName: String): Flow<List<PermissionRule>> {
        val workspacePath = workspaceRepository.currentPath()
        val state = getProjectState(workspacePath)
        return flow {
            ensureProjectLoaded(workspacePath)
            emitAll(state.filterNotNull())
        }
    }

    /**
     * 评估用：当前项目规则 + 全局规则合并（项目在前）。一次性读取快照。
     */
    suspend fun loadEffectiveForCurrentProject(): List<PermissionRule> {
        ensureGlobalLoaded()
        val global = globalState.value ?: emptyList()
        val workspacePath = workspaceRepository.currentPath()
        ensureProjectLoaded(workspacePath)
        val project = getProjectState(workspacePath).value ?: emptyList()
        return project + global
    }

    /** 按 scope 新增规则。PROJECT 写入当前项目；无当前项目则忽略并告警。 */
    suspend fun add(scope: PermissionScope, rule: PermissionRule) {
        when (scope) {
            PermissionScope.GLOBAL -> editGlobal { if (rule !in it) it.add(rule) }
            PermissionScope.PROJECT -> {
                val workspacePath = workspaceRepository.currentPath()
                editProject(workspacePath) { if (rule !in it) it.add(rule) }
            }
        }
        FileLogger.i(TAG, "记忆授权规则[$scope]: ${rule.toolName} ${rule.pattern}")
    }

    suspend fun removeGlobalRule(rule: PermissionRule) = editGlobal { it.remove(rule) }

    suspend fun removeProjectRule(projectName: String, rule: PermissionRule) {
        val workspacePath = workspaceRepository.currentPath()
        editProject(workspacePath) { it.remove(rule) }
    }

    /** 把一条项目规则提升为全局：项目删、全局加。 */
    suspend fun promoteToGlobal(projectName: String, rule: PermissionRule) {
        val workspacePath = workspaceRepository.currentPath()
        editProject(workspacePath) { it.remove(rule) }
        editGlobal { if (rule !in it) it.add(rule) }
        FileLogger.i(TAG, "提升为全局: ${rule.toolName} ${rule.pattern}")
    }

    // ── 内部写入 ────────────────────────────────────────────────

    private suspend fun editGlobal(mutate: (MutableList<PermissionRule>) -> Unit) {
        ensureGlobalLoaded()
        mutex.withLock {
            val list = (globalState.value ?: emptyList()).toMutableList()
            mutate(list)
            withContext(Dispatchers.IO) { writeToFile(globalFile, list) }
            globalState.value = list
        }
    }

    private suspend fun editProject(workspacePath: String, mutate: (MutableList<PermissionRule>) -> Unit) {
        ensureProjectLoaded(workspacePath)
        mutex.withLock {
            val state = getProjectState(workspacePath)
            val list = (state.value ?: emptyList()).toMutableList()
            mutate(list)
            withContext(Dispatchers.IO) { writeToFile(projectFileForPath(workspacePath), list) }
            state.value = list
        }
    }
}
