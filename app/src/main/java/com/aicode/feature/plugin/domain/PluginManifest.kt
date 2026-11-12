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

/** 插件运行时类型：`javascript` 用 node 启动，`python` 用 python3 启动。 */
object PluginRuntimeTypes {
    const val JAVASCRIPT = "javascript"
    const val PYTHON = "python"

    val ALL = setOf(JAVASCRIPT, PYTHON)
}

/** `contributes.runtime.dependencies` 声明的依赖输入。 */
data class PluginDependencyRef(val tool: String, val file: String)

/** 插件业务逻辑入口：容器内进程启动所需的信息。 */
data class PluginRuntimeRef(
    val type: String,
    val entry: String,
    val command: List<String>? = null,
    val dependencies: PluginDependencyRef? = null,
    /** 为 true 时进程不参与空闲回收，直到插件停用、卸载、版本替换或 App 进程结束。 */
    val resident: Boolean = false
)

/** `contributes.tools` 声明的 AI 可调用工具。`handler` 是 runtime 导出的 handlers 表里的稳定键。 */
data class PluginToolRef(
    val id: String,
    val title: LocalizedText,
    val description: LocalizedText,
    val inputSchema: String,
    val handler: String
)

/**
 * hook 的三种模式：`observe` 只观察并返回日志；`transform` 等待宿主应用结构化变更；
 * `review` 只在宿主 ASK 分支做代审核决定。
 */
object PluginHookModes {
    const val OBSERVE = "observe"
    const val TRANSFORM = "transform"
    const val REVIEW = "review"

    val ALL = setOf(OBSERVE, TRANSFORM, REVIEW)
}

/** 宿主支持的插件生命周期事件；清单 `event` 只能取这里的值。 */
object PluginHookEvents {
    /** App 进程启动完成；用于常驻插件初始化自己的进程与服务。 */
    const val APP_STARTED = "app.started"

    const val AGENT_RUN_STARTED = "agent.run.started"
    const val AGENT_RUN_FINISHED = "agent.run.finished"
    const val AGENT_REQUEST_STARTED = "agent.request.started"
    const val AGENT_REQUEST_FINISHED = "agent.request.finished"
    const val AGENT_TOOL_STARTED = "agent.tool.started"
    const val AGENT_TOOL_FINISHED = "agent.tool.finished"
    const val AGENT_COMPACTION = "agent.compaction"

    /** 一次工具调用执行前；可拒绝或改写参数，改写后重新走完整权限判断。 */
    const val AGENT_TOOL_BEFORE = "agent.tool.before"

    /** 一次工具调用成功结束后；可改写结果的 `data`。 */
    const val AGENT_TOOL_AFTER = "agent.tool.after"

    /** 每次实际发出 provider HTTP 请求前；可改写 URL、headers 与 JSON body。 */
    const val AGENT_HTTP_BEFORE = "agent.http.before"

    /** 宿主判定需要使用者确认后、弹窗前的代审核。 */
    const val AGENT_PERMISSION_REVIEW = "agent.permission.review"

    /** 只允许 `mode: "transform"` 的事件。 */
    val TRANSFORM = setOf(AGENT_TOOL_BEFORE, AGENT_TOOL_AFTER, AGENT_HTTP_BEFORE)

    /** 只允许 `mode: "review"` 的事件。 */
    val REVIEW = setOf(AGENT_PERMISSION_REVIEW)

    val ALL = setOf(
        APP_STARTED,
        AGENT_RUN_STARTED,
        AGENT_RUN_FINISHED,
        AGENT_REQUEST_STARTED,
        AGENT_REQUEST_FINISHED,
        AGENT_TOOL_STARTED,
        AGENT_TOOL_FINISHED,
        AGENT_COMPACTION,
        AGENT_TOOL_BEFORE,
        AGENT_TOOL_AFTER,
        AGENT_HTTP_BEFORE,
        AGENT_PERMISSION_REVIEW
    )
}

/**
 * `contributes.hooks` 声明的一条生命周期 hook。[handler] 是 runtime 导出的 handlers 表里的稳定键；
 * [mode] 缺省为 [PluginHookModes.OBSERVE]，可写 hook 必须显式声明 `transform`。
 */
data class PluginHookRef(
    val id: String,
    val event: String,
    val handler: String,
    val mode: String = PluginHookModes.OBSERVE
)

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
