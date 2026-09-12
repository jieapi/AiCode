package com.aicode.feature.agent.domain.model

/**
 * Agent 的工作模式。
 */
enum class AgentMode {
    BUILD, // 默认模式，允许所有授权操作
    PLAN,  // 计划模式，拦截修改类操作，只读/探索为主
    AUTO,  // 自动模式，放行所有权限（不弹窗），仅用户手动可切换；AI 无法通过 switchMode 进入
    TARGET // 目标驱动模式，AI 依据 goalStatement 自主执行，达成或失败即终止；免逐步弹窗授权（等同 AUTO + 灾难防护）
}

/**
 * TARGET 模式的目标终止原因。
 */
enum class GoalTerminationReason {
    ACHIEVED,    // 目标达成（completeGoal 工具触发）
    FAILED,      // 连续失败达阈值
    STEP_LIMIT,  // 工具调用步数达上限
    INTERRUPTED  // 用户手动切换模式中断
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
    /** TARGET 模式的目标声明；null 表示非 TARGET 模式或未设定。 */
    val goalStatement: String? = null,
    /** TARGET 模式终止原因（GoalTerminationReason.name）；null 表示未终止或非 TARGET 模式。 */
    val goalTerminationReason: String? = null,
    /** 当前 TARGET 执行已产生的工具调用步数。 */
    val goalStepCount: Int = 0,
    /** 当前 TARGET 执行的连续工具失败计数。 */
    val goalFailCount: Int = 0
)
