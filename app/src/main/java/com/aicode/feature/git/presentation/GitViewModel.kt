package com.aicode.feature.git.presentation

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.core.util.LineDiff
import com.aicode.feature.git.domain.GitCommandFailureException
import com.aicode.feature.git.domain.GitErrorMessage
import com.aicode.feature.git.domain.GitRepository
import com.aicode.feature.git.domain.model.GitBranch
import com.aicode.feature.git.domain.model.GitCommit
import com.aicode.feature.git.domain.model.GitFileChange
import com.aicode.feature.git.domain.model.GitGraph
import com.aicode.feature.git.domain.model.GitStatus
import com.aicode.feature.git.domain.model.GitTab
import com.aicode.feature.git.domain.model.GitTag
import com.aicode.feature.git.presentation.component.DiffData
import com.aicode.feature.git.presentation.component.DiffRow
import com.aicode.feature.git.presentation.component.highlightCode
import com.aicode.feature.git.presentation.component.inferSyntaxLanguage
import com.aicode.feature.settings.data.repository.AppThemeMode
import com.aicode.feature.settings.data.repository.ThemeSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Git 页的 UI 状态与操作。
 *
 * 进入页面即 [refresh] 并发拉取 status/graph/remote，随后异步拉取全量分支/标签；每个写操作
 * （暂存/提交/拉取/推送）执行后自动刷新并通过 [GitUiState.toast] 反馈。[GitUiState.busy]
 * 守卫防止并发写操作相互踩踏。
 */
@HiltViewModel
class GitViewModel @Inject constructor(
    private val repository: GitRepository,
    @param:ApplicationContext private val context: Context,
    private val themeSettings: ThemeSettingsRepository
) : ViewModel() {

    private companion object {
        const val TAG = "GitViewModel"
        /** diff 行数上限：超过则跳过 LCS，避免移动端 O(n·m) 内存压力。与 FileTools 对齐。 */
        const val MAX_DIFF_LINES = 2000
        /** 单行长度上限：超长单行（如压缩的 JSON）渲染会撑爆 Compose Constraints，直接降级提示。 */
        const val MAX_DIFF_LINE_LENGTH = 2000
    }

    data class GitUiState(
        val loading: Boolean = true,
        val notARepo: Boolean = false,
        val status: GitStatus? = null,
        val branches: List<GitBranch> = emptyList(),
        val commits: List<GitCommit> = emptyList(),
        /** 拓扑图视图：提交（含父哈希）+ 引用 + 泳道布局，供 LogTab 绘制彩色分支连线。 */
        val graph: GitGraph = GitGraph.EMPTY,
        val tags: List<GitTag> = emptyList(),
        val tab: GitTab = GitTab.STATUS,
        val busy: Boolean = false,
        val toast: String? = null,
        /** 是否已配置远程仓库，控制拉取/推送按钮可用性。 */
        val hasRemote: Boolean = false,
        /** 是否已配置全局署名 user.name（git config --global），控制提交按钮可用性；无署名提交会成为失败提交。 */
        val hasIdentity: Boolean = false,
        /** 工作区里发现的 git 仓库路径列表（根 + 一层子目录）。 */
        val repos: List<String> = emptyList(),
        /** 当前选中的 git 仓库路径；null 表示未选中（工作区根 + 子目录都非仓库）。 */
        val currentRepo: String? = null,
        /** 工作区根的直接子目录（供「选择已有仓库」手动指定）。 */
        val subdirs: List<String> = emptyList(),
        /** 当前工作区根路径（仓库选择弹窗的基准目录）。 */
        val workspaceRoot: String = "",
        /** 是否显示仓库选择弹窗。 */
        val repoPickerVisible: Boolean = false,
        /** 当前在详情弹层中查看的提交 hash；null 表示未打开。 */
        val commitDetailHash: String? = null,
        /** 已懒加载的提交文件清单，按 hash 缓存。 */
        val commitFiles: Map<String, List<GitFileChange>> = emptyMap(),
        /** 正在加载文件清单的提交 hash。 */
        val loadingCommit: String? = null,
        /** 正在分页加载更旧的提交（滚动到底触发）。不置 busy，避免阻塞写操作。 */
        val graphLoadingMore: Boolean = false,
        /** 全量分支/标签是否已加载（进入页面即后台拉取，不阻塞首屏；切 BRANCHES tab 时未完成则继续等）。 */
        val branchesLoaded: Boolean = false,
        /** 正在加载全量分支/标签。 */
        val branchesLoading: Boolean = false,
        /** 正在切换分支。 */
        val checkoutLoading: String? = null,
        /** 是否显示 diff 全屏页；进入即置 true，diffData 为 null 时页内显示加载中。 */
        val diffVisible: Boolean = false,
        /** 正在查看 diff 的文件路径；加载中（diffData 为 null）时供顶栏显示文件名。 */
        val diffPath: String? = null,
        /** diff 视图数据；非 null 时 diff 页渲染内容。 */
        val diffData: DiffData? = null
    )

    private val _state = MutableStateFlow(GitUiState())
    val state: StateFlow<GitUiState> = _state.asStateFlow()

    init { refresh() }

    /** 仓库快照：并发拉取 status/graph/remote（+可选 identity）的结果。不含全量分支/标签——
     * 那些在 [refresh] 成功后异步拉取（[loadBranches]），避免阻塞首屏。
     * graph 的 refs 标注只用本地分支（[repository.localRefsOnly]，亚秒级），远程/标签标注由 [loadBranches] 补全。 */
    private data class RepoSnapshot(
        val status: GitStatus,
        val graph: GitGraph,
        val hasRemote: Boolean,
        val hasIdentity: Boolean = false
    )

    /** 并发拉取轻量快照：status + graph(本地 refs) + remote + 可选 identity。 */
    private suspend fun loadSnapshot(includeIdentity: Boolean): RepoSnapshot = coroutineScope {
        val s = async { repository.status() }
        val localRefs = async { repository.localRefsOnly() }
        val r = async { repository.hasRemote() }
        val id = async { if (includeIdentity) repository.getUserName().isNotBlank() else false }
        val g = async { repository.graph(refs = localRefs.await()) }
        RepoSnapshot(s.await(), g.await(), r.await(), id.await())
    }

    fun setTab(tab: GitTab) {
        _state.update { it.copy(tab = tab) }
        // 兜底：进入页面即已异步加载；若尚未开始/完成（如加载失败后重进），切 tab 时再补一次。
        if (tab == GitTab.BRANCHES && !_state.value.branchesLoaded && !_state.value.branchesLoading) {
            loadBranches()
        }
    }

    /**
     * 加载全量分支/标签（进入页面 [refresh] 成功后即触发，无需等切 BRANCHES tab）。
     * 一次 [repository.loadAllRefs] 拉取，填充 branches/tags 并用全量 refs 更新 graph 的标注。
     * 不置 busy，只读不阻塞写操作。已加载过则跳过；写操作后需手动调 [refreshBranchesIfLoaded] 同步。
     */
    fun loadBranches() {
        if (_state.value.branchesLoading || _state.value.branchesLoaded) return
        _state.update { it.copy(branchesLoading = true) }
        viewModelScope.launch {
            try {
                val allRefs = repository.loadAllRefs()
                // 用全量 refs 更新 graph 标注（远程分支+标签补全）。
                val graph = repository.graphAppend(_state.value.graph.commits, allRefs.refsByCommit)
                val commits = graph.commits.map { GitCommit(it.hash, it.shortHash, it.author, it.date, it.message) }
                _state.update {
                    it.copy(branches = allRefs.branches, tags = allRefs.tags, graph = graph, commits = commits, branchesLoading = false, branchesLoaded = true)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                FileLogger.e(TAG, "加载分支列表失败", e)
                _state.update { it.copy(branchesLoading = false, toast = context.getString(R.string.git_toast_load_branches_failed, e.message)) }
            }
        }
    }

    /**
     * 写操作后同步刷新已加载的分支/标签（若已加载过）。未加载过则不主动拉，保持延迟加载语义。
     */
    private fun refreshBranchesIfLoaded() {
        if (!_state.value.branchesLoaded) return
        viewModelScope.launch {
            try {
                val allRefs = repository.loadAllRefs()
                _state.update { it.copy(branches = allRefs.branches, tags = allRefs.tags) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                FileLogger.e(TAG, "刷新分支列表失败", e)
            }
        }
    }

    fun refresh() {
        if (_state.value.busy) return
        // 容器未就绪时不执行 git 命令，直接提示用户去终端页完成初始化，避免误显示"非 Git 仓库"。
        repository.notReadyHint()?.let { hint ->
            _state.update { it.copy(loading = false, toast = hint) }
            return
        }
        _state.update { it.copy(loading = true, toast = null) }
        viewModelScope.launch {
            try {
                // 发现工作区里的 git 仓库（根 + 一层子目录），选中当前或默认仓库。
                val repos = repository.discoverRepos()
                val subdirs = repository.listSubdirectories()
                val workspaceRoot = repository.workspaceRoot()
                val current = repository.currentRepoPath
                // 当前选中仓库已不在发现列表（如切换了工作区）时回退默认：工作区根优先，否则第一个子目录仓库。
                val effectiveRepo = current?.takeIf { it in repos } ?: repos.firstOrNull()
                repository.setRepoPath(effectiveRepo)
                if (effectiveRepo == null) {
                    _state.update { it.copy(loading = false, notARepo = true, repos = repos, subdirs = subdirs, workspaceRoot = workspaceRoot, currentRepo = null) }
                    return@launch
                }
                val snap = loadSnapshot(includeIdentity = true)
                val commits = snap.graph.commits.map { GitCommit(it.hash, it.shortHash, it.author, it.date, it.message) }
                _state.update {
                    it.copy(loading = false, notARepo = false, status = snap.status, commits = commits, graph = snap.graph, hasRemote = snap.hasRemote, hasIdentity = snap.hasIdentity, branchesLoaded = false, branchesLoading = false, branches = emptyList(), tags = emptyList(), repos = repos, subdirs = subdirs, workspaceRoot = workspaceRoot, currentRepo = effectiveRepo)
                }
                // 页面已打开：后台拉取全量分支/标签，用户切到 BRANCHES tab 时无需再等。
                loadBranches()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                FileLogger.e(TAG, "刷新失败", e)
                _state.update { it.copy(loading = false, toast = context.getString(R.string.git_toast_refresh_failed, e.message)) }
            }
        }
    }

    /** 执行一个写操作：置 busy → 跑命令 → 刷新 → 反馈。操作间互斥。 */
    private fun runAction(
        @StringRes nameRes: Int,
        action: suspend () -> String
    ) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, toast = null) }
        viewModelScope.launch {
            val name = context.getString(nameRes)
            val msg = try {
                action()
                context.getString(R.string.git_toast_action_success, name)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                FileLogger.e(TAG, "${name}失败", e)
                val reason = (e as? GitCommandFailureException)?.output ?: e.message
                context.getString(R.string.git_toast_action_failed, name, GitErrorMessage.friendly(reason ?: ""))
            }
            // 刷新以反映新状态；失败也刷新，让 UI 与仓库一致。
            try {
                if (repository.isRepo()) {
                    val snap = loadSnapshot(includeIdentity = false)
                    val commits = snap.graph.commits.map { GitCommit(it.hash, it.shortHash, it.author, it.date, it.message) }
                    _state.update { it.copy(busy = false, status = snap.status, commits = commits, graph = snap.graph, hasRemote = snap.hasRemote, notARepo = false, toast = msg) }
                    refreshBranchesIfLoaded()
                } else {
                    _state.update { it.copy(busy = false, notARepo = true, toast = msg) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(busy = false, toast = context.getString(R.string.git_toast_action_refresh_failed, msg)) }
            }
        }
    }

    fun stage(path: String) = runAction(R.string.git_stage, { repository.stage(path) })
    fun unstage(path: String) = runAction(R.string.git_unstage, { repository.unstage(path) })
    fun stageAll() = runAction(R.string.git_action_stage_all, { repository.stageAll() })
    fun unstageAll() = runAction(R.string.git_action_unstage_all, { repository.unstageAll() })
    fun commit(message: String) = runAction(R.string.git_action_commit, { repository.commit(message) })
    /** 在当前工作区执行 `git init` 初始化仓库；成功后 runAction 末尾自动刷新（notARepo 翻 false）。 */
    fun initRepo() = runAction(R.string.git_action_init, { repository.initRepo() })

    /** 显示仓库选择弹窗（列出工作区发现的 git 仓库）。 */
    fun showRepoPicker() = _state.update { it.copy(repoPickerVisible = true) }

    /** 关闭仓库选择弹窗。 */
    fun hideRepoPicker() = _state.update { it.copy(repoPickerVisible = false) }

    /**
     * 切换到指定路径的 git 仓库（手动指定或从发现列表选择）。
     * 先校验目标路径是 git 仓库，通过后设置当前仓库路径并刷新。
     */
    fun switchRepo(path: String) {
        if (_state.value.busy) return
        _state.update { it.copy(repoPickerVisible = false) }
        viewModelScope.launch {
            try {
                if (!repository.isGitRepo(path)) {
                    _state.update { it.copy(toast = context.getString(R.string.git_toast_not_repo)) }
                    return@launch
                }
                repository.setRepoPath(path)
                refresh()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                FileLogger.e(TAG, "切换仓库失败", e)
                _state.update { it.copy(toast = context.getString(R.string.git_toast_switch_repo_failed, e.message)) }
            }
        }
    }
    fun pull() {
        if (!_state.value.hasRemote) {
            _state.update { it.copy(toast = context.getString(R.string.git_toast_no_remote_pull)) }
            return
        }
        runAction(R.string.git_pull, { repository.pull() })
    }
    fun push() {
        if (!_state.value.hasRemote) {
            _state.update { it.copy(toast = context.getString(R.string.git_toast_no_remote_push)) }
            return
        }
        runAction(R.string.git_push, { repository.push() })
    }

    /**
     * 打开某条提交的详情弹层。若文件清单尚未加载则懒加载（不置 [GitUiState.busy]，
     * 因为这是只读查看，不应阻塞 status/branches 的写操作）。
     */
    fun openCommitDetail(hash: String) {
        val current = _state.value
        if (current.commitDetailHash == hash) return
        _state.update { it.copy(commitDetailHash = hash) }
        if (hash in current.commitFiles) return
        _state.update { it.copy(loadingCommit = hash) }
        viewModelScope.launch {
            val files = try {
                repository.commitFiles(hash)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                FileLogger.e(TAG, "加载提交文件失败: $hash", e)
                emptyList()
            }
            _state.update {
                it.copy(commitFiles = it.commitFiles + (hash to files), loadingCommit = null)
            }
        }
    }

    fun closeCommitDetail() = _state.update { it.copy(commitDetailHash = null) }

    fun consumeToast() = _state.update { it.copy(toast = null) }

    /**
     * 分页加载更旧的提交。UI 滚到底时调用：取已加载提交作为锚点，[repository.graphAppend] 用
     * `--skip` 取下一页并整体重算泳道布局，返回完整图直接替换 state.graph。不置 busy，避免阻塞写操作。
     * 守卫：正在加载或无更多时直接返回。失败 toast 提示且保留 hasMore 允许重试。
     */
    fun loadMoreCommits() {
        val current = _state.value
        if (current.graphLoadingMore || !current.graph.hasMore) return
        _state.update { it.copy(graphLoadingMore = true) }
        viewModelScope.launch {
            try {
                val graph = repository.graphAppend(current.graph.commits, current.graph.refs)
                val commits = graph.commits.map { GitCommit(it.hash, it.shortHash, it.author, it.date, it.message) }
                _state.update { it.copy(graph = graph, commits = commits, graphLoadingMore = false) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                FileLogger.e(TAG, "加载更多提交失败", e)
                _state.update { it.copy(graphLoadingMore = false, toast = context.getString(R.string.git_toast_load_more_failed, e.message)) }
            }
        }
    }

    /**
     * 切换到指定分支或标签。成功后刷新全量状态。
     */
    fun checkoutBranch(ref: String, isRemote: Boolean = false) {
        if (_state.value.busy || _state.value.checkoutLoading != null) return
        _state.update { it.copy(checkoutLoading = ref, toast = null) }
        viewModelScope.launch {
            val msg = try {
                repository.checkout(ref, isRemote)
                context.getString(R.string.git_toast_checkout_success, ref)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                FileLogger.e(TAG, "切换分支失败", e)
                val reason = (e as? GitCommandFailureException)?.output ?: e.message
                context.getString(R.string.git_toast_checkout_failed, GitErrorMessage.friendly(reason ?: ""))
            }
            try {
                if (repository.isRepo()) {
                    val snap = loadSnapshot(includeIdentity = false)
                    val commits = snap.graph.commits.map { GitCommit(it.hash, it.shortHash, it.author, it.date, it.message) }
                    _state.update { it.copy(checkoutLoading = null, status = snap.status, commits = commits, graph = snap.graph, hasRemote = snap.hasRemote, notARepo = false, toast = msg) }
                    // 切换分支后分支列表可能变化，若已加载过则刷新。
                    refreshBranchesIfLoaded()
                } else {
                    _state.update { it.copy(checkoutLoading = null, notARepo = true, toast = msg) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(checkoutLoading = null, toast = context.getString(R.string.git_toast_action_refresh_failed, msg)) }
            }
        }
    }

    /**
     * 创建新分支。name 为新分支名；startPoint 为基准分支（null/空 → 从当前 HEAD）；
     * checkout=true 则创建并切换。复用 runAction：置 busy → 跑命令 → 刷新 → toast。
     */
    fun createBranch(name: String, startPoint: String?, checkout: Boolean) {
        if (name.isBlank()) return
        runAction(R.string.git_action_create_branch, { repository.createBranch(name, startPoint, checkout) })
    }

    /**
     * 安全删除本地分支（git branch -d）。未合并或为当前分支时 git 报错，经 runAction toast 透出。
     */
    fun deleteBranch(name: String) {
        if (name.isBlank()) return
        runAction(R.string.git_action_delete_branch, { repository.deleteBranch(name) })
    }

    /**
     * 删除远程分支（git push --delete）。会改远端，失败经 runAction toast 透出。
     */
    fun deleteRemoteBranch(ref: String) {
        if (ref.isBlank()) return
        runAction(R.string.git_action_delete_remote_branch, { repository.deleteRemoteBranch(ref) })
    }

    /**
     * 重命名本地分支（git branch -m）。当前分支也可重命名。失败经 runAction toast 透出。
     */
    fun renameBranch(oldName: String, newName: String) {
        if (oldName.isBlank() || newName.isBlank()) return
        runAction(R.string.git_action_rename_branch, { repository.renameBranch(oldName, newName) })
    }

    /**
     * 创建轻量标签（git tag <name>），指向当前 HEAD。失败经 runAction toast 透出。
     */
    fun createTag(name: String) {
        if (name.isBlank()) return
        runAction(R.string.git_action_create_tag, { repository.createTag(name) })
    }

    /**
     * 删除本地标签（git tag -d）。失败经 runAction toast 透出。
     */
    fun deleteTag(name: String) {
        if (name.isBlank()) return
        runAction(R.string.git_action_delete_tag, { repository.deleteTag(name) })
    }

    /**
     * 退出 diff 视图，清空 diff 状态。
     */
    fun clearDiff() = _state.update { it.copy(diffVisible = false, diffPath = null, diffData = null) }

    /**
     * 加载某次提交中某文件的差异（改动前 vs 改动后）。
 * hash 为提交 hash，path 为文件路径。后台线程算 diff + 高亮，完成后填 diffData。
 */
    fun loadCommitFileDiff(hash: String, path: String) {
        openDiff(path)
        viewModelScope.launch {
            val data = runCatching { computeDiff(path, "${hash}^", hash, repository::showFileContent) }
                .getOrElse { e ->
                    FileLogger.e(TAG, "加载提交文件 diff 失败: $path", e)
                    null
                }
            finishDiff(data)
        }
    }

    /**
     * 加载工作区某文件的未暂存差异（HEAD vs 工作区当前内容）。
 * path 为文件路径。用 showFileContent("HEAD", path) 取版本库快照，用 worktreeFileContent(path) 取工作区当前内容。
 */
    fun loadWorktreeDiff(path: String) {
        loadDiff(path, "HEAD", "工作区") { ref, p ->
            if (ref == "工作区") repository.worktreeFileContent(p)
            else repository.showFileContent(ref, p)
        }
    }

    /** 加载某文件的暂存区差异（HEAD vs index）。 */
    fun loadStagedDiff(path: String) {
        loadDiff(path, "HEAD", "暂存区") { ref, p ->
            if (ref == "暂存区") repository.indexFileContent(p)
            else repository.showFileContent(ref, p)
        }
    }

    private fun loadDiff(
        path: String,
        oldRef: String,
        newRef: String,
        contentProvider: suspend (String, String) -> String
    ) {
        openDiff(path)
        viewModelScope.launch {
            val data = runCatching {
                computeDiff(path, oldRef, newRef, contentProvider)
            }.getOrElse { e ->
                FileLogger.e(TAG, "加载 diff 失败: $path", e)
                null
            }
            finishDiff(data)
        }
    }

    /** 进入 diff 全屏页（页内加载中），已有 diff 页打开时忽略。 */
    private fun openDiff(path: String) {
        if (_state.value.diffVisible) return
        _state.update { it.copy(diffVisible = true, diffPath = path, diffData = null) }
    }

    /** diff 计算完成：成功填数据，失败关闭 diff 页并 toast。 */
    private fun finishDiff(data: DiffData?) {
        if (data == null) {
            _state.update { it.copy(diffVisible = false, diffData = null, toast = context.getString(R.string.git_toast_diff_failed)) }
        } else {
            _state.update { it.copy(diffData = data) }
        }
    }

    /**
     * 取旧/新两份文件内容，用 [LineDiff] 算行级差异，对每行做语法高亮，组装成 [DiffData]。
 * 二进制文件（含 NUL 字节）或超大文件（任一侧超 [MAX_DIFF_LINES]）降级处理。
 * [contentProvider] 负责按 ref 取文件内容，供提交 diff 和工作区 diff 复用。
 */
    private suspend fun computeDiff(
        path: String,
        oldRef: String,
        newRef: String,
        contentProvider: suspend (String, String) -> String
    ): DiffData = withContext(Dispatchers.Default) {
        val oldContent = contentProvider(oldRef, path)
        val newContent = contentProvider(newRef, path)

        // 二进制检测：git show 对二进制文件返回乱码，直接看是否含 NUL。
        if (oldContent.contains('\u0000') || newContent.contains('\u0000')) {
            return@withContext DiffData(path, oldRef, newRef, emptyList(), 0, 0, isBinary = true)
        }

        val oldLines = oldContent.split('\n')
        val newLines = newContent.split('\n')
        if (maxOf(oldLines.size, newLines.size) > MAX_DIFF_LINES ||
            oldLines.any { it.length > MAX_DIFF_LINE_LENGTH } ||
            newLines.any { it.length > MAX_DIFF_LINE_LENGTH }
        ) {
            return@withContext DiffData(path, oldRef, newRef, emptyList(), 0, 0, isLarge = true)
        }

        val diffLines = LineDiff.diff(oldContent, newContent)
        val language = inferSyntaxLanguage(path)

        // 对旧/新文件整体各跑一次高亮，拿到全文的 token 区间；渲染时按行偏移截取对应 SpanStyle。
        // 语法主题跟随当前 UI 主题，避免深色模式下 light 主题的 token 色（如 = 和 {}）看不清。
        // 两侧并行算（高亮是 diff 耗时主体），整体已在 Default 线程池，不碰主线程。
        val darkMode = currentDarkMode()
        val (oldHighlighted, newHighlighted) = coroutineScope {
            val old = async { highlightCode(oldContent, language, darkMode) }
            val new = async { highlightCode(newContent, language, darkMode) }
            old.await() to new.await()
        }

        // 构建行号 + 高亮截取。oldLineOffsets/newLineOffsets 为每行在全文中的起始偏移。
        val oldOffsets = lineOffsets(oldContent)
        val newOffsets = lineOffsets(newContent)

        var oldNum = 0
        var newNum = 0
        val rows = diffLines.map { line ->
            val (oldN, newN) = when (line.type) {
                LineDiff.LineType.CONTEXT -> { oldNum++; newNum++; oldNum to newNum }
                LineDiff.LineType.REMOVE -> { oldNum++; oldNum to null }
                LineDiff.LineType.ADD -> { newNum++; null to newNum }
            }
            // 截取该行在高亮全文中的 SpanStyle。行索引 = 行号 - 1（0-based）。
            val highlighted = when (line.type) {
                LineDiff.LineType.REMOVE -> sliceLineHighlight(oldHighlighted, oldOffsets, oldN?.let { it - 1 })
                LineDiff.LineType.ADD -> sliceLineHighlight(newHighlighted, newOffsets, newN?.let { it - 1 })
                LineDiff.LineType.CONTEXT -> sliceLineHighlight(oldHighlighted, oldOffsets, oldN?.let { it - 1 })
            }
            DiffRow(line.type, line.text, highlighted, oldN, newN)
        }

        val added = rows.count { it.type == LineDiff.LineType.ADD }
        val removed = rows.count { it.type == LineDiff.LineType.REMOVE }
        return@withContext DiffData(path, oldRef, newRef, rows, added, removed)
    }

    /**
     * 当前实际深色模式：AUTO 跟随系统，与 MainActivity 里 AIEditorTheme 的 darkTheme 判定保持一致。
     */
    private suspend fun currentDarkMode(): Boolean {
        val themeMode = themeSettings.themeModeFlow.first()
        val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return when (themeMode) {
            AppThemeMode.AUTO -> systemDark
            AppThemeMode.DARK -> true
            AppThemeMode.LIGHT -> false
        }
    }

    /**
     * 从全文高亮 AnnotatedString 中截取某行的 SpanStyle，返回只含该行文本+样式的新 AnnotatedString。
 * lineIndex 超出范围或高亮为 null 时返回 null（降级纯文本）。
 */
    private fun sliceLineHighlight(
        highlighted: androidx.compose.ui.text.AnnotatedString?,
        offsets: IntArray,
        lineIndex: Int?
    ): androidx.compose.ui.text.AnnotatedString? {
        if (highlighted == null || lineIndex == null || lineIndex < 0 || lineIndex >= offsets.size) return null
        val start = offsets[lineIndex]
        val end = if (lineIndex + 1 < offsets.size) offsets[lineIndex + 1] - 1 else highlighted.length
        if (start >= highlighted.length || end <= start) return null
        return highlighted.subSequence(start, end)
    }

    /** 计算每行在全文中的起始偏移（含末行后的虚拟偏移 = 全文长度）。 */
    private fun lineOffsets(text: String): IntArray {
        val lines = text.split('\n')
        val offsets = IntArray(lines.size + 1)
        var pos = 0
        for (i in lines.indices) {
            offsets[i] = pos
            pos += lines[i].length + 1 // +1 为换行符
        }
        offsets[lines.size] = pos
        return offsets
    }
}
