package com.aicode.feature.agent.domain.subagent

import com.aicode.core.util.FileLogger
import com.aicode.feature.plugin.domain.PluginAgentSource
import com.aicode.feature.workspace.domain.FileAccessProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 子代理定义仓库，聚合全局与项目级两级来源；同名定义项目级优先（与技能、MCP 两级配置一致）。
 * 启停状态由 [AgentDefinitionConfigRepository] 持有：被禁用的定义仍出现在设置页列表里，
 * 但不进主代理的可派发清单、也不能被 [find] 派发出去。
 *
 * 全局定义 `~/.aicode/agents`、项目级定义 `<workspace>/.aicode/agents`，两者都经 [FileAccessProvider]
 * 以容器路径读写，跟随当前执行环境（本地宿主目录或远程 SSH 工作区）。
 * 插件子代理来自手机端插件包，只读，只进入可派发清单（[listEnabled] 与 [find]），不出现在 [listAll]（设置页的可编辑列表）。
 */
@Singleton
class AgentDefinitionRepository @Inject constructor(
    private val globalSource: GlobalDirectoryAgentSource,
    private val projectSource: ProjectDirectoryAgentSource,
    private val configRepository: AgentDefinitionConfigRepository,
    private val fileAccess: FileAccessProvider,
    private val pluginSource: PluginAgentSource
) {
    /**
     * 定义内容版本：经本仓库的 save/delete/setDisabled 修改定义后递增，插件集合变化也会使它变化。
     * 系统提示词里的可用清单按它做缓存失效——否则会话中途新建的子代理
     * 要等到下一个会话才出现在主代理的派发清单里。外部直接改文件不经过这里，
     * 这部分维持原有的「新会话才刷新」行为。
     */
    @Volatile private var definitionsVersionField: Long = 0
    val definitionsVersion: Long get() = definitionsVersionField + pluginSource.revision

    private fun bumpVersion() {
        definitionsVersionField++
    }
    /** 全部定义（含来源作用域），未过滤禁用，按名称排序。不含插件定义。 */
    fun listAll(): List<AgentDefinitionEntry> =
        mergeAll(globalSource.listDefinitions(), projectSource.listDefinitions())

    /** 已启用的定义（注入主代理的可派发清单用），其后追加生效中的插件子代理。 */
    fun listEnabled(): List<AgentDefinitionEntry> {
        val disabled = configRepository.disabledNames()
        val own = listAll().filterNot { it.definition.name.lowercase() in disabled }
        return own + pluginEntries(own, disabled)
    }

    private fun pluginEntries(own: List<AgentDefinitionEntry>, disabled: Set<String>): List<AgentDefinitionEntry> {
        val taken = own.mapTo(HashSet()) { it.definition.name.lowercase() }
        return pluginSource.listDefinitions()
            .filter { it.name.lowercase() !in disabled && taken.add(it.name.lowercase()) }
            .sortedBy { it.name.lowercase() }
            .map { AgentDefinitionEntry(it, AgentDefinitionScope.PLUGIN) }
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
            ?: pluginSource.listAllDefinitions().firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** 当前被禁用的子代理名集合（小写归一化）。批量判断取一次即可，避免逐个重读配置文件。 */
    fun disabledNames(): Set<String> = configRepository.disabledNames()

    /** 该子代理是否在任一作用域中被禁用。 */
    fun isDisabled(name: String): Boolean = name.lowercase() in configRepository.disabledNames()

    /** 在指定作用域启用/禁用某个子代理。 */
    fun setDisabled(name: String, disabled: Boolean, scope: AgentDefinitionScope) {
        configRepository.setDisabled(name, disabled, scope)
        bumpVersion()
    }

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
            }?.definition?.filePath
        }

        val provider = fileAccess
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
            // 名字未改时写回原文件，不能按 name 重拼文件名：内置 Explore 的文件叫 explore.md
            // 而 frontmatter 里写的是 Explore，重拼会在大小写敏感的文件系统上多出一份 Explore.md。
            val target = existingFile?.takeIf { overwritingSelf && provider.isFile(it) }
                ?: "${root.trimEnd('/')}/$name.md"
            provider.writeFile(target, text, overwrite = true)
            // 改名后清掉旧文件，否则会多出一个同内容的旧名子代理
            if (!overwritingSelf && existingFile != null && existingFile != target && provider.isFile(existingFile)) {
                runCatching { provider.delete(existingFile) }.onFailure {
                    FileLogger.w(TAG, "重命名后删除旧定义失败: $existingFile")
                }
            }
            bumpVersion()
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
        val filePath = entry.definition.filePath ?: return false
        val provider = fileAccess
        if (!provider.isFile(filePath)) return false
        val deleted = runCatching { provider.delete(filePath); true }.getOrDefault(false)
        if (deleted) bumpVersion()
        return deleted
    }

    /** 指定作用域的定义目录（容器路径）。 */
    fun agentsRoot(scope: AgentDefinitionScope): String =
        if (scope == AgentDefinitionScope.GLOBAL) globalSource.agentsRoot else projectSource.agentsRoot

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
