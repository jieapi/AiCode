package com.aicode.feature.agent.domain.model

import com.aicode.feature.workspace.domain.model.WorkspaceRef

/**
 * Agent 的工作模式。
 */
enum class AgentMode {
    BUILD, // 默认模式，允许所有授权操作
    PLAN,  // 计划模式，拦截修改类操作，只读/探索为主
    AUTO;  // 自动模式，放行所有权限（不弹窗），仅用户手动可切换；AI 无法通过 planMode 进入

    /** 权限宽松度：数值越大越宽。用于把子会话的权限压到不超过父会话。 */
    val permissiveness: Int
        get() = when (this) {
            PLAN -> 0
            BUILD -> 1
            AUTO -> 2
        }

    /**
     * 把子会话期望的模式收敛到「不比 [parent] 更宽」的范围。
     *
     * 子代理必须继承父会话的权限边界：PLAN 下派出一个定义里写了 `mode: build` 的子代理，
     * 就能绕过只读限制去改文件——业界（Claude Code / Codex）都把「子代理不继承」视为安全漏洞。
     * 定义里的 mode 只允许往更严的方向收（如 AUTO 父会话派一个 PLAN 子代理），不允许放大。
     */
    fun limitedBy(parent: AgentMode): AgentMode =
        if (permissiveness <= parent.permissiveness) this else parent
}

/**
 * 会话级持续目标（goal）的生命周期状态；[goal] 为 null 时无意义。
 * ACTIVE 表示自主循环进行中；其余为停止态。
 */
enum class GoalStatus {
    ACTIVE,    // 自主循环进行中
    PAUSED,    // 用户暂停
    COMPLETED, // AI 判定目标已达成
    BLOCKED,   // 受阻，需用户介入
    EXHAUSTED  // 轮次或 token 预算耗尽
}

/**
 * 一次独立的聊天会话。消息通过 sessionId 归属到会话，切换会话即切换聊天历史。
 */
data class ChatSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val workspacePath: String = "",
    val mode: AgentMode = AgentMode.BUILD,
    /** 进入 PLAN 前的模式（如 AUTO）；退出 PLAN 时恢复，null 视为 BUILD。 */
    val modeBeforePlan: AgentMode? = null,
    /** 会话级持续目标文本；null 表示未设定目标。 */
    val goal: String? = null,
    /** goal 生命周期状态；[goal] 为 null 时无意义。 */
    val goalStatus: GoalStatus? = null,
    /** goal 自主循环已执行的续跑轮数（用于轮次上限）。 */
    val goalTurnCount: Int = 0,
    /** 设定 goal 时本会话累计输入 token 快照，用于计算 goal 期间的 token 消耗。 */
    val goalStartInputTokens: Int = 0,
    /** 设定 goal 时本会话累计输出 token 快照。 */
    val goalStartOutputTokens: Int = 0,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.MEDIUM,
    val providerId: String? = null,
    val model: String? = null,
    val totalInputTokens: Int = 0,
    val totalOutputTokens: Int = 0,
    val lastInputTokens: Int = 0,
    val isPinned: Boolean = false,
    /** 子代理会话：父会话 id；null 表示普通根会话。 */
    val parentId: String? = null,
    /** 子代理会话：派生子代理的类型（如 coder / researcher）；null 表示普通根会话。 */
    val subagentType: String? = null,
    /** 会话绑定的工作流 ID；null 表示使用默认内置工作流。 */
    val workflowId: String? = null,
    /** 会话绑定的工作流持久化状态；null 表示无状态。 */
    val workflowState: String? = null,
    val workspaceRef: WorkspaceRef? = null
)
