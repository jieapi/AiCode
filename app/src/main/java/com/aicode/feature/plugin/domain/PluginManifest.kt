package com.aicode.feature.plugin.domain

/** `contributes` 里指向包内 Markdown 文件的一条贡献。[file] 相对于包根目录。 */
data class PluginContributionRef(val id: String, val file: String)

/**
 * 插件页可挂载的宿主槽位。插件只能取这里的值，不能自定义插入位置，
 * 避免第三方入口打乱宿主布局；新增槽位属于宿主插件 API 变更。
 */
object PluginUiSlots {
    const val SETTINGS_GENERAL = "settings.general"
    const val SETTINGS_AI = "settings.ai"
    const val SETTINGS_ENVIRONMENT = "settings.environment"
    const val SETTINGS_PERMISSIONS = "settings.permissions"
    const val SETTINGS_DATA = "settings.data"
    const val SETTINGS_HELP = "settings.help"

    /** 侧栏底部、浏览器与设置之上。 */
    const val DRAWER_FOOTER = "drawer.footer"

    /** 聊天顶栏「更多」菜单底部。 */
    const val CHAT_HEADER_MENU = "chat.header.menu"

    /** 聊天空态建议区。 */
    const val CHAT_WELCOME = "chat.welcome"

    /** 会话长按菜单。 */
    const val CHAT_SESSION_MENU = "chat.session.menu"

    /** 文件树文件/目录长按菜单。 */
    const val FILES_TREE_MENU = "files.tree.menu"

    /** 代码编辑器选中文本后的浮动菜单。 */
    const val EDITOR_SELECTION_MENU = "editor.selection.menu"

    /** 备份页「选择导出数据」组末尾，用于插件提供额外数据选项。 */
    const val BACKUP_DATA = "backup.data"

    val ALL = setOf(
        SETTINGS_GENERAL, SETTINGS_AI, SETTINGS_ENVIRONMENT, SETTINGS_PERMISSIONS, SETTINGS_DATA, SETTINGS_HELP,
        DRAWER_FOOTER, CHAT_HEADER_MENU, CHAT_WELCOME, CHAT_SESSION_MENU, FILES_TREE_MENU, EDITOR_SELECTION_MENU,
        BACKUP_DATA
    )

    /** 这些槽位只接受动作入口，不提供页面或提示词。 */
    val ACTION_ONLY = setOf(FILES_TREE_MENU, EDITOR_SELECTION_MENU)

    /** 这些槽位只接受数据选项：没有页面、没有 handler，由宿主渲染开关。 */
    val DATA_ONLY = setOf(BACKUP_DATA)

    /** 单插件在每个槽位可声明的入口数；超出在安装时拒绝，避免一个插件挤满列表。 */
    const val MAX_ENTRIES_PER_PLUGIN = 2

    /** 设置页槽位：只接受页面入口，交互型入口在聊天上下文里使用。 */
    fun isSettingsSlot(slot: String): Boolean = slot.startsWith("settings.")
}

/** 槽位入口的三种形态：HTML 页面、调用插件 runtime 的动作，或宿主渲染的数据选项。 */
enum class PluginUiEntryKind { PAGE, ACTION, DATA }

/**
 * `contributes.uiEntries` 声明的一个槽位入口。`page` 与 `handler` 必须且只能出现一个；
 * `page` 是包内 HTML 相对路径，`handler` 走插件 runtime 的 `ui.invoke`。
 */
