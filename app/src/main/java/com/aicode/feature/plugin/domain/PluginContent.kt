package com.aicode.feature.plugin.domain

import com.aicode.feature.agent.domain.skill.Skill
import com.aicode.feature.agent.domain.skill.SkillParser
import com.aicode.feature.agent.domain.subagent.AgentDefinition
import com.aicode.feature.agent.domain.subagent.AgentDefinitionParser
import com.aicode.feature.settings.domain.model.AdaptiveCardRoot
import com.aicode.feature.settings.domain.parser.AdaptiveCardParser
import java.io.File

/**
 * 模型与使用者看到的贡献名：`<插件ID>:<局部ID>`。命名空间由宿主生成，
 * 插件无法覆盖内置功能、使用者自建内容或其他插件的同名内容。
 */
fun pluginContributionName(pluginId: String, localId: String): String = "$pluginId:$localId"

data class PluginSkill(val localId: String, val skill: Skill)

data class PluginAgent(val localId: String, val definition: AgentDefinition)

data class PluginWorkspaceTool(
    val pluginId: String,
    val localId: String,
    val title: LocalizedText,
    val description: LocalizedText,
    val icon: String,
    val category: String,
    val cardRoot: AdaptiveCardRoot?
) {
    /** 唯一的全局工具键名：`<插件ID>:<局部ID>` */
    val key: String get() = pluginContributionName(pluginId, localId)
}

data class PluginContent(
    val skills: List<PluginSkill> = emptyList(),
    val agents: List<PluginAgent> = emptyList(),
    val workspaceTools: List<PluginWorkspaceTool> = emptyList()
)

sealed interface ContentLoadResult {
    data class Ok(val content: PluginContent) : ContentLoadResult
    data class Failed(val errors: List<PluginError>) : ContentLoadResult
}

/**
 * 读取清单引用的 Markdown 贡献文件并转换成技能 / 子代理定义。
 * 预览（暂存目录）与加载已安装插件共用这一份校验，保证装进去的和读出来的是同一套规则。
 */
object PluginContentLoader {
    const val MAX_CONTENT_BYTES = 512 * 1024

    private val WHITESPACE = Regex("\\s+")

    fun load(dir: File, manifest: PluginManifest): ContentLoadResult {
        val errors = mutableListOf<PluginError>()
        val skills = manifest.skills.mapNotNull { loadSkill(dir, manifest.id, it, errors) }
        val agents = manifest.agents.mapNotNull { loadAgent(dir, manifest.id, it, errors) }
        val workspaceTools = manifest.workspaceTools.map { loadWorkspaceTool(dir, manifest.id, it, errors) }
        return if (errors.isEmpty()) ContentLoadResult.Ok(PluginContent(skills, agents, workspaceTools)) else ContentLoadResult.Failed(errors)
    }

    private fun loadWorkspaceTool(
        dir: File,
        pluginId: String,
        ref: PluginWorkspaceToolRef,
        errors: MutableList<PluginError>
    ): PluginWorkspaceTool {
        val file = File(dir, ref.pageFile)
        val card = if (file.isFile && file.length() <= MAX_CONTENT_BYTES) {
            val text = runCatching { file.readText() }.getOrNull()
            text?.let { AdaptiveCardParser.parseAdaptiveCard(it) }
        } else null

        if (card == null && ref.pageFile.isNotBlank() && !file.exists()) {
            errors.add(PluginError(PluginErrorCode.FILE_MISSING, ref.pageFile))
        }

        return PluginWorkspaceTool(
            pluginId = pluginId,
            localId = ref.id,
            title = ref.title,
            description = ref.description,
            icon = ref.icon,
            category = ref.category,
            cardRoot = card
        )
    }

    private fun loadSkill(
        dir: File,
        pluginId: String,
        ref: PluginContributionRef,
        errors: MutableList<PluginError>
    ): PluginSkill? {
        val text = readText(dir, ref, errors) ?: return null
        val parsed = SkillParser.parseText(text, ref.id)
        if (parsed.instructions.isBlank()) {
            errors += PluginError(PluginErrorCode.CONTENT_INVALID, "${ref.file}: empty")
            return null
        }
        val skill = parsed.copy(
            name = pluginContributionName(pluginId, ref.id),
            // 描述会进系统提示词的技能清单，压成单行，避免插件借换行伪造清单条目
            description = singleLine(parsed.description),
            dirPath = null
        )
        return PluginSkill(ref.id, skill)
    }

    private fun loadAgent(
        dir: File,
        pluginId: String,
        ref: PluginContributionRef,
        errors: MutableList<PluginError>
    ): PluginAgent? {
        val text = readText(dir, ref, errors) ?: return null
        val parsed = AgentDefinitionParser.parseText(text, ref.id)
        if (parsed == null) {
            errors += PluginError(PluginErrorCode.CONTENT_INVALID, "${ref.file}: invalid")
            return null
        }
        // 供应商与模型绑定会动用使用者的付费渠道，插件子代理一律继承父会话
        if (parsed.providerId != null || parsed.model != null) {
            errors += PluginError(PluginErrorCode.CONTENT_INVALID, "${ref.file}: provider/model")
            return null
        }
        val definition = parsed.copy(
            name = pluginContributionName(pluginId, ref.id),
            description = singleLine(parsed.description),
            filePath = null
        )
        return PluginAgent(ref.id, definition)
    }

    private fun readText(dir: File, ref: PluginContributionRef, errors: MutableList<PluginError>): String? {
        val file = File(dir, ref.file)
        val inside = file.canonicalFile.toPath().startsWith(dir.canonicalFile.toPath())
        if (!inside || !file.isFile) {
            errors += PluginError(PluginErrorCode.FILE_MISSING, ref.file)
            return null
        }
        if (file.length() > MAX_CONTENT_BYTES) {
            errors += PluginError(PluginErrorCode.CONTENT_INVALID, "${ref.file}: too large")
            return null
        }
        return file.readText(Charsets.UTF_8)
    }

    private fun singleLine(text: String): String = text.replace(WHITESPACE, " ").trim()
}
