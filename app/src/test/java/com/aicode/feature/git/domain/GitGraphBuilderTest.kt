package com.aicode.feature.git.domain

import com.aicode.feature.git.domain.model.GitGraph
import com.aicode.feature.git.domain.model.GitGraphRef
import com.aicode.feature.git.domain.model.GraphCommit
import com.aicode.feature.git.domain.model.GraphEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提交拓扑图的解析与泳道布局（[GitGraphBuilder]）。纯 JVM 逻辑，无 Android 依赖：
 * - parseGraphCommits：`git log --pretty=format:%H%x1f...` 输出 → [GraphCommit] 列表
 * - buildGraph：提交列表 + refs 映射 + hasMore → [GitGraph]（refs 过滤 + 泳道布局）
 */
class GitGraphBuilderTest {

    // ── parseGraphCommits：字段解析 ──────────────────────────────

    @Test
    fun parsesFullLine_allFieldsIncludingBody() {
        // body 取第 7 段。注意 %b 多行正文会被按行拆散（逐行解析），此处用单行正文验证字段完整
        val raw = "a1b2c3d4e5f6\u001fa1b2c3d\u001fAlice\u001f2 days ago\u001ffix: 问题\u001fp1p2p3 q1q2q3\u001f正文第一行"
        val commits = GitGraphBuilder.parseGraphCommits(raw)
        assertEquals(1, commits.size)
        assertEquals(
            GraphCommit(
                hash = "a1b2c3d4e5f6",
                shortHash = "a1b2c3d",
                author = "Alice",
                date = "2 days ago",
                message = "fix: 问题",
                parents = listOf("p1p2p3", "q1q2q3"),
                body = "正文第一行"
            ),
            commits[0]
        )
    }

    @Test
    fun parsesLine_withoutBody_bodyEmpty() {
        // 6 段（无 %b），body 缺省为空串
        val raw = "a1b2c3d4e5f6\u001fa1b2c3d\u001fAlice\u001f2 days ago\u001ffix: 问题\u001fr1r2r3"
        val commits = GitGraphBuilder.parseGraphCommits(raw)
        assertEquals(1, commits.size)
        assertEquals("", commits[0].body)
        assertEquals(listOf("r1r2r3"), commits[0].parents)
    }

    @Test
    fun parsesLine_withbodySegment_keepsBody() {
        // 第 7 段存在且含 |，不应影响其他字段
        val raw = "a1b2c3d4e5f6\u001fa1b2c3d\u001fAlice\u001f2 days ago\u001ffix\u001f\u001fbody | with pipe"
        val commits = GitGraphBuilder.parseGraphCommits(raw)
        assertEquals("body | with pipe", commits[0].body)
    }

    @Test
    fun parsesLine_withoutParents_parentsEmpty() {
        // %P 为空（根提交）→ parents 空列表
        val raw = "a1b2c3d4e5f6\u001fa1b2c3d\u001fAlice\u001f2 days ago\u001ffix\u001f"
        val commits = GitGraphBuilder.parseGraphCommits(raw)
        assertEquals(1, commits.size)
        assertTrue(commits[0].parents.isEmpty())
        assertFalse(commits[0].isMerge)
    }

    @Test
    fun parsesMultipleLines_removesCarriageReturn() {
        // git 输出经终端通道可能带 \r\n，逐行须去掉尾部 \r
        val raw = "a1b2c3\u001fa1b2c3\u001fAlice\u001f2 days ago\u001ffix: 1\u001f\r\n" +
            "d4e5f6\u001fd4e5f6\u001fBob\u001f3 days ago\u001ffeat: 2\u001fr0r0r0\r\n"
        val commits = GitGraphBuilder.parseGraphCommits(raw)
        assertEquals(2, commits.size)
        assertEquals("a1b2c3", commits[0].hash)
        assertEquals("d4e5f6", commits[1].hash)
    }

    @Test
    fun parentsSeparatedByMultipleSpaces_filteredToHashList() {
        // 父列表内部可能有多余空格（正常单空格，防御性过滤）
        val raw = "a1b2c3\u001fa1b2c3\u001fAlice\u001f2 days ago\u001fmerge\u001fp1  p2  \u001f"
        val commits = GitGraphBuilder.parseGraphCommits(raw)
        assertEquals(listOf("p1", "p2"), commits[0].parents)
        assertTrue(commits[0].isMerge)
    }

