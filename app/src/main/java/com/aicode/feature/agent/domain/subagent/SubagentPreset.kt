package com.aicode.feature.agent.domain.subagent

import kotlinx.serialization.Serializable

/**
 * 一个子代理预设：创建子代理时可复用的「模型 + 模式提醒」组合。
 *
 * 用户在设置页「子代理模型」二级页中自由增删排序（子代理1、子代理2…），
 * AI 批量创建子代理且未显式指定模型时，按任务顺序自动依次取预设列表；也可通过
 * preset 参数显式指定某个预设（按 name 匹配）。预设列表为空时回退主会话模型。
 */
@Serializable
data class SubagentPreset(
    val id: String,
    /** 预设显示名（如「子代理1」），task 显式指定时按此匹配。 */
    val name: String,
    /** 该预设使用的 AI provider id；为空回退主会话。 */
    val providerId: String? = null,
    /** 该预设使用的模型名；为空回退主会话。 */
    val model: String? = null,
    /** 该预设自带的模式提醒（内置 key AUTO/PLAN 或自定义文本），注入子代理首条消息末尾。 */
    val modeReminders: List<String> = emptyList()
)