package com.aicode.feature.agent.domain.skill

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.putJsonArray

/**
 * 技能启停配置持久化，支持全局 + 项目级两级：
 * - 全局：`filesDir/aicode/skills.json`（跨项目、跨升级保留）；
 * - 项目级：`workspacePath/.aicode/skills.json`（随工作区走，可 git 追踪）。
 *
 * 格式：`{"disabled": ["skill-a", "skill-b"]}`，仅存「禁用名单」这一个事实；
 * 生效禁用集合 = 全局 + 项目并集。每次读取都从磁盘加载，外部手工编辑即时生效；
 * 名单中不存在的技能名在过滤时天然被忽略，无需清理。
 */
@Singleton
class SkillConfigRepository @Inject constructor(
    private val containerInstaller: ContainerInstaller,
    private val workspaceRepository: WorkspaceRepository,
    private val projectAicodeRoot: ProjectAicodeRoot
) {
    /** 全局配置文件：`filesDir/aicode/skills.json`。 */
    private fun globalFile(): File = File(containerInstaller.aicodeDir, CONFIG_FILE)

    /** 当前工作区的项目级配置文件：`workspacePath/.aicode/skills.json`。 */
    private fun projectFile(): File = File(projectAicodeRoot.current(), CONFIG_FILE)

    /** 当前生效的禁用技能名集合（全局 + 项目并集，归一化为小写）。 */
    fun disabledNames(): Set<String> {
        val global = readDisabled(globalFile())
        val project = readDisabled(projectFile())
        return (global + project).map { it.lowercase() }.toSet()
    }

    /** 在指定作用域的配置中启用/禁用某个技能。 */
    fun setDisabled(name: String, disabled: Boolean, scope: SkillScope) {
        val file = if (scope == SkillScope.GLOBAL) globalFile() else projectFile()
        val names = readDisabled(file).toMutableSet()
        if (disabled) names.add(name) else names.remove(name)
        writeDisabled(file, names)
    }

    // ── 外部变更监听：容器内/手工直接增删改技能目录或 skills.json 后，数秒内通知 UI 刷新 ──

    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _changes = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** 技能目录或配置文件被外部修改（内容与上次快照不一致）时广播一次。 */
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    @Volatile
    private var lastSnapshot: String? = null
    @Volatile
    private var watchingInitialized = false

    /**
     * 启动监听：2s 轮询全局/当前工作区项目技能目录树与两个 skills.json 的 mtime 快照，
     * 外部修改后广播 [changes]。App 常驻期间一直运行；内部写入会更新快照，不会误触发。
     * 项目级只盯当前工作区（路径每次轮询现算），切换工作区后自动跟随。幂等可重复调。
     */
    fun startWatching() {
        watchScope.launch {
            while (true) {
                delay(WATCH_POLL_MS)
                runCatching { checkChanged() }
            }
        }
    }

    private suspend fun checkChanged() {
        val snapshot = snapshotKey()
        if (!watchingInitialized) {
            lastSnapshot = snapshot
            watchingInitialized = true
            return
        }
        if (lastSnapshot == snapshot) return
        lastSnapshot = snapshot
        _changes.tryEmit(Unit)
        FileLogger.i(TAG, "检测到技能目录或配置变化，已通知刷新")
    }

    /** 快照：全局/项目技能目录树 + 两个配置文件的 mtime/size。 */
    private fun snapshotKey(): String = buildString {
        appendStamp(File(containerInstaller.aicodeDir, "skills"))
        appendStamp(File(File(workspaceRepository.currentPath(), AICODE_DIR), "skills"))
        appendStamp(globalFile())
        appendStamp(projectFile())
    }

    /** 目录 → 递归所有文件的相对路径+mtime+size；文件 → 自身。不存在则无输出。 */
    private fun StringBuilder.appendStamp(file: File) {
        if (file.isDirectory) {
            file.walkTopDown()
                .filter { it.isFile }
                .sortedBy { it.absolutePath }
                .forEach { append(it.relativeTo(file).path).append(':').append(it.lastModified()).append(':').append(it.length()).append(';') }
        } else if (file.isFile) {
            append(file.name).append(':').append(file.lastModified()).append(':').append(file.length()).append(';')
        }
    }

    companion object {
        private const val TAG = "SkillConfigRepository"
        private const val CONFIG_FILE = "skills.json"
        private const val AICODE_DIR = ".aicode"
        /** 外部变更轮询间隔：手工编辑后约 2s 内刷新。 */
        private const val WATCH_POLL_MS = 2000L
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
        private val PRETTY_JSON = Json { prettyPrint = true }

        fun parseDisabled(raw: String): Set<String> {
            val root = runCatching { JSON.parseToJsonElement(raw).jsonObject }.getOrElse {
                FileLogger.w(TAG, "技能配置 JSON 解析失败: ${it.message}")
                return emptySet()
            }
            return (root["disabled"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.toSet()
                ?: emptySet()
        }

        fun serializeDisabled(names: Set<String>): String {
            val root = buildJsonObject {
                putJsonArray("disabled") { names.sorted().forEach { add(it) } }
            }
            return PRETTY_JSON.encodeToString(JsonObject.serializer(), root)
        }

        fun readDisabled(file: File): Set<String> {
            if (!file.isFile) return emptySet()
            return runCatching { parseDisabled(file.readText()) }.getOrElse {
                FileLogger.w(TAG, "读取 ${file.name} 失败: ${it.message}")
                emptySet()
            }
        }

        fun writeDisabled(file: File, names: Set<String>) {
            file.parentFile?.mkdirs()
            val json = serializeDisabled(names)
            // 临时文件 + rename 原子落盘，避免写一半崩溃损坏配置
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) {
                // rename 失败（罕见），回退直接写，避免丢配置
                file.writeText(json)
            }
        }
    }
}
