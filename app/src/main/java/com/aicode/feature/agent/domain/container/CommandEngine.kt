package com.aicode.feature.agent.domain.container

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** 流式执行命令时产生的事件，供终端逐行实时渲染。 */
sealed interface CommandEvent {
    /** 一行标准输出/错误（stderr 已合并到 stdout）。 */
    data class Line(val text: String) : CommandEvent
    /** 命令结束，附退出码（兜底 shell 无法取到时为 null）。 */
    data class Exit(val code: Int?) : CommandEvent
}

/** 一次命令执行的结果：限幅后的完整输出 + 退出码（超时/异常时为 null）。 */
data class CommandResult(val output: String, val exitCode: Int?)

/**
 * 命令执行后端抽象：把"在哪执行命令"从硬编码的本地 PRoot 解耦。
 *
 * 两套实现：
 * - [LinuxContainerEngine]：本地 PRoot 容器，原有逻辑零变化；
 * - `RemoteSshEngine`：远程 SSH 服务器，用 sshj exec channel 执行。
 *
 * 工具层（[com.aicode.feature.agent.domain.tool.container.ExecuteCommandTool]、
 * [com.aicode.feature.agent.domain.tool.explorer.SearchCodeTool]、
 * [com.aicode.feature.git.domain.GitRepository]）依赖本接口而非具体实现，
 * 由 DI 按当前执行模式注入对应实例。
 *
 * PRoot 专属方法（[LinuxContainerEngine.startStdioProcess]、
 * [LinuxContainerEngine.buildProotInvocation]、[LinuxContainerEngine.incPromptInFlight] 等）
 * 不属于本接口——它们仅供本地 MCP stdio / 凭据 helper / 终端 PTY 使用，远程模式下仍走本地。
 */
interface CommandEngine {

    /** 执行后端初始化进度，供 UI 实时展示。远程模式下通常恒为 [ContainerInitState.Ready]。 */
    val initProgress: StateFlow<ContainerInitState>

    /**
     * 流式执行命令：每读到一行就 emit 一个 [CommandEvent.Line]，命令结束 emit [CommandEvent.Exit]。
     * 首次调用可能触发后端初始化（本地会解压 rootfs）。
     *
     * [timeoutMs] 为命令最长执行时间（毫秒），超时后强制终止并追加超时提示。
     */
    fun runCommandStream(
        command: String,
        projectPath: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Flow<CommandEvent>

    /**
     * 同步执行命令并返回输出。首次调用可能触发后端初始化。
     * [timeoutMs] 为命令最长执行时间（毫秒），超时后强制终止，返回已收集的部分输出。
     */
    suspend fun runCommandSync(
        command: String,
        projectPath: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): String

    /**
     * 同 [runCommandSync]，但一并返回退出码（超时/异常时为 null）。
     * 供需要据退出码判成败的调用方使用（如 git 写操作）。
     */
    suspend fun runCommandSyncWithExit(
        command: String,
        projectPath: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): CommandResult

    /**
     * 同 [runCommandSyncWithExit]，但输出**不做限幅截断**（返回完整输出）。
     * 供需要完整文本的调用方使用（如 git diff 页读取 diff/文件内容）：
     * AI 工具链路的默认限幅（[BoundedOutput] 头尾各 2 万字符）会把截断占位符
     * 混入 diff 数据流，导致 UI 渲染出伪 diff 行。调用方须自行对超大输出兜底
     * （如 diff 页的 2000 行/行长保护）。
     */
    suspend fun runCommandSyncUnbounded(
        command: String,
        projectPath: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): CommandResult

    /**
     * 仅在后端已就绪时执行命令；不会触发初始化（不解压 rootfs / 不建 SSH 连接）。
     * 未就绪时返回 null，让调用方走 fallback。供只读工具做性能增强使用（如 search 优先用 rg）。
     */
    suspend fun runCommandSyncIfReady(
        command: String,
        projectPath: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): CommandResult?

    /** 后端是否已安装就绪。本地按 rootfs/proot 判断；远程按 SSH 连接是否存活判断。 */
    fun isContainerInstalled(): Boolean

    /** 基础包是否已配置完成。本地按 provision 标记判断；远程恒 true（工具由用户自行保证）。 */
    fun isProvisioned(): Boolean

    /** 默认命令 shell 路径。本地按 provision 状态选 bash/sh；远程通常为 /bin/bash。 */
    fun defaultShell(): String

    /**
     * 后端是否已就绪可执行命令。null 表示就绪；非 null 为未就绪的原因提示，
     * 由调用方展示给用户并引导其完成初始化（本地为「请先进入终端页面初始化」）。
     *
     * 默认实现恒返回 null（远程 SSH 的连接失败由首次命令自动重试处理，不属此类）。
     * [LinuxContainerEngine] 覆写为按 rootfs/provision 状态返回引导文案。
     */
    fun notReadyHint(): String? = null

    /**
     * 幂等地确保后端可用：本地会解压 rootfs/proot 并配置基础包（首次耗时）；
     * 远程则建立 SSH 连接。仅由终端页（唯一初始化入口）调用，命令执行入口不再自动触发。
     */
    suspend fun ensureInstalled()

    companion object {
        /** 命令默认超时（毫秒）。 */
        const val DEFAULT_TIMEOUT_MS = 120_000L

        /** 命令超时上限（毫秒）。 */
        const val MAX_TIMEOUT_MS = 1_800_000L
    }
}

/**
 * 日志打印前对命令串脱敏：命令可能带 env 前缀（如面板脚本的 `AICODE_PROVIDER_API_KEY=...`）
 * 或内联凭据（`--header "Authorization: Bearer ..."`）。按敏感键名把值打码为 `***`。
 */
private val SENSITIVE_COMMAND_VALUE_REGEX = Regex(
    """(?i)(?<![A-Za-z0-9])(api[_-]?key|access[_-]?token|auth[_-]?token|token|password|passwd|secret|authorization|auth[_-]?cookie|cookie)\b\s*([=:])\s*(['"]?)(?:Bearer\s+|Basic\s+)?[^\s'"]+"""
)

fun sanitizeCommandForLog(command: String): String =
    SENSITIVE_COMMAND_VALUE_REGEX.replace(command) { m ->
        m.groupValues[1] + m.groupValues[2] + m.groupValues[3] + "***"
    }
