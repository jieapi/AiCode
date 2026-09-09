package com.aicode.feature.git.domain

import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.CommandResult
import com.aicode.feature.git.domain.model.GitBranch
import com.aicode.feature.git.domain.model.GitCommit
import com.aicode.feature.git.domain.model.GitFileChange
import com.aicode.feature.git.domain.model.GitGraphRef
import com.aicode.feature.git.domain.model.GitStatus
import com.aicode.feature.git.domain.model.GitTag
import com.aicode.feature.git.domain.model.GraphCommit
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GitRepository] 的输出解析逻辑（mock 掉 CommandEngine 与 WorkspaceRepository）。
 * 所有用例的 engine 输出均按真实 git 命令输出构造（porcelain / for-each-ref / diff-tree /
 * ls-files / log），聚焦解析：中文路径不转义、分支跟踪信息、refs 组装、分页与提交消息 shell 转义。
 * 纯 JVM，不触 Android API（git 执行本身被 mock）。
 */
class GitRepositoryTest {

    private val workPath = "/root/workspace"

    /** 构造给定输出的 repo；[exitCode] 非 0 用于覆盖 gitChecked 失败路径。 */
    private fun createRepo(output: String, exitCode: Int? = 0): GitRepository {
        val engine = mockk<CommandEngine>()
        coEvery { engine.runCommandSyncUnbounded(any(), any(), any()) } returns CommandResult(output, exitCode)
        val workspace = mockk<WorkspaceRepository>()
        every { workspace.currentPath() } returns workPath
        return GitRepository(engine, workspace)
    }

    // ── status()：porcelain v1 -b 解析 ───────────────────────────

    @Test
    fun status_parsesBranchTrackingAndChanges() = runTest {
        val repo = createRepo(
            "## feature/foo...origin/feature/foo [ahead 2, behind 3]\n" +
                " M src/Main.kt\n" +
                "A  src/New.kt\n" +
                "?? untracked/文件.txt"
        )
        val status = repo.status()
        assertEquals(
            GitStatus(
                branch = "feature/foo",
                ahead = 2,
                behind = 3,
                staged = listOf(GitFileChange("src/New.kt", "A", staged = true)),
                unstaged = listOf(GitFileChange("src/Main.kt", "M", staged = false)),
                untracked = listOf("untracked/文件.txt"),
                upstream = "origin/feature/foo",
                isDetached = false
            ),
            status
        )
        assertTrue(status.hasChanges)
    }

    @Test
    fun status_noUpstream_upstreamNull() = runTest {
        val repo = createRepo("## main")
        val status = repo.status()
        assertEquals("main", status.branch)
        assertNull(status.upstream)
        assertEquals(0, status.ahead)
        assertEquals(0, status.behind)
        assertFalse(status.hasChanges)
    }

    @Test
    fun status_detachedHead_branchIsHead() = runTest {
        val repo = createRepo("## HEAD (no branch)")
        val status = repo.status()
        assertTrue(status.isDetached)
        assertEquals("HEAD", status.branch)
    }

    @Test
    fun status_chinesePath_keptUnescaped() = runTest {
        // core.quotepath=false 后中文路径原样输出，不得转义为 \NNN
        val repo = createRepo(" M 中文目录/文件.txt\n?? 中文未跟踪.txt\nA  新建/目录/测试.txt")
        val status = repo.status()
        assertEquals(listOf(GitFileChange("中文目录/文件.txt", "M", staged = false)), status.unstaged)
        assertEquals(listOf("中文未跟踪.txt"), status.untracked)
        assertEquals(listOf(GitFileChange("新建/目录/测试.txt", "A", staged = true)), status.staged)
    }

    @Test
    fun status_renamedPath_usesNewPath() = runTest {
        val repo = createRepo("R  old.txt -> 新目录/新名.txt")
        val status = repo.status()
        assertEquals(listOf(GitFileChange("新目录/新名.txt", "R", staged = true)), status.staged)
        assertTrue(status.unstaged.isEmpty())
    }

