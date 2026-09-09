package com.aicode.feature.agent.domain.subagent

import java.io.File

/** 子代理定义的来源作用域：全局（跨项目共享）或项目级（仅当前工作区生效，可 git 追踪）。 */
enum class AgentDefinitionScope { GLOBAL, PROJECT }

/**
 * 子代理系统提示词的可注入片段。省略 `inject` 时使用 [AgentDefinition.DEFAULT_INJECT]。
 *
 * BASE 与 MAIN_RULES 互斥语义上并不强制，同时写则两者都注入（MAIN_RULES 在前）。
 */
enum class InjectPart(val token: String) {
    /** 子代理专用精简基线（`90-subagent-base.md`）：工具用法、路径约定、安全边界。 */
    BASE("base"),

    /** 主代理的完整静态规则基线（`00`~`70` 全部片段），需要子代理与主代理行为完全一致时使用。 */
    MAIN_RULES("mainRules"),

    /** 可用技能清单（子代理可 loadSkill）。 */
    SKILLS("skills"),

    /** 全局 + 项目记忆的摘要清单。 */
    MEMORY("memory"),

    /** 项目规则（AGENTS.md / CLAUDE.md）。 */
    PROJECT_RULES("projectRules");

    companion object {
        /** frontmatter 里的宽松写法映射：忽略大小写、连字符与下划线差异。 */
        fun fromToken(token: String): InjectPart? {
            val normalized = token.trim().lowercase().replace("-", "").replace("_", "")
            return when (normalized) {
                "base" -> BASE
                "mainrules" -> MAIN_RULES
                "skills", "skill" -> SKILLS
                "memory", "memories" -> MEMORY
                "projectrules", "projectrule" -> PROJECT_RULES
                else -> null
            }
        }
    }
}

/**
 * 一个自定义子代理定义，来自 `agents/<name>.md` 的 frontmatter + 正文。
 *
 * @param name 唯一标识，`task(agent="<name>")` 用它派发
 * @param description 何时该派这个子代理，注入主代理提示词供其选择
 * @param providerId 绑定的 provider id；null 表示继承父会话
 * @param model 绑定的模型名；null 表示继承父会话
 * @param reasoningEffort 思考强度（low/medium/high）；null 表示继承父会话
 * @param allowedTools 工具白名单；空表示继承全量工具
 * @param disallowedTools 工具黑名单，先于白名单生效
 * @param inject 要注入的提示词片段
 * @param prompt agent 自身的系统提示词（正文）
 * @param file 定义文件，供设置页展示与删除
 * @param title 群聊显示名（可选）；缺省用 [name]
 * @param avatarColor 头像色相（可选，如 "#E8505B" 或 "hsl:120"）；缺省按名字哈希确定
 * @param avatarShape 头像形状（可选，如 circle/squircle/hexagon）；缺省 circle
 */
data class AgentDefinition(
    val name: String,
    val description: String,
    val providerId: String? = null,
    val model: String? = null,
    val reasoningEffort: String? = null,
    val allowedTools: List<String> = emptyList(),
    val disallowedTools: List<String> = emptyList(),
    val inject: Set<InjectPart> = DEFAULT_INJECT,
    val prompt: String,
    val file: File? = null,
    val title: String? = null,
    val avatarColor: String? = null,
    val avatarShape: String? = null
) {
    /**
     * 按白名单/黑名单裁剪工具名集合。`task` 永远被剔除——子代理不能嵌套派子代理；
     * `group_message` 永远保留——群聊成员由自定义 agent 定义驱动时，私信工具不能被
     * 定义里的 tools 白名单滤掉（非群聊场景调用会返回 NOT_GROUP_MEMBER，模型自行理解）。
     *
     * 黑名单先生效，再与白名单取交集；支持 `mcp__server__*` 形式的通配，
     * 以便一次禁掉某个 MCP server 的全部工具。
     */
    fun filterToolNames(all: List<String>): List<String> {
        val afterDeny = all.filterNot { name -> disallowedTools.any { matches(it, name) } }
        val afterAllow = if (allowedTools.isEmpty()) {
            afterDeny
        } else {
            afterDeny.filter { name -> allowedTools.any { matches(it, name) } }
        }
        val filtered = afterAllow.filterNot { it == NESTED_TOOL }
        return if (GROUP_MESSAGE_TOOL in all) filtered + GROUP_MESSAGE_TOOL else filtered
    }

    private fun matches(pattern: String, toolName: String): Boolean {
        val p = pattern.trim()
        if (p.isEmpty()) return false
        if (p.endsWith("*")) return toolName.startsWith(p.dropLast(1), ignoreCase = true)
        return toolName.equals(p, ignoreCase = true)
    }

    companion object {
        /** 省略 `inject` 时的默认注入项：精简基线 + 技能 + 记忆 + 项目规则。 */
        val DEFAULT_INJECT: Set<InjectPart> = setOf(
            InjectPart.BASE,
            InjectPart.SKILLS,
            InjectPart.MEMORY,
            InjectPart.PROJECT_RULES
        )

        /** 子代理不可嵌套派发，其工具集永远剔除该工具。 */
        const val NESTED_TOOL = "task"

        /** 群聊私信工具：定义类子代理的工具集永远保留（群聊成员必用）。 */
        const val GROUP_MESSAGE_TOOL = "group_message"
    }
}

/** 一个生效的子代理定义条目：定义本体 + 其来源作用域，供 UI 标注「全局/项目」。 */
data class AgentDefinitionEntry(
    val definition: AgentDefinition,
    val scope: AgentDefinitionScope
)

/** 设置页新建/编辑子代理时提交的表单快照。 */
data class AgentDefinitionForm(
    val name: String,
    val description: String,
    val providerId: String? = null,
    val model: String? = null,
    val reasoningEffort: String? = null,
    val allowedTools: List<String> = emptyList(),
    val disallowedTools: List<String> = emptyList(),
    val inject: Set<InjectPart> = AgentDefinition.DEFAULT_INJECT,
    val prompt: String = ""
)

/** 保存子代理定义失败的原因。 */
enum class AgentSaveError {
    /** 名称为空、过长或含不能做文件名的字符。 */
    INVALID_NAME,

    /** 提示词正文为空：解析器会直接丢掉这种定义，存下来也用不了。 */
    EMPTY_PROMPT,

    /** 同作用域已有同名子代理。 */
    NAME_CONFLICT,

    /** 写盘失败。 */
    IO_FAILED
}
