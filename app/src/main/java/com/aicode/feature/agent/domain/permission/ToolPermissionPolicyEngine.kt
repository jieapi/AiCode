package com.aicode.feature.agent.domain.permission

import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolCapability
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
 */
@Singleton
class ToolPermissionPolicyEngine @Inject constructor(
    private val rulesRepo: PermissionRulesRepository
) {
    private companion object {
        /** 以 `command` 参数承载 shell 命令、按命令前缀做指令级匹配的工具。 */
        val SHELL_TOOLS = setOf("Bash")

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
         * 合并后的子代理工具：只读操作（read/list）自动放行，无需弹窗；
         * 写操作（create/stop）走正常规则评估。
         */
        const val TASK_TOOL = "task"
        private val TASK_READ_ACTIONS = setOf("read", "list", "wait", "waitAll")
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
        val rememberDisabledReason: String? = null
    )

    suspend fun evaluate(tool: AgentTool?, toolName: String, args: Map<String, JsonElement>, mode: com.aicode.feature.agent.domain.model.AgentMode): EvalResult {
        val capabilities = tool?.effectiveCapabilities(args).orEmpty()
        if (mode == com.aicode.feature.agent.domain.model.AgentMode.PLAN && isDangerousTool(toolName, args, capabilities)) {
            return EvalResult(Verdict.DENY, emptyList(), denyReason = "当前处于 PLAN（计划）模式，系统物理沙盒已禁止修改系统状态或执行写操作。请在计划模式下仅调用只读工具探索代码，不要尝试修改文件或执行命令。")
        }

        if (mode == com.aicode.feature.agent.domain.model.AgentMode.AUTO) {
            // AUTO 模式放行所有权限，但仍保留灾难性 rm 防护（根目录/系统目录删除）
            if (isShellTool(toolName, args)) {
                val command = ((args["command"] ?: args["input"]) as? JsonPrimitive)?.content
                if (command != null) {
                    val analysis = ShellCommandParser.analyze(command)
                    val catastrophicReason = checkCatastrophicRm(analysis.segments)
                    if (catastrophicReason != null) {
                        return EvalResult(Verdict.DENY, emptyList(), denyReason = catastrophicReason)
                    }
                }
            }
            return EvalResult(Verdict.ALLOW, emptyList())
        }

        if (capabilities == setOf(ToolCapability.READ_AGENT_CONFIG)) {
            return EvalResult(Verdict.ALLOW, emptyList())
        }

        // task 只读动作（read/list）：不放行 DENY 规则，其余直接自动放行（不弹窗）。
        if (toolName == TASK_TOOL) {
            val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase() ?: "create"
            if (action in TASK_READ_ACTIONS) {
                val rules = rulesRepo.loadEffectiveForCurrentProject().filter { it.toolName == toolName }
                val whole = rules.filter { it.pattern == PermissionRule.WHOLE_TOOL }
                if (whole.any { it.decision == PermissionDecision.DENY }) {
                    return EvalResult(Verdict.DENY, emptyList(), denyReason = "该工具被项目权限规则策略禁止执行")
                }
                return EvalResult(Verdict.ALLOW, emptyList())
            }
        }

        val rules = rulesRepo.loadEffectiveForCurrentProject().filter { it.toolName == toolName }
        return if (isShellTool(toolName, args)) evaluateShell(rules, args) else evaluateGeneric(rules, capabilities)
    }

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

    /** 把「始终允许」的选择落库为 ALLOW 规则（去重交给仓库）。 */
    suspend fun remember(toolName: String, patterns: List<String>, scope: PermissionScope) {
        patterns.distinct().forEach { pattern ->
            rulesRepo.add(scope, PermissionRule(toolName, pattern, PermissionDecision.ALLOW))
        }
    }

    private fun evaluateGeneric(rules: List<PermissionRule>, capabilities: Set<ToolCapability>): EvalResult {
        val whole = rules.filter { it.pattern == PermissionRule.WHOLE_TOOL }
        if (whole.any { it.decision == PermissionDecision.DENY }) return EvalResult(Verdict.DENY, emptyList(), denyReason = "该工具被项目权限规则策略禁止执行")
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
        val sysDirs = setOf("/bin", "/boot", "/dev", "/etc", "/home", "/lib", "/lib64", "/opt", "/proc", "/root", "/run", "/sbin", "/sys", "/usr", "/var")
        for (seg in segments) {
            val rmInfo = ShellCommandParser.parseRmInfo(seg)
            if (!rmInfo.isRm) continue
            for (rawPath in rmInfo.targetPaths) {
                val p = rawPath.trim()
                val normalized = if (p.length > 1) p.trimEnd('/') else p
                if (normalized == "/" || normalized == "/*" || normalized == "//*") return "安全防护：禁止执行高危删除操作（根目录删除）"
                if (normalized == "*" || normalized == ".*" || normalized == "." || normalized == "./*" || normalized == ".." || normalized == "../.*") return "安全防护：禁止执行高危删除操作（全局或相对路径通配删除）"
                if (normalized == "~" || normalized == "~/*") return "安全防护：禁止执行高危删除操作（用户目录删除）"
                if (normalized == "~/workspace" || normalized == "~/workspace/*" || normalized == "~/workspace/.*" || normalized == "~/workspace/." || normalized == "~/workspace/.." || normalized.startsWith("~/workspace/../") || normalized == "/root/workspace" || normalized == "/root/workspace/*" || normalized == "/root/workspace/.*" || normalized == "/root/workspace/." || normalized == "/root/workspace/.." || normalized.startsWith("/root/workspace/../") || normalized == "workspace" || normalized == "workspace/*") return "安全防护：禁止执行高危删除操作（工作区根目录删除）"
                if (normalized == "/tmp" || normalized == "/tmp/*") return "安全防护：禁止执行高危删除操作（系统临时目录整体删除）"
                if (sysDirs.any { normalized == it || normalized.startsWith("$it/") || normalized.startsWith("$it/*") }) {
                    return "安全防护：禁止执行高危删除操作（系统关键目录删除）"
                }
            }
        }
        return null
    }

    private fun evaluateShell(rules: List<PermissionRule>, args: Map<String, JsonElement>): EvalResult {
        val command = ((args["command"] ?: args["input"]) as? JsonPrimitive)?.content
            ?: return EvalResult(Verdict.ASK, emptyList())

        val analysis = ShellCommandParser.analyze(command)
        val allow = rules.filter { it.decision == PermissionDecision.ALLOW }
        val deny = rules.filter { it.decision == PermissionDecision.DENY }

        // 0) rm 高危操作防护：禁止直接删除系统根目录、工作区根目录或系统关键目录
        val catastrophicReason = checkCatastrophicRm(analysis.segments)
        if (catastrophicReason != null) {
            return EvalResult(Verdict.DENY, emptyList(), denyReason = catastrophicReason)
        }

        // 1) DENY 优先（含对内置安全白名单的覆盖）：任一段命中 DENY 即拒。
        if (analysis.segments.any { seg -> deny.any { ShellCommandParser.matches(it.pattern, seg) } }) {
            return EvalResult(Verdict.DENY, emptyList(), denyReason = "该命令被项目权限规则策略禁止执行")
        }

        // 2) 不可静态判定 → 必须弹窗、不可记忆（内置白名单也不适用）。
        if (!analysis.analyzable) return EvalResult(Verdict.ASK, emptyList())

        // 3) 内置安全白名单：每段都命中安全前缀（ls/git status 等）→ 自动放行，不弹窗。
        if (analysis.segments.isNotEmpty() &&
            analysis.segments.all { BuiltInSafeCommands.isSafe(it) }
        ) {
            return EvalResult(Verdict.ALLOW, emptyList())
        }

        // 4) 已记忆的 ALLOW：每段都命中 → 放行。
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

        // 5) 否则弹窗，可记忆前缀（子命令分发器记 程序名+子命令）。
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
