package com.aicode.feature.plugin.domain

import com.aicode.feature.agent.domain.skill.Skill
import com.aicode.feature.agent.domain.skill.SkillSource
import com.aicode.feature.agent.domain.subagent.AgentDefinition
import com.aicode.feature.agent.domain.subagent.AgentDefinitionSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 插件贡献的技能来源。内容来自手机端插件包，不经 FileAccessProvider，
 * 因此不会写入远程服务器的技能目录，SSH 模式下同样可用。
 */
@Singleton
class PluginSkillSource @Inject constructor(
    private val manager: PluginManager
) : SkillSource {

    override fun listSkills(): List<Skill> = manager.activeSkills()

    /** 全部插件技能，不论插件是否启用，供设置页全量展示。 */
    fun listAllSkills(): List<Skill> = manager.allSkills()

    override fun loadInstructions(name: String): String? =
        listSkills().firstOrNull { it.name.equals(name, ignoreCase = true) }?.instructions
}

/** 插件贡献的子代理来源。 */
@Singleton
class PluginAgentSource @Inject constructor(
    private val manager: PluginManager
) : AgentDefinitionSource {

    override fun listDefinitions(): List<AgentDefinition> = manager.activeAgents()

    /** 含已停用插件的定义，供还原在途子会话使用。 */
    fun listAllDefinitions(): List<AgentDefinition> = manager.allAgents()

    /** 插件集合的变更序号，并入子代理清单的缓存版本。 */
    val revision: Long get() = manager.revision
}
