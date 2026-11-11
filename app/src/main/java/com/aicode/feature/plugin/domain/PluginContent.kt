package com.aicode.feature.plugin.domain

import com.aicode.feature.agent.domain.mcp.McpConfigParser
import com.aicode.feature.agent.domain.mcp.McpServerConfig
import com.aicode.feature.agent.domain.skill.Skill
import com.aicode.feature.agent.domain.skill.SkillParser
import com.aicode.feature.agent.domain.subagent.AgentDefinition
import com.aicode.feature.agent.domain.subagent.AgentDefinitionParser
import com.aicode.feature.settings.domain.model.AdaptiveCardRoot
import com.aicode.feature.settings.domain.parser.AdaptiveCardParser
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
    val cardRoot: AdaptiveCardRoot?
) {
    /** 唯一的全局工具键名：`<插件ID>:<局部ID>` */
    val key: String get() = pluginContributionName(pluginId, localId)
}

/** 设置页入口：页面文件已校验存在于 [installDir]，由宿主 WebView 加载。 */
data class PluginSettingsPage(
    val pluginId: String,
    val localId: String,
    val title: LocalizedText,
    val anchor: String,
    val pageFile: String,
    val icon: String,
    val installDir: File,
    val pluginVersion: String = "",
    val dataDir: File? = null
) {
    val key: String get() = pluginContributionName(pluginId, localId)
}

/** 已校验存在性的插件业务入口文件。 */
data class PluginRuntimeEntry(val type: String, val entryFile: File)

/** 已解析的 AI 工具贡献：schema 已读入，handler 留给运行时按需调用。 */
data class PluginTool(
    val pluginId: String,
    val localId: String,
    val title: LocalizedText,
    val description: LocalizedText,
    val inputSchema: JsonObject,
    val handler: String,
    val runtimeEntry: File
) {
    val key: String get() = pluginContributionName(pluginId, localId)
}

/** 已解析的生命周期 hook；[runtimeEntry] 与工具共用插件 runtime 的入口文件。 */
data class PluginHook(
    val pluginId: String,
    val localId: String,
    val event: String,
    val handler: String,
    val runtimeEntry: File
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

data class PluginContent(
    val skills: List<PluginSkill> = emptyList(),
    val agents: List<PluginAgent> = emptyList(),
    val workspaceTools: List<PluginWorkspaceTool> = emptyList(),
    val settingsPages: List<PluginSettingsPage> = emptyList(),
    val runtime: PluginRuntimeEntry? = null,
    val tools: List<PluginTool> = emptyList(),
    val hooks: List<PluginHook> = emptyList(),
    val mcp: List<PluginMcpServer> = emptyList()
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

    private val WHITESPACE = Regex("\\s+")

    fun load(dir: File, manifest: PluginManifest): ContentLoadResult {
        val errors = mutableListOf<PluginError>()
        val skills = manifest.skills.mapNotNull { loadSkill(dir, manifest.id, it, errors) }
        val agents = manifest.agents.mapNotNull { loadAgent(dir, manifest.id, it, errors) }
        val workspaceTools = manifest.workspaceTools.map { loadWorkspaceTool(dir, manifest.id, it, errors) }
        val settingsPages = manifest.settingsPages.mapNotNull { loadSettingsPage(dir, manifest.id, it, errors) }
        val runtime = manifest.runtime?.let { loadRuntimeEntry(dir, it, errors) }
        if ((manifest.tools.isNotEmpty() || manifest.hooks.isNotEmpty()) && runtime == null) {
            errors += PluginError(PluginErrorCode.CONTENT_INVALID, "runtime")
        }
        val tools = manifest.tools.mapNotNull { loadTool(dir, manifest.id, it, runtime, errors) }
        val hooks = manifest.hooks.mapNotNull { loadHook(manifest.id, it, runtime) }
        val mcp = manifest.mcp.flatMap { loadMcp(dir, manifest.id, it, errors) }
        return if (errors.isEmpty()) {
            ContentLoadResult.Ok(PluginContent(skills, agents, workspaceTools, settingsPages, runtime, tools, hooks, mcp))
        } else {
            ContentLoadResult.Failed(errors)
        }
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

    private fun loadRuntimeEntry(
        dir: File,
        ref: PluginRuntimeRef,
        errors: MutableList<PluginError>
    ): PluginRuntimeEntry? {
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
        return PluginRuntimeEntry(ref.type, file)
    }

    private fun loadTool(
        dir: File,
        pluginId: String,
        ref: PluginToolRef,
        runtime: PluginRuntimeEntry?,
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
            runtimeEntry = runtime.entryFile
        )
    }

    private fun loadHook(
        pluginId: String,
        ref: PluginHookRef,
        runtime: PluginRuntimeEntry?
    ): PluginHook? {
        if (runtime == null) return null
        return PluginHook(
            pluginId = pluginId,
            localId = ref.id,
            event = ref.event,
            handler = ref.handler,
            runtimeEntry = runtime.entryFile
        )
    }

    private fun loadWorkspaceTool(
        dir: File,
        pluginId: String,
        ref: PluginWorkspaceToolRef,
        errors: MutableList<PluginError>
    ): PluginWorkspaceTool {
        val file = File(dir, ref.pageFile)
        val card = if (file.isFile && file.length() <= MAX_CONTENT_BYTES) {
            val text = runCatching { file.readText() }.getOrNull()
            text?.let { AdaptiveCardParser.parseAdaptiveCard(it) }
        } else null

        if (card == null && ref.pageFile.isNotBlank() && !file.exists()) {
            errors.add(PluginError(PluginErrorCode.FILE_MISSING, ref.pageFile))
        }

        return PluginWorkspaceTool(
            pluginId = pluginId,
            localId = ref.id,
            title = ref.title,
            description = ref.description,
            icon = ref.icon,
            category = ref.category,
            cardRoot = card
        )
    }

    /** 设置页 HTML 只校验存在性与大小，不解析内容；页面由宿主 WebView 加载。 */
    private fun loadSettingsPage(
        dir: File,
        pluginId: String,
        ref: PluginSettingsPageRef,
        errors: MutableList<PluginError>
    ): PluginSettingsPage? {
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
        return PluginSettingsPage(
            pluginId = pluginId,
            localId = ref.id,
            title = ref.title,
            anchor = ref.anchor,
            pageFile = ref.page,
            icon = ref.icon,
            installDir = dir
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
