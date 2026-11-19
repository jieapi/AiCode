package com.aicode.feature.plugin.domain

import com.aicode.feature.agent.domain.mcp.McpConfigParser
import com.aicode.feature.agent.domain.mcp.McpServerConfig
import com.aicode.feature.agent.domain.skill.Skill
import com.aicode.feature.agent.domain.skill.SkillParser
import com.aicode.feature.agent.domain.subagent.AgentDefinition
import com.aicode.feature.agent.domain.subagent.AgentDefinitionParser
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 模型与使用者看到的贡献名：`<插件ID>:<局部ID>`。命名空间由宿主生成，
 * 插件无法覆盖内置功能、使用者自建内容或其他插件的同名内容。
 */
fun pluginContributionName(pluginId: String, localId: String): String = "$pluginId:$localId"

data class PluginSkill(val localId: String, val skill: Skill)

data class PluginAgent(val localId: String, val definition: AgentDefinition)

data class PluginWorkspaceTool(
    val pluginId: String,
    val localId: String,
    val title: LocalizedText,
    val description: LocalizedText,
    val icon: String,
    val category: String,
    val pageFile: String,
    val installDir: File,
    val pluginVersion: String
) {
    /** 唯一的全局工具键名：`<插件ID>:<局部ID>` */
    val key: String get() = pluginContributionName(pluginId, localId)
}

/** 已校验存在性的插件 HTML 页面；[slot] 非空时表示它由某个槽位入口打开。 */
data class PluginPage(
    val pluginId: String,
    val localId: String,
    val title: LocalizedText,
    val slot: String?,
    val pageFile: String,
    val icon: String,
    val installDir: File,
    val pluginVersion: String = "",
    val installRevision: String? = null
) {
    val key: String get() = pluginContributionName(pluginId, localId)

    /** WebView/页面桥缓存键：安装 revision 变化后强制创建新实例，避免开发重载继续使用旧页面。 */
    val cacheKey: String get() = listOfNotNull(key, pluginVersion, installRevision)
        .filter { it.isNotBlank() }
        .joinToString("@")
}

/** 已解析的槽位入口：页面或运行时动作。 */
data class PluginUiEntry(
    val pluginId: String,
    val localId: String,
    val title: LocalizedText,
    val slot: String,
    val icon: String,
    val page: PluginPage? = null,
    val handler: String? = null,
    val confirm: Boolean = false
) {
    val key: String get() = pluginContributionName(pluginId, localId)

    val kind: PluginUiEntryKind
        get() = when {
            slot == PluginUiSlots.BACKUP_DATA -> PluginUiEntryKind.DATA
            page != null -> PluginUiEntryKind.PAGE
            else -> PluginUiEntryKind.ACTION
        }

    /** 仅页面入口有 WebView 缓存键。 */
    val cacheKey: String? get() = page?.cacheKey
}

/** 已校验存在性的插件业务入口文件。 */
/** 已解析的插件运行时：进程入口、启动命令与依赖，工具与 hook 共用。 */
data class PluginRuntime(
    val pluginId: String,
    val type: String,
    val installDir: File,
    val entryFile: File,
    val command: List<String>,
    val dependencies: PluginDependency? = null,
    val resident: Boolean = false
)

/** 已校验存在的依赖输入文件。 */
data class PluginDependency(val tool: String, val file: File)

/** 已解析的 AI 工具贡献：schema 已读入，handler 留给运行时按需调用。 */
data class PluginTool(
    val pluginId: String,
    val localId: String,
    val title: LocalizedText,
    val description: LocalizedText,
    val inputSchema: JsonObject,
    val handler: String,
    val runtime: PluginRuntime
) {
    val key: String get() = pluginContributionName(pluginId, localId)
}

/** 已解析的生命周期 hook；[runtime] 与工具共用同一个插件运行时。 */
data class PluginHook(
    val pluginId: String,
    val localId: String,
    val event: String,
    val handler: String,
    val runtime: PluginRuntime,
    val mode: String = PluginHookModes.OBSERVE
) {
    val key: String get() = pluginContributionName(pluginId, localId)
}

/** 已解析的插件 MCP server；config.name 已加上插件命名空间，避免覆盖用户配置。 */
data class PluginMcpServer(
    val pluginId: String,
    val localId: String,
    val originalName: String,
    val config: McpServerConfig
) {
    val key: String get() = "$pluginId:$localId:$originalName"
}

/** 已解析的插件提供商：宿主只读取兼容服务地址与模型，不接触上游协议。 */
data class PluginProviderDefinition(
    val pluginId: String,
    val localId: String,
    val name: LocalizedText,
    val description: LocalizedText,
    val format: String,
    val baseUrl: String,
    val pluginVersion: String
) {
    val key: String get() = pluginContributionName(pluginId, localId)
}

/** 已解析的插件执行后端定义；由适配器转成 [com.aicode.feature.agent.domain.container.ExecutionBackend]。 */
data class PluginExecutionBackendDefinition(
    val pluginId: String,
    val localId: String,
    val name: LocalizedText,
    val description: LocalizedText,
    val capabilities: PluginExecutionBackendCapabilitiesRef,
    val handlers: PluginExecutionBackendHandlersRef,
    val runtime: PluginRuntime,
    val pluginVersion: String
) {
    val key: String get() = pluginContributionName(pluginId, localId)
}

/** 已解析的插件 Agent 工作流定义；由适配器转成 [com.aicode.feature.agent.domain.workflow.AgentWorkflow]。 */
data class PluginWorkflowDefinition(
    val pluginId: String,
    val localId: String,
    val title: LocalizedText,
    val description: LocalizedText,
    val capabilities: PluginWorkflowCapabilitiesRef,
    val handlers: PluginWorkflowHandlersRef,
    val runtime: PluginRuntime,
    val pluginVersion: String
) {
    val key: String get() = pluginContributionName(pluginId, localId)
}

/** 已解析的插件外部会话源定义。 */
data class PluginExternalSessionDefinition(
    val pluginId: String,
    val localId: String,
    val title: LocalizedText,
    val description: LocalizedText,
    val handlers: PluginExternalSessionHandlersRef,
    val runtime: PluginRuntime,
    val pluginVersion: String
) {
    val key: String get() = pluginContributionName(pluginId, localId)
}

/** 已解析的 Shizuku 声明：仅用途说明，授权后可直接执行任意命令。 */
data class PluginShizukuDefinition(
    val pluginId: String,
    val purpose: String
)

/** 已解析的备份事件声明：仅用途说明，授权后才能收到备份与还原文件。 */
data class PluginBackupDefinition(
    val pluginId: String,
    val purpose: String
)

/** 已解析的模型访问声明：插件可调用使用者已配置的供应商，Key 仍由宿主持有。 */
data class PluginModelAccessDefinition(
    val pluginId: String,
    val purpose: String
)

/** 已校验存在性的宿主托管浮层页面。 */
data class PluginOverlaySurface(
    val pluginId: String,
    val localId: String,
    val pageFile: String,
    val installDir: File,
    val pluginVersion: String,
    val width: Int,
    val height: Int,
    val draggable: Boolean,
    val snap: String = PluginOverlaySnap.NONE,
    val installRevision: String? = null
) {
    val key: String get() = pluginContributionName(pluginId, localId)
}

/**
 * 已解析的插件图标：内置图标名或已校验存在的包内位图，两者只会有一个非空。
 * 清单值以 `.png` / `.webp` 结尾时按包内相对路径处理，其余按内置图标名处理。
 */
data class PluginIcon(val name: String? = null, val file: File? = null)

/** 已解析的悬浮窗声明；页面由宿主 WebView 加载。 */
data class PluginOverlayDefinition(
    val pluginId: String,
    val purpose: String,
    val surfaces: List<PluginOverlaySurface>
)

data class PluginContent(
    val icon: PluginIcon? = null,
    val skills: List<PluginSkill> = emptyList(),
    val agents: List<PluginAgent> = emptyList(),
    val workspaceTools: List<PluginWorkspaceTool> = emptyList(),
    val uiEntries: List<PluginUiEntry> = emptyList(),
    val runtime: PluginRuntime? = null,
    val tools: List<PluginTool> = emptyList(),
    val hooks: List<PluginHook> = emptyList(),
    val mcp: List<PluginMcpServer> = emptyList(),
    val providers: List<PluginProviderDefinition> = emptyList(),
    val executionBackends: List<PluginExecutionBackendDefinition> = emptyList(),
    val workflows: List<PluginWorkflowDefinition> = emptyList(),
    val sessions: List<PluginExternalSessionDefinition> = emptyList(),
    val shizuku: PluginShizukuDefinition? = null,
    val overlay: PluginOverlayDefinition? = null,
    val backup: PluginBackupDefinition? = null,
    val modelAccess: PluginModelAccessDefinition? = null,
    val configPage: PluginPage? = null
)

sealed interface ContentLoadResult {
    data class Ok(val content: PluginContent) : ContentLoadResult
    data class Failed(val errors: List<PluginError>) : ContentLoadResult
}

/**
 * 读取清单引用的 Markdown 贡献文件并转换成技能 / 子代理定义。
 * 预览（暂存目录）与加载已安装插件共用这一份校验，保证装进去的和读出来的是同一套规则。
 */
object PluginContentLoader {
    const val MAX_CONTENT_BYTES = 512 * 1024
    const val MAX_SCHEMA_BYTES = 256 * 1024
    const val MAX_RUNTIME_BYTES = 512 * 1024
    const val MAX_MCP_BYTES = 256 * 1024
    const val MAX_ICON_BYTES = 256 * 1024

    private val WHITESPACE = Regex("\\s+")

    fun load(dir: File, manifest: PluginManifest, installRevision: String? = null): ContentLoadResult {
        val errors = mutableListOf<PluginError>()
        val icon = manifest.icon?.let { loadIcon(dir, it, errors) }
        val skills = manifest.skills.mapNotNull { loadSkill(dir, manifest.id, it, errors) }
        val agents = manifest.agents.mapNotNull { loadAgent(dir, manifest.id, it, errors) }
        val workspaceTools = manifest.workspaceTools.map {
            loadWorkspaceTool(dir, manifest.id, manifest.version.toString(), it, errors)
        }
        val uiEntries = manifest.uiEntries.mapNotNull {
            loadUiEntry(dir, manifest.id, manifest.version.toString(), it, errors, installRevision)
        }
        val runtime = manifest.runtime?.let { loadRuntime(dir, manifest.id, it, errors) }
        if ((manifest.tools.isNotEmpty() || manifest.hooks.isNotEmpty() || manifest.executionBackends.isNotEmpty() || manifest.workflows.isNotEmpty() || manifest.sessions.isNotEmpty()) && runtime == null) {
            errors += PluginError(PluginErrorCode.CONTENT_INVALID, "runtime")
        }
        val tools = manifest.tools.mapNotNull { loadTool(dir, manifest.id, it, runtime, errors) }
        val hooks = manifest.hooks.mapNotNull { loadHook(manifest.id, it, runtime) }
        val mcp = manifest.mcp.flatMap { loadMcp(dir, manifest.id, it, errors) }
        val providers = manifest.providers.map { provider ->
            PluginProviderDefinition(
                pluginId = manifest.id,
                localId = provider.id,
                name = provider.name,
                description = provider.description,
                format = provider.format,
                baseUrl = provider.baseUrl,
                pluginVersion = manifest.version.toString()
            )
        }
        val executionBackends = if (runtime != null) {
            manifest.executionBackends.map { backend ->
                PluginExecutionBackendDefinition(
                    pluginId = manifest.id,
                    localId = backend.id,
                    name = backend.name,
                    description = backend.description,
                    capabilities = backend.capabilities,
                    handlers = backend.handlers,
                    runtime = runtime,
                    pluginVersion = manifest.version.toString()
                )
            }
        } else {
            emptyList()
        }
        val workflows = if (runtime != null) {
            manifest.workflows.map { workflow ->
                PluginWorkflowDefinition(
                    pluginId = manifest.id,
                    localId = workflow.id,
                    title = workflow.title,
                    description = workflow.description,
                    capabilities = workflow.capabilities,
                    handlers = workflow.handlers,
                    runtime = runtime,
                    pluginVersion = manifest.version.toString()
                )
            }
        } else {
            emptyList()
        }
        val sessions = if (runtime != null) {
            manifest.sessions.map { session ->
                PluginExternalSessionDefinition(
                    pluginId = manifest.id,
                    localId = session.id,
                    title = session.title,
                    description = session.description,
                    handlers = session.handlers,
                    runtime = runtime,
                    pluginVersion = manifest.version.toString()
                )
            }
        } else {
            emptyList()
        }
        val configPage = manifest.configPage?.let {
            loadConfigPage(dir, manifest.id, manifest.version.toString(), it, errors, installRevision)
        }
        val shizuku = manifest.shizuku?.let {
            PluginShizukuDefinition(
                pluginId = manifest.id,
                purpose = it.purpose
            )
        }
        val backup = manifest.backup?.let {
            PluginBackupDefinition(
                pluginId = manifest.id,
                purpose = it.purpose
            )
        }
        val modelAccess = manifest.modelAccess?.let {
            PluginModelAccessDefinition(
                pluginId = manifest.id,
                purpose = it.purpose
            )
        }
        val overlay = manifest.overlay?.let { declaration ->
            val surfaces = declaration.surfaces.mapNotNull { surface ->
                loadOverlaySurface(dir, manifest.id, manifest.version.toString(), surface, errors, installRevision)
            }
            if (surfaces.size == declaration.surfaces.size) {
                PluginOverlayDefinition(
                    pluginId = manifest.id,
                    purpose = declaration.purpose,
                    surfaces = surfaces
                )
            } else {
                null
            }
        }
        return if (errors.isEmpty()) {
            ContentLoadResult.Ok(
                PluginContent(
                    icon = icon,
                    skills = skills,
                    agents = agents,
                    workspaceTools = workspaceTools,
                    uiEntries = uiEntries,
                    runtime = runtime,
                    tools = tools,
                    hooks = hooks,
                    mcp = mcp,
                    providers = providers,
                    executionBackends = executionBackends,
                    workflows = workflows,
                    sessions = sessions,
                    shizuku = shizuku,
                    overlay = overlay,
                    backup = backup,
                    modelAccess = modelAccess,
                    configPage = configPage
                )
            )
        } else {
            ContentLoadResult.Failed(errors)
        }
    }

    private fun loadIcon(dir: File, value: String, errors: MutableList<PluginError>): PluginIcon? {
        if (!PluginManifestParser.isIconFilePath(value)) return PluginIcon(name = value)
        val file = File(dir, value)
        val inside = file.canonicalFile.toPath().startsWith(dir.canonicalFile.toPath())
        if (!inside || !file.isFile) {
            errors += PluginError(PluginErrorCode.FILE_MISSING, value)
            return null
        }
        if (file.length() > MAX_ICON_BYTES) {
            errors += PluginError(PluginErrorCode.CONTENT_INVALID, "$value: too large")
            return null
        }
        return PluginIcon(file = file)
    }

    private fun loadMcp(
        dir: File,
        pluginId: String,
        ref: PluginMcpRef,
        errors: MutableList<PluginError>
    ): List<PluginMcpServer> {
        val text = readFileText(dir, ref.file, MAX_MCP_BYTES, errors) ?: return emptyList()
        val servers = McpConfigParser.parse(text, "PluginContent")
        if (servers.isEmpty()) {
            errors += PluginError(PluginErrorCode.CONTENT_INVALID, "${ref.file}: no servers")
            return emptyList()
        }
        val seen = mutableSetOf<String>()
        return servers.mapNotNull { server ->
            val namespaced = namespacedMcpName(pluginId, ref.id, server.name)
            if (!seen.add(namespaced)) {
                errors += PluginError(PluginErrorCode.DUPLICATE_ID, "${ref.file}:${server.name}")
                return@mapNotNull null
            }
            PluginMcpServer(
                pluginId = pluginId,
                localId = ref.id,
                originalName = server.name,
                config = server.copy(name = namespaced)
            )
        }
    }

    private fun namespacedMcpName(pluginId: String, localId: String, serverName: String): String =
        "plugin__${sanitizeMcpPart(pluginId)}__${sanitizeMcpPart(localId)}__${sanitizeMcpPart(serverName)}"

    private fun sanitizeMcpPart(value: String): String = value.map { c ->
        if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-') c else '_'
    }.joinToString("").ifBlank { "server" }

    private fun loadRuntime(
        dir: File,
        pluginId: String,
        ref: PluginRuntimeRef,
        errors: MutableList<PluginError>
    ): PluginRuntime? {
        val file = File(dir, ref.entry)
        val inside = file.canonicalFile.toPath().startsWith(dir.canonicalFile.toPath())
        if (!inside || !file.isFile) {
            errors += PluginError(PluginErrorCode.FILE_MISSING, ref.entry)
            return null
        }
        if (file.length() > MAX_RUNTIME_BYTES) {
            errors += PluginError(PluginErrorCode.CONTENT_INVALID, "${ref.entry}: too large")
            return null
        }
        val dependency = ref.dependencies?.let { dep ->
            val depFile = File(dir, dep.file)
            val depInside = depFile.canonicalFile.toPath().startsWith(dir.canonicalFile.toPath())
            if (!depInside || !depFile.isFile) {
                errors += PluginError(PluginErrorCode.FILE_MISSING, dep.file)
                return null
            }
            PluginDependency(dep.tool, depFile)
        }
        val command = ref.command ?: defaultCommand(ref.type, ref.entry)
        val effectiveCommand = if (ref.type == PluginRuntimeTypes.PYTHON && File(dir, ".venv/bin/python").isFile) {
            listOf(".venv/bin/python") + command.drop(1)
        } else command
        return PluginRuntime(
            pluginId = pluginId,
            type = ref.type,
            installDir = dir,
            entryFile = file,
            command = effectiveCommand,
            dependencies = dependency,
            resident = ref.resident
        )
    }

    private fun defaultCommand(type: String, entry: String): List<String> = when (type) {
        PluginRuntimeTypes.JAVASCRIPT -> listOf("node", entry)
        PluginRuntimeTypes.PYTHON -> listOf("python3", entry)
        else -> emptyList()
    }

