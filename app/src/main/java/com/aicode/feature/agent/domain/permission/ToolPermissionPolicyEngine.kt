package com.aicode.feature.agent.domain.permission

import android.content.Context
import com.aicode.R
import dagger.hilt.android.qualifiers.ApplicationContext
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.settings.data.repository.ToolSafetySettingsRepository
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 工具授权策略引擎：在弹窗之前评估一次工具调用应「自动放行 / 自动拒绝 / 询问」，并在用户选择
 * 「始终允许」后把规则记忆下来。
 *
 * 评估顺序（Bash）：
 *   1) 任一段命中 DENY 规则 → DENY（即便命令不可静态判定，DENY 也对已解析的段生效；可覆盖内置白名单）；
 *   2) 命令不可静态判定（含命令替换/分组/绝对路径重定向等）→ ASK，且不可记忆；
 *   3) 所有段都命中内置安全白名单（ls/git status 等只读命令，见 [BuiltInSafeCommands]）→ ALLOW；
 *   4) 所有段都命中已记忆的 ALLOW 规则 → ALLOW；
 *   5) 否则 → ASK，可记忆前缀为各段的「程序名」或「程序名+子命令」（对 git/npm 等子命令分发器，
 *      记 `git pull` 而非 `git`，使不同子命令各自独立授权）。
 * 非 shell 的 ASK 工具按整工具（pattern=`*`）匹配。
 *
 * [SHIZUKU_TOOL]（Shizuku）按高危处理：无论内置白名单或已记忆规则，一律 ASK 且不可记忆；
 * AUTO 模式也不自动放行（除非已开启「禁用安全拦截」）。
 */