    // ── parseGraphCommits：容错 ─────────────────────────────────

    @Test
    fun shortLine_lessThan6Segments_returnsNull() {
        // 5 段（缺 %P）→ 整行丢弃
        val raw = "a1b2c3\u001fa1b2c3\u001fAlice\u001f2 days ago\u001ffix"
        assertEquals(0, GitGraphBuilder.parseGraphCommits(raw).size)
    }

    @Test
    fun blankLines_skipped() {
        val raw = "a1b2c3\u001fa1b2c3\u001fAlice\u001f2 days ago\u001ffix\u001f\n\n"
        val commits = GitGraphBuilder.parseGraphCommits(raw)
        assertEquals(1, commits.size)
    }

    @Test
    fun emptyRaw_returnsEmpty() {
        assertTrue(GitGraphBuilder.parseGraphCommits("").isEmpty())
    }

    // ── parseGraphCommits：历史 bug 回归（提交信息含 '|'） ────────

    @Test
    fun messageContainingPipe_doesNotSplitParents() {
        // 回归：提交信息含 '|' 曾导致 parents 被误拆成假父（幽灵泳道），
        // 分隔符必须是 0x1f 而非 '|'。这里 parents 应完整保留为两个真实父哈希。
        val raw = "m1m2m3\u001fm1m2m3\u001fAlice\u001f2 days ago\u001ffix: merge | conflict\u001fp1p2p3 p4p5p6\u001f"
        val commits = GitGraphBuilder.parseGraphCommits(raw)
        assertEquals(1, commits.size)
        assertEquals(listOf("p1p2p3", "p4p5p6"), commits[0].parents)
        assertTrue(commits[0].isMerge)
    }

    @Test
    fun messageContainingPipe_simpleCommit_staysSingleParent() {
        // 信息含 | 的普通提交不得被误判为 merge（parent 只有一个）
        val raw = "a1b2c3\u001fa1b2c3\u001fAlice\u001f2 days ago\u001fupdate a | b\u001fr0r0r0\u001f"
        val commits = GitGraphBuilder.parseGraphCommits(raw)
        assertEquals(listOf("r0r0r0"), commits[0].parents)
        assertFalse(commits[0].isMerge)
    }

    // ── buildGraph：空输入 ──────────────────────────────────────

    @Test
    fun emptyCommits_returnsEmptyGraph() {
        assertEquals(GitGraph.EMPTY, GitGraphBuilder.buildGraph(emptyList(), emptyMap(), hasMore = false))
    }

    // ── buildGraph：refs 过滤 ───────────────────────────────────

    @Test
    fun refs_filteredToLoadedCommitHashes() {
        val loaded = GraphCommit("a1b2c3", "a1b2c3", "Alice", "d", "fix", parents = emptyList())
        val refs = mapOf(
            "a1b2c3" to listOf(GitGraphRef("main", isBranch = true, isCurrent = true, isRemote = false)),
            "unloaded1" to listOf(GitGraphRef("other", isBranch = true, isCurrent = false, isRemote = false))
        )
        val graph = GitGraphBuilder.buildGraph(listOf(loaded), refs, hasMore = true)
        assertEquals(setOf("a1b2c3"), graph.refs.keys)
        assertEquals("main", graph.refs.getValue("a1b2c3")[0].name)
    }

    @Test
    fun refs_allUnloaded_returnsEmptyRefs() {
        val loaded = GraphCommit("a1b2c3", "a1b2c3", "Alice", "d", "fix", parents = emptyList())
        val refs = mapOf(
            "unloaded1" to listOf(GitGraphRef("x", isBranch = true, isCurrent = false, isRemote = false)),
            "unloaded2" to listOf(GitGraphRef("y", isBranch = true, isCurrent = false, isRemote = false))
        )
        val graph = GitGraphBuilder.buildGraph(listOf(loaded), refs, hasMore = false)
        assertTrue(graph.refs.isEmpty())
    }

