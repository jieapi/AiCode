package com.aicode.feature.agent.domain.permission

import android.content.Context
import com.aicode.R
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.settings.data.repository.ToolSafetySettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具授权策略引擎的分支覆盖。规则加载经 mock 注入，聚焦 [ToolPermissionPolicyEngine.evaluate]
 * 的裁决逻辑：PLAN/AUTO 模式特判、DENY 优先、白名单/记忆规则、rm 高危删除防护与不可记忆降级。
 * 底层 [ShellCommandParser] / [BuiltInSafeCommands] 的解析细节由各自独立测试覆盖，不在此重复。
 */
class ToolPermissionPolicyEngineTest {

    /** 最近一次带格式参数的 getString 收到的参数，供断言拦截原因里回显了命中规则与触发片段。 */
    private val formatArgs = mutableListOf<String>()

    private fun appContext(): Context = mockk {
        every { getString(any()) } answers { "resource:${firstArg<Int>()}" }
        // 带格式参数的文案（拦截原因回显命中规则 / 触发片段）：记录参数，返回资源 id 便于断言。
        every { getString(any(), *anyVararg()) } answers {
            formatArgs.clear()
            formatArgs += secondArg<Array<Any?>>().map { it.toString() }
            "resource:${firstArg<Int>()}"
        }
    }

    private fun engine(vararg rules: PermissionRule, safetyDisabled: Boolean = false): ToolPermissionPolicyEngine {
        val repo = mockk<PermissionRulesRepository>(relaxed = true)
        coEvery { repo.loadEffectiveForCurrentProject() } returns rules.toList()
        val safety = mockk<ToolSafetySettingsRepository>(relaxed = true)
        coEvery { safety.isSafetyInterceptionDisabled() } returns safetyDisabled
        return ToolPermissionPolicyEngine(repo, safety, appContext())
    }

    private fun tool(vararg caps: ToolCapability): AgentTool {
        val t = mockk<AgentTool>(relaxed = true)
        every { t.capabilities } returns caps.toSet()
        every { t.effectiveCapabilities(any()) } returns caps.toSet()
        return t
    }

    private fun bash(command: String) = mapOf("command" to JsonPrimitive(command))

    private fun terminal(action: String) = mapOf("action" to JsonPrimitive(action))

    // ── PLAN 模式：写/执行类工具一律拒绝 ─────────────────────────────