@Singleton
class ToolPermissionPolicyEngine @Inject constructor(
    private val rulesRepo: PermissionRulesRepository,
    private val toolSafetySettings: ToolSafetySettingsRepository,
    @param:ApplicationContext private val appContext: Context
) {
    private companion object {
        /** 以 `command` 参数承载 shell 命令、按命令前缀做指令级匹配的工具。 */
        val SHELL_TOOLS = setOf("Bash", SHIZUKU_TOOL)

        /** Shizuku 工具：以 adb shell 身份直接操作宿主 Android 系统，按高危处理（一律弹窗、不可记忆）。 */
        const val SHIZUKU_TOOL = "Shizuku"

        /**
         * 合并后的终端会话工具：其 `start` 动作承载 shell 命令，需走指令级前缀匹配；
         * `send`/`read` 动作不承载命令，按整工具匹配。故在 [evaluate] 中按 action 路由。
         */
        const val TERMINAL_TOOL = "terminal"
        const val TERMINAL_SHELL_ACTION = "start"
        val NON_REMEMBERABLE_CAPABILITIES = setOf(
            ToolCapability.MODIFY_AGENT_CONFIG,
            ToolCapability.MODIFY_CONTAINER_ENV
        )

        /**
         * 合并后的子代理工具：只读与发消息操作（read/list/send）自动放行，无需弹窗；
         * 写操作（create/stop）走正常规则评估；del（删除子会话及全部消息）不可逆，
         * 只支持单次放行，不落入「始终允许」记忆——否则一次 ALWAYS 会让后续删除全部静默。
         */
        const val TASK_TOOL = "task"
        private val TASK_AUTO_ACTIONS = setOf("read", "list", "send")
        private const val TASK_DELETE_ACTION = "del"

        /**
         * 浏览器工具：navigate/click/fill/select/evaluate/dialog/back/forward/reload/newTab/closeTab/selectTab 为写操作（PLAN 拦截），
         * getText/getHtml/getBackbone/screenshot/console/wait/listTabs 为只读（PLAN 放行）。
         */
        const val BROWSER_TOOL = "browser"
        private val BROWSER_READ_ONLY_ACTIONS = setOf("getText", "getHtml", "getBackbone", "screenshot", "console", "wait", "listTabs")

        /**
         * 提权参数：非 AUTO 模式下，命令因内置安全防护（灾难性 rm）被拒时，
         * 可在调用时置为 true 重试，把硬拒绝降级为一次性用户授权。
         */
        const val ELEVATE_ARG = "elevate"

        /** 容器内 `~` 展开目标（PRoot 以 root 运行）。 */
        const val HOME_DIR = "/root"
        const val HOME_TOKEN = "\$HOME"
        const val HOME_BRACED = "\${HOME}"

        const val REASON_RM_ROOT = "安全防护：禁止执行高危删除操作（根目录删除）"
        const val REASON_RM_RELATIVE = "安全防护：禁止执行高危删除操作（全局或相对路径通配删除）"
        const val REASON_RM_HOME = "安全防护：禁止执行高危删除操作（用户目录删除）"
        const val REASON_RM_WORKSPACE = "安全防护：禁止执行高危删除操作（工作区根目录删除）"
        const val REASON_RM_TMP = "安全防护：禁止执行高危删除操作（系统临时目录整体删除）"
        const val REASON_RM_SYSTEM_DIR = "安全防护：禁止执行高危删除操作（系统关键目录删除）"

        /** 相对/通配类删除目标（归一化后判定）：整体删除当前目录或任意内容。 */
        val RELATIVE_WILDCARD_TARGETS = setOf("*", ".*", ".", "..", "../*", "../.*")

        /** 根级通配（`/et*`、`/usr*` 等）可能展开为受保护的系统目录，直接拦截。 */
        val ROOT_LEVEL_GLOB = Regex("^/[^/*?]*[*?]")

        /** 工作区根目录的删除目标（归一化后判定）；工作区的子目录不在此列。 */
        val WORKSPACE_ROOT_TARGETS = setOf("workspace", "workspace/*", "$HOME_DIR/workspace", "$HOME_DIR/workspace/*")

        /** 受保护的系统关键目录：命中其本身或其任意子路径即拦截（`/tmp`、工作区子树单独处理）。 */
        val PROTECTED_SYSTEM_DIRS = setOf(
            "/bin", "/boot", "/dev", "/etc", "/home", "/lib", "/lib64", "/lost+found",
            "/media", "/mnt", "/opt", "/proc", "/root", "/run", "/sbin", "/srv", "/sys", "/usr", "/var"
        )

        const val REASON_UNANALYZABLE_DESTRUCTIVE =
            "安全防护：命令含命令替换、子 shell 或绝对路径重定向等无法静态判定的构造，且疑似破坏性操作，无法确认安全"

        /** 疑似破坏性程序只在 shell 命令位置匹配，避免把参数/搜索词里的名字当成待执行程序。 */
        val DESTRUCTIVE_PROGRAM = Regex(
            "(?:^|[;&|()\\n]|`|\\$\\()\\s*(?:(?:[A-Za-z_][A-Za-z0-9_]*=\\S*)\\s+)*(?:(?:sudo|command|exec|env|busybox)\\s+)*(?:rm|dd|shred|truncate|mkfs(?:\\.[a-z0-9]+)?|wipefs|fdisk|sfdisk|parted|mkswap|blkdiscard)(?=$|[\\s;&|()`<>])"
        )
        val FIND_DELETE_COMMAND = Regex("(?:^|[;&|()\\n]|`|\\$\\()\\s*(?:find)\\b[^;&|()]*\\s(?<!['\"\\\\])-delete(?=$|[\\s;&|()])")
    }

    enum class Verdict { ALLOW, DENY, ASK }

    /**
     * @param verdict 评估结论。
     * @param rememberablePatterns 当 [verdict] 为 ASK 时，「始终允许」会记忆的模式；为空表示不可记忆
     *   （命令不可静态判定，只能单次放行）。
     */
    data class EvalResult(
        val verdict: Verdict,
        val rememberablePatterns: List<String>,
        val denyReason: String? = null,
        val rememberDisabledReason: String? = null,
        /** ASK 时的弹窗标题覆盖；null 表示用工具默认标题。 */
        val askTitle: String? = null
    )

    fun captureProject(workspacePath: String): PermissionRulesRepository.ProjectContext =
        rulesRepo.captureProject(workspacePath)

    suspend fun evaluate(tool: AgentTool?, toolName: String, args: Map<String, JsonElement>, mode: com.aicode.feature.agent.domain.model.AgentMode): EvalResult =
        evaluate(tool, toolName, args, mode, null)

    suspend fun evaluate(
        tool: AgentTool?,
        toolName: String,
        args: Map<String, JsonElement>,
        mode: com.aicode.feature.agent.domain.model.AgentMode,
        project: PermissionRulesRepository.ProjectContext?
    ): EvalResult {
        val capabilities = tool?.effectiveCapabilities(args).orEmpty()
        if (mode == com.aicode.feature.agent.domain.model.AgentMode.PLAN && isDangerousTool(toolName, args, capabilities)) {
            return EvalResult(Verdict.DENY, emptyList(), denyReason = appContext.getString(R.string.tool_plan_mode_denied))
        }

        if (mode == com.aicode.feature.agent.domain.model.AgentMode.AUTO) {
            // AUTO 模式放行所有权限；灾难性 rm 防护（根目录/系统目录删除）默认保留，
            // 可在「工具授权」设置中关闭（禁用安全拦截）。遭遇拦截时仍可凭 elevate 参数提权重试。
            val safetyDisabled = toolSafetySettings.isSafetyInterceptionDisabled()
            if (!safetyDisabled && isShellTool(toolName, args)) {
                val command = ((args["command"] ?: args["input"]) as? JsonPrimitive)?.content
                if (command != null) {
                    val analysis = ShellCommandParser.analyze(command)
                    checkCatastrophicRm(analysis.segments)?.let { return elevationOrDeny(it, args) }
                    // 命令替换/子 shell/绝对路径重定向等无法静态判定的构造，删除目标不可知；
                    // 若同时疑似破坏性，宁可拦下（可提权），避免绕过安全防护。
                    if (!analysis.analyzable && looksDestructive(command, analysis)) {
                        return elevationOrDeny(unanalyzableReason(analysis), args)
                    }
                }
            }
            // Shizuku 直接操作宿主系统，AUTO 下也不自动放行，仍需逐次确认（除非已开启「禁用安全拦截」）。
            if (toolName == SHIZUKU_TOOL && !safetyDisabled) {
                return EvalResult(Verdict.ASK, emptyList(), rememberDisabledReason = appContext.getString(R.string.tool_shizuku_confirmation_reason))
            }
            return EvalResult(Verdict.ALLOW, emptyList())
        }

        if (capabilities == setOf(ToolCapability.READ_AGENT_CONFIG)) {
            return EvalResult(Verdict.ALLOW, emptyList())
        }

        if (toolName == TASK_TOOL) {
            val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase() ?: "create"
            // 只读与发消息动作（read/list/send）：不放行 DENY 规则，其余直接自动放行（不弹窗）。
            if (action in TASK_AUTO_ACTIONS) {
                val rules = loadRules(project).filter { it.toolName == toolName }
                if (rules.any { it.pattern == PermissionRule.WHOLE_TOOL && it.decision == PermissionDecision.DENY }) {
                    return EvalResult(Verdict.DENY, emptyList(), denyReason = appContext.getString(R.string.tool_permission_rule_denied))
                }
                return EvalResult(Verdict.ALLOW, emptyList())
            }
            if (action == TASK_DELETE_ACTION) {
                // 删除子会话及其全部消息不可逆：尊重显式 DENY，其余一律单次确认，不提供
                // 「始终允许」——create/stop 落下的 ALWAYS 记忆不得连带放行删除。
                val rules = loadRules(project).filter { it.toolName == toolName }
                if (rules.any { it.pattern == PermissionRule.WHOLE_TOOL && it.decision == PermissionDecision.DENY }) {
                    return EvalResult(Verdict.DENY, emptyList(), denyReason = appContext.getString(R.string.tool_permission_rule_denied))
                }
                return EvalResult(
                    Verdict.ASK,
                    rememberablePatterns = emptyList(),
                    rememberDisabledReason = "删除子代理会话及其消息不可逆，仅支持单次放行"
                )
            }
        }

        val rules = loadRules(project).filter { it.toolName == toolName }
        return if (isShellTool(toolName, args)) {
            evaluateShell(rules, args, forceAsk = toolName == SHIZUKU_TOOL)
        } else {
            evaluateGeneric(rules, capabilities)
        }
    }

    private suspend fun loadRules(project: PermissionRulesRepository.ProjectContext?): List<PermissionRule> =
        if (project == null) rulesRepo.loadEffectiveForCurrentProject()
        else rulesRepo.loadEffectiveForProject(project)

    private fun isDangerousTool(toolName: String, args: Map<String, JsonElement>, capabilities: Set<ToolCapability>): Boolean {
        val dangerousCapabilities = setOf(
            ToolCapability.WRITE_WORKSPACE,
            ToolCapability.EXECUTE_COMMANDS,
            ToolCapability.NETWORK_WRITE,
            ToolCapability.MODIFY_AGENT_CONFIG,
            ToolCapability.MODIFY_CONTAINER_ENV,
            ToolCapability.EXTERNAL_TOOL
        )
        if (capabilities.any { it in dangerousCapabilities }) return true

        // 只读探索工具在 PLAN 模式下永远安全
        val safePlanModeTools = setOf("list", "search")
        if (toolName in safePlanModeTools) return false

        val dangerousTools = setOf(
            "writeFile",
            "editFile",
            "Bash"
        )
        if (toolName in dangerousTools) return true
        if (toolName == TERMINAL_TOOL) {
            val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase()
            return action != "read"
        }
        if (toolName == BROWSER_TOOL) {
            val action = (args["action"] as? JsonPrimitive)?.content?.trim()
            return action !in BROWSER_READ_ONLY_ACTIONS
        }
        return false
    }

    /** 是否按 shell 命令前缀匹配：[SHELL_TOOLS] 中的工具，或终端工具的 start/send 动作。 */
    private fun isShellTool(toolName: String, args: Map<String, JsonElement>): Boolean {
        if (toolName in SHELL_TOOLS) return true
        if (toolName == TERMINAL_TOOL) {
            val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase()
            return action == TERMINAL_SHELL_ACTION || action == "send"
        }
        return false
    }

    /** 调用是否携带提权参数（`elevate: true`）。 */
    private fun isElevationRequested(args: Map<String, JsonElement>): Boolean =
        (args[ELEVATE_ARG] as? JsonPrimitive)?.content?.trim()?.lowercase() == "true"

    /**
     * 该命令是否疑似破坏性（仅在命令不可静态判定时使用）：含实际文件输出重定向（排除 `/dev/null`、
     * `/dev/stdout`、`/dev/stderr`、fd 复制如 `2>&1`）、`find -delete` 或命令位置上的
     * 常见破坏性程序名。参数和搜索词不作为命令执行。
     */
    private fun looksDestructive(command: String, analysis: ShellCommandParser.Analysis): Boolean =
        analysis.hasUnsafeOutputRedirect ||
            FIND_DELETE_COMMAND.containsMatchIn(command) ||
            DESTRUCTIVE_PROGRAM.containsMatchIn(command)

    /**
     * 不可静态判定时的拦截原因：附带具体触发构造。
     * 只说「无法静态分析」时 AI 不知道是 `$(...)` 还是绝对路径重定向，只能整条反复重试。
     */
    private fun unanalyzableReason(analysis: ShellCommandParser.Analysis): String {
        val constructs = analysis.blockers.joinToString("、").ifEmpty { "未知构造" }
        return "$REASON_UNANALYZABLE_DESTRUCTIVE（触发构造：$constructs）"
    }

    /**
     * 灾难性删除的裁决：携带提权参数时降级为一次性用户授权（ASK），否则硬拒绝（DENY）
     * 并在原因里提示可提权重试。AUTO 与非 AUTO 模式共用。提权仅对本次调用生效，不可记忆。
     */
    private fun elevationOrDeny(catastrophicReason: String, args: Map<String, JsonElement>): EvalResult =
        if (isElevationRequested(args)) {
            EvalResult(
                verdict = Verdict.ASK,
                rememberablePatterns = emptyList(),
                askTitle = "高危操作提权确认",
                rememberDisabledReason = "命令命中内置安全防护，提权仅支持单次放行，不可记忆"
            )
        } else {
            EvalResult(
                Verdict.DENY,
                emptyList(),
                denyReason = "$catastrophicReason。如确需执行，可在调用时加 `$ELEVATE_ARG: true` 重试，系统将向用户请求授权。"
            )
        }

    /** 把「始终允许」的选择落库为 ALLOW 规则（去重交给仓库）。 */
    suspend fun remember(toolName: String, patterns: List<String>, scope: PermissionScope) {
        patterns.distinct().forEach { pattern ->
            rulesRepo.add(scope, PermissionRule(toolName, pattern, PermissionDecision.ALLOW))
        }
    }

    suspend fun remember(toolName: String, patterns: List<String>, project: PermissionRulesRepository.ProjectContext) {
        patterns.distinct().forEach { pattern ->
            rulesRepo.addToProject(project, PermissionRule(toolName, pattern, PermissionDecision.ALLOW))
        }
    }

    private fun evaluateGeneric(rules: List<PermissionRule>, capabilities: Set<ToolCapability>): EvalResult {
        val whole = rules.filter { it.pattern == PermissionRule.WHOLE_TOOL }
        if (whole.any { it.decision == PermissionDecision.DENY }) return EvalResult(Verdict.DENY, emptyList(), denyReason = appContext.getString(R.string.tool_permission_rule_denied))
        if (whole.any { it.decision == PermissionDecision.ALLOW }) return EvalResult(Verdict.ALLOW, emptyList())
        if (capabilities.any { it in NON_REMEMBERABLE_CAPABILITIES }) {
            return EvalResult(
                Verdict.ASK,
                rememberablePatterns = emptyList(),
                rememberDisabledReason = "该工具会修改 Agent 配置、容器环境或调用外部动态工具，为降低误授权风险，仅支持单次放行"
            )
        }
        return EvalResult(Verdict.ASK, listOf(PermissionRule.WHOLE_TOOL))
    }

    private fun checkCatastrophicRm(segments: List<List<String>>): String? {
        for (seg in segments) {
            val rmInfo = ShellCommandParser.parseRmInfo(seg)
            if (!rmInfo.isRm) continue
            for (rawPath in rmInfo.targetPaths) {
                catastrophicReasonFor(rawPath)?.let { return it }
            }
        }
        return null
    }

    /** 单个删除目标的裁决：命中受保护范围返回对应原因，否则 null。 */
    private fun catastrophicReasonFor(rawPath: String): String? {
        val raw = rawPath.trim()
        if (raw.isEmpty()) return null
        val path = normalizePath(raw)
        return when {
            path == "/" || path == "/*" -> REASON_RM_ROOT
            path in RELATIVE_WILDCARD_TARGETS -> REASON_RM_RELATIVE
            ROOT_LEVEL_GLOB.matches(path) -> REASON_RM_SYSTEM_DIR
            path == HOME_DIR || path == "$HOME_DIR/*" -> REASON_RM_HOME
            path in WORKSPACE_ROOT_TARGETS -> REASON_RM_WORKSPACE
            // 工作区子目录（构建产物等）属正常操作，置于系统目录判定之前放行
            path.startsWith("$HOME_DIR/workspace/") || path.startsWith("workspace/") -> null
            path == "/tmp" || path == "/tmp/*" -> REASON_RM_TMP
            PROTECTED_SYSTEM_DIRS.any { path == it || path.startsWith("$it/") } -> REASON_RM_SYSTEM_DIR
            else -> null
        }
    }

    /**
     * 词法路径归一化：把开头的 `~` / `$HOME` / `${HOME}` 展开为容器家目录，折叠重复 `/`，
     * 解析 `.` 与 `..`。纯字符串处理、不访问文件系统，故无法覆盖 `$(...)` 等运行时才可知的路径。
     */
    private fun normalizePath(raw: String): String {
        val expanded = when {
            raw == "~" -> HOME_DIR
            raw.startsWith("~/") -> HOME_DIR + raw.drop(1)
            raw == HOME_TOKEN || raw == HOME_BRACED -> HOME_DIR
            raw.startsWith("$HOME_TOKEN/") -> HOME_DIR + raw.drop(HOME_TOKEN.length)
            raw.startsWith("$HOME_BRACED/") -> HOME_DIR + raw.drop(HOME_BRACED.length)
            else -> raw
        }
        val absolute = expanded.startsWith("/")
        val parts = ArrayDeque<String>()
        for (part in expanded.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty() && parts.last() != "..") parts.removeLast() else if (!absolute) parts.addLast("..")
                else -> parts.addLast(part)
            }
        }
        val joined = parts.joinToString("/")
        return when {
            absolute -> "/$joined"
            joined.isEmpty() -> "."
            else -> joined
        }
    }

    private fun evaluateShell(
        rules: List<PermissionRule>,
        args: Map<String, JsonElement>,
        forceAsk: Boolean = false
    ): EvalResult {
        val command = ((args["command"] ?: args["input"]) as? JsonPrimitive)?.content
            ?: return EvalResult(Verdict.ASK, emptyList())

        val analysis = ShellCommandParser.analyze(command)
        val allow = rules.filter { it.decision == PermissionDecision.ALLOW }
        val deny = rules.filter { it.decision == PermissionDecision.DENY }

        // 0) rm 高危操作防护：禁止直接删除系统根目录、工作区根目录或系统关键目录。
        //    携带提权参数时降级为一次性用户授权。
        val catastrophicReason = checkCatastrophicRm(analysis.segments)
        if (catastrophicReason != null) {
            return elevationOrDeny(catastrophicReason, args)
        }

        // 1) DENY 优先（含对内置安全白名单的覆盖）：任一段命中 DENY 即拒。
        val deniedByPattern = deny.firstOrNull { rule ->
            analysis.segments.any { seg -> ShellCommandParser.matches(rule.pattern, seg) }
        }
        if (deniedByPattern != null) {
            // 回显命中规则与被拦的段：AI 才知道是哪一段越界，而不是整条命令反复重试。
            val segment = analysis.segments.first { ShellCommandParser.matches(deniedByPattern.pattern, it) }
            return EvalResult(
                Verdict.DENY,
                emptyList(),
                denyReason = appContext.getString(
                    R.string.tool_command_rule_denied_detail,
                    deniedByPattern.toCompact(),
                    segment.joinToString(" ")
                )
            )
        }

        // 2) Shizuku 高危工具：无论内置白名单或已记忆规则，一律弹窗且不可记忆。
        if (forceAsk) {
            return EvalResult(Verdict.ASK, emptyList(), rememberDisabledReason = appContext.getString(R.string.tool_shizuku_confirmation_reason))
        }

        // 3) 不可静态判定 → 必须弹窗、不可记忆（内置白名单也不适用）。
        if (!analysis.analyzable) {
            return EvalResult(
                Verdict.ASK,
                emptyList(),
                // 把触发构造写进弹窗说明：用户与 AI 都能看出这条命令为什么没法自动判定。
                rememberDisabledReason = appContext.getString(
                    R.string.tool_command_unanalyzable_reason,
                    analysis.blockers.joinToString("、")
                )
            )
        }

        // 4) 内置安全白名单：每段都命中安全前缀（ls/git status 等）→ 自动放行，不弹窗。
        if (analysis.segments.isNotEmpty() &&
            analysis.segments.all { BuiltInSafeCommands.isSafe(it) }
        ) {
            return EvalResult(Verdict.ALLOW, emptyList())
        }

        // 5) 已记忆的 ALLOW：每段都命中 → 放行。
        // 对 rm 命令进行精细化校验：避免存量或宽泛的 "rm"/"rm -rf" 无目标规则放行高风险删除
        val allAllowed = analysis.segments.isNotEmpty() &&
            analysis.segments.all { seg ->
                val rmInfo = ShellCommandParser.parseRmInfo(seg)
                allow.any { rule ->
                    if (!ShellCommandParser.matches(rule.pattern, seg)) {
                        false
                    } else if (rmInfo.isRm) {
                        val ruleRmInfo = ShellCommandParser.parseRmInfo(rule.pattern.split(Regex("\\s+")))
                        ruleRmInfo.targetPaths.isNotEmpty() && (!rmInfo.isRecursive || ruleRmInfo.isRecursive)
                    } else {
                        true
                    }
                }
            }
        if (allAllowed) return EvalResult(Verdict.ALLOW, emptyList())

        // 6) 否则弹窗，可记忆前缀（子命令分发器记 程序名+子命令）。
        val hasRmUnrememberable = analysis.segments.any { seg ->
            val rmInfo = ShellCommandParser.parseRmInfo(seg)
            rmInfo.isRm && (rmInfo.targetPaths.isEmpty() || rmInfo.isRecursive || rmInfo.isWildcard)
        }
        if (hasRmUnrememberable) {
            return EvalResult(
                verdict = Verdict.ASK,
                rememberablePatterns = emptyList(),
                rememberDisabledReason = "高风险删除操作（递归/通配/强制批量删除），为确保数据安全不可记忆，仅可单次放行"
            )
        }

        val rememberable = analysis.segments.mapNotNull { ShellCommandParser.rememberablePrefix(it) }.distinct()
        return EvalResult(Verdict.ASK, rememberable)
    }
}
