package com.aicode.feature.agent.domain.skill

import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.AgentConfigKind
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 项目级技能来源：`<projectRoot>/.aicode/skills/`，随工作区走，可 git 追踪。
 * 以容器路径经 [FileAccessProvider] 访问：本地映射到宿主工作区，远程经 SSH 落到远程工作区。
 */
@Singleton
class ProjectDirectorySkillSource @Inject constructor(
    private val fileAccess: FileAccessProvider
) : SkillSource {

    val skillsRoot: String = "${WorkspacePathMapper.CONTAINER_ROOT}/.aicode/skills"

    override fun listSkills(): List<Skill> = SkillDirectoryScanner.scan(fileAccess, skillsRoot)

    override fun loadInstructions(name: String): String? {
        val snapshot = fileAccess.cachedAgentConfigSnapshot(skillsRoot, AgentConfigKind.SKILLS)
        val skills = snapshot?.let(SkillDirectoryScanner::fromSnapshot) ?: listSkills()
        val skill = skills.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return null
        val dir = skill.dirPath ?: return skill.instructions
        return SkillParser.parse(fileAccess, dir)?.takeIf { it.name.equals(name, ignoreCase = true) }?.instructions
    }
}
