package com.aicode.feature.agent.domain.subagent

import com.aicode.core.util.FileLogger
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 子代理定义仓库，聚合全局与项目级两级来源；同名定义项目级优先（与技能、MCP 两级配置一致）。
 * 启停状态由 [AgentDefinitionConfigRepository] 持有：被禁用的定义仍出现在设置页列表里，
 * 但不进主代理的可派发清单、也不能被 [find] 派发出去。
 */
@Singleton
class AgentDefinitionRepository @Inject constructor(
    private val localSource: LocalDirectoryAgentSource,
    private val projectSource: ProjectDirectoryAgentSource,
    private val configRepository: AgentDefinitionConfigRepository
) {
    /** 全部定义（含来源作用域），未过滤禁用，按名称排序。 */
    fun listAll(): List<AgentDefinitionEntry> =
        mergeAll(localSource.listDefinitions(), projectSource.listDefinitions())

    /** 已启用的定义（注入主代理的可派发清单用）。 */
    fun listEnabled(): List<AgentDefinitionEntry> {
        val disabled = configRepository.disabledNames()
        return listAll().filterNot { it.definition.name.lowercase() in disabled }
    }

    /** 按名称查找可派发的定义（忽略大小写）；不存在或已被禁用时返回 null。 */
    fun find(name: String): AgentDefinition? =
        listEnabled().firstOrNull { it.definition.name.equals(name, ignoreCase = true) }?.definition

    /**
     * 按名称查找定义，包含已禁用的。已经派出去的子会话要用它还原自己的提示词与工具集——
     * 禁用只该拦住新的派发，不该让在跑的子代理中途换一套配置。
     */
    fun findIncludingDisabled(name: String): AgentDefinition? =
        listAll().firstOrNull { it.definition.name.equals(name, ignoreCase = true) }?.definition

    /** 该子代理是否在任一作用域中被禁用。 */
    fun isDisabled(name: String): Boolean = name.lowercase() in configRepository.disabledNames()

    /** 在指定作用域启用/禁用某个子代理。 */
    fun setDisabled(name: String, disabled: Boolean, scope: AgentDefinitionScope) =
        configRepository.setDisabled(name, disabled, scope)

    /**
     * 写入定义文件（新建或编辑）。[originalName] 为编辑前的名称，新建时传 null；
     * 改了名就写新文件再删旧文件，等价于重命名。返回 null 表示成功。
     */
    fun save(
        form: AgentDefinitionForm,
        scope: AgentDefinitionScope,
        originalName: String? = null
    ): AgentSaveError? {
        val name = form.name.trim()
        if (!isValidName(name)) return AgentSaveError.INVALID_NAME
        if (form.prompt.isBlank()) return AgentSaveError.EMPTY_PROMPT

        val overwritingSelf = originalName != null && originalName.equals(name, ignoreCase = true)
        if (!overwritingSelf) {
            val taken = listAll().any {
                it.scope == scope && it.definition.name.equals(name, ignoreCase = true)
            }
            if (taken) return AgentSaveError.NAME_CONFLICT
        }

        val existingFile = originalName?.let { old ->
            listAll().firstOrNull {
                it.scope == scope && it.definition.name.equals(old, ignoreCase = true)
            }?.definition?.file
        }

        val root = agentsRoot(scope)
        val text = AgentDefinitionParser.serialize(
            name = name,
            description = form.description,
            providerId = form.providerId,
            model = form.model,
            reasoningEffort = form.reasoningEffort,
            mode = form.mode,
            allowedTools = form.allowedTools,
            disallowedTools = form.disallowedTools,
            inject = form.inject,
            prompt = form.prompt
        )

        return try {
            root.mkdirs()
            // 名字未改时写回原文件，不能按 name 重拼文件名：内置 Explore 的文件叫 explore.md
            // 而 frontmatter 里写的是 Explore，重拼会在大小写敏感的文件系统上多出一份 Explore.md。
            val target = existingFile?.takeIf { overwritingSelf && it.isFile } ?: File(root, "$name.md")
            target.writeText(text)
            // 改名后清掉旧文件，否则会多出一个同内容的旧名子代理
            if (!overwritingSelf && existingFile?.isFile == true && existingFile != target) {
                if (!existingFile.delete()) {
                    FileLogger.w(TAG, "重命名后删除旧定义失败: ${existingFile.absolutePath}")
                }
            }
            null
        } catch (e: Exception) {
            FileLogger.e(TAG, "保存子代理定义失败: $name", e)
            AgentSaveError.IO_FAILED
        }
    }

    /** 删除指定作用域的定义文件，不可恢复。返回是否成功。 */
    fun delete(name: String, scope: AgentDefinitionScope): Boolean {
        val entry = listAll().firstOrNull {
            it.definition.name.equals(name, ignoreCase = true) && it.scope == scope
        } ?: return false
        val file = entry.definition.file ?: return false
        return file.isFile && file.delete()
    }

    /** 指定作用域的定义目录。 */
    fun agentsRoot(scope: AgentDefinitionScope): File =
        if (scope == AgentDefinitionScope.GLOBAL) localSource.agentsRoot else projectSource.agentsRoot

    companion object {
        private const val TAG = "AgentDefinitionRepository"

        /** 名称同时用作文件名，禁掉路径分隔符与保留字符；长度上限防止极端文件名。 */
        internal fun isValidName(name: String): Boolean {
            if (name.isBlank() || name.length > MAX_NAME_LENGTH) return false
            if (name == "." || name == "..") return false
            return name.none { it in ILLEGAL_NAME_CHARS || it.isISOControl() }
        }

        private const val MAX_NAME_LENGTH = 40
        private val ILLEGAL_NAME_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

        /** 合并两级来源：同名项目级覆盖全局，按名称排序。 */
        internal fun mergeAll(
            global: List<AgentDefinition>,
            project: List<AgentDefinition>
        ): List<AgentDefinitionEntry> {
            val byName = LinkedHashMap<String, AgentDefinitionEntry>()
            global.forEach { byName[it.name.lowercase()] = AgentDefinitionEntry(it, AgentDefinitionScope.GLOBAL) }
            project.forEach { byName[it.name.lowercase()] = AgentDefinitionEntry(it, AgentDefinitionScope.PROJECT) }
            return byName.values.sortedBy { it.definition.name.lowercase() }
        }
    }
}