    @Test
    fun planMode_deniesWorkspaceWriteTool() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.WRITE_WORKSPACE), "writeFile", emptyMap(), AgentMode.PLAN)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
        assertEquals("resource:${R.string.tool_plan_mode_denied}", r.denyReason)
    }

    @Test
    fun planMode_deniesBash() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls -la"), AgentMode.PLAN)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    @Test
    fun planMode_deniesTerminalStart() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "terminal", terminal("start"), AgentMode.PLAN)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    @Test
    fun planMode_allowsReadOnlyExplorerTool() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.READ_WORKSPACE), "list", emptyMap(), AgentMode.PLAN)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
    }

    @Test
    fun planMode_allowsTerminalRead() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.READ_WORKSPACE), "terminal", terminal("read"), AgentMode.PLAN)
        // 无任何规则时按整工具 ASK（可记忆），而非 DENY
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
    }

    // ── AUTO 模式：放行但保留灾难性 rm 防护 ──────────────────────────

    @Test
    fun autoMode_allowsAnyTool() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.WRITE_WORKSPACE), "writeFile", emptyMap(), AgentMode.AUTO)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun autoMode_allowsBash() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls -la"), AgentMode.AUTO)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun autoMode_stillBlocksCatastrophicRm() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /"), AgentMode.AUTO)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
        assertTrue(r.denyReason?.startsWith("安全防护：禁止执行高危删除操作（根目录删除）") == true)
    }

    @Test
    fun autoMode_stillBlocksWorkspaceRm() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf ~/workspace/*"), AgentMode.AUTO)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    @Test
    fun autoMode_disabledSafety_allowsCatastrophicRm() = runTest {
        val e = engine(safetyDisabled = true)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /"), AgentMode.AUTO)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun disabledSafety_buildMode_stillBlocksCatastrophicRm() = runTest {
        val e = engine(safetyDisabled = true)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    // ── 只读 Agent 配置自动放行 ─────────────────────────────────────

    @Test
    fun readAgentConfig_capabilityAutoAllow() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.READ_AGENT_CONFIG), "customTool", emptyMap(), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    // ── task 只读动作放行，whole DENY 仍生效 ─────────────────────────

    @Test
    fun taskReadAction_allowsWithoutRules() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXTERNAL_TOOL), "task", terminal("read"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun taskReadAction_deniedWhenWholeRuleDenies() = runTest {
        val e = engine(
            PermissionRule("task", PermissionRule.WHOLE_TOOL, PermissionDecision.DENY)
        )
        val r = e.evaluate(tool(ToolCapability.EXTERNAL_TOOL), "task", terminal("read"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    // ── task 的 del（删除子会话及消息）不可逆：单次确认，不受 ALWAYS 记忆放行 ──

    @Test
    fun taskDeleteAction_asksUnrememberableEvenWithWholeAllow() = runTest {
        // 用户此前对 task 选过「始终允许」（create/stop 落下的记忆）——不得连带放行删除
        val e = engine(
            PermissionRule("task", PermissionRule.WHOLE_TOOL, PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.MODIFY_SESSION_STATE), "task", terminal("del"), AgentMode.BUILD)

        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue("删除不可记忆，必须逐次确认", r.rememberablePatterns.isEmpty())
        assertNotNull(r.rememberDisabledReason)
    }

    @Test
    fun taskDeleteAction_deniedWhenWholeRuleDenies() = runTest {
        val e = engine(
            PermissionRule("task", PermissionRule.WHOLE_TOOL, PermissionDecision.DENY)
        )
        val r = e.evaluate(tool(ToolCapability.MODIFY_SESSION_STATE), "task", terminal("del"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    // ── DENY 优先于内置白名单 ───────────────────────────────────────

    @Test
    fun denyRule_overridesSafeWhitelist() = runTest {
        val e = engine(
            PermissionRule("Bash", "ls", PermissionDecision.DENY)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls -la"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    // ── 不可静态判定：必弹窗、不可记忆 ───────────────────────────────

    @Test
    fun unanalyzableCommand_asksWithoutRememberable() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("echo $(whoami)"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
    }

    // ── 内置安全白名单自动放行 ───────────────────────────────────────

    @Test
    fun safeCommand_autoAllowed() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls -la"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun mixOfSafeAndUnsafe_asks() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls -la && rm x"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
    }

    // ── 已记忆 ALLOW 规则 ───────────────────────────────────────────

    @Test
    fun rememberedPrefix_allows() = runTest {
        val e = engine(
            PermissionRule("Bash", "git pull", PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("git pull origin main"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun rememberedPrefix_doesNotMatchOtherSubcommand() = runTest {
        val e = engine(
            PermissionRule("Bash", "git pull", PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("git clone https://x"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
    }

    // ── rm 精细校验：无目标 / 递归 / 通配的规则不得放行 ───────────────

    @Test
    fun bareRmRule_doesNotAllowRmWithTarget() = runTest {
        val e = engine(
            PermissionRule("Bash", "rm", PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm file.txt"), AgentMode.BUILD)
        // 规则无目标路径 → 不匹配；且 rm 单文件可记忆为完整前缀
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertEquals(listOf("rm file.txt"), r.rememberablePatterns)
    }

    @Test
    fun nonRecursiveRmRule_doesNotAllowRecursiveRm() = runTest {
        val e = engine(
            PermissionRule("Bash", "rm file.txt", PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /some/dir"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty()) // 递归删除不可记忆
        assertNotNull(r.rememberDisabledReason)
    }

    @Test
    fun recursiveRmRule_allowsRecursiveRm() = runTest {
        val e = engine(
            PermissionRule("Bash", "rm -rf /tmp/build", PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /tmp/build"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    // ── 灾难性 rm 防护在 BUILD 模式同样生效 ─────────────────────────

    @Test
    fun catastrophicRm_deniedEvenWithAllowRule() = runTest {
        val e = engine(
            PermissionRule("Bash", "rm -rf", PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    @Test
    fun systemDirRm_denied() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /etc"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    // ── 提权：非 AUTO 模式下携带 elevate 将硬拒绝降级为一次性授权 ──────

    @Test
    fun catastrophicRm_elevate_asksUser() = runTest {
        val e = engine()
        val r = e.evaluate(
            tool(ToolCapability.EXECUTE_COMMANDS),
            "Bash",
            mapOf("command" to JsonPrimitive("rm -rf /"), "elevate" to JsonPrimitive(true)),
            AgentMode.BUILD
        )
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.askTitle)
    }

    @Test
    fun catastrophicRm_denyReasonHintsElevate() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
        assertTrue(r.denyReason?.contains("elevate") == true)
    }

    @Test
    fun catastrophicRm_autoMode_elevate_asksUser() = runTest {
        val e = engine()
        val r = e.evaluate(
            tool(ToolCapability.EXECUTE_COMMANDS),
            "Bash",
            mapOf("command" to JsonPrimitive("rm -rf /"), "elevate" to JsonPrimitive(true)),
            AgentMode.AUTO
        )
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
    }

    // ── 路径归一化：多种等价写法均需拦截，工作区子目录放行 ─────────────

    private suspend fun denied(command: String): Boolean =
        engine().evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash(command), AgentMode.BUILD).verdict ==
            ToolPermissionPolicyEngine.Verdict.DENY

    @Test
    fun abnormalPathSpellings_denied() = runTest {
        assertTrue(denied("rm -rf //etc"))
        assertTrue(denied("rm -rf /./etc"))
        assertTrue(denied("rm -rf /etc/../etc"))
        assertTrue(denied("rm -rf /tmp/../etc"))
        assertTrue(denied("rm -rf /foo/../"))
        assertTrue(denied("rm -rf \$HOME"))
        assertTrue(denied("rm -rf \${HOME}/foo"))
        assertTrue(denied("rm -rf /et*"))
        assertTrue(denied("rm -rf /usr*"))
        assertTrue(denied("rm -rf /srv"))
        assertTrue(denied("rm -rf /mnt"))
    }

    @Test
    fun workspaceSubdir_allowed() = runTest {
        assertTrue(!denied("rm -rf ~/workspace/build"))
        assertTrue(!denied("rm -rf ~/workspace/app/src"))
        assertTrue(!denied("rm -rf /tmp/build"))
    }

    @Test
    fun workspaceRoot_stillDenied() = runTest {
        assertTrue(denied("rm -rf ~/workspace"))
        assertTrue(denied("rm -rf ~/workspace/*"))
        assertTrue(denied("rm -rf ~/workspace/.."))
    }

    // ── AUTO：无法静态判定的疑似破坏性命令保守拦截 ─────────────────

    @Test
    fun autoMode_unanalyzableDestructive_denied() = runTest {
        val e = engine()
        assertEquals(
            ToolPermissionPolicyEngine.Verdict.DENY,
            e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf \$(echo /etc)"), AgentMode.AUTO).verdict
        )
        assertEquals(
            ToolPermissionPolicyEngine.Verdict.DENY,
            e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("cat > /etc/passwd"), AgentMode.AUTO).verdict
        )
    }

    @Test
    fun autoMode_unanalyzableBenign_allowed() = runTest {
        val e = engine()
        assertEquals(
            ToolPermissionPolicyEngine.Verdict.ALLOW,
            e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("echo \$(date)"), AgentMode.AUTO).verdict
        )
    }

    @Test
    fun autoMode_safetyDisabled_unanalyzable_allowed() = runTest {
        val e = engine(safetyDisabled = true)
        assertEquals(
            ToolPermissionPolicyEngine.Verdict.ALLOW,
            e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("cat > /etc/passwd"), AgentMode.AUTO).verdict
        )
    }

    // ── Shizuku 高危工具：一律弹窗、不可记忆，AUTO 不豁免 ─────────────

    private fun shizuku(command: String) = mapOf("command" to JsonPrimitive(command))

    @Test
    fun shizuku_buildMode_asksWithoutRememberable() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.rememberDisabledReason)
    }

    @Test
    fun shizuku_safeCommandStillAsks() = runTest {
        val e = engine()
        // 内置安全白名单（ls）对 Shizuku 不适用，仍需弹窗
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("ls -la /sdcard"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
    }

    @Test
    fun shizuku_rememberedAllowRuleIgnored() = runTest {
        val e = engine(PermissionRule("Shizuku", "pm", PermissionDecision.ALLOW))
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
    }

    @Test
    fun shizuku_denyRuleStillDenies() = runTest {
        val e = engine(PermissionRule("Shizuku", "pm", PermissionDecision.DENY))
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    @Test
    fun shizuku_autoMode_asks() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.AUTO)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.rememberDisabledReason)
    }

    @Test
    fun shizuku_autoMode_safetyDisabled_allows() = runTest {
        val e = engine(safetyDisabled = true)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.AUTO)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun shizuku_buildMode_safetyDisabled_stillAsksWithoutRememberable() = runTest {
        val e = engine(safetyDisabled = true)
        // 开关仅解除 AUTO 豁免，不可记忆在 BUILD 下始终生效
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
    }

    @Test
    fun shizuku_planMode_denied() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.PLAN)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    // ── 非 shell 工具 ───────────────────────────────────────────────

    @Test
    fun genericTool_wholeRuleAllows() = runTest {
        val e = engine(
            PermissionRule("writeFile", PermissionRule.WHOLE_TOOL, PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.WRITE_WORKSPACE), "writeFile", emptyMap(), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun genericTool_unrememberableCapability_asksOnce() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.MODIFY_AGENT_CONFIG), "editFile", emptyMap(), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.rememberDisabledReason)
    }

    @Test
    fun genericTool_wholeRuleDenies() = runTest {
        val e = engine(
            PermissionRule("writeFile", PermissionRule.WHOLE_TOOL, PermissionDecision.DENY)
        )
        val r = e.evaluate(tool(ToolCapability.WRITE_WORKSPACE), "writeFile", emptyMap(), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    // ── 拦截原因回显：命中规则 / 触发片段 ──────────────────────────

    /**
     * DENY 只说「权限规则禁止」时，AI 不知道该改哪一段，只能整条换写法反复重试。
     * 回显命中规则与被拦段之后，一次就能定位。
     */
    @Test
    fun shellDeny_echoesMatchedRuleAndOffendingSegment() = runTest {
        val e = engine(PermissionRule("Bash", "git push", PermissionDecision.DENY))
        val r = e.evaluate(
            tool(ToolCapability.EXECUTE_COMMANDS),
            "Bash",
            bash("ls && git push origin main"),
            AgentMode.BUILD
        )

        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
        assertEquals("resource:${R.string.tool_command_rule_denied_detail}", r.denyReason)
        assertEquals(listOf("Bash(git push)", "git push origin main"), formatArgs)
    }

    /** AUTO 下不可静态判定 + 疑似破坏性：原因里要写明是哪个构造，AI 才知道怎么拆。 */
    @Test
    fun autoMode_unanalyzableDestructiveReasonNamesConstruct() = runTest {
        val e = engine()
        val r = e.evaluate(
            tool(ToolCapability.EXECUTE_COMMANDS),
            "Bash",
            bash("rm -rf \$(pwd)/build"),
            AgentMode.AUTO
        )

        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
        assertTrue(r.denyReason!!.contains("触发构造：命令替换 \$(...)"))
        assertTrue(r.denyReason!!.contains("elevate"))
    }

    /** 非 AUTO 下不可静态判定走弹窗：弹窗也要说明为什么不能「始终允许」。 */
    @Test
    fun buildMode_unanalyzableAskExplainsWhyNotRememberable() = runTest {
        val e = engine()
        val r = e.evaluate(
            tool(ToolCapability.EXECUTE_COMMANDS),
            "Bash",
            bash("ls \$(pwd)"),
            AgentMode.BUILD
        )

        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
        assertEquals("resource:${R.string.tool_command_unanalyzable_reason}", r.rememberDisabledReason)
        assertEquals(listOf("命令替换 \$(...)"), formatArgs)
    }

    @Test
    fun explicitProjectEvaluation_readsCapturedProjectInsteadOfCurrentProject() = runTest {
        val repo = mockk<PermissionRulesRepository>(relaxed = true)
        val project = mockk<PermissionRulesRepository.ProjectContext>()
        every { repo.captureProject("/project-a") } returns project
        coEvery { repo.loadEffectiveForProject(project) } returns listOf(
            PermissionRule("writeFile", "*", PermissionDecision.DENY),
            PermissionRule("Bash", "git pull")
        )
        coEvery { repo.loadEffectiveForCurrentProject() } returns listOf(PermissionRule("writeFile", "*"))
        val e = ToolPermissionPolicyEngine(repo, mockk(relaxed = true), appContext())
        val captured = e.captureProject("/project-a")

        val write = e.evaluate(tool(ToolCapability.WRITE_WORKSPACE), "writeFile", emptyMap(), AgentMode.BUILD, captured)
        val shell = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("git pull"), AgentMode.BUILD, captured)

        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, write.verdict)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, shell.verdict)
        coVerify(exactly = 2) { repo.loadEffectiveForProject(project) }
        coVerify(exactly = 0) { repo.loadEffectiveForCurrentProject() }
    }

    @Test
    fun explicitProjectRemember_dedupesAndWritesOnlyCapturedProject() = runTest {
        val repo = mockk<PermissionRulesRepository>(relaxed = true)
        val project = mockk<PermissionRulesRepository.ProjectContext>()
        val e = ToolPermissionPolicyEngine(repo, mockk(relaxed = true), appContext())

        e.remember("Bash", listOf("git pull", "git pull", "ls"), project)

        coVerify(exactly = 2) { repo.addToProject(project, any()) }
        coVerify { repo.addToProject(project, PermissionRule("Bash", "git pull", PermissionDecision.ALLOW)) }
        coVerify { repo.addToProject(project, PermissionRule("Bash", "ls", PermissionDecision.ALLOW)) }
        coVerify(exactly = 0) { repo.add(any(), any()) }
    }

    // ── remember：去重后逐条落库 ────────────────────────────────────

    @Test
    fun remember_dedupesAndAddsEach() = runTest {
        val repo = mockk<PermissionRulesRepository>(relaxed = true)
        coEvery { repo.add(any(), any()) } just runs
        val e = ToolPermissionPolicyEngine(repo, mockk(relaxed = true), appContext())

        e.remember("Bash", listOf("git pull", "git pull", "ls"), PermissionScope.PROJECT)

        coVerify(exactly = 2) { repo.add(PermissionScope.PROJECT, any()) }
        coVerify { repo.add(PermissionScope.PROJECT, PermissionRule("Bash", "git pull", PermissionDecision.ALLOW)) }
        coVerify { repo.add(PermissionScope.PROJECT, PermissionRule("Bash", "ls", PermissionDecision.ALLOW)) }
    }
}