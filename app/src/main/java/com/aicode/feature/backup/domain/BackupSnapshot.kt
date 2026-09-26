package com.aicode.feature.backup.domain

import com.aicode.feature.agent.domain.mcp.McpServerConfig
import com.aicode.feature.agent.domain.permission.PermissionRule
import com.aicode.feature.settings.data.repository.SyncSettingsSnapshot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 备份快照：一次导出/导入的完整数据集。各 DTO 与 Room Entity 同构但解耦，避免序列化框架绑定到 Room。
 *
 * [schemaVersion] 记录导出时的 AgentDatabase 版本，导入时据此判断兼容性：
 * 备份版本 > 当前 App 版本则拒绝（字段可能缺失）；< 当前则允许（新字段取默认值）。
 */
@Serializable
data class BackupSnapshot(
    val schemaVersion: Int,
    val appVersion: String = "",
    val createdAt: Long,
    val providers: List<ProviderDto> = emptyList(),
    val remoteConnections: List<RemoteConnectionDto> = emptyList(),
    val remoteMounts: List<RemoteMountDto> = emptyList(),
    val chatSessions: List<ChatSessionDto> = emptyList(),
    val agentMessages: List<AgentMessageDto> = emptyList(),
    val todoItems: List<TodoItemDto> = emptyList(),
    val mcpServers: List<McpServerConfig> = emptyList(),
    val globalPermissionRules: List<PermissionRule> = emptyList(),
    val themeMode: String? = null,
    val themePresetId: String? = null,
    val dynamicColorEnabled: Boolean = false,
    val keepaliveEnabled: Boolean = false,
    val screenOnEnabled: Boolean = false,
    val agentSoundEnabled: Boolean = false,
    val autoRemoveStaleModels: Boolean = true,
    val startupSessionMode: String? = null,
    val firstByteTimeoutSec: Int = 300,
    val streamIdleTimeoutSec: Int = 0,
    val maxNetworkRetries: Int = 6,
    val enterToSend: Boolean = false,
    val compactionThresholdPercent: Int = 85,
    val softCompactionThresholdPercent: Int = 60,
    val sendFileMaxSizeMb: Int = 100,
    val deleteExternalWorkspaceSessions: Boolean = false,
    val logLevel: String? = null,
    val visionProviderId: String = "",
    val visionModel: String = "",
    val compactionProviderId: String = "",
    val compactionModel: String = "",
    val syncSettings: SyncSettingsSnapshot? = null
)

/**
 * 流式备份的元数据分片（tar 内 metadata.json）：不含聊天大表，大表走各 *.jsonl。
 * 与 [BackupSnapshot] 的元数据字段一一对应，导入时合并 jsonl 还原完整数据。
 */
@Serializable
data class BackupMetadata(
    val schemaVersion: Int,
    val appVersion: String = "",
    val createdAt: Long,
    val providers: List<ProviderDto> = emptyList(),
    val remoteConnections: List<RemoteConnectionDto> = emptyList(),
    val remoteMounts: List<RemoteMountDto> = emptyList(),
    val mcpServers: List<McpServerConfig> = emptyList(),
    val globalPermissionRules: List<PermissionRule> = emptyList(),
    val themeMode: String? = null,
    val themePresetId: String? = null,
    val dynamicColorEnabled: Boolean = false,
    val keepaliveEnabled: Boolean = false,
    val screenOnEnabled: Boolean = false,
    val agentSoundEnabled: Boolean = false,
    val autoRemoveStaleModels: Boolean = true,
    val startupSessionMode: String? = null,
    val firstByteTimeoutSec: Int = 300,
    val streamIdleTimeoutSec: Int = 0,
    val maxNetworkRetries: Int = 6,
    val enterToSend: Boolean = false,
    val compactionThresholdPercent: Int = 85,
    val softCompactionThresholdPercent: Int = 60,
    val sendFileMaxSizeMb: Int = 100,
    val deleteExternalWorkspaceSessions: Boolean = false,
    val logLevel: String? = null,
    val visionProviderId: String = "",
    val visionModel: String = "",
    val compactionProviderId: String = "",
    val compactionModel: String = "",
    val syncSettings: SyncSettingsSnapshot? = null,
    val workspaces: List<WorkspaceBackupMeta> = emptyList()
)

/** 备份元数据中的一个工作区段：名称 + 备份的文件数（用于导入摘要）。 */
@Serializable
data class WorkspaceBackupMeta(
    val name: String,
    val fileCount: Int
)

