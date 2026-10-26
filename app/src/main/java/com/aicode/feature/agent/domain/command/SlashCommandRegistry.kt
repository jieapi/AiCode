package com.aicode.feature.agent.domain.command

import android.content.Context
import com.aicode.feature.agent.domain.skill.Skill
import com.aicode.feature.agent.domain.skill.SkillRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import com.aicode.feature.plugin.domain.PluginManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 斜杠命令注册表：聚合内置命令（[ActionCommandHandler]）与已启用技能，
 * 统一供 `/` 菜单展示与发送时解析。
 *
 * - 菜单：[commands] 为合并后的 [SlashCommand] 列表——内置命令优先，同名技能被跳过，按名排序。
 * - 解析：[resolve] 把 `/name args` 拆成命令名与参数，命中内置命令或技能；纯内存，无 IO。
 * - 刷新：[refresh] 重新扫描技能（文件 IO），调用方负责触发时机。
 */
@Singleton
class SlashCommandRegistry @Inject constructor(
    actionHandlers: Set<@JvmSuppressWildcards ActionCommandHandler>,
    private val skillRepository: SkillRepository,
    @param:ApplicationContext private val context: Context,
    private val pluginManager: PluginManager? = null
) {
    /** 供单测等不注入插件管理器的场景使用。 */
    constructor(
        actionHandlers: Set<ActionCommandHandler>,
        skillRepository: SkillRepository,
        context: Context
    ) : this(actionHandlers, skillRepository, context, null)

    private val actions: List<ActionCommandHandler> = actionHandlers.sortedBy { it.name.lowercase() }
    private val actionsByName: Map<String, ActionCommandHandler> =
        actions.associateBy { it.name.lowercase() }

    /** 技能快照：由 [refresh] 在 IO 线程更新，[resolve] 只读。 */
    @Volatile
    private var skills: List<Skill> = emptyList()

    private val _commands = MutableStateFlow<List<SlashCommand>>(emptyList())

    /** `/` 菜单展示的命令列表。 */
    val commands: StateFlow<List<SlashCommand>> = _commands.asStateFlow()

    init {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch { refresh() }
        pluginManager?.let { pm ->
            scope.launch {
                pm.plugins.collect { refresh() }
            }
        }
    }

    /** 重新扫描技能并重建菜单。涉及文件 IO，调用方应在 IO 上下文调用。 */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        skills = skillRepository.listSkills()
        _commands.value = buildCommands()
    }

    private fun buildCommands(): List<SlashCommand> = buildList {
        actions.forEach {
            add(
                SlashCommand(
                    name = it.name,
                    description = context.getString(it.descriptionRes),
                    kind = SlashCommandKind.ACTION,
                    acceptsArgs = it.acceptsArgs
                )
            )
        }
        skills.filterNot { it.name.lowercase() in actionsByName }
            .sortedBy { it.name.lowercase() }
            .forEach {
            add(
                SlashCommand(
                    name = it.name,
                    description = it.description,
                    kind = SlashCommandKind.SKILL,
                    acceptsArgs = true
                )
            )
        }
    }

    /**
     * 解析发送文本：仅当输入以 `/` 开头且命中内置命令或技能时返回非空。
     * 内置命令不接受参数时，带参数视为未命中（回落为普通消息）。
     */
    fun resolve(input: String): ResolvedCommand? {
        val trimmed = input.trim()
        if (!trimmed.startsWith('/')) return null
        val body = trimmed.substring(1)
        val name = body.substringBefore(' ').trim()
        if (name.isEmpty()) return null
        val args = body.substringAfter(' ', "").trim()

        actionsByName[name.lowercase()]?.let { handler ->
            if (!handler.acceptsArgs && args.isNotEmpty()) return null
            return ResolvedCommand.Action(handler, args)
        }
        val skill = skills.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return null
        return ResolvedCommand.SkillCommand(skill, args)
    }

    /** 解析结果：内置动作或技能。 */
    sealed interface ResolvedCommand {
        val name: String
        val args: String

        data class Action(val handler: ActionCommandHandler, override val args: String) : ResolvedCommand {
            override val name: String get() = handler.name
        }

        data class SkillCommand(val skill: Skill, override val args: String) : ResolvedCommand {
            override val name: String get() = skill.name
        }
    }

    companion object {
        /**
         * 构造技能本轮指令：把技能正文里的 `$ARGUMENTS` 替换为 [args]；
         * 无占位且 [args] 非空时追加一行，保证用户输入不丢失。
         */
        fun buildSkillPrompt(skill: Skill, args: String): String =
            substituteArguments(skill.instructions, args)
    }
}
