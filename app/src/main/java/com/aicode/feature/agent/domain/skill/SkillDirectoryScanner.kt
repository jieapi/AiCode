package com.aicode.feature.agent.domain.skill

import com.aicode.feature.workspace.domain.FileAccessProvider

/**
 * 目录型技能源的共享扫描逻辑：递归查找 SKILL.md / CLAUDE.md，
 * 每个含指令文件的目录解析为一个 Skill。
 *
 * 目录经 [FileAccessProvider] 以容器路径访问，本地与远程（SSH）同一套逻辑。
 */
object SkillDirectoryScanner {
    /** 允许一定的嵌套深度（比如 repo/skills/my-skill/SKILL.md）。 */
    private const val MAX_DEPTH = 4
    private const val SKILL_FILE = "SKILL.md"
    private const val CLAUDE_FILE = "CLAUDE.md"

    /**
     * 扫描 [root] 目录下所有合法技能，按名称排序。
     * 目录不存在时返回空列表。
     */
    fun scan(provider: FileAccessProvider, root: String): List<Skill> {
        val dirs = provider.listFilesRecursive(root, MAX_DEPTH)
            .filter { relative ->
                val name = relative.substringAfterLast('/')
                name.equals(SKILL_FILE, ignoreCase = true) || name.equals(CLAUDE_FILE, ignoreCase = true)
            }
            .map { it.substringBeforeLast('/', "") }
            .distinct()

        val base = root.trimEnd('/')
        return dirs.mapNotNull { relative ->
            val dirPath = if (relative.isEmpty()) base else "$base/$relative"
            SkillParser.parse(provider, dirPath)
        }.sortedBy { it.name.lowercase() }
    }
}
