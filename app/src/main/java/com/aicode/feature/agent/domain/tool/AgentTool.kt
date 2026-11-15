package com.aicode.feature.agent.domain.tool

import com.aicode.feature.agent.domain.model.AgentImage
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

@Serializable
sealed class ToolResult {
    @Serializable
    @SerialName("success")
    data class Success(
        val data: JsonElement,
        val images: List<AgentImage> = emptyList()
    ) : ToolResult()

    @Serializable
    @SerialName("error")
    data class Error(val message: String, val code: String = "UNKNOWN") : ToolResult()

    @Serializable
    @SerialName("partial")
    data class Partial(val data: JsonElement, val message: String) : ToolResult()
}

private val ToolResultTransportJson = Json {
    classDiscriminator = "status"
    encodeDefaults = true
}

fun ToolResult.toTransportString(): String {
    return ToolResultTransportJson.encodeToString(this)
}

data class ToolParameter(
    val name: String,
    val type: ParameterType,
    val description: String,
    val required: Boolean = true,
    val enum: List<String>? = null,
    /**
     * 当 [type] 为 [ParameterType.ARRAY] 时，描述数组元素的 JSON Schema（原样并入
     * function-calling 的 items 字段）。例如元素是对象时传
     * `{"type":"object","properties":{...},"required":[...]}`。为空则不输出 items。
     */
    val itemsSchema: Map<String, Any>? = null
)

enum class ParameterType {
    STRING, INTEGER, BOOLEAN, ARRAY, OBJECT
}

enum class ToolPermissionPolicy {
    AUTO_APPROVE, ASK
}

enum class ToolCapability {
    READ_WORKSPACE,
    WRITE_WORKSPACE,
    EXECUTE_COMMANDS,
    NETWORK_READ,
    NETWORK_WRITE,
    READ_AGENT_CONFIG,
    MODIFY_AGENT_CONFIG,
    MODIFY_CONTAINER_ENV,
    USER_INTERACTION,
    MODIFY_SESSION_STATE,
    MODIFY_TODO_STATE,
    EXTERNAL_TOOL
}

data class PendingToolPermission(
    val id: String,
    val toolName: String,
    val title: String,
    val summary: String,
    val details: String,
    val argsPreview: String,
    /**
     * 「始终允许」会记忆的模式列表（shell 命令为命令前缀：子命令分发器记 `git pull` 这类带子命令的
     * 前缀，普通程序记 `cat`/`ls`；非 shell 工具为 `*`）。
     * 为空表示该调用不可记忆（命令不可静态判定），UI 应禁用「始终允许」、只留单次放行。
     * 由 [com.aicode.feature.agent.domain.permission.ToolPermissionPolicyEngine] 评估后填入。
     */
    val rememberablePatterns: List<String> = emptyList(),
    val rememberDisabledReason: String? = null,
    /** 发起该授权请求的会话 id。多会话并行时用于在 UI 上区分弹窗归属、点亮侧边栏等待授权指示。 */
    val sessionId: String = "",
    /**
     * 发起方：[ORIGIN_CHAT] 为聊天页 Agent 循环，[ORIGIN_PLUGIN] 为插件工具调用。
     * 插件发起的请求不进入聊天页弹窗队列，只通过插件侧的 pending/resolve 处理。
     */
    val origin: String = ORIGIN_CHAT
) {
    companion object {
        const val ORIGIN_CHAT = "chat"
        const val ORIGIN_PLUGIN = "plugin"
    }
}

abstract class AgentTool {
    abstract val name: String
    abstract val description: String
    abstract val parameters: Map<String, ToolParameter>
    open val permissionPolicy: ToolPermissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    open val capabilities: Set<ToolCapability> = emptySet()

    open fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> {
        return capabilities
    }

    abstract suspend fun execute(args: Map<String, JsonElement>): ToolResult

    open suspend fun executeWithContext(args: Map<String, JsonElement>, context: com.aicode.feature.agent.domain.model.AgentContext): ToolResult {
        return execute(args)
    }
    open fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "确认执行工具",
            summary = "AI 请求执行 $name",
            details = argsPreview,
            argsPreview = argsPreview
        )
    }

    /**
     * 生成符合 JSON Schema 的参数描述，用于真正传给大模型的 function-calling 接口
     * （OpenAI 的 function.parameters / Anthropic 的 input_schema）。
     *
     * 设为 open：MCP 等外部工具携带的是任意原始 inputSchema，无法用受限的 [ParameterType]
     * 枚举表达，需要覆写本方法直接透传服务端 schema。
     */
    open fun toJsonSchema(): Map<String, Any> {
        val properties = LinkedHashMap<String, Any>()
        val required = mutableListOf<String>()
        parameters.forEach { (key, param) ->
            val prop = LinkedHashMap<String, Any>()
            prop["type"] = param.type.name.lowercase()
            prop["description"] = param.description
            param.enum?.let { prop["enum"] = it }
            if (param.type == ParameterType.ARRAY) param.itemsSchema?.let { prop["items"] = it }
            properties[key] = prop
            if (param.required) required.add(key)
        }
        return mapOf(
            "type" to "object",
            "properties" to properties,
            "required" to required
        )
    }
}

@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, JsonElement>
)

/**
 * 可流式执行的工具：在最终结果产生之前，逐步 emit 过程输出（如命令的逐行 stdout），
 * 让 UI 能实时显示「执行过程」而非只有最终结果。
 *
 * 实现类应同时实现 [AgentTool.execute] 作为非流式兜底；工作流优先走 [executeStream]。
 */
interface StreamingAgentTool {
    fun executeStream(args: Map<String, JsonElement>, context: com.aicode.feature.agent.domain.model.AgentContext): Flow<ToolStreamEvent>
}

/** 流式工具执行过程中产生的事件。 */
sealed class ToolStreamEvent {
    /** 一段新的过程输出（通常是一行）。 */
    data class Progress(val chunk: String) : ToolStreamEvent()
    /** 执行结束，附最终聚合结果（喂回模型用）。 */
    data class Completed(val result: ToolResult) : ToolStreamEvent()
}
