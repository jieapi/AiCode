package com.aicode.feature.agent.domain.skill

import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.FileAccessProvider
import org.yaml.snakeyaml.Yaml

object SkillParser {
    private const val TAG = "SkillParser"
    private const val MAX_DESC_CHARS = 500
    private const val SKILL_FILE = "SKILL.md"
    private const val CLAUDE_FILE = "CLAUDE.md"

    /**
     * 解析一个 skill 目录；无 SKILL.md / CLAUDE.md 或无 name 时视为非法，返回 null。
     * 目录经 [provider] 以容器路径读取，本地/远程统一。
     */
    fun parse(provider: FileAccessProvider, dirPath: String): Skill? {
        val dir = dirPath.trimEnd('/')
        // 优先 SKILL.md，其次 CLAUDE.md（兼容只用 CLAUDE.md 的技能）；名称匹配忽略大小写
        val fileName = runCatching {
            provider.listFiles(dir).map { it.name }
        }.getOrElse {
            FileLogger.w(TAG, "列出技能目录失败: $dir", it)
            return null
        }.let { names ->
            names.firstOrNull { it.equals(SKILL_FILE, ignoreCase = true) }
                ?: names.firstOrNull { it.equals(CLAUDE_FILE, ignoreCase = true) }
        } ?: return null

        val text = runCatching { provider.readFile("$dir/$fileName") }.getOrElse {
            FileLogger.w(TAG, "读取 Skill 文件失败: $dir/$fileName", it)
            return null
        }

        val (frontmatter, body) = splitAndParseFrontmatter(text)

        // name 优先取 frontmatter，缺省回退到目录名
        val name = frontmatter["name"]?.toString()?.takeIf { it.isNotBlank() }
            ?: dir.substringAfterLast('/').ifBlank { dir }
        val description = (frontmatter["description"]?.toString() ?: "").take(MAX_DESC_CHARS)

        val requiredTools = try {
            val toolsRaw = frontmatter["required_tools"]
            if (toolsRaw is List<*>) {
                toolsRaw.filterIsInstance<String>()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            FileLogger.w(TAG, "解析 required_tools 失败: $dir/$fileName", e)
            emptyList()
        }

        return Skill(
            name = name,
            description = description,
            requiredTools = requiredTools,
            dirPath = dir,
            instructions = body.trim()
        )
    }

    /**
     * 利用 SnakeYAML 切分并解析 YAML frontmatter。
     * @return (frontmatter 键值对, 正文)
     */
    private fun splitAndParseFrontmatter(text: String): Pair<Map<String, Any>, String> {
        val normalized = text.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) return emptyMap<String, Any>() to normalized

        val end = normalized.indexOf("\n---", startIndex = 3)
        if (end < 0) return emptyMap<String, Any>() to normalized

        val block = normalized.substring(4, end)
        val rest = normalized.substring(end + 4).removePrefix("\n")
        
        val map = try {
            val yaml = Yaml()
            val loaded = yaml.load<Map<String, Any>>(block)
            loaded ?: emptyMap()
        } catch (e: Exception) {
            FileLogger.w(TAG, "解析 YAML 失败", e)
            emptyMap()
        }
        
        return map to rest
    }

    /**
     * 把设置页表单写回 `SKILL.md` 文本（frontmatter + 正文），与 [parse] 成对。
     *
     * `name` 总是写出来：技能名优先取 frontmatter，改名只需改这里而不必动目录名（技能目录
     * 里的脚本常被正文按原路径引用，跟着改名会把引用打断）。
     * 工具名是标识符，直接进方括号列表；其余文本字段一律加引号，免得描述里的冒号或 # 把 YAML 弄坏。
     */
    fun serialize(
        name: String,
        description: String,
        requiredTools: List<String>,
        instructions: String
    ): String = buildString {
        appendLine("---")
        appendLine("name: ${quote(name)}")
        appendLine("description: ${quote(description)}")
        if (requiredTools.isNotEmpty()) appendLine("required_tools: [${requiredTools.joinToString(", ")}]")
        appendLine("---")
        appendLine(instructions.trim())
    }

    /** frontmatter 字符串值：双引号包裹，转义反斜杠与引号，换行压成空格保证单行。 */
    private fun quote(value: String): String {
        val escaped = value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace(Regex("\\s*\\n\\s*"), " ")
            .trim()
        return "\"$escaped\""
    }
}
