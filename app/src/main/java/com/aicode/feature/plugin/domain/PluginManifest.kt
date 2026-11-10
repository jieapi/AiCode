package com.aicode.feature.plugin.domain

/**
 * 宿主认识的插件能力。清单只能申请这里列出的能力，插件无法自定义新权限。
 * 本构建实现技能、子代理、工作区工具、AI 工具与生命周期 hook；其余能力在对应功能落地前一律按不支持拒绝。
 */
enum class PluginPermission(val id: String) {
    SKILLS_REGISTER("skills.register"),
    AGENTS_REGISTER("agents.register"),
    WORKSPACE_TOOLS("workspace.tools"),
    TOOLS_REGISTER("tools.register"),
    AGENT_EVENTS("agent.events"),
    MCP_REGISTER("mcp.register"),
    MCP_CONNECT("mcp.connect");

    companion object {
        fun fromId(id: String): PluginPermission? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 一条权限申请。[ids] 为该权限覆盖的本插件贡献局部 ID（`scope.ids`）；[events] 只对
 * [PluginPermission.AGENT_EVENTS] 有意义，为该权限覆盖的生命周期事件（`scope.events`）。
 */
data class PluginPermissionRequest(
    val permission: PluginPermission,
    val required: Boolean,
    val reason: LocalizedText,
    val ids: List<String>,
    val events: List<String> = emptyList()
)

/** `contributes` 里指向包内 Markdown 文件的一条贡献。[file] 相对于包根目录。 */
data class PluginContributionRef(val id: String, val file: String)

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

    val ALL = setOf(AGENT_RUN_STARTED, AGENT_RUN_FINISHED)
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
    val permissions: List<PluginPermissionRequest>,
    val skills: List<PluginContributionRef>,
    val agents: List<PluginContributionRef>,
    val workspaceTools: List<PluginWorkspaceToolRef> = emptyList(),
    val runtime: PluginRuntimeRef? = null,
    val tools: List<PluginToolRef> = emptyList(),
    val hooks: List<PluginHookRef> = emptyList(),
    val mcp: List<PluginMcpRef> = emptyList()
) {
    /** 清单声明的贡献总数。 */
    val contributionCount: Int get() = skills.size + agents.size + workspaceTools.size + tools.size + hooks.size + mcp.size

    fun requestFor(permission: PluginPermission): PluginPermissionRequest? =
        permissions.firstOrNull { it.permission == permission }

    /** 必须获得授权，插件才允许启用的能力。 */
    val requiredPermissions: Set<PluginPermission>
        get() = permissions.filter { it.required }.map { it.permission }.toSet()
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
    UNSUPPORTED_PERMISSION,
    API_INCOMPATIBLE,
    DUPLICATE_ID,
    PERMISSION_SCOPE_MISMATCH,
    FILE_MISSING,
    CONTENT_INVALID,
    STORAGE_FAILED,
    PREVIEW_EXPIRED,
    PERMISSION_NOT_GRANTED
}

data class PluginError(val code: PluginErrorCode, val detail: String = "")
