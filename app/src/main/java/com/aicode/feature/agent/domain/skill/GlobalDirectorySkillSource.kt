package com.aicode.feature.agent.domain.skill

import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.AgentConfigKind
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全局技能来源：`~/.aicode/skills`，跨项目共享。以容器路径经 [FileAccessProvider] 访问，
 * 跟随当前执行环境——本地模式落 App 私有 `filesDir/aicode/skills`（容器内 `/root/.aicode/skills`），
 * 远程模式经 SSH 落服务器用户 home 下的 `.aicode/skills`。远程是独立执行环境，读写均以远端为准。
 */
@Singleton
class GlobalDirectorySkillSource @Inject constructor(
    private val fileAccess: FileAccessProvider
) : SkillSource {

    val skillsRoot: String = "~/.aicode/skills"

    override fun listSkills(): List<Skill> = SkillDirectoryScanner.scan(fileAccess, skillsRoot)

    override fun loadInstructions(name: String): String? {
        val snapshot = fileAccess.cachedAgentConfigSnapshot(skillsRoot, AgentConfigKind.SKILLS)
        val skills = snapshot?.let(SkillDirectoryScanner::fromSnapshot) ?: listSkills()
        val skill = skills.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return null
        val dir = skill.dirPath ?: return skill.instructions
        return SkillParser.parse(fileAccess, dir)?.takeIf { it.name.equals(name, ignoreCase = true) }?.instructions
    }
}