    @Test
    fun status_bothModified_addedToStagedAndUnstaged() = runTest {
        val repo = createRepo("MM app/src/X.kt")
        val status = repo.status()
        assertEquals(listOf(GitFileChange("app/src/X.kt", "M", staged = true)), status.staged)
        assertEquals(listOf(GitFileChange("app/src/X.kt", "M", staged = false)), status.unstaged)
    }

    @Test
    fun status_quotedPath_unquoted() = runTest {
        // 含空格路径在 porcelain 里带引号，需还原
        val repo = createRepo(" M \"a b.txt\"")
        val status = repo.status()
        assertEquals(listOf(GitFileChange("a b.txt", "M", staged = false)), status.unstaged)
    }

    // ── branches() / loadAllRefs()：for-each-ref 解析 ────────────

    @Test
    fun loadAllRefs_parsesBranchesRemotesAndTags() = runTest {
        val repo = createRepo(
            "main\u001f7f2a1b3c4d5e\u001f*\u001frefs/heads/main\u001forigin/main\u001f[ahead 1, behind 2]\n" +
                "feature\u001f9a8b7c6d5e4f\u001f\u001frefs/heads/feature\u001f\u001f\n" +
                "origin/feature\u001f3c2d1e0f9a8b\u001f\u001frefs/remotes/origin/feature\u001f\u001f\n" +
                "v1.0\u001f7f2a1b3c4d5e\u001f\u001frefs/tags/v1.0\u001f\u001f"
        )
        val refs = repo.loadAllRefs()
        assertEquals(
            listOf(
                GitBranch("main", current = true, remote = false, upstream = "origin/main", ahead = 1, behind = 2),
                GitBranch("feature", current = false, remote = false),
                GitBranch("origin/feature", current = false, remote = true)
            ),
            refs.branches
        )
        assertEquals(listOf(GitTag("v1.0", "7f2a1b3")), refs.tags)
        assertEquals(
            "main",
            refs.refsByCommit.getValue("7f2a1b3c4d5e")[0].name
        )
        // 同一 hash 上的本地分支与标签都保留，HEAD 标记只落在本地分支
        val refsOnHash = refs.refsByCommit.getValue("7f2a1b3c4d5e")
        assertEquals(
            listOf(
                GitGraphRef("main", isBranch = true, isCurrent = true, isRemote = false),
                GitGraphRef("v1.0", isBranch = false, isCurrent = false, isRemote = false)
            ),
            refsOnHash
        )
        assertEquals(GitGraphRef("origin/feature", isBranch = true, isCurrent = false, isRemote = true),
            refs.refsByCommit.getValue("3c2d1e0f9a8b")[0])
    }

    @Test
    fun loadAllRefs_tagsReversedByRefname() = runTest {
        // for-each-ref 按 refname 升序输出 v0.9 在前，解析后须反转为 [v1.0, v0.9]
        val repo = createRepo(
            "v0.9\u001f1a2b3c4d5e6f\u001f\u001frefs/tags/v0.9\u001f\u001f\n" +
                "v1.0\u001f7f2a1b3c4d5e\u001f\u001frefs/tags/v1.0\u001f\u001f"
        )
        val refs = repo.loadAllRefs()
        assertEquals(listOf("v1.0", "v0.9"), refs.tags.map { it.name })
        assertEquals("7f2a1b3", refs.tags[0].shortHash)
    }

    @Test
    fun loadAllRefs_blankOutput_returnsEmpty() = runTest {
        val empty = createRepo("").loadAllRefs()
        assertTrue(empty.branches.isEmpty())
        assertTrue(empty.tags.isEmpty())
        assertTrue(empty.refsByCommit.isEmpty())
    }

    @Test
    fun loadAllRefs_fatalOutput_returnsEmpty() = runTest {
        val fatal = createRepo("fatal: Not a git repository").loadAllRefs()
        assertTrue(fatal.branches.isEmpty() && fatal.tags.isEmpty() && fatal.refsByCommit.isEmpty())
    }