data class PluginUiEntryRef(
    val id: String,
    val slot: String,
    val title: LocalizedText,
    val page: String? = null,
    val handler: String? = null,
    /** 仅动作入口：执行前让宿主弹确认框。 */
    val confirm: Boolean = false,
    val icon: String = "book"
) {
    val kind: PluginUiEntryKind
        get() = when {
            slot == PluginUiSlots.BACKUP_DATA -> PluginUiEntryKind.DATA
            page != null -> PluginUiEntryKind.PAGE
            else -> PluginUiEntryKind.ACTION
        }
}

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

    /** 插件安装或版本替换完成；只派发给该插件自身。 */
    const val PLUGIN_INSTALLED = "plugin.installed"

    /** 插件卸载生效前；此时安装目录与数据目录仍可访问。 */
    const val PLUGIN_UNINSTALLED = "plugin.uninstalled"

    /** 插件启用生效后。 */
    const val PLUGIN_ENABLED = "plugin.enabled"

    /** 插件停用生效前；此时插件仍可运行。 */
    const val PLUGIN_DISABLED = "plugin.disabled"

    /** 一次完整备份开始写入归档前；payload 带插件暂存目录，插件把额外数据写入其中。 */
    const val BACKUP_EXPORT_BEFORE = "backup.export.before"

    /** 一次还原成功后；payload 带备份包中该插件区段的暂存目录，插件从其中恢复数据。 */
    const val BACKUP_IMPORT_FINISHED = "backup.import.finished"

    /** 涉及备份文件与使用者数据的事件；声明这些事件必须同时声明 contributes.backup。 */
    val BACKUP = setOf(BACKUP_EXPORT_BEFORE, BACKUP_IMPORT_FINISHED)

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
        AGENT_PERMISSION_REVIEW,
        PLUGIN_INSTALLED,
        PLUGIN_UNINSTALLED,
        PLUGIN_ENABLED,
        PLUGIN_DISABLED,
        BACKUP_EXPORT_BEFORE,
        BACKUP_IMPORT_FINISHED
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

/** 插件提供商当前支持的兼容格式；第一版只开放 OpenAI Chat Completions。 */
object PluginProviderFormats {
    const val OPENAI = "openai"

    val ALL = setOf(OPENAI)
}

/**
 * `contributes.providers` 声明的提供商：[baseUrl] 指向插件自行启动或明确提供的
 * OpenAI 兼容服务，宿主复用现有 OpenAI 适配器请求，不内置新的上游协议。
 */
data class PluginProviderRef(
    val id: String,
    val name: LocalizedText,
    val description: LocalizedText,
    val format: String,
    val baseUrl: String
)

/**
 * `contributes.shizuku` 声明插件使用 Shizuku。使用者单独授权后，插件可在 Android 系统
 * 直接执行任意命令；声明只保留用途说明。
 */
data class PluginShizukuRef(val purpose: String)

/**
 * `contributes.backup` 声明插件接收备份与还原事件。事件会带出完整备份文件，
 * 可能包含 API Key、会话记录等敏感数据，因此必须由使用者单独授权。
 */
data class PluginBackupRef(val purpose: String)

/**
 * `contributes.modelAccess` 声明插件会调用使用者已配置的 AI 供应商。
 * 声明本身不授予 Key 读取权限；请求经宿主适配器发出，会计入对应渠道的用量。
 */
data class PluginModelAccessRef(val purpose: String)

/** `contributes.overlay.surfaces[]` 声明的宿主托管浮层；页面由宿主 WebView 加载。 */
data class PluginOverlaySurfaceRef(
    val id: String,
    val page: String,
    val width: Int = 240,
    val height: Int = 320,
    val draggable: Boolean = true,
    val snap: String = PluginOverlaySnap.NONE
)

/**
 * 浮层松手后的吸附轴。窗口几何由宿主完成，插件只消费 `snap` 事件调整贴边后的表现。
 */
object PluginOverlaySnap {
    const val NONE = "none"
    const val X = "x"
    const val Y = "y"
    const val BOTH = "both"

    val ALL = setOf(NONE, X, Y, BOTH)
}

/** `contributes.overlay` 声明插件使用系统悬浮窗；使用者需在插件详情中单独授权。 */
data class PluginOverlayRef(
    val purpose: String,
    val surfaces: List<PluginOverlaySurfaceRef> = emptyList()
)

/** `contributes.executionBackends[].capabilities` 声明执行后端具备的能力与工作区行为。 */
data class PluginExecutionBackendCapabilitiesRef(
    val filesystem: Boolean = true,
    val command: Boolean = true,
    val terminal: Boolean = false,
    val fileWatch: Boolean = false,
    val portForward: Boolean = false,
    val isRemoteEnvironment: Boolean = true,
    val workspaceDiscovery: String = PluginBackendDiscoveryModes.SCAN,
    val projectConfigLocation: String = PluginBackendConfigLocations.APP_PRIVATE_DIR
)

object PluginBackendDiscoveryModes {
    const val SCAN = "scan"
    const val REGISTER = "register"
    const val HYBRID = "hybrid"

    val ALL = setOf(SCAN, REGISTER, HYBRID)
}

