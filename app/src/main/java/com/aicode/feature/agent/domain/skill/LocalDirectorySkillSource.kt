package com.aicode.feature.agent.domain.skill

import com.aicode.feature.workspace.domain.LocalFileAccess
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全局技能来源：`filesDir/aicode/skills`（容器内 `/root/.aicode/skills`），跨项目、跨升级保留。
 * 始终落在 App 私有目录，与本机执行模式无关，故固定走本地文件访问。
 */
@Singleton
class LocalDirectorySkillSource @Inject constructor(
    private val localFileAccess: LocalFileAccess
) : SkillSource {

    val skillsRoot: String = "${WorkspacePathMapper.AICODE_ROOT}/skills"

    override fun listSkills(): List<Skill> = SkillDirectoryScanner.scan(localFileAccess, skillsRoot)

    override fun loadInstructions(name: String): String? =
        listSkills().firstOrNull { it.name.equals(name, ignoreCase = true) }?.instructions
}
