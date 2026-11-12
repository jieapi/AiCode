package com.aicode.feature.plugin.domain

/** `contributes` 里指向包内 Markdown 文件的一条贡献。[file] 相对于包根目录。 */
data class PluginContributionRef(val id: String, val file: String)

/**
 * 设置页入口可挂载的宿主分组。插件只能取这里的值，不能自定义插入位置，
 * 避免第三方入口打乱设置页布局；新增分组属于宿主插件 API 变更。
 */
object PluginSettingsAnchors {
    const val GENERAL = "settings.general"
    const val AI = "settings.ai"
    const val ENVIRONMENT = "settings.environment"
    const val PERMISSIONS = "settings.permissions"
    const val DATA = "settings.data"
    const val HELP = "settings.help"

    val ALL = setOf(GENERAL, AI, ENVIRONMENT, PERMISSIONS, DATA, HELP)
}

/** `contributes.settingsPages` 声明的设置页入口；[page] 是包内 HTML 相对路径。 */
data class PluginSettingsPageRef(
    val id: String,
    val title: LocalizedText,
    val anchor: String,
    val page: String,
    val icon: String = "book"
)

/** `contributes.workspaceTools` 声明的工作区工具项，包含入口元数据与页面定义文件引用。 */
data class PluginWorkspaceToolRef(
    val id: String,
    val title: LocalizedText,
    val description: LocalizedText,
    val icon: String = "play",
    val category: String = "通用",
    val pageFile: String
)

/** 插件业务逻辑入口：发布包里已打包的 JavaScript 文件，由隔离运行时加载。 */
data class PluginRuntimeRef(val type: String, val entry: String)

/** `contributes.tools` 声明的 AI 可调用工具。`handler` 是 runtime 导出的 handlers 表里的稳定键。 */
data class PluginToolRef(
    val id: String,
    val title: LocalizedText,
    val description: LocalizedText,
    val inputSchema: String,
    val handler: String
)

/** 宿主支持的插件生命周期事件；清单 `event` 只能取这里的值。 */
object PluginHookEvents {
    const val AGENT_RUN_STARTED = "agent.run.started"
    const val AGENT_RUN_FINISHED = "agent.run.finished"
    const val AGENT_REQUEST_STARTED = "agent.request.started"
    const val AGENT_REQUEST_FINISHED = "agent.request.finished"
    const val AGENT_TOOL_STARTED = "agent.tool.started"
    const val AGENT_TOOL_FINISHED = "agent.tool.finished"
    const val AGENT_COMPACTION = "agent.compaction"

    val ALL = setOf(
        AGENT_RUN_STARTED,
        AGENT_RUN_FINISHED,
        AGENT_REQUEST_STARTED,
        AGENT_REQUEST_FINISHED,
        AGENT_TOOL_STARTED,
        AGENT_TOOL_FINISHED,
        AGENT_COMPACTION
    )
}

/** `contributes.hooks` 声明的一条生命周期 hook。`handler` 是 runtime 导出的 handlers 表里的稳定键。 */
data class PluginHookRef(val id: String, val event: String, val handler: String)

/** `contributes.mcp` 声明的 MCP 配置文件；文件沿用现有 `{"mcpServers": {...}}` 结构。 */
data class PluginMcpRef(val id: String, val file: String)

/**
 * 已通过结构校验的插件清单（`plugin.json`）。校验只证明格式合法，不代表可信：
 * 安装身份还取决于来源，清单里的 `id` / `publisher` 文本不是身份证明。
 */
data class PluginManifest(
    val manifestVersion: Int,
    val id: String,
    val name: LocalizedText,
    val version: SemVer,
    val publisher: String,
    val repository: String,
    val description: LocalizedText,
    val license: String,
    val pluginApi: String,
    val structuredUi: String?,
    val skills: List<PluginContributionRef>,
    val agents: List<PluginContributionRef>,
    val workspaceTools: List<PluginWorkspaceToolRef> = emptyList(),
    val settingsPages: List<PluginSettingsPageRef> = emptyList(),
    val runtime: PluginRuntimeRef? = null,
    val tools: List<PluginToolRef> = emptyList(),
    val hooks: List<PluginHookRef> = emptyList(),
    val mcp: List<PluginMcpRef> = emptyList(),
    val configPage: String? = null
) {
    /** 清单声明的贡献总数。 */
    val contributionCount: Int
        get() = skills.size + agents.size + workspaceTools.size + settingsPages.size +
            tools.size + hooks.size + mcp.size + (if (configPage != null) 1 else 0)
}

/** 安装与加载过程中的失败原因。UI 按 [PluginErrorCode] 取本地化文案，[detail] 是语言无关的定位信息。 */
enum class PluginErrorCode {
    ARCHIVE_UNREADABLE,
    ARCHIVE_TOO_LARGE,
    ARCHIVE_TOO_MANY_ENTRIES,
    ARCHIVE_ENTRY_TOO_LARGE,
    ARCHIVE_UNSAFE_PATH,
    ARCHIVE_DUPLICATE_ENTRY,
    MANIFEST_MISSING,
    MANIFEST_TOO_LARGE,
    MANIFEST_NOT_JSON,
    FIELD_MISSING,
    FIELD_INVALID,
    UNSUPPORTED_MANIFEST_VERSION,
    UNSUPPORTED_FIELD,
    UNSUPPORTED_CONTRIBUTION,
    API_INCOMPATIBLE,
    DUPLICATE_ID,
    FILE_MISSING,
    CONTENT_INVALID,
    STORAGE_FAILED,
    PREVIEW_EXPIRED
}

data class PluginError(val code: PluginErrorCode, val detail: String = "")