object PluginBackendConfigLocations {
    const val WORKSPACE_DIR = "workspaceDir"
    const val APP_PRIVATE_DIR = "appPrivateDir"

    val ALL = setOf(WORKSPACE_DIR, APP_PRIVATE_DIR)
}

/** `contributes.executionBackends[].handlers` 声明各执行能力对应的 runtime handler 名称。 */
data class PluginExecutionBackendHandlersRef(
    val fs: String? = null,
    val exec: String? = null,
    val execStream: String? = null,
    val execHealth: String? = null,
    val terminal: String? = null,
    val workspaceList: String? = null,
    val workspaceCreate: String? = null,
    val workspaceDelete: String? = null,
    val workspaceSelect: String? = null
)

/** `contributes.executionBackends[]` 声明一个插件提供的执行后端。 */
data class PluginExecutionBackendRef(
    val id: String,
    val name: LocalizedText,
    val description: LocalizedText = LocalizedText.single(""),
    val capabilities: PluginExecutionBackendCapabilitiesRef = PluginExecutionBackendCapabilitiesRef(),
    val handlers: PluginExecutionBackendHandlersRef
)

/** `contributes.workflows[].capabilities` 声明插件工作流的能力集。 */
data class PluginWorkflowCapabilitiesRef(
    val streaming: Boolean = true,
    val tools: Boolean = true,
    val compaction: Boolean = false,
    val titleGeneration: Boolean = false
)

/** `contributes.workflows[].handlers` 声明插件工作流对应的 runtime handler 名称。 */
data class PluginWorkflowHandlersRef(
    val startTurn: String,
    val cancelTurn: String,
    val compact: String? = null,
    val generateTitle: String? = null
)

/** `contributes.workflows[]` 声明一个插件提供的 Agent 工作流。 */
data class PluginWorkflowRef(
    val id: String,
    val title: LocalizedText,
    val description: LocalizedText = LocalizedText.single(""),
    val capabilities: PluginWorkflowCapabilitiesRef = PluginWorkflowCapabilitiesRef(),
    val handlers: PluginWorkflowHandlersRef
)

/** `contributes.sessions[].handlers` 声明外部会话源对应的 runtime handler 名称。 */
data class PluginExternalSessionHandlersRef(
    val refresh: String? = null,
    val readMessages: String,
    val createSession: String? = null,
    val deleteSession: String? = null,
    val renameSession: String? = null,
    val pinSession: String? = null
)

/** `contributes.sessions[]` 声明一个插件提供的外部会话源。 */
data class PluginExternalSessionRef(
    val id: String,
    val title: LocalizedText,
    val description: LocalizedText = LocalizedText.single(""),
    val handlers: PluginExternalSessionHandlersRef
)

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
    val skills: List<PluginContributionRef>,
    val agents: List<PluginContributionRef>,
    val workspaceTools: List<PluginWorkspaceToolRef> = emptyList(),
    val uiEntries: List<PluginUiEntryRef> = emptyList(),
    val runtime: PluginRuntimeRef? = null,
    val tools: List<PluginToolRef> = emptyList(),
    val hooks: List<PluginHookRef> = emptyList(),
    val mcp: List<PluginMcpRef> = emptyList(),
    val providers: List<PluginProviderRef> = emptyList(),
    val executionBackends: List<PluginExecutionBackendRef> = emptyList(),
    val workflows: List<PluginWorkflowRef> = emptyList(),
    val sessions: List<PluginExternalSessionRef> = emptyList(),
    val shizuku: PluginShizukuRef? = null,
    val overlay: PluginOverlayRef? = null,
    val backup: PluginBackupRef? = null,
    val modelAccess: PluginModelAccessRef? = null,
    val configPage: String? = null,
    /** 内置图标名，或包内 `.png` / `.webp` 相对路径；缺省用宿主默认图标。 */
    val icon: String? = null
) {
    /** 清单声明的贡献总数。 */
    val contributionCount: Int
        get() = skills.size + agents.size + workspaceTools.size + uiEntries.size +
            tools.size + hooks.size + mcp.size + providers.size + executionBackends.size +
            workflows.size + sessions.size +
            (if (shizuku != null) 1 else 0) +
            (if (overlay != null) 1 + overlay.surfaces.size else 0) + (if (backup != null) 1 else 0) +
            (if (modelAccess != null) 1 else 0) + (if (configPage != null) 1 else 0)
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