    @Test
    fun branches_delegatesToLoadAllRefs() = runTest {
        val repo = createRepo(
            "main\u001f7f2a1b3c4d5e\u001f*\u001frefs/heads/main\u001f\u001f\n" +
                "origin/main\u001f7f2a1b3c4d5e\u001f\u001frefs/remotes/origin/main\u001f\u001f"
        )
        assertEquals(listOf("main", "origin/main"), repo.branches().map { it.name })
    }

    // ── log(limit)：%x1f 分隔解析 ────────────────────────────────

    @Test
    fun log_parsesFields() = runTest {
        val repo = createRepo(
            "a1b2c3d4\u001fa1b2c3\u001fAlice\u001f2 days ago\u001ffix: 问题\n" +
                "e5f6a7b8\u001fe5f6a7\u001fBob\u001f3 days ago\u001ffeat: 新功能"
        )
        val commits = repo.log(limit = 50)
        assertEquals(
            listOf(
                GitCommit("a1b2c3d4", "a1b2c3", "Alice", "2 days ago", "fix: 问题"),
                GitCommit("e5f6a7b8", "e5f6a7", "Bob", "3 days ago", "feat: 新功能")
            ),
            commits
        )
    }

    @Test
    fun log_messageWithPipe_keptWhole() = runTest {
        // 提交信息含 '|'：分隔符是 0x1f，message 应完整保留
        val repo = createRepo("a1b2c3d4\u001fa1b2c3\u001fAlice\u001f2 days ago\u001ffix: merge | conflict")
        val commits = repo.log(limit = 50)
        assertEquals("fix: merge | conflict", commits.single().message)
    }

    @Test
    fun log_blankOutput_returnsEmpty() = runTest {
        assertTrue(createRepo("").log(limit = 50).isEmpty())
    }

    @Test
    fun log_fatalOutput_returnsEmpty() = runTest {
        assertTrue(createRepo("fatal: your current branch 'main' does not have any commits yet").log(limit = 50).isEmpty())
    }

    @Test
    fun log_shortLine_skipped() = runTest {
        // 不足 5 段（缺 message）的行整行丢弃
        val repo = createRepo("a1b2c3d4\u001fa1b2c3\u001fAlice\u001f2 days ago")
        assertTrue(repo.log(limit = 50).isEmpty())
    }

    // ── commitFiles(hash)：diff-tree --name-status 解析 ──────────

    @Test
    fun commitFiles_parsesNameStatus() = runTest {
        val repo = createRepo(
            "M\tapp/src/Main.kt\n" +
                "A\tapp/src/New.kt\n" +
                "D\tapp/src/Old.kt\n" +
                "A\t中文/文件.txt"
        )
        val files = repo.commitFiles("a1b2c3d4")
        assertEquals(
            listOf(
                GitFileChange("app/src/Main.kt", "M", staged = false),
                GitFileChange("app/src/New.kt", "A", staged = false),
                GitFileChange("app/src/Old.kt", "D", staged = false),
                GitFileChange("中文/文件.txt", "A", staged = false)
            ),
            files
        )
    }

    @Test
    fun commitFiles_quotedPath_unquoted() = runTest {
        val repo = createRepo("M\t\"a b.txt\"")
        val files = repo.commitFiles("a1b2c3d4")
        assertEquals(listOf(GitFileChange("a b.txt", "M", staged = false)), files)
    }

    @Test
    fun commitFiles_noTabLines_skipped() = runTest {
        // fatal 行/空白行无 tab，跳过 → 空列表（提交无改动或命令失败）
        val repo = createRepo("fatal: ambiguous argument 'x': unknown revision or path not in the working tree.\n")
        assertTrue(repo.commitFiles("badhash").isEmpty())
    }

    // ── untrackedFilesIn(dir)：ls-files --others 解析 ────────────

    @Test
    fun untrackedFilesIn_parsesLines() = runTest {
        val repo = createRepo(
            "newDir/1.txt\n" +
                "newDir/2.txt\n" +
                "中文/新文件.txt\n" +
                "\n"
        )
        val files = repo.untrackedFilesIn("newDir")
        assertEquals(listOf("newDir/1.txt", "newDir/2.txt", "中文/新文件.txt"), files)
    }

