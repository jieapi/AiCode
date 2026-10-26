package com.aicode.feature.agent.domain.skill

/** 技能的来源作用域：全局（跨项目共享）、项目级（仅当前工作区生效）或插件提供。 */
enum class SkillScope { GLOBAL, PROJECT, PLUGIN }

/** 一个生效的技能条目：技能本体 + 其来源作用域，供 UI 标注「全局/项目」。 */
data class SkillEntry(
    val skill: Skill,
    val scope: SkillScope
)
