package com.aicode.feature.agent.domain.skill

import com.aicode.core.util.FileLogger
import com.aicode.feature.plugin.domain.PluginSkillSource
import com.aicode.feature.workspace.domain.FileAccessProvider
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Skill 仓库，聚合各 [SkillSource]（全局目录 + 项目目录 + 插件）提供的技能，
 * 并按 [SkillConfigRepository] 的禁用名单过滤注入清单。
 *
 * 全局技能 `~/.aicode/skills`、项目级技能 `<workspace>/.aicode/skills`，两者都经 [FileAccessProvider]
 * 以容器路径读写，跟随当前执行环境（本地宿主目录或远程 SSH 工作区）。
 * 插件技能来自手机端插件包，只读，只进入注入清单与正文加载，不出现在 [listAllSkills]（设置页的可编辑列表）。
 */
@Singleton
class SkillRepository @Inject constructor(
    private val globalDirectorySkillSource: GlobalDirectorySkillSource,
    private val projectDirectorySkillSource: ProjectDirectorySkillSource,
    private val skillConfigRepository: SkillConfigRepository,
    private val fileAccess: FileAccessProvider,
    private val pluginSkillSource: PluginSkillSource
) {
    /** 全部技能（含来源作用域），未过滤禁用；同名技能项目级优先（与 MCP 两级配置一致）。 */
    fun listAllSkills(): List<SkillEntry> =
        mergeAll(globalDirectorySkillSource.listSkills(), projectDirectorySkillSource.listSkills())

    /** 启用的技能列表（注入系统提示词用），禁用技能被过滤；插件技能排在用户技能之后。 */
    fun listSkills(): List<Skill> {
        val disabled = skillConfigRepository.disabledNames()
        val own = filterDisabled(listAllSkills(), disabled).map { it.skill }
        return appendPluginSkills(own, pluginSkillSource.listSkills(), disabled)
    }

    /** 当前被禁用的技能名集合（小写归一化）。批量判断取一次即可，避免逐个重读配置文件。 */
    fun disabledNames(): Set<String> = skillConfigRepository.disabledNames()

    /** 读取指定 skill 的完整指令正文；不存在 / 解析失败 / 已被禁用时返回 null。 */
    fun loadInstructions(name: String): String? {
        if (name.lowercase() in skillConfigRepository.disabledNames()) return null
        return projectDirectorySkillSource.loadInstructions(name)
            ?: globalDirectorySkillSource.loadInstructions(name)
            ?: pluginSkillSource.loadInstructions(name)
    }

    /** 技能是否在任一作用域中被禁用。 */
    fun isSkillDisabled(name: String): Boolean =
        name.lowercase() in skillConfigRepository.disabledNames()

    /** 在指定作用域启用/禁用某个技能。 */
    fun setSkillDisabled(name: String, disabled: Boolean, scope: SkillScope) =
        skillConfigRepository.setDisabled(name, disabled, scope)

    /**
     * 写入技能文件（新建或编辑）。[originalName] 为编辑前的名称，新建时传 null；返回 null 表示成功。
     *
     * 编辑时写回原目录里的原指令文件（可能叫 CLAUDE.md），改名只改 frontmatter 的 name、不动目录名——
     * 技能正文常按 `~/.aicode/skills/<目录>/run.py` 引用同目录脚本，跟着改名会把这些引用打断。
     */
    fun save(form: SkillForm, scope: SkillScope, originalName: String? = null): SkillSaveError? {
        val name = form.name.trim()
        if (!isValidName(name)) return SkillSaveError.INVALID_NAME
        if (form.instructions.isBlank()) return SkillSaveError.EMPTY_INSTRUCTIONS

        val entries = listAllSkills()
        val keepingName = originalName != null && originalName.equals(name, ignoreCase = true)
        if (!keepingName) {
            val taken = entries.any { it.scope == scope && it.skill.name.equals(name, ignoreCase = true) }
            if (taken) return SkillSaveError.NAME_CONFLICT
        }

        val existingDir = originalName?.let { old ->
            entries.firstOrNull {
                it.scope == scope && it.skill.name.equals(old, ignoreCase = true)
            }?.skill?.dirPath
        }

        val text = SkillParser.serialize(
            name = name,
            description = form.description,
            requiredTools = form.requiredTools,
            instructions = form.instructions
        )

        val provider = fileAccess
        return try {
            val dir = existingDir?.takeIf { provider.isDirectory(it) } ?: "${skillsRoot(scope)}/$name"
            provider.mkdirs(dir)
            val target = instructionFile(provider, dir) ?: "${dir.trimEnd('/')}/$INSTRUCTION_FILE"
            provider.writeFile(target, text, overwrite = true)
            null
        } catch (e: Exception) {
            FileLogger.e(TAG, "保存技能失败: $name", e)
            SkillSaveError.IO_FAILED
        }
    }

    /** 指定作用域的技能根目录（容器路径）。 */
    fun skillsRoot(scope: SkillScope): String =
        if (scope == SkillScope.GLOBAL) {
            globalDirectorySkillSource.skillsRoot
        } else {
            projectDirectorySkillSource.skillsRoot
        }

    /**
     * 从 Markdown 文本导入技能到指定作用域；[fallbackName] 为 frontmatter 缺 name 时的兜底名。
     * 名称非法 / 同名冲突 / 正文为空时整体失败，不落盘。
     */
    fun importMarkdown(text: String, fallbackName: String, scope: SkillScope): SkillImportReport =
        SkillImporter.importMarkdown(fileAccess, skillsRoot(scope), existingNamesIn(scope), text, fallbackName)

    /** 从 zip 输入流导入技能（可含多个技能目录）到指定作用域。 */
    fun importZip(input: InputStream, fallbackName: String, scope: SkillScope): SkillImportReport =
        SkillImporter.importArchive(fileAccess, skillsRoot(scope), existingNamesIn(scope), input, fallbackName)

    /** 指定作用域下已有技能名（小写），供导入查重。 */
    private fun existingNamesIn(scope: SkillScope): Set<String> =
        listAllSkills().filter { it.scope == scope }.map { it.skill.name.lowercase() }.toSet()

    /** 删除指定作用域的技能（删除其目录，不可恢复）。返回是否成功。 */
    fun deleteSkill(name: String, scope: SkillScope): Boolean {
        val entry = listAllSkills().firstOrNull {
            it.skill.name.equals(name, ignoreCase = true) && it.scope == scope
        } ?: return false
        val dirPath = entry.skill.dirPath ?: return false
        return safeDeleteSkillDir(fileAccess, dirPath)
    }

    companion object {
        private const val TAG = "SkillRepository"

        /** 新建技能时写入的指令文件名。 */
        private const val INSTRUCTION_FILE = "SKILL.md"

        /** 名称同时用作目录名，禁掉路径分隔符与保留字符；长度上限防止极端目录名。 */
        internal fun isValidName(name: String): Boolean {
            if (name.isBlank() || name.length > MAX_NAME_LENGTH) return false
            if (name == "." || name == "..") return false
            return name.none { it in ILLEGAL_NAME_CHARS || it.isISOControl() }
        }

        private const val MAX_NAME_LENGTH = 64
        private val ILLEGAL_NAME_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

        /** 目录里已有的指令文件完整路径：SKILL.md 优先，其次 CLAUDE.md（与 [SkillParser.parse] 同一套回退）。 */
        internal fun instructionFile(provider: FileAccessProvider, dirPath: String): String? {
            val files = runCatching { provider.listFiles(dirPath) }.getOrNull() ?: return null
            val name = files.firstOrNull { !it.isDirectory && it.name.equals("SKILL.md", ignoreCase = true) }?.name
                ?: files.firstOrNull { !it.isDirectory && it.name.equals("CLAUDE.md", ignoreCase = true) }?.name
                ?: return null
            return "${dirPath.trimEnd('/')}/$name"
        }

        /** 合并两级来源：同名项目级覆盖全局，按名称排序。 */
        internal fun mergeAll(global: List<Skill>, project: List<Skill>): List<SkillEntry> {
            val byName = LinkedHashMap<String, SkillEntry>()
            global.forEach { byName[it.name.lowercase()] = SkillEntry(it, SkillScope.GLOBAL) }
            project.forEach { byName[it.name.lowercase()] = SkillEntry(it, SkillScope.PROJECT) }
            return byName.values.sortedBy { it.skill.name.lowercase() }
        }

        /** 过滤禁用技能（禁用名单已归一化为小写）。 */
        internal fun filterDisabled(entries: List<SkillEntry>, disabled: Set<String>): List<SkillEntry> =
            entries.filterNot { it.skill.name.lowercase() in disabled }

        /**
         * 把插件技能追加到用户技能之后。插件技能名带 `插件ID:` 前缀，正常不会重名；
         * 万一重名，用户自建的优先，插件不能覆盖。
         */
        internal fun appendPluginSkills(own: List<Skill>, plugin: List<Skill>, disabled: Set<String>): List<Skill> {
            val taken = own.mapTo(HashSet()) { it.name.lowercase() }
            return own + plugin
                .filter { it.name.lowercase() !in disabled && taken.add(it.name.lowercase()) }
                .sortedBy { it.name.lowercase() }
        }

        /** 仅当目录存在且含 SKILL.md/CLAUDE.md 指令文件时才删除，避免误删非技能目录。 */
        internal fun safeDeleteSkillDir(provider: FileAccessProvider, dirPath: String): Boolean {
            if (!provider.isDirectory(dirPath)) return false
            val hasInstruction = runCatching { provider.listFiles(dirPath) }.getOrNull()?.any {
                !it.isDirectory && (it.name.equals("SKILL.md", ignoreCase = true) || it.name.equals("CLAUDE.md", ignoreCase = true))
            } ?: false
            if (!hasInstruction) return false
            return runCatching { provider.deleteRecursively(dirPath); true }.getOrDefault(false)
        }
    }
}