fun BackupSnapshot.toMetadata() = BackupMetadata(
    schemaVersion = schemaVersion,
    appVersion = appVersion,
    createdAt = createdAt,
    providers = providers,
    remoteConnections = remoteConnections,
    remoteMounts = remoteMounts,
    mcpServers = mcpServers,
    globalPermissionRules = globalPermissionRules,
    themeMode = themeMode,
    themePresetId = themePresetId,
    dynamicColorEnabled = dynamicColorEnabled,
    keepaliveEnabled = keepaliveEnabled,
    screenOnEnabled = screenOnEnabled,
    agentSoundEnabled = agentSoundEnabled,
    autoRemoveStaleModels = autoRemoveStaleModels,
    startupSessionMode = startupSessionMode,
    firstByteTimeoutSec = firstByteTimeoutSec,
    streamIdleTimeoutSec = streamIdleTimeoutSec,
    maxNetworkRetries = maxNetworkRetries,
    enterToSend = enterToSend,
    compactionThresholdPercent = compactionThresholdPercent,
    sendFileMaxSizeMb = sendFileMaxSizeMb,
    deleteExternalWorkspaceSessions = deleteExternalWorkspaceSessions,
    logLevel = logLevel,
    visionProviderId = visionProviderId,
    visionModel = visionModel,
    compactionProviderId = compactionProviderId,
    compactionModel = compactionModel,
    syncSettings = syncSettings
)

@Serializable
data class ProviderDto(
    val id: String,
    val name: String,
    val type: String,
    val apiKey: String,
    val baseUrl: String,
    val defaultModel: String,
    val models: String = "",
    val selectedModel: String = "",
    val isEnabled: Boolean = true,
    val useFullUrl: Boolean = false,
    val useResponseApi: Boolean = false,
    /** 提供商级缓存开关；null 表示旧备份无此字段，导入时回退默认值。 */
    val anthropicCacheBreakpoints: Boolean? = null,
    val openaiChatCacheKey: Boolean? = null,
    /** 自定义面板脚本路径；null 表示旧备份无此字段，导入时回退默认值 ""。序列化名沿用历史命名以兼容旧备份。 */
    @SerialName("balanceScriptPath")
    val dashboardScriptPath: String? = null,
    /** 自定义面板刷新间隔（分钟）；null 表示旧备份无此字段，导入时回退默认值 5。序列化名沿用历史命名以兼容旧备份。 */
    @SerialName("balanceRefreshInterval")
    val dashboardRefreshInterval: Int? = null,
    /** 提供商列表排序序号；null 表示旧备份无此字段，导入时回退默认 0。 */
    val sortOrder: Int? = null,
    /** 多 Key 模式相关字段；null 表示旧备份无此字段，导入时回退默认值。 */
    val multiKeyEnabled: Boolean? = null,
    val apiKeys: String? = null,
    val keyRotationStrategy: String? = null,
    val keyFailoverThreshold: Int? = null,
    val keyCooldownMinutes: Int? = null,
    /** 自定义面板 (DIY) 脚本参数（JSON 编码）；null 表示旧备份无此字段，导入时回退为空。 */
    val scriptParams: String? = null,
    /** 多 Key 自动切换状态码（逗号分隔）；null 表示旧备份无此字段，导入时回退空串（即默认码表）。 */
    val keySwitchStatusCodes: String? = null
)

@Serializable
data class RemoteConnectionDto(
    val id: String,
    val name: String,
    val protocol: String,
    val host: String,
    val port: Int,
    val username: String,
    val authType: String = "password",
    val authData: String,
    val passphrase: String? = null
)

@Serializable
data class RemoteMountDto(
    val id: String,
    val connectionId: String,
    val remotePath: String,
    val localMountPath: String,
    val isActive: Boolean = false,
    val autoConnect: Boolean = true
)

@Serializable
data class ChatSessionDto(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val workspacePath: String = "",
    val mode: String = "BUILD",
    val modeBeforePlan: String? = null,
    val reasoningEffort: String = "MEDIUM",
    val providerId: String? = null,
    val model: String? = null,
    val isPinned: Boolean = false,
    /** 子代理会话的父会话 id；丢了它恢复后子会话会变成一堆根会话。 */
    val parentId: String? = null,
    val subagentType: String? = null
)

@Serializable
data class AgentMessageDto(
    val id: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val toolCallsJson: String? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolArgs: String? = null,
    val isError: Boolean = false,
    val reasoning: String? = null,
    val signature: String? = null,
    val attachmentsJson: String? = null,
    val isCompacted: Boolean = false,
    val isContextSummary: Boolean = false,
    val isCompactionMarker: Boolean = false,
    /** Anthropic thinking / redacted_thinking 内容块的原样快照（JSON 数组文本）。 */
    val thinkingBlocksJson: String? = null
)

@Serializable
data class TodoItemDto(
    val id: String,
    val sessionId: String,
    val subject: String,
    val description: String = "",
    val status: String = "PENDING",
    val priority: Int = 0,
    val order: Int = 0,
    val createdAt: Long,
    val updatedAt: Long
)
