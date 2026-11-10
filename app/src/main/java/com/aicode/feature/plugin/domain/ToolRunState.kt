package com.aicode.feature.plugin.domain

/** 插件工具一次运行的阶段。 */
enum class ToolRunPhase { IDLE, RUNNING, SUCCESS, FAILED, CANCELLED }

/** 工具详情页运行面板的状态快照。 */
data class ToolRunState(
    val phase: ToolRunPhase = ToolRunPhase.IDLE,
    val lines: List<String> = emptyList(),
    val exitCode: Int? = null,
    val tabId: String? = null,
    val command: String? = null
)