    @Test
    fun untrackedFilesIn_blankOrFatal_returnsEmpty() = runTest {
        assertTrue(createRepo("").untrackedFilesIn("dir").isEmpty())
        assertTrue(createRepo("fatal: not a git repository").untrackedFilesIn("dir").isEmpty())
    }

    // ── graph() / graphAppend()：log + refs → GitGraph ───────────

    @Test
    fun graph_blankOutput_returnsEmptyGraph() = runTest {
        val graph = createRepo("").graph()
        assertTrue(graph.commits.isEmpty())
        assertTrue(graph.refs.isEmpty())
        assertFalse(graph.hasMore)
    }

    @Test
    fun graph_parsesCommitsAndFiltersRefs() = runTest {
        val repo = createRepo(
            "aaaa1111\u001faaaa1111\u001fAlice\u001f2 days ago\u001ffix: 修复 | 分号\u001fbbbb2222\u001f正文单行\n" +
                "bbbb2222\u001fbbbb2222\u001fBob\u001f3 days ago\u001ffeat: init\u001f"
        )
        val refs = mapOf(
            "aaaa1111" to listOf(GitGraphRef("main", isBranch = true, isCurrent = true, isRemote = false)),
            // 未加载的 hash 应被过滤掉
            "zzzz9999" to listOf(GitGraphRef("other", isBranch = true, isCurrent = false, isRemote = false))
        )
        val graph = repo.graph(limit = 100, refs = refs)
        assertEquals(2, graph.commits.size)
        assertEquals("aaaa1111", graph.commits[0].hash)
        assertEquals("正文单行", graph.commits[0].body)
        assertEquals(listOf("bbbb2222"), graph.commits[0].parents)
        assertEquals(setOf("aaaa1111"), graph.refs.keys)
        assertFalse(graph.hasMore)
        // 泳道：单链全在 0 列
        assertEquals(mapOf("aaaa1111" to 0, "bbbb2222" to 0), graph.lanes)
    }

    @Test
    fun graph_smallBatch_hasMoreTrue() = runTest {
        // 返回条数达到 limit → 还有更旧提交可加载
        val repo = createRepo("aaaa1111\u001faaaa1111\u001fAlice\u001f2 days ago\u001ffix\u001f")
        assertTrue(repo.graph(limit = 1).hasMore)
    }

    @Test
    fun graphAppend_mergesExistingAndDedupes() = runTest {
        val existing = listOf(
            GraphCommit("aaaa1111", "aaaa1111", "Alice", "d", "newer", parents = listOf("bbbb2222"))
        )
        // 模拟极端情况下新批次仍含已加载提交（--skip 理论上避免，防御性去重）
        val repo = createRepo(
            "aaaa1111\u001faaaa1111\u001fAlice\u001f2 days ago\u001fnewer\u001fbbbb2222\u001f\n" +
                "bbbb2222\u001fbbbb2222\u001fBob\u001f3 days ago\u001folder\u001f"
        )
        val graph = repo.graphAppend(existing, emptyMap(), limit = 100)
        assertEquals(listOf("aaaa1111", "bbbb2222"), graph.commits.map { it.hash })
    }

    @Test
    fun graphAppend_existingNonEmpty_logFatal_buildsFromExisting() = runTest {
        // 追加批次失败时保留已加载的提交重算布局，不降级为 EMPTY
        val existing = listOf(
            GraphCommit("aaaa1111", "aaaa1111", "Alice", "d", "newer", parents = emptyList())
        )
        val graph = createRepo("fatal: not a git repository").graphAppend(existing, emptyMap(), limit = 100)
        assertEquals(listOf("aaaa1111"), graph.commits.map { it.hash })
        assertFalse(graph.hasMore)
    }

