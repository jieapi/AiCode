package com.aicode.feature.agent.domain.tool.root

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.BoundedOutput
import com.aicode.feature.agent.domain.root.RootManager
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

        /**
         * 容器专属路径特征。命中即在结果后追加提示：Root 作用于宿主，这些路径在宿主上
         * 不存在（或指向 rootfs 内的空占位目录），AI 多半是想操作容器工作区。
         */
        val CONTAINER_PATH_REGEX = Regex(
            """(?:^|[\s"'=;&|(`])(~/.aicode|~/workspace|/root/workspace|/root/.aicode|/root/.config)"""
        )
    }

    override val name = "Root"

    override val description =
        "以 root（uid 0）身份在 Android 宿主系统上执行 Shell 命令（真机视角，不是容器内）。" +
            "⚠️ 路径与 `Bash` 不同：`Bash`/`readFile`/`writeFile`/`terminal` 运行在 Linux 容器内，" +
            "它们看到的 `~/workspace`、`/etc`、`/root` 都是容器内路径；" +
            "`Root` 作用于宿主真机，看到的是真实的 `/data`、`/system`、`/sdcard`。" +
            "宿主的 App 私有目录为 `/data/user/0/<包名>/files/`，其中 `projects/<项目名>/` 是工作区、" +
            "`aicode/` 是 AI 配置、`rootfs/` 是容器根文件系统。" +
            "相比 `Shizuku`（adb shell，uid 2000），root 还可访问 `/data/data`、`/data/adb` 等受限目录。" +
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

    /**
     * 若命令里出现容器专属路径，返回一段纠正提示（否则返回空串）。
     *
     * 只提示、不改写命令：自动翻译路径一旦判断错会静默写错位置，比报错更糟。
     */
    private fun containerPathWarning(command: String): String {
        val hit = CONTAINER_PATH_REGEX.find(command)?.groupValues?.get(1) ?: return ""
        return buildString {
            append("\n\n[Root 路径提示] 命令中出现容器路径「")
            append(hit)
            append("」。`Root` 直接作用于宿主 Android，该路径在宿主上不存在（或指向 rootfs 内的空占位目录）。")
            append("\n")
            append(rootManager.hostPathHint())
            append("\n若目标是容器工作区文件，请改用 `Bash` / `readFile` / `writeFile`（它们才在容器内）。")
        }
    }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val command = args["command"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("缺少必需参数: command")

        // 刻意不做 state 门禁：state 只是缓存（App 启动/进程重建后为初值），拿它拒绝执行会
        // 造成「明明已授权却报未 root」。这里一律真实执行一次，由 root 管理器在需要时弹窗授权，
        // 执行结果再反过来校正 state（见 RootManager.updateStateFromResult）。
        // 状态未知时顺手在后台补探测，供设置页显示。
        rootManager.probeInBackgroundIfUnknown()

        return try {
            val timeoutMs = resolveTimeoutMs(args)
            FileLogger.d(TAG, "Root exec (timeout=${timeoutMs}ms): $command")
            val result = rootManager.runCommand(command, timeoutMs)
            val output = BoundedOutput().apply { append(result.output) }.build()
            FileLogger.v(TAG, "Root exec 完成，输出 ${result.output.length} 字符，退出码 ${result.exitCode}")
            val denialHint = if (result.exitCode != 0 && output.isBlank()) {
                "\n\n[Root 提示] 命令没有输出且退出码非 0（${result.exitCode}），" +
                    "通常是 root 授权被拒（请在 root 管理器弹窗中选择「允许」，或在其应用列表中放开本应用）。"
            } else {
                ""
            }
            ToolResult.Success(JsonPrimitive(output + containerPathWarning(command) + denialHint))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "Root exec 失败: $command", e)
            ToolResult.Error(
                "执行 Root 命令失败: ${e.message}\n" +
                    "（若提示未检测到 su：设备可能未 root；若设备确实已 root，请确认 root 管理器未禁用/隐藏 su）",
                code = "ROOT_EXEC_FAILED"
            )
        }
    }
}