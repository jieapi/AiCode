package com.aicode.feature.agent.domain.container

import com.aicode.feature.terminal.domain.TerminalSessionProvider
import com.aicode.feature.workspace.domain.FileAccessProvider

/** 工作区发现策略：扫描目录 / 由后端主动注册 / 混合模式。 */
enum class WorkspaceDiscoveryMode {
    SCAN,
    REGISTER,
    HYBRID
}

/** 项目级 `.aicode` 配置（记忆、规则等）的存储位置。 */
enum class ProjectConfigLocation {
    WORKSPACE_DIR,
    APP_PRIVATE_DIR
}

/** 执行后端声明的能力集合与环境语义。 */
data class BackendCapabilities(
    val filesystem: Boolean = true,
    val command: Boolean = true,
    val terminal: Boolean = true,
    val fileWatch: Boolean = false,
    val portForward: Boolean = false,
    val isRemoteEnvironment: Boolean = false,
    val workspaceDiscovery: WorkspaceDiscoveryMode = WorkspaceDiscoveryMode.SCAN,
    val projectConfigLocation: ProjectConfigLocation = ProjectConfigLocation.WORKSPACE_DIR
)

/**
 * 统一的执行后端抽象（本地 PRoot、远程 SSH 或插件提供的自定义后端）。
 *
 * 汇聚命令执行（[commandEngine]）、文件访问（[fileAccess]）与可选终端会话（[terminalSessionProvider]），
 * 以及提示词与配置缓存所需的环境描述元数据。
 */
interface ExecutionBackend {
    /** 后端全局唯一 ID，内置如 `"builtin:local-proot"`，插件后端格式为 `"<pluginId>:<localId>"`。 */
    val id: String

    val instanceId: String
        get() = id

    /** 面向 UI 与提示词的展示名称。 */
    val displayName: String

    /** 后端声明的能力矩阵。 */
    val capabilities: BackendCapabilities

    /** 注入系统提示词 `{{AICODE_ENVIRONMENT}}` 的环境描述。 */
    fun environmentDescription(): String

    /** 提示词源缓存使用的环境隔离键；环境身份变化时需返回不同键。 */
    fun environmentCacheKey(): String

    /** 面向 AI 展示的工作区根路径形式（如 `~/workspace`）。 */
    fun workspaceRootDisplay(workspacePath: String): String

    /** 项目级私有资源在 App 私有目录下的隔离目录名（如 `<name>-<hash>`）。 */
    fun projectConfigKey(workspacePath: String): String

    /** 命令执行引擎。 */
    val commandEngine: CommandEngine

    /** 文件读写后端。 */
    val fileAccess: FileAccessProvider

    /** 终端会话后端；不支持终端时返回 null。 */
    val terminalSessionProvider: TerminalSessionProvider?
}