    private fun loadTool(
        dir: File,
        pluginId: String,
        ref: PluginToolRef,
        runtime: PluginRuntime?,
        errors: MutableList<PluginError>
    ): PluginTool? {
        if (runtime == null) return null
        val text = readFileText(dir, ref.inputSchema, MAX_SCHEMA_BYTES, errors) ?: return null
        val schema = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
        if (schema == null) {
            errors += PluginError(PluginErrorCode.CONTENT_INVALID, "${ref.inputSchema}: not object")
            return null
        }
        val type = (schema["type"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        if (type != "object") {
            errors += PluginError(PluginErrorCode.CONTENT_INVALID, "${ref.inputSchema}: type")
            return null
        }
        return PluginTool(
            pluginId = pluginId,
            localId = ref.id,
            title = ref.title,
            description = ref.description,
            inputSchema = schema,
            handler = ref.handler,
            runtime = runtime
        )
    }

    private fun loadHook(
        pluginId: String,
        ref: PluginHookRef,
        runtime: PluginRuntime?
    ): PluginHook? {
        if (runtime == null) return null
        return PluginHook(
            pluginId = pluginId,
            localId = ref.id,
            event = ref.event,
            handler = ref.handler,
            runtime = runtime,
            mode = ref.mode
        )
    }

    private fun loadWorkspaceTool(
        dir: File,
        pluginId: String,
        pluginVersion: String,
        ref: PluginWorkspaceToolRef,
        errors: MutableList<PluginError>
    ): PluginWorkspaceTool {
        val file = File(dir, ref.pageFile)
        val inside = file.canonicalFile.toPath().startsWith(dir.canonicalFile.toPath())
        val isHtml = ref.pageFile.endsWith(".html", ignoreCase = true) ||
            ref.pageFile.endsWith(".htm", ignoreCase = true)
        if (!isHtml) {
            errors += PluginError(PluginErrorCode.CONTENT_INVALID, "${ref.pageFile}: workspace tool page must be html")
        } else if (!inside || !file.isFile) {
            errors += PluginError(PluginErrorCode.FILE_MISSING, ref.pageFile)
        } else if (file.length() > MAX_CONTENT_BYTES) {
            errors += PluginError(PluginErrorCode.CONTENT_INVALID, "${ref.pageFile}: too large")
        }
        return PluginWorkspaceTool(
            pluginId = pluginId,
            localId = ref.id,
            title = ref.title,
            description = ref.description,
            icon = ref.icon,
            category = ref.category,
            pageFile = ref.pageFile,
            installDir = dir,
            pluginVersion = pluginVersion
        )
    }

    /** 浮层页面只校验存在性与大小；页面由宿主 WebView 加载。 */
    private fun loadOverlaySurface(
        dir: File,
        pluginId: String,
        pluginVersion: String,
        ref: PluginOverlaySurfaceRef,
        errors: MutableList<PluginError>,
        installRevision: String? = null
    ): PluginOverlaySurface? {
        val file = File(dir, ref.page)
        val inside = file.canonicalFile.toPath().startsWith(dir.canonicalFile.toPath())
        if (!inside || !file.isFile) {
            errors += PluginError(PluginErrorCode.FILE_MISSING, ref.page)
            return null
        }
        if (file.length() > MAX_CONTENT_BYTES) {
            errors += PluginError(PluginErrorCode.CONTENT_INVALID, "${ref.page}: too large")
            return null
        }
        return PluginOverlaySurface(
            pluginId = pluginId,
            localId = ref.id,
            pageFile = ref.page,
            installDir = dir,
            pluginVersion = pluginVersion,
            installRevision = installRevision,
            width = ref.width,
            height = ref.height,
            draggable = ref.draggable,
            snap = ref.snap
        )
    }

    /** 槽位页 HTML 只校验存在性与大小，不解析内容；页面由宿主 WebView 加载。 */
    private fun loadUiEntry(
        dir: File,
        pluginId: String,
        pluginVersion: String,
        ref: PluginUiEntryRef,
        errors: MutableList<PluginError>,
        installRevision: String? = null
    ): PluginUiEntry? {
        val page = ref.page?.let { loadUiPage(dir, pluginId, pluginVersion, ref, errors, installRevision) }
        if (ref.page != null && page == null) return null
        return PluginUiEntry(
            pluginId = pluginId,
            localId = ref.id,
            title = ref.title,
            slot = ref.slot,
            icon = ref.icon,
            page = page,
            handler = ref.handler,
            confirm = ref.confirm
        )
    }

    /** 页面入口的 HTML 只校验存在性与大小，不解析内容。 */
    private fun loadUiPage(
        dir: File,
        pluginId: String,
        pluginVersion: String,
        ref: PluginUiEntryRef,
        errors: MutableList<PluginError>,
        installRevision: String? = null
    ): PluginPage? {
        val page = ref.page ?: return null
        val file = File(dir, page)
        val inside = file.canonicalFile.toPath().startsWith(dir.canonicalFile.toPath())
        if (!inside || !file.isFile) {
            errors += PluginError(PluginErrorCode.FILE_MISSING, page)
            return null
        }
        if (file.length() > MAX_CONTENT_BYTES) {
            errors += PluginError(PluginErrorCode.CONTENT_INVALID, "$page: too large")
            return null
        }
        return PluginPage(
            pluginId = pluginId,
            localId = ref.id,
            title = ref.title,
            slot = ref.slot,
            pageFile = page,
            icon = ref.icon,
            installDir = dir,
            pluginVersion = pluginVersion,
            installRevision = installRevision
        )
    }

    /** 插件专属配置页 HTML 只校验存在性与大小，不解析内容；页面由宿主 WebView 加载。 */
    private fun loadConfigPage(
        dir: File,
        pluginId: String,
        pluginVersion: String,
        relativePath: String,
        errors: MutableList<PluginError>,
        installRevision: String? = null
    ): PluginPage? {
        val file = File(dir, relativePath)
        val inside = file.canonicalFile.toPath().startsWith(dir.canonicalFile.toPath())
        if (!inside || !file.isFile) {
            errors += PluginError(PluginErrorCode.FILE_MISSING, relativePath)
            return null
        }
        if (file.length() > MAX_CONTENT_BYTES) {
            errors += PluginError(PluginErrorCode.CONTENT_INVALID, "$relativePath: too large")
            return null
        }
        return PluginPage(
            pluginId = pluginId,
            localId = "config",
            title = LocalizedText(mapOf("default" to "Configuration", "zh-CN" to "插件配置", "en" to "Configuration")),
            slot = null,
            pageFile = relativePath,
            icon = "settings",
            installDir = dir,
            pluginVersion = pluginVersion,
            installRevision = installRevision
        )
    }

    private fun loadSkill(
        dir: File,
        pluginId: String,
        ref: PluginContributionRef,
        errors: MutableList<PluginError>
    ): PluginSkill? {
        val text = readText(dir, ref, errors) ?: return null
        val parsed = SkillParser.parseText(text, ref.id)
        if (parsed.instructions.isBlank()) {
            errors += PluginError(PluginErrorCode.CONTENT_INVALID, "${ref.file}: empty")
            return null
        }
        val skill = parsed.copy(
            name = pluginContributionName(pluginId, ref.id),
            // 描述会进系统提示词的技能清单，压成单行，避免插件借换行伪造清单条目
            description = singleLine(parsed.description),
            dirPath = null
        )
        return PluginSkill(ref.id, skill)
    }

    private fun loadAgent(
        dir: File,
        pluginId: String,
        ref: PluginContributionRef,
        errors: MutableList<PluginError>
    ): PluginAgent? {
        val text = readText(dir, ref, errors) ?: return null
        val parsed = AgentDefinitionParser.parseText(text, ref.id)
        if (parsed == null) {
            errors += PluginError(PluginErrorCode.CONTENT_INVALID, "${ref.file}: invalid")
            return null
        }
        // 供应商与模型绑定会动用使用者的付费渠道，插件子代理一律继承父会话
        if (parsed.providerId != null || parsed.model != null) {
            errors += PluginError(PluginErrorCode.CONTENT_INVALID, "${ref.file}: provider/model")
            return null
        }
        val definition = parsed.copy(
            name = pluginContributionName(pluginId, ref.id),
            description = singleLine(parsed.description),
            filePath = null
        )
        return PluginAgent(ref.id, definition)
    }

    private fun readText(dir: File, ref: PluginContributionRef, errors: MutableList<PluginError>): String? =
        readFileText(dir, ref.file, MAX_CONTENT_BYTES, errors)

    private fun readFileText(
        dir: File,
        relativePath: String,
        maxBytes: Int,
        errors: MutableList<PluginError>
    ): String? {
        val file = File(dir, relativePath)
        val inside = file.canonicalFile.toPath().startsWith(dir.canonicalFile.toPath())
        if (!inside || !file.isFile) {
            errors += PluginError(PluginErrorCode.FILE_MISSING, relativePath)
            return null
        }
        if (file.length() > maxBytes) {
            errors += PluginError(PluginErrorCode.CONTENT_INVALID, "$relativePath: too large")
            return null
        }
        return file.readText(Charsets.UTF_8)
    }

    private fun singleLine(text: String): String = text.replace(WHITESPACE, " ").trim()
}