    @Test
    fun graphAppend_mergeCommit_computesMergeLanes() = runTest {
        val repo = createRepo(
            "m1m1m1\u001fm1m1m1\u001fAlice\u001f2 days ago\u001fMerge branch 'feat'\u001fp1p1p1 p2p2p2\u001f\n" +
                "p1p1p1\u001fp1p1p1\u001fBob\u001f3 days ago\u001ffeat: x\u001fr0r0r0\u001f\n" +
                "p2p2p2\u001fp2p2p2\u001fCarol\u001f3 days ago\u001ffeat: y\u001fr0r0r0\u001f\n" +
                "r0r0r0\u001fr0r0r0\u001fDan\u001f4 days ago\u001froot\u001f"
        )
        val graph = repo.graph(limit = 100)
        assertTrue(graph.commits[0].isMerge)
        assertEquals(mapOf("m1m1m1" to 0, "p1p1p1" to 0, "p2p2p2" to 1, "r0r0r0" to 0), graph.lanes)
        assertTrue(graph.edges.any { it.isMergeIn && it.fromLane == 0 && it.toLane == 1 })
        assertEquals(1, graph.maxLane)
    }

    @Test
    fun graph_fork_branchesToSeparateLanes() = runTest {
        val repo = createRepo(
            "c1c1c1\u001fc1c1c1\u001fAlice\u001f2 days ago\u001fbranch A\u001fr0r0r0\u001f\n" +
                "c2c2c2\u001fc2c2c2\u001fBob\u001f2 days ago\u001fbranch B\u001fr0r0r0\u001f\n" +
                "r0r0r0\u001fr0r0r0\u001fDan\u001f4 days ago\u001froot\u001f"
        )
        val graph = repo.graph(limit = 100)
        assertEquals(mapOf("c1c1c1" to 0, "c2c2c2" to 1, "r0r0r0" to 0), graph.lanes)
        assertEquals(1, graph.maxLane)
    }

    // ── commit()：消息 shell 转义与失败路径 ───────────────────────

    @Test
    fun commit_messageWithPipe_shellSingleQuoted() = runTest {
        val engine = mockk<CommandEngine>()
        val workspace = mockk<WorkspaceRepository>()
        every { workspace.currentPath() } returns workPath
        val slot = slot<String>()
        coEvery { engine.runCommandSyncUnbounded(capture(slot), any(), any()) } returns CommandResult("", 0)
        val repo = GitRepository(engine, workspace)

        repo.commit("fix: 表格 | 分号; 括号(1)")
        // '|' 是 shell 管道符，整条消息必须单引号包裹传递
        assertEquals("git -c core.quotepath=false commit -m 'fix: 表格 | 分号; 括号(1)'", slot.captured)
    }

    @Test
    fun commit_messageWithSingleQuote_escaped() = runTest {
        val engine = mockk<CommandEngine>()
        val workspace = mockk<WorkspaceRepository>()
        every { workspace.currentPath() } returns workPath
        val slot = slot<String>()
        coEvery { engine.runCommandSyncUnbounded(capture(slot), any(), any()) } returns CommandResult("", 0)
        val repo = GitRepository(engine, workspace)

        repo.commit("it's fixed")
        // 内嵌单引号关闭-转义-重开：it'\''s fixed
        assertEquals("git -c core.quotepath=false commit -m 'it'\\''s fixed'", slot.captured)
    }

    @Test
    fun commit_nonZeroExit_throwsGitCommandFailureException() = runTest {
        val repo = createRepo("fatal: 提交失败", exitCode = 1)
        val e = runCatching { repo.commit("msg") }.exceptionOrNull()
        assertTrue(e is GitCommandFailureException)
        assertEquals("fatal: 提交失败", (e as GitCommandFailureException).output)
    }

    @Test
    fun commit_nonZeroExitWithBlankOutput_throwsWithExitCodeMessage() = runTest {
        val repo = createRepo("", exitCode = 1)
        val e = runCatching { repo.commit("msg") }.exceptionOrNull()
        assertTrue(e is GitCommandFailureException)
        assertEquals("git 退出码 1", e?.message)
    }
}