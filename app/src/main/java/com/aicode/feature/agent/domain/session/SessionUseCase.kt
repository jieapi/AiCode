package com.aicode.feature.agent.domain.session

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.model.GoalStatus
import com.aicode.feature.agent.domain.model.ReasoningEffort
import com.aicode.feature.agent.presentation.MessageRole
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionUseCase @Inject constructor(
    private val chatSessionDao: ChatSessionDao,
    private val agentMessageDao: AgentMessageDao
) {
    companion object {
        private const val TAG = "SessionUseCase"
        const val TITLE_MAX = 20
        /** 工具占位行前缀：标记「执行中、结果未回」的孤儿，UI 与回放据此识别。 */
        const val PENDING_TOOL_MARKER = "[running]"
        /** 历史版本 emoji 前缀；冷启动收尾与回放仍需识别。 */
        const val LEGACY_PENDING_TOOL_MARKER = "\u23F3"
        /** 历史版本停止 emoji 前缀；UI 剥离结果文本时兼容。 */
        const val LEGACY_STOPPED_TOOL_MARKER = "\u23F9"
        const val INTERRUPTED_TOOL_TEXT = "执行被中断（应用已关闭）"
    }

    /** 冷启动收尾：上次进程被杀时若有工具正在执行，其占位行会永久显示「执行中」。 */
    suspend fun initColdStartCleanup() {
        runCatching {
            val n = listOf(PENDING_TOOL_MARKER, LEGACY_PENDING_TOOL_MARKER).sumOf { marker ->
                agentMessageDao.markPendingToolsInterrupted(
                    toolRole = MessageRole.TOOL.name,
                    pendingPrefix = "$marker%",
                    interruptedContent = INTERRUPTED_TOOL_TEXT
                )
            }
            if (n > 0) FileLogger.i(TAG, "冷启动收尾 $n 条残留「执行中」工具行为已中断")
        }.onFailure { FileLogger.e(TAG, "回收残留执行中工具行失败", it) }
    }

    fun newSessionEntity(
        workspacePath: String,
        providerId: String? = null,
        model: String? = null,
        reasoningEffort: String = ReasoningEffort.MEDIUM.name
    ): ChatSessionEntity {
        val now = System.currentTimeMillis()
        return ChatSessionEntity(
            id = UUID.randomUUID().toString(),
            title = "新会话",
            workspacePath = workspacePath,
            createdAt = now,
            updatedAt = now,
            providerId = providerId,
            model = model,
            reasoningEffort = reasoningEffort
        )
    }

    fun deriveTitle(request: String): String {
        val clean = request.trim().replace(Regex("\\s+"), " ")
        return if (clean.length <= TITLE_MAX) clean.ifBlank { "新对话" }
        else clean.take(TITLE_MAX) + "…"
    }

    /** 删除会话及其全部子代理会话（消息 + 会话记录一并删除）。返回被删除的会话 id 列表。 */
    suspend fun deleteSession(id: String): List<String> {
        val deleted = mutableListOf(id)
        // 递归收集子会话（v1 仅一层，循环即可）
        chatSessionDao.getSubSessionsByParentOnce(id).forEach { child ->
            agentMessageDao.deleteBySession(child.id)
            chatSessionDao.delete(child.id)
            deleted.add(child.id)
        }
        agentMessageDao.deleteBySession(id)
        chatSessionDao.delete(id)
        return deleted
    }

    /** 删除某工作区下的全部会话（含子代理会话）及其消息，返回删除的会话数。 */
    suspend fun deleteSessionsByWorkspace(workspacePath: String): Int {
        val sessions = chatSessionDao.getAllSessionsByWorkspaceOnce(workspacePath)
        if (sessions.isEmpty()) return 0
        sessions.forEach { session -> agentMessageDao.deleteBySession(session.id) }
        chatSessionDao.deleteByWorkspace(workspacePath)
        return sessions.size
    }

    suspend fun getFirstSessionOfWorkspace(workspacePath: String): ChatSessionEntity? {
        return chatSessionDao.getRootSessionsByWorkspaceOnce(workspacePath).firstOrNull()
    }

    /** 工作区里最近更新的根会话（忽略置顶），供启动时进入最近会话。 */
    suspend fun getMostRecentSessionOfWorkspace(workspacePath: String): ChatSessionEntity? {
        return chatSessionDao.getLatestRootSessionByWorkspace(workspacePath)
    }

    /** 工作区下可复用的空会话（从未发送过消息的根会话，取最近更新的一个），供「新建会话」直接切过去。 */
    suspend fun getEmptySessionOfWorkspace(workspacePath: String): ChatSessionEntity? {
        return chatSessionDao.getEmptyRootSessionByWorkspace(workspacePath)
    }

    /** 回收工作区下多余的空会话（从未发送过消息），保留 [keepId]；保证列表最多一个空会话。 */
    suspend fun recycleEmptySessions(workspacePath: String, keepId: String? = null): Int {
        var count = 0
        chatSessionDao.getRootSessionsByWorkspaceOnce(workspacePath).forEach { session ->
            if (session.id != keepId && !agentMessageDao.hasMessages(session.id)) {
                deleteSession(session.id)
                count++
            }
        }
        return count
    }

    /**
     * 创建子代理会话，继承父会话的 provider/model/reasoningEffort/workspacePath。
     * @param title 子会话标题（由 task 描述派生）
     * @param parentId 父会话 id
     * @param subagentType 子代理类型 id（自定义 agent 名，或默认通用子代理标识）
     * @param providerId 覆盖 provider；null 表示继承父会话
     * @param model 覆盖模型；null 表示继承父会话
     * @param reasoningEffort 覆盖思考强度；null 表示继承父会话
     * @param mode 覆盖运行模式；null 表示继承父会话（只能往更严的方向收，不能放大，见 [resolveChildMode]）
     */
    fun newSubSessionEntity(
        title: String,
        parentId: String,
        parent: ChatSessionEntity,
        subagentType: String,
        providerId: String? = null,
        model: String? = null,
        reasoningEffort: String? = null,
        mode: AgentMode? = null
    ): ChatSessionEntity {
        val now = System.currentTimeMillis()
        return ChatSessionEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            workspacePath = parent.workspacePath,
            createdAt = now,
            updatedAt = now,
            // 默认继承父会话 mode：权限引擎按会话 mode 判定写拦截，默认 BUILD 会让 PLAN
            // 模式下派出的子代理绕过只读限制，反过来 AUTO 模式的子代理又会弹授权窗打扰用户。
            mode = resolveChildMode(mode, parent.mode),
            providerId = providerId ?: parent.providerId,
            model = model ?: parent.model,
            reasoningEffort = reasoningEffort ?: parent.reasoningEffort,
            parentId = parentId,
            subagentType = subagentType
        )
    }

    /**
     * 子会话实际生效的模式：默认继承父会话；自定义子代理的定义可覆盖，但只能更严、不能更宽。
     *
     * 若允许放大，PLAN 下派一个 `mode: build` 的子代理就能直接改文件——这正是业界把
     * 「子代理不继承父权限」列为安全漏洞的原因。父会话 mode 值异常时按继承处理。
     */
    internal fun resolveChildMode(requested: AgentMode?, parentMode: String): String {
        val parent = runCatching { AgentMode.valueOf(parentMode) }.getOrNull() ?: return parentMode
        return (requested ?: parent).limitedBy(parent).name
    }

    suspend fun upsertSession(entity: ChatSessionEntity) {
        chatSessionDao.upsert(entity)
    }

    suspend fun updateTitle(sessionId: String, title: String) {
        chatSessionDao.updateTitle(sessionId, title)
    }

    suspend fun updatePinned(sessionId: String, pinned: Boolean) {
        chatSessionDao.updatePinned(sessionId, pinned)
    }

    suspend fun touch(sessionId: String, timestamp: Long) {
        chatSessionDao.touch(sessionId, timestamp)
    }

    suspend fun getSessionById(id: String): ChatSessionEntity? {
        return chatSessionDao.getById(id)
    }

    suspend fun updateMode(sessionId: String, mode: String) {
        val s = chatSessionDao.getById(sessionId) ?: return
        val newMode = runCatching { AgentMode.valueOf(mode) }.getOrNull() ?: return
        val currentMode = runCatching { AgentMode.valueOf(s.mode) }.getOrNull()
        // 用户手动进入 PLAN 时同样记下进入前的模式，退出 PLAN 时恢复（与 AI 自切保持同一套语义）。
        val modeBeforePlan = when {
            newMode == AgentMode.PLAN && currentMode != AgentMode.PLAN -> currentMode?.name
            newMode == AgentMode.PLAN -> s.modeBeforePlan
            else -> null
        }
        chatSessionDao.upsert(s.copy(mode = newMode.name, modeBeforePlan = modeBeforePlan))
    }

    suspend fun updateProviderModel(sessionId: String, providerId: String?, model: String?) {
        chatSessionDao.updateProviderModel(sessionId, providerId, model)
    }

    suspend fun updateReasoningEffort(sessionId: String, effort: String) {
        chatSessionDao.updateReasoningEffort(sessionId, effort)
    }

    suspend fun updateWorkflow(id: String, workflowId: String?, workflowState: String? = null) {
        chatSessionDao.updateWorkflow(id, workflowId, workflowState)
    }

    suspend fun updateWorkflowState(id: String, workflowState: String?) {
        chatSessionDao.updateWorkflowState(id, workflowState)
    }

    /**
     * 设定/覆盖会话 goal：重置轮次计数，并记录当前累计 token 作为消耗基线，状态置 ACTIVE。
     * [goal] 为 null/空白则清除目标。
     */
    suspend fun setGoal(sessionId: String, goal: String?, startInputTokens: Int, startOutputTokens: Int) {
        val trimmed = goal?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmed == null) {
            chatSessionDao.updateGoal(sessionId, null, null, 0, 0)
        } else {
            chatSessionDao.updateGoal(sessionId, trimmed, GoalStatus.ACTIVE.name, startInputTokens, startOutputTokens)
        }
    }

    suspend fun updateGoalStatus(sessionId: String, status: GoalStatus?) {
        chatSessionDao.updateGoalStatus(sessionId, status?.name)
    }

    suspend fun incrementGoalTurn(sessionId: String) {
        chatSessionDao.incrementGoalTurn(sessionId)
    }

    suspend fun isSessionEmpty(sessionId: String): Boolean {
        return !agentMessageDao.hasMessages(sessionId)
    }
}
