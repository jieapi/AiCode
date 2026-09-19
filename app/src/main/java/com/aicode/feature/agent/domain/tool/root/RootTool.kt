package com.aicode.feature.agent.domain.tool.root

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.BoundedOutput
import com.aicode.feature.agent.domain.root.RootManager
import com.aicode.feature.agent.domain.root.RootState
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject

/**
 * 以 root（uid 0）身份执行命令的工具。
 *
 * 与 [com.aicode.feature.agent.domain.tool.shizuku.ShizukuTool]（adb shell，uid 2000）并列：
 * 二者都直接作用于宿主 Android 系统，但 root 具备 shell 身份没有的权限——
 * 可访问 `/data/data`、`/data/adb` 等受限目录，执行需要 uid 0 的系统操作。
 *
 * 需设备已 root 且用户授权（授权由 root 管理器弹窗完成），否则返回错误提示。
 */
class RootTool @Inject constructor(
    private val rootManager: RootManager
) : AgentTool() {
    private companion object {
        const val TAG = "RootTool"

        const val DEFAULT_TIMEOUT_SECONDS = 120L
        const val MAX_TIMEOUT_SECONDS = 1_800L
    }

    override val name = "Root"

    override val description =
        "以 root（uid 0）身份在 Android 系统上执行 Shell 命令。" +
            "相比 `Shizuku`（adb shell，uid 2000），root 可访问系统受限目录并执行需要超级用户权限的操作：" +
            "读写 `/data/data`、`/data/adb`，修改系统属性，管理其他应用等。" +
            "与 `Bash`（在本地容器或远程 SSH 中执行）不同，它直接作用于宿主 Android 系统本身。" +
            "使用前设备需已 root 并在弹出授权框时允许，未就绪时会返回错误提示。"

    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.EXECUTE_COMMANDS)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "command" to ToolParameter(
            name = "command",
            type = ParameterType.STRING,
            description = "要以 root 身份执行的 Shell 命令",
            required = true
        ),
        "timeout" to ToolParameter(
            name = "timeout",
            type = ParameterType.INTEGER,
            description = "命令最长执行时间（秒），超时将被强制终止。默认 $DEFAULT_TIMEOUT_SECONDS 秒，上限 $MAX_TIMEOUT_SECONDS 秒。",
            required = false
        )
    )

    private fun resolveTimeoutMs(args: Map<String, JsonElement>): Long {
        val seconds = args["timeout"]?.jsonPrimitive?.longOrNull ?: DEFAULT_TIMEOUT_SECONDS
        return seconds.coerceIn(1L, MAX_TIMEOUT_SECONDS) * 1000L
    }

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val command = args["command"]?.jsonPrimitive?.contentOrNull ?: "未知命令"
        val timeoutSeconds = resolveTimeoutMs(args) / 1000L
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "确认执行 Root 命令",
            summary = command,
            details = "将以 root（uid 0）身份在 Android 系统上执行，权限高于 adb shell。\n超时：${timeoutSeconds} 秒",
            argsPreview = argsPreview
        )
    }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val command = args["command"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("缺少必需参数: command")

        // UNAVAILABLE 时提前失败，避免无谓地起子进程；DENIED 仍尝试执行，
        // 因为状态可能是未探测时的初值，首次调用会触发 root 管理器弹框重新授权。
        if (rootManager.state.value == RootState.UNAVAILABLE) {
            return ToolResult.Error(
                "未检测到 root：设备可能未 root，或 root 方案未提供 su",
                code = "ROOT_NOT_AVAILABLE"
            )
        }

        return try {
            val timeoutMs = resolveTimeoutMs(args)
            FileLogger.d(TAG, "Root exec (timeout=${timeoutMs}ms): $command")
            val result = rootManager.runCommand(command, timeoutMs)
            val output = BoundedOutput().apply { append(result.output) }.build()
            FileLogger.v(TAG, "Root exec 完成，输出 ${result.output.length} 字符，退出码 ${result.exitCode}")
            ToolResult.Success(JsonPrimitive(output))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "Root exec 失败: $command", e)
            ToolResult.Error("执行 Root 命令失败: ${e.message}")
        }
    }
}