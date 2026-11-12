package com.aicode.feature.agent.domain.tool.container

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.StreamingAgentTool
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.domain.tool.ToolStreamEvent
import com.aicode.feature.terminal.domain.TerminalSessionProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * 取出 JSON 字符串参数的「真实内容」。
 *
 * 注意：不能用 `toString().removeSurrounding("\"")`：[toString] 返回 JSON 序列化形式（外层带引号、
 * 内部引号被转义成 `\"`），剥掉外层引号后内部仍是字面 `\"`，会把 `echo "*.vue"` 变成
 * `echo \"*.vue\"` 导致 shell 把引号当字面输出。必须用 [jsonPrimitive]/[contentOrNull] 取解码后内容。
 */
private fun JsonElement.asPlainString(): String? =
    (this as? JsonPrimitive)?.contentOrNull

/**
 * 解析标签 id 列表参数：接受 JSON 数组（`["term-1","term-2"]`），
 * 也兼容模型偶尔传的逗号/分号/空白分隔字符串（`"term-1, term-2"`）。
 */
internal fun parseTabIds(element: JsonElement?): List<String> {
    val raw = when (element) {
        is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> element.contentOrNull?.split(',', ';', ' ', '\n', '\t').orEmpty()
        else -> emptyList()
    }
    return raw.map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * `read` 的 filter：按行保留匹配 [regex] 的行（[Regex.containsMatchIn]，即行内任意位置命中即可）。
 * 行分隔与 [String.lineSequence] 一致，兼容 `\n`、`\r\n`、`\r`。
 */
internal fun filterOutputLines(output: String, regex: Regex): List<String> =
    output.lineSequence().filter { regex.containsMatchIn(it) }.toList()

/**
 * 统一的后台终端会话工具：用一个 `action` 参数区分三种操作，把原先的
 * `run_background_command` / `send_terminal_input` / `read_terminal_output` 三个工具合一。
 *
 * - `start`：把长驻命令（如 `npm run dev`、服务进程）开在一个常驻后台终端标签里，立即返回 tab_id，
 *   不阻塞等待。命令进程持续运行，AI 之后凭返回的 id 用 send/read 继续。
 * - `send`：按 tab_id 向某个后台或交互终端发送一行命令/输入（默认自动回车执行），随后短暂捕获输出。
 * - `key`：按 tab_id 发送常见快捷键/控制字符（如 Ctrl-C、Ctrl-D、方向键）。
 * - `read`：按 tab_id 读取该终端当前的全部输出（含后台命令实时日志）；省略 tab_id 则列出所有标签；
 *   可用 `filter` 传正则，只返回匹配的行（适合从大段日志里挑关键信息）。
 * - `close`：按 tab_id 关闭某个终端标签；也可用 `tab_ids` 数组一次关闭多个。
 *
 * 授权：start 动作按 shell 命令前缀匹配（见 [com.aicode.feature.agent.domain.permission.ToolPermissionPolicyEngine]），
 * send 动作按输入内容匹配，key/read/close 按整工具匹配。
 */
class TerminalSessionTool @Inject constructor(
    private val sessionManager: TerminalSessionProvider
) : AgentTool(), StreamingAgentTool {
    private companion object {
        const val TAG = "TerminalSessionTool"

        /** start 启动后捕获初始输出的最长时间（毫秒）：命令若提前退出则立即返回，否则最多等满。 */
        const val START_CAPTURE_MS = 5_000L

        /** 轮询命令是否退出的间隔（毫秒）。 */
        const val START_POLL_INTERVAL_MS = 200L

        val SUPPORTED_KEYS = listOf(
            "ctrl+c",
            "ctrl+d",
            "ctrl+z",
            "ctrl+l",
            "ctrl+u",
            "ctrl+w",
            "esc",
            "tab",
            "enter",
            "up",
            "down",
            "left",
            "right"
        )
    }

    override val name = "terminal"
    override val description =
        "启动和管理当前执行环境（本地容器或远程 SSH）的持续终端会话，适合耗时任务（构建、安装依赖）、常驻服务或需要多次输入的交互命令。支持发送输入、快捷键和读取输出；会自行结束且需等待结果的任务用 start + notify=true，常驻或交互式任务按需 read/send/key。中断前台进程用 key=ctrl+c。read 可用 filter 正则按行筛选输出，close 可用 tab_ids 一次关闭多个标签。"
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.EXECUTE_COMMANDS)

    override fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> {
        return when (actionOf(args)) {
            "read" -> setOf(ToolCapability.READ_WORKSPACE)
            else -> capabilities
        }
    }

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "操作类型：start=启动后台命令；send=向终端发输入；key=发送快捷键；read=读终端输出（可用 filter 正则筛选）；close=关闭终端标签（可用 tab_ids 批量）",
            required = true,
            enum = listOf("start", "send", "key", "read", "close")
        ),
        "command" to ToolParameter(
            name = "command",
            type = ParameterType.STRING,
            description = "start 必填：在终端会话中启动的 Shell 命令，如构建命令或 npm run dev。",
            required = false
        ),
        "title" to ToolParameter(
            name = "title",
            type = ParameterType.STRING,
            description = "start 可选：标签显示名；不填则用 tab id",
            required = false
        ),
        "notify" to ToolParameter(
            name = "notify",
            type = ParameterType.BOOLEAN,
            description = "start 可选：耗时但会自行结束、且需要结果的任务设为 true，结束后通知 AI 并附退出码与末尾输出，无需轮询或 sleep 等待。start 只捕获约 5 秒初始输出。默认 false；常驻服务或交互式任务按需用 read 查看输出。",
            required = false
        ),
        "tab_id" to ToolParameter(
            name = "tab_id",
            type = ParameterType.STRING,
            description = "send/key/read/close 用：目标终端标签 id，如 'term-2'；read 省略则列出所有标签；close 可改用 tab_ids 批量关闭",
            required = false
        ),
        "tab_ids" to ToolParameter(
            name = "tab_ids",
            type = ParameterType.ARRAY,
            description = "close 可选：要关闭的终端标签 id 数组，可一次关闭多个，如 [\"term-1\",\"term-2\"]；与 tab_id 同时给时合并去重",
            required = false,
            itemsSchema = mapOf("type" to "string")
        ),
        "filter" to ToolParameter(
            name = "filter",
            type = ParameterType.STRING,
            description = "read 可选：按行筛选输出的正则表达式（Java 语法，区分大小写，可加 (?i) 忽略大小写），只返回匹配的行；无匹配时返回提示与原输出行数",
            required = false
        ),
        "input" to ToolParameter(
            name = "input",
            type = ParameterType.STRING,
            description = "send 必填：要发送的命令或文本",
            required = false
        ),
        "key" to ToolParameter(
            name = "key",
            type = ParameterType.STRING,
            description = "key 必填：要发送的快捷键/控制字符。常用：ctrl+c 中断前台进程，ctrl+d 发送 EOF，ctrl+z 挂起，esc/tab/enter/方向键用于交互程序",
            required = false,
            enum = SUPPORTED_KEYS
        ),
        "submit" to ToolParameter(
            name = "submit",
            type = ParameterType.BOOLEAN,
            description = "send 可选：是否在末尾追加回车以执行该命令，默认 true",
            required = false
        ),
        "elevate" to ToolParameter(
            name = "elevate",
            type = ParameterType.BOOLEAN,
            description = "start/send 的命令被安全防护拒绝且确需执行时，设为 true 重试，通过弹窗申请用户单次授权，不保存为长期规则。PLAN 模式不支持。",
            required = false
        )
    )

    private fun actionOf(args: Map<String, JsonElement>): String =
        args["action"]?.asPlainString()?.trim()?.lowercase() ?: ""

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission = when (actionOf(args)) {
        "start" -> {
            val command = args["command"]?.asPlainString() ?: "未知命令"
            val title = args["title"]?.asPlainString()
            PendingToolPermission(
                id = callId,
                toolName = name,
                title = "确认启动后台命令",
                summary = command,
                details = "后台标签：${title?.takeIf { it.isNotBlank() } ?: "自动命名"}\n命令会持续运行，直到进程退出或手动停止。",
                argsPreview = argsPreview
            )
        }
        "send" -> {
            val tabId = args["tab_id"]?.asPlainString() ?: "未知标签"
            val input = args["input"]?.asPlainString() ?: "未知输入"
            val submit = args["submit"]?.asPlainString()?.toBooleanStrictOrNull() ?: true
            PendingToolPermission(
                id = callId,
                toolName = name,
                title = "确认发送终端输入",
                summary = input,
                details = "目标终端：$tabId\n自动回车执行：${if (submit) "是" else "否"}",
                argsPreview = argsPreview
            )
        }
        "key" -> {
            val tabId = args["tab_id"]?.asPlainString() ?: "未知标签"
            val key = args["key"]?.asPlainString() ?: "未知快捷键"
            PendingToolPermission(
                id = callId,
                toolName = name,
                title = "确认发送终端快捷键",
                summary = key,
                details = "目标终端：$tabId",
                argsPreview = argsPreview
            )
        }
        "close" -> {
            val ids = (parseTabIds(args["tab_ids"]) + parseTabIds(args["tab_id"])).distinct()
            val summary = ids.joinToString("、").ifBlank { "未知标签" }
            PendingToolPermission(
                id = callId,
                toolName = name,
                title = if (ids.size > 1) "确认关闭 ${ids.size} 个终端标签" else "确认关闭终端标签",
                summary = summary,
                details = "将关闭终端 $summary，并终止其中仍在运行的进程。",
                argsPreview = argsPreview
            )
        }
        else -> {
            val tabId = args["tab_id"]?.asPlainString()
            PendingToolPermission(
                id = callId,
                toolName = name,
                title = "确认读取终端输出",
                summary = tabId?.takeIf { it.isNotBlank() } ?: "列出所有标签",
                details = if (tabId.isNullOrBlank()) "列出当前所有终端标签及其状态" else "读取终端 $tabId 的当前输出",
                argsPreview = argsPreview
            )
        }
    }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult = execute(args, null)

    /** 非流式兜底路径也带上发起会话 id，与 [executeStream] 保持一致（后台命令通知能回投到源会话）。 */
    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: com.aicode.feature.agent.domain.model.AgentContext
    ): ToolResult = execute(args, context.sessionId)

    private suspend fun execute(args: Map<String, JsonElement>, sourceSessionId: String?): ToolResult =
        when (actionOf(args)) {
            "start" -> start(args, sourceSessionId)
            "send" -> send(args)
            "key" -> sendKey(args)
            "read" -> read(args)
            "close" -> close(args)
            else -> ToolResult.Error("缺少或非法的 action 参数（应为 start/send/key/read/close）")
        }

    /**
     * 事件流路径下，让 start/send 像 Bash 一样把等待窗口内的新输出实时推给 UI。
     * 其它动作保持非流式语义，直接返回最终结果。
     */
    override fun executeStream(
        args: Map<String, JsonElement>,
        context: com.aicode.feature.agent.domain.model.AgentContext
    ): Flow<ToolStreamEvent> = flow {
        when (actionOf(args)) {
            "start" -> streamStart(args, context)
            "send" -> streamSend(args)
            else -> emit(ToolStreamEvent.Completed(execute(args)))
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<ToolStreamEvent>.streamStart(
        args: Map<String, JsonElement>,
        context: com.aicode.feature.agent.domain.model.AgentContext
    ) {
        val command = args["command"]?.asPlainString()
        if (command == null) {
            emit(ToolStreamEvent.Completed(ToolResult.Error("start 操作缺少必需参数：command")))
            return
        }
        val title = args["title"]?.asPlainString()
        val notify = args["notify"]?.asPlainString()?.toBooleanStrictOrNull() ?: false
        val tabId = try {
            withContext(Dispatchers.Main) { sessionManager.startBackgroundCommand(command, title, notify, context.sessionId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "启动后台命令失败: $command", e)
            emit(ToolStreamEvent.Completed(ToolResult.Error("启动后台命令失败：${e.message}")))
            return
        }

        FileLogger.i(TAG, "后台命令已启动 tab=$tabId(流式捕获): $command")
        captureOutputFor(tabId, initialOutput = "")
        val result = withContext(Dispatchers.Main) { capturedResult(tabId, actionLabel = "start") }
        emit(ToolStreamEvent.Completed(result))
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<ToolStreamEvent>.streamSend(args: Map<String, JsonElement>) {
        val tabId = args["tab_id"]?.asPlainString()
        if (tabId.isNullOrBlank()) {
            emit(ToolStreamEvent.Completed(ToolResult.Error("send 操作缺少必需参数：tab_id")))
            return
        }
        val input = args["input"]?.asPlainString()
        if (input == null) {
            emit(ToolStreamEvent.Completed(ToolResult.Error("send 操作缺少必需参数：input")))
            return
        }
        val submit = args["submit"]?.asPlainString()?.toBooleanStrictOrNull() ?: true

        val before = withContext(Dispatchers.Main) {
            if (sessionManager.listTabs().none { it.id == tabId }) null else sessionManager.getTabOutput(tabId)
        }
        if (before == null) {
            emit(ToolStreamEvent.Completed(ToolResult.Error("未找到终端标签：$tabId")))
            return
        }

        val ok = withContext(Dispatchers.Main) { sessionManager.sendInput(tabId, input, appendNewline = submit) }
        if (!ok) {
            emit(ToolStreamEvent.Completed(ToolResult.Error("未找到终端标签：$tabId")))
            return
        }
        FileLogger.i(TAG, "向 $tabId 发送输入(流式捕获): $input")

        captureOutputFor(tabId, initialOutput = before)
        val result = withContext(Dispatchers.Main) { capturedResult(tabId, actionLabel = "send") }
        emit(ToolStreamEvent.Completed(result))
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<ToolStreamEvent>.captureOutputFor(
        tabId: String,
        initialOutput: String
    ) {
        var elapsed = 0L
        var lastOutput = initialOutput
        while (elapsed < START_CAPTURE_MS) {
            delay(START_POLL_INTERVAL_MS)
            elapsed += START_POLL_INTERVAL_MS

            val output = withContext(Dispatchers.Main) { sessionManager.getTabOutput(tabId) }
                ?: break
            val delta = outputDelta(lastOutput, output)
            if (delta.isNotEmpty()) {
                emit(ToolStreamEvent.Progress(delta))
                lastOutput = output
            }
        }
    }

    /**
     * 启动后台命令标签，并捕获最多 [START_CAPTURE_MS] 毫秒内的初始输出后返回。
     *
     * 不像普通 start 那样只返回 tab_id 就结束——那样 AI 还得再调一次 read 才知道命令有没有立刻报错
     * （如命令不存在、端口被占）。这里启动后轮询：命令提前退出则立即返回（早失败早看见），否则等满
     * [START_CAPTURE_MS] 抓一段初始输出（dev server 的启动横幅、首次报错等通常都在这几秒内）。
     * 返回对象含 tab_id / 是否仍在运行 / 捕获到的输出，AI 据此判断要不要继续 send/read。
     *
     * TerminalSessionManager 内部需主线程，故整段切到 Main。
     */
    private suspend fun start(args: Map<String, JsonElement>, sourceSessionId: String?): ToolResult = withContext(Dispatchers.Main) {
        val command = args["command"]?.asPlainString()
            ?: return@withContext ToolResult.Error("start 操作缺少必需参数：command")
        val title = args["title"]?.asPlainString()
        val notify = args["notify"]?.asPlainString()?.toBooleanStrictOrNull() ?: false
        try {
            val tabId = sessionManager.startBackgroundCommand(command, title, notify, sourceSessionId)
            FileLogger.i(TAG, "后台命令已启动 tab=$tabId: $command")

            // 轮询捕获初始输出：命令退出则提前结束，否则最多等满 START_CAPTURE_MS。
            var elapsed = 0L
            while (elapsed < START_CAPTURE_MS) {
                delay(START_POLL_INTERVAL_MS)
                elapsed += START_POLL_INTERVAL_MS
                val running = sessionManager.listTabs().firstOrNull { it.id == tabId }?.running ?: false
                if (!running) break
            }

            val running = sessionManager.listTabs().firstOrNull { it.id == tabId }?.running ?: false
            val output = sessionManager.getTabOutput(tabId) ?: ""
            FileLogger.v(TAG, "start 捕获 tab=$tabId running=$running 输出 ${output.length} 字符")
            ToolResult.Success(
                JsonObject(
                    mapOf(
                        "tab_id" to JsonPrimitive(tabId),
                        "running" to JsonPrimitive(running),
                        "output" to JsonPrimitive(output)
                    )
                )
            )
        } catch (e: Exception) {
            FileLogger.e(TAG, "启动后台命令失败: $command", e)
            ToolResult.Error("启动后台命令失败：${e.message}")
        }
    }

    /**
     * 向指定终端发送一行输入，默认回车执行，并等待一小段时间捕获输出。
     *
     * 与 start 保持一致：返回 tab_id / running / output，避免 AI 发送命令后必须再手动 read 一次
     * 才能看到即时结果。交互 shell 没有可靠的“命令结束”信号，因此这里固定等待 [START_CAPTURE_MS]。
     */
    private suspend fun send(args: Map<String, JsonElement>): ToolResult = withContext(Dispatchers.Main) {
        val tabId = args["tab_id"]?.asPlainString()
            ?: return@withContext ToolResult.Error("send 操作缺少必需参数：tab_id")
        val input = args["input"]?.asPlainString()
            ?: return@withContext ToolResult.Error("send 操作缺少必需参数：input")
        val submit = args["submit"]?.asPlainString()?.toBooleanStrictOrNull() ?: true
        if (sessionManager.listTabs().none { it.id == tabId }) {
            return@withContext ToolResult.Error("未找到终端标签：$tabId", "TERMINAL_NOT_FOUND")
        }
        val ok = sessionManager.sendInput(tabId, input, appendNewline = submit)
        if (ok) {
            FileLogger.i(TAG, "向 $tabId 发送输入: $input")
            var elapsed = 0L
            while (elapsed < START_CAPTURE_MS) {
                delay(START_POLL_INTERVAL_MS)
                elapsed += START_POLL_INTERVAL_MS
            }
            capturedResult(tabId, actionLabel = "send")
        } else {
            val exists = sessionManager.listTabs().any { it.id == tabId }
            if (exists) {
                ToolResult.Error("终端 $tabId 已结束，无法发送命令，如需执行新命令请用 start 新建终端", "TERMINAL_NOT_ACTIVE")
            } else {
                ToolResult.Error("未找到终端标签：$tabId", "TERMINAL_NOT_FOUND")
            }
        }
    }

    /** 向指定终端发送预定义快捷键/控制字符。TerminalSessionManager 需主线程。 */
    private suspend fun sendKey(args: Map<String, JsonElement>): ToolResult = withContext(Dispatchers.Main) {
        val tabId = args["tab_id"]?.asPlainString()
            ?: return@withContext ToolResult.Error("key 操作缺少必需参数：tab_id")
        val key = args["key"]?.asPlainString()
            ?: return@withContext ToolResult.Error("key 操作缺少必需参数：key")
        if (sessionManager.listTabs().none { it.id == tabId }) {
            return@withContext ToolResult.Error("未找到终端标签：$tabId", "TERMINAL_NOT_FOUND")
        }
        val normalized = normalizeKey(key)
        val ok = when (normalized) {
            "ctrl+c" -> sessionManager.writeBytesToTab(tabId, 0x03)
            "ctrl+d" -> sessionManager.writeBytesToTab(tabId, 0x04)
            "ctrl+z" -> sessionManager.writeBytesToTab(tabId, 0x1A)
            "ctrl+l" -> sessionManager.writeBytesToTab(tabId, 0x0C)
            "ctrl+u" -> sessionManager.writeBytesToTab(tabId, 0x15)
            "ctrl+w" -> sessionManager.writeBytesToTab(tabId, 0x17)
            "esc", "escape" -> sessionManager.writeBytesToTab(tabId, 0x1B)
            "tab" -> sessionManager.writeBytesToTab(tabId, 0x09)
            "enter", "return" -> sessionManager.writeBytesToTab(tabId, 0x0D)
            "up" -> sessionManager.writeToTab(tabId, "\u001B[A")
            "down" -> sessionManager.writeToTab(tabId, "\u001B[B")
            "right" -> sessionManager.writeToTab(tabId, "\u001B[C")
            "left" -> sessionManager.writeToTab(tabId, "\u001B[D")
            else -> return@withContext ToolResult.Error("不支持的快捷键：$key，支持：${SUPPORTED_KEYS.joinToString(", ")}")
        }
        if (ok) {
            FileLogger.i(TAG, "向 $tabId 发送快捷键: $normalized")
            ToolResult.Success(JsonPrimitive("已向终端标签 $tabId 发送快捷键 $normalized，可用 terminal(action=\"read\", tab_id=\"$tabId\") 查看结果。"))
        } else {
            val exists = sessionManager.listTabs().any { it.id == tabId }
            if (exists) {
                ToolResult.Error("终端 $tabId 已结束，无法发送快捷键", "TERMINAL_NOT_ACTIVE")
            } else {
                ToolResult.Error("未找到终端标签：$tabId", "TERMINAL_NOT_FOUND")
            }
        }
    }

    /** 读取终端输出；省略 tab_id 则列出所有标签。TerminalSessionManager 需主线程。 */
    private suspend fun read(args: Map<String, JsonElement>): ToolResult = withContext(Dispatchers.Main) {
        val tabId = args["tab_id"]?.asPlainString()
        if (tabId.isNullOrBlank()) {
            val list = sessionManager.listTabs()
            if (list.isEmpty()) return@withContext ToolResult.Success(JsonPrimitive("当前没有终端标签。"))
            val text = list.joinToString("\n") {
                val state = if (it.running) "运行中" else "已结束"
                val kind = if (it.isBackground) "后台" else "交互"
                "${it.id}  [$kind/$state]  ${it.title}" + (it.command?.let { c -> "  ($c)" } ?: "")
            }
            return@withContext ToolResult.Success(JsonPrimitive(text))
        }
        val filter = args["filter"]?.asPlainString()?.trim()?.takeIf { it.isNotEmpty() }
        val regex = if (filter == null) {
            null
        } else {
            try {
                Regex(filter)
            } catch (e: Exception) {
                return@withContext ToolResult.Error(
                    "filter 不是合法的正则表达式：${e.message ?: filter}",
                    "INVALID_REGEX"
                )
            }
        }
        if (sessionManager.listTabs().none { it.id == tabId }) {
            return@withContext ToolResult.Error("未找到终端标签：$tabId", "TERMINAL_NOT_FOUND")
        }
        val output = sessionManager.getTabOutput(tabId)
            ?: return@withContext ToolResult.Error("未找到终端标签：$tabId")
        if (regex == null) {
            FileLogger.v(TAG, "读取 $tabId 输出 ${output.length} 字符")
            return@withContext ToolResult.Success(JsonPrimitive(output))
        }
        val matched = filterOutputLines(output, regex)
        FileLogger.v(TAG, "读取 $tabId 输出 ${output.length} 字符，filter=$filter 命中 ${matched.size} 行")
        val text = if (matched.isEmpty()) {
            "没有匹配 $filter 的行（原输出共 ${output.lineSequence().count()} 行）。"
        } else {
            matched.joinToString("\n")
        }
        ToolResult.Success(JsonPrimitive(text))
    }

    /**
     * 关闭终端标签，支持 `tab_ids` 数组一次关闭多个（与 `tab_id` 合并去重）。
     * 单个标签的返回文案保持不变；批量时逐个关闭并汇报未找到的 id。TerminalSessionManager 需主线程。
     */
    private suspend fun close(args: Map<String, JsonElement>): ToolResult = withContext(Dispatchers.Main) {
        val ids = (parseTabIds(args["tab_ids"]) + parseTabIds(args["tab_id"])).distinct()
        if (ids.isEmpty()) {
            return@withContext ToolResult.Error("close 操作缺少必需参数：tab_ids 或 tab_id", "MISSING_TAB_ID")
        }
        val closed = mutableListOf<String>()
        val missing = mutableListOf<String>()
        ids.forEach { id ->
            if (sessionManager.listTabs().none { it.id == id }) {
                missing += id
                return@forEach
            }
            if (sessionManager.closeTab(id)) {
                closed += id
                FileLogger.i(TAG, "关闭终端标签: $id")
            } else {
                missing += id
            }
        }
        when {
            closed.isEmpty() -> ToolResult.Error(
                "未找到终端标签：${missing.joinToString("、")}",
                "TERMINAL_NOT_FOUND"
            )
            closed.size == 1 && missing.isEmpty() ->
                ToolResult.Success(JsonPrimitive("已关闭终端标签 ${closed.first()}。"))
            else -> {
                val text = buildString {
                    append("已关闭终端标签：${closed.joinToString("、")}。")
                    if (missing.isNotEmpty()) append("未找到：${missing.joinToString("、")}。")
                }
                ToolResult.Success(JsonPrimitive(text))
            }
        }
    }

    private fun normalizeKey(key: String): String =
        key.trim().lowercase().replace(" ", "").replace("_", "+").replace("-", "+")

    private fun capturedResult(tabId: String, actionLabel: String): ToolResult {
        val tab = sessionManager.listTabs().firstOrNull { it.id == tabId }
            ?: return ToolResult.Error("未找到终端标签：$tabId")
        val rawOutput = sessionManager.getTabOutput(tabId) ?: ""
        FileLogger.v(
            TAG,
            "$actionLabel 捕获 tab=$tabId running=${tab.running} 输出 ${rawOutput.length} 字符"
        )
        return ToolResult.Success(
            JsonObject(
                mapOf(
                    "tab_id" to JsonPrimitive(tabId),
                    "running" to JsonPrimitive(tab.running),
                    "output" to JsonPrimitive(rawOutput)
                )
            )
        )
    }

    private fun outputDelta(previous: String, current: String): String {
        if (current.isEmpty() || current == previous) return ""
        if (current.startsWith(previous)) return current.substring(previous.length)

        // 找 previous 后缀与 current 前缀的最长重叠。原实现用 current.take(length) 每次分配子串，
        // 对长 transcript 是 O(n^2)；改为先比首字符再逐字符比对，无分配。
        val maxOverlap = minOf(previous.length, current.length)
        val first = current[0]
        for (length in maxOverlap downTo 1) {
            val start = previous.length - length
            if (previous[start] != first) continue
            if (regionMatches(previous, start, current, length)) {
                return current.substring(length)
            }
        }
        return current
    }

    private fun regionMatches(a: String, aOffset: Int, b: String, length: Int): Boolean {
        for (i in 1 until length) {
            if (a[aOffset + i] != b[i]) return false
        }
        return true
    }
}
