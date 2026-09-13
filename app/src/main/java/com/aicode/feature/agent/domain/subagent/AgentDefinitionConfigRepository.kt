package com.aicode.feature.agent.domain.subagent

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
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
 * 子代理启停配置持久化，与技能的 `skills.json` 同一套约定，支持全局 + 项目级两级：
 * - 全局：`filesDir/aicode/agents.json`（跨项目、跨升级保留）；
 * - 项目级：`workspacePath/.aicode/agents.json`（随工作区走，可 git 追踪）。
 *
 * 格式 `{"disabled": ["name-a"]}`，只存禁用名单；生效禁用集合 = 全局 + 项目并集。
 * 每次读取都从磁盘加载，外部手工编辑即时生效；名单里已不存在的子代理名在过滤时天然被忽略。
 */
@Singleton
class AgentDefinitionConfigRepository @Inject constructor(
    private val containerInstaller: ContainerInstaller,
    private val projectAicodeRoot: ProjectAicodeRoot
) {
    private fun globalFile(): File = File(containerInstaller.aicodeDir, CONFIG_FILE)

    private fun projectFile(): File = File(projectAicodeRoot.current(), CONFIG_FILE)

    /** 当前生效的禁用子代理名集合（全局 + 项目并集，归一化为小写）。 */
    fun disabledNames(): Set<String> =
        (readDisabled(globalFile()) + readDisabled(projectFile())).map { it.lowercase() }.toSet()

    /** 在指定作用域的配置中启用/禁用某个子代理。 */
    fun setDisabled(name: String, disabled: Boolean, scope: AgentDefinitionScope) {
        val file = if (scope == AgentDefinitionScope.GLOBAL) globalFile() else projectFile()
        val names = readDisabled(file).toMutableSet()
        if (disabled) names.add(name) else names.removeAll { it.equals(name, ignoreCase = true) }
        writeDisabled(file, names)
    }

    companion object {
        private const val TAG = "AgentDefinitionConfigRepository"
        private const val CONFIG_FILE = "agents.json"
        private const val AICODE_DIR = ".aicode"
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
        private val PRETTY_JSON = Json { prettyPrint = true }

        internal fun parseDisabled(raw: String): Set<String> {
            val root = runCatching { JSON.parseToJsonElement(raw).jsonObject }.getOrElse {
                FileLogger.w(TAG, "子代理配置 JSON 解析失败: ${it.message}")
                return emptySet()
            }
            return (root["disabled"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.toSet()
                ?: emptySet()
        }

        internal fun serializeDisabled(names: Set<String>): String {
            val root = buildJsonObject {
                putJsonArray("disabled") { names.sorted().forEach { add(it) } }
            }
            return PRETTY_JSON.encodeToString(JsonObject.serializer(), root)
        }

        private fun readDisabled(file: File): Set<String> {
            if (!file.isFile) return emptySet()
            return runCatching { parseDisabled(file.readText()) }.getOrElse {
                FileLogger.w(TAG, "读取 ${file.name} 失败: ${it.message}")
                emptySet()
            }
        }

        private fun writeDisabled(file: File, names: Set<String>) {
            file.parentFile?.mkdirs()
            val json = serializeDisabled(names)
            // 临时文件 + rename 原子落盘，避免写一半崩溃损坏配置
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) file.writeText(json)
        }
    }
}