    @Test
    fun hasMore_passedThrough() {
        val loaded = GraphCommit("a1b2c3", "a1b2c3", "Alice", "d", "fix", parents = emptyList())
        assertTrue(GitGraphBuilder.buildGraph(listOf(loaded), emptyMap(), hasMore = true).hasMore)
        assertFalse(GitGraphBuilder.buildGraph(listOf(loaded), emptyMap(), hasMore = false).hasMore)
    }

    // ── buildGraph：泳道布局 ────────────────────────────────────

    @Test
    fun simpleChain_computesSingleLane() {
        val head = GraphCommit("h1h2h3", "h1h2h3", "Alice", "d", "c2", parents = listOf("r0r0r0"))
        val root = GraphCommit("r0r0r0", "r0r0r0", "Bob", "d", "c1", parents = emptyList())
        val graph = GitGraphBuilder.buildGraph(listOf(head, root), emptyMap(), hasMore = false)
        assertEquals(mapOf("h1h2h3" to 0, "r0r0r0" to 0), graph.lanes)
        assertEquals(listOf(GraphEdge(0, 0, 0, isMergeIn = false)), graph.edges)
        assertEquals(0, graph.maxLane)
    }

    @Test
    fun rootCommit_noEdges() {
        val root = GraphCommit("r0r0r0", "r0r0r0", "Alice", "d", "root", parents = emptyList())
        val graph = GitGraphBuilder.buildGraph(listOf(root), emptyMap(), hasMore = false)
        assertTrue(graph.edges.isEmpty())
        assertEquals(mapOf("r0r0r0" to 0), graph.lanes)
        assertEquals(0, graph.maxLane)
    }

    @Test
    fun fork_branchComputesCrossEdge() {
        // 两个子提交指向同一父（分叉）：第二个子提交应占新泳道并向父跨列
        val c1 = GraphCommit("c1c1c1", "c1c1c1", "Alice", "d", "branch A", parents = listOf("r0r0r0"))
        val c2 = GraphCommit("c2c2c2", "c2c2c2", "Bob", "d", "branch B", parents = listOf("r0r0r0"))
        val root = GraphCommit("r0r0r0", "r0r0r0", "Dan", "d", "root", parents = emptyList())
        val graph = GitGraphBuilder.buildGraph(listOf(c1, c2, root), emptyMap(), hasMore = false)
        assertEquals(mapOf("c1c1c1" to 0, "c2c2c2" to 1, "r0r0r0" to 0), graph.lanes)
        assertEquals(
            listOf(
                GraphEdge(0, 0, 0, isMergeIn = false),
                GraphEdge(1, 0, 1, isMergeIn = false)
            ),
            graph.edges
        )
        assertEquals(1, graph.maxLane)
    }

    @Test
    fun mergeCommit_computesMergeInEdge() {
        // 合并提交：第二父占新泳道，产生 isMergeIn 入边
        val merge = GraphCommit("m1m1m1", "m1m1m1", "Alice", "d", "Merge", parents = listOf("p1p1p1", "p2p2p2"))
        val p1 = GraphCommit("p1p1p1", "p1p1p1", "Bob", "d", "feat x", parents = listOf("r0r0r0"))
        val p2 = GraphCommit("p2p2p2", "p2p2p2", "Carol", "d", "feat y", parents = listOf("r0r0r0"))
        val root = GraphCommit("r0r0r0", "r0r0r0", "Dan", "d", "root", parents = emptyList())
        val graph = GitGraphBuilder.buildGraph(listOf(merge, p1, p2, root), emptyMap(), hasMore = false)
        assertTrue(graph.commits[0].isMerge)
        assertEquals(mapOf("m1m1m1" to 0, "p1p1p1" to 0, "p2p2p2" to 1, "r0r0r0" to 0), graph.lanes)
        assertEquals(4, graph.edges.size)
        // 第二父的入边：fromLane=当前列 0，toLane=父列 1，lane=父列 1，isMergeIn=true
        assertTrue(graph.edges.any { it.isMergeIn && it.fromLane == 0 && it.toLane == 1 && it.lane == 1 })
        assertEquals(1, graph.maxLane)
    }
}