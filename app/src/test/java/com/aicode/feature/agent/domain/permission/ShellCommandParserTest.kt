package com.aicode.feature.agent.domain.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellCommandParserTest {

    @Test
    fun singleSegment_isAnalyzable_andPrefixIsProgram() {
        val a = ShellCommandParser.analyze("git status -s")
        assertTrue(a.analyzable)
        assertEquals(1, a.segments.size)
        assertEquals(listOf("git", "status", "-s"), a.segments[0])
        assertEquals("git", ShellCommandParser.programPrefix(a.segments[0]))
    }

    @Test
    fun envAssignment_isStrippedFromPrefix() {
        val a = ShellCommandParser.analyze("FOO=bar BAZ=1 git push")
        assertEquals("git", ShellCommandParser.programPrefix(a.segments[0]))
        assertTrue(ShellCommandParser.matches("git", a.segments[0]))
    }

    @Test
    fun operators_splitIntoSegments() {
        val and = ShellCommandParser.analyze("npm install && git push")
        assertTrue(and.analyzable)
        assertEquals(2, and.segments.size)
        assertEquals("npm", ShellCommandParser.programPrefix(and.segments[0]))
        assertEquals("git", ShellCommandParser.programPrefix(and.segments[1]))

        val pipe = ShellCommandParser.analyze("cat a | grep b ; echo c")
        assertEquals(3, pipe.segments.size)
    }

    @Test
    fun commandSubstitution_isNotAnalyzable() {
        assertFalse(ShellCommandParser.analyze("echo \$(whoami)").analyzable)
        assertFalse(ShellCommandParser.analyze("echo `whoami`").analyzable)
        assertFalse(ShellCommandParser.analyze("(cd x && ls)").analyzable)
    }

    @Test
    fun absoluteOutputRedirect_isNotAnalyzable_butRelativeIs() {
        assertFalse(ShellCommandParser.analyze("echo hi > /etc/passwd").analyzable)
        assertFalse(ShellCommandParser.analyze("echo hi >>/sdcard/x").analyzable)
        assertTrue(ShellCommandParser.analyze("echo hi > out.txt").analyzable)
    }

    @Test
    fun devNullAndStdioRedirects_areAnalyzableAndExcludedFromTokens() {
        listOf(
            "ls -la 2>/dev/null",
            "ls -la >/dev/null 2>&1",
            "ls -la 2>&1 >/dev/null",
            "ls -la &>/dev/null",
            "ls -la &>>/dev/null",
            "ls -la >& /dev/null",
            "ls -la >| /dev/null",
            "ls -la > \"/dev/null\"",
            "ls -la > '/dev/null'",
            "ls -la >/dev/stdout 2>/dev/stderr"
        ).forEach { cmd ->
            val a = ShellCommandParser.analyze(cmd)
            assertTrue("应可静态判定: $cmd, blockers=${a.blockers}", a.analyzable)
            assertTrue("不应有 blocker: $cmd", a.blockers.isEmpty())
            assertFalse("不应视为不安全输出重定向: $cmd", a.hasUnsafeOutputRedirect)
            assertEquals("重定向算子与目标不应污染参数 token: $cmd", listOf(listOf("ls", "-la")), a.segments)
        }
    }

    @Test
    fun fdDuplication_doesNotSplitSegmentsOrTriggerUnsafeRedirect() {
        val pipe = ShellCommandParser.analyze("ls -la 2>&1 | head -n 5")
        assertTrue(pipe.analyzable)
        assertFalse(pipe.hasUnsafeOutputRedirect)
        assertEquals(listOf(listOf("ls", "-la"), listOf("head", "-n", "5")), pipe.segments)

        val closeFd = ShellCommandParser.analyze("echo hi 2>&- && ls")
        assertTrue(closeFd.analyzable)
        assertFalse(closeFd.hasUnsafeOutputRedirect)
        assertEquals(listOf(listOf("echo", "hi"), listOf("ls")), closeFd.segments)

        val subshell = ShellCommandParser.analyze("(ls -la >/dev/null 2>&1)")
        assertFalse(subshell.analyzable)
        assertEquals(listOf("子 shell 或分组括号"), subshell.blockers)
        assertFalse(subshell.hasUnsafeOutputRedirect)
        assertEquals(listOf(listOf("ls", "-la")), subshell.segments)
    }

    @Test
    fun relativeOutputRedirects_areClassifiedByWorkspaceContainment() {
        listOf(
            "echo hi > result.txt",
            "echo hi > build/logs/output.txt",
            "echo hi > ./result.txt",
            "echo hi > cache/../result.txt"
        ).forEach { cmd ->
            val analysis = ShellCommandParser.analyze(cmd)
            assertTrue("工作区内相对重定向应可静态判定: $cmd", analysis.analyzable)
            assertFalse("工作区内相对重定向不应视为越界: $cmd", analysis.hasUnsafeOutputRedirect)
        }

        listOf(
            "echo hi > ../outside.txt",
            "echo hi > subdir/../../outside.txt",
            "echo hi > \$OUTPUT_FILE",
            "echo hi > ~/outside.txt",
            "echo hi > *.log"
        ).forEach { cmd ->
            val analysis = ShellCommandParser.analyze(cmd)
            assertTrue("动态或越界相对重定向应保留不安全标记: $cmd", analysis.hasUnsafeOutputRedirect)
        }
    }

    @Test
    fun devSubpathsOtherThanNullAndStdio_stillBlocked() {
        listOf(
            "echo hi > /dev/null/evil",
            "echo hi > /dev/sda",
            "echo hi &>/etc/passwd",
            "echo hi >& /etc/passwd",
            "echo hi >| /etc/passwd",
            "cat <> /etc/passwd",
            "echo hi > /dev/../etc/passwd"
        ).forEach { cmd ->
            val a = ShellCommandParser.analyze(cmd)
            assertFalse("危险路径重定向必须拦截: $cmd", a.analyzable)
            assertTrue("必须标为不安全输出重定向: $cmd", a.hasUnsafeOutputRedirect)
        }
    }

    @Test
    fun quotedAndEscapedConstructs_doNotTriggerFalseRedirectsOrUnclosedQuotes() {
        val escapedQuote = ShellCommandParser.analyze("echo \"hello \\\"world\\\" > /etc\"")
        assertTrue(escapedQuote.analyzable)
        assertFalse(escapedQuote.hasUnsafeOutputRedirect)

        val escapedSubst = ShellCommandParser.analyze("echo \"\\\$(whoami)\"")
        assertTrue(escapedSubst.analyzable)

        val nestedSubstWithDevNull = ShellCommandParser.analyze("echo \"\$(git rev-parse HEAD 2>/dev/null)\"")
        assertFalse(nestedSubstWithDevNull.analyzable)
        assertEquals(listOf("命令替换 $(...)"), nestedSubstWithDevNull.blockers)
        assertFalse(nestedSubstWithDevNull.hasUnsafeOutputRedirect)

        val nestedSubstWithQuotedApostrophe = ShellCommandParser.analyze("echo \"\$(echo \"don't\")\"")
        assertEquals(listOf("命令替换 $(...)"), nestedSubstWithQuotedApostrophe.blockers)

        val nestedSubstWithAbsoluteWrite = ShellCommandParser.analyze("echo \"\$(cat x > /etc/passwd)\"")
        assertFalse(nestedSubstWithAbsoluteWrite.analyzable)
        assertTrue(nestedSubstWithAbsoluteWrite.hasUnsafeOutputRedirect)
        assertTrue(nestedSubstWithAbsoluteWrite.blockers.contains("输出重定向到绝对路径 /etc/passwd"))

        val backtickWithDevNull = ShellCommandParser.analyze("echo `git status 2>/dev/null`")
        assertFalse(backtickWithDevNull.analyzable)
        assertEquals(listOf("反引号命令替换"), backtickWithDevNull.blockers)
        assertFalse(backtickWithDevNull.hasUnsafeOutputRedirect)

        val backtickWithAbsoluteWrite = ShellCommandParser.analyze("echo `cat x > /etc/passwd`")
        assertTrue(backtickWithAbsoluteWrite.hasUnsafeOutputRedirect)
        assertTrue(backtickWithAbsoluteWrite.blockers.contains("输出重定向到绝对路径 /etc/passwd"))
    }

    @Test
    fun rmWithDevNullRedirect_keepsOnlyRealDeleteTargets() {
        val a = ShellCommandParser.analyze("rm -f cache.tmp 2>/dev/null")
        assertTrue(a.analyzable)
        assertEquals(listOf(listOf("rm", "-f", "cache.tmp")), a.segments)
        assertEquals("rm -f cache.tmp", ShellCommandParser.rememberablePrefix(a.segments[0]))
    }

    @Test
    fun operatorsInsideQuotes_areNotSplit() {
        val a = ShellCommandParser.analyze("echo \"a && b | c\"")
        assertTrue(a.analyzable)
        assertEquals(1, a.segments.size)
        assertEquals(listOf("echo", "a && b | c"), a.segments[0])
    }

    @Test
    fun unterminatedQuote_isNotAnalyzable() {
        assertFalse(ShellCommandParser.analyze("echo \"abc").analyzable)
    }

    /** 不可静态判定时要能说清是哪个构造触发的，否则上层只能说「无法分析」而 AI 无从修正。 */
    @Test
    fun blockers_nameTheOffendingConstruct() {
        assertEquals(listOf("命令替换 \$(...)"), ShellCommandParser.analyze("echo \$(whoami)").blockers)
        assertEquals(listOf("反引号命令替换"), ShellCommandParser.analyze("echo `whoami`").blockers)
        assertEquals(listOf("子 shell 或分组括号"), ShellCommandParser.analyze("(cd x && ls)").blockers)
        assertEquals("未闭合的引号", ShellCommandParser.analyze("echo \"abc").blockers.single())
    }

    @Test
    fun blockers_includeRedirectTarget() {
        assertEquals(
            listOf("输出重定向到绝对路径 /etc/passwd"),
            ShellCommandParser.analyze("echo hi > /etc/passwd").blockers
        )
    }

    /** 多种构造同时出现时逐条记录、不重复；可静态判定的命令没有任何 blocker。 */
    @Test
    fun blockers_areDistinctAndEmptyWhenAnalyzable() {
        val multi = ShellCommandParser.analyze("echo \$(a) \$(b) `c`")

        assertEquals(listOf("命令替换 \$(...)", "反引号命令替换"), multi.blockers)
        assertTrue(ShellCommandParser.analyze("git status").blockers.isEmpty())
    }

    @Test
    fun matches_isTokenPrefix() {
        assertTrue(ShellCommandParser.matches("git", listOf("git", "status")))
        assertTrue(ShellCommandParser.matches("git", listOf("git")))
        assertTrue(ShellCommandParser.matches("npm install", listOf("npm", "install", "lodash")))
        // 不同子命令不命中更具体的前缀规则
        assertFalse(ShellCommandParser.matches("git status", listOf("git", "push")))
        // 字面首 token：路径形式不被裸程序名规则命中
        assertFalse(ShellCommandParser.matches("node", listOf("/usr/bin/node", "x.js")))
    }

    @Test
    fun rememberablePrefix_dispatcher_includesSubcommand() {
        // 子命令分发器：连子命令一起记，使 git pull / git clone 各自独立授权。
        assertEquals("git pull", ShellCommandParser.rememberablePrefix(ShellCommandParser.analyze("git pull").segments[0]))
        assertEquals("git clone", ShellCommandParser.rememberablePrefix(ShellCommandParser.analyze("git clone https://x/y").segments[0]))
        assertEquals("npm install", ShellCommandParser.rememberablePrefix(ShellCommandParser.analyze("npm install lodash --save").segments[0]))
        assertEquals("cargo build", ShellCommandParser.rememberablePrefix(ShellCommandParser.analyze("cargo build --release").segments[0]))
    }

    @Test
    fun rememberablePrefix_dispatcher_withLeadingFlag_fallsBackToProgram() {
        // 程序名后紧跟 flag：无法稳定取子命令，退化为仅程序名（不误记带 flag 的怪前缀）。
        assertEquals("git", ShellCommandParser.rememberablePrefix(ShellCommandParser.analyze("git --version").segments[0]))
    }

    @Test
    fun rememberablePrefix_nonDispatcher_isProgramOnly() {
        // 普通程序：仅记程序名，按整程序放行（cat 任意文件）。
        assertEquals("cat", ShellCommandParser.rememberablePrefix(ShellCommandParser.analyze("cat README.md").segments[0]))
        assertEquals("ls", ShellCommandParser.rememberablePrefix(ShellCommandParser.analyze("ls -la /tmp").segments[0]))
    }

    @Test
    fun rememberablePrefix_distinctSubcommandsAreIndependent() {
        // 验证记忆的前缀互不串放：git pull 规则不命中 git push。
        val pullRule = ShellCommandParser.rememberablePrefix(ShellCommandParser.analyze("git pull").segments[0])
        assertEquals("git pull", pullRule)
        assertFalse(ShellCommandParser.matches(pullRule!!, ShellCommandParser.analyze("git push").segments[0]))
    }

    @Test
    fun rmCommand_fineGrainedHandling() {
        // 单文件删除记忆为完整前缀，不记忆为宽泛的 "rm"
        val singleFile = ShellCommandParser.analyze("rm temp.log").segments[0]
        assertEquals("rm temp.log", ShellCommandParser.rememberablePrefix(singleFile))

        // 带选项的单文件删除
        val forceFile = ShellCommandParser.analyze("rm -f cache.tmp").segments[0]
        assertEquals("rm -f cache.tmp", ShellCommandParser.rememberablePrefix(forceFile))

        // 递归删除不可记忆
        val recursiveDir = ShellCommandParser.analyze("rm -rf build").segments[0]
        assertEquals(null, ShellCommandParser.rememberablePrefix(recursiveDir))

        // 通配符删除不可记忆
        val wildcardFile = ShellCommandParser.analyze("rm *.class").segments[0]
        assertEquals(null, ShellCommandParser.rememberablePrefix(wildcardFile))

        // 无目标参数不可记忆
        val noTarget = ShellCommandParser.analyze("rm --help").segments[0]
        assertEquals(null, ShellCommandParser.rememberablePrefix(noTarget))
    }
}
