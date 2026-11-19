package com.aicode.feature.plugin.domain

import java.net.URI
import java.net.URISyntaxException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** 宿主实现的插件 API 版本；清单 `engines.pluginApi` 的范围必须包含它。 */
object PluginApi {
    val VERSION = SemVer(1, 0, 0)
}

sealed interface ManifestParseResult {
    data class Success(val manifest: PluginManifest) : ManifestParseResult
    data class Failure(val errors: List<PluginError>) : ManifestParseResult
}

/**
 * `plugin.json` 的严格解析器：未知字段、未实现的贡献类型一律拒绝，不静默忽略。
 * 只做静态结构校验，不读取包内其他文件，也不执行任何代码。
 */
object PluginManifestParser {
    const val MAX_MANIFEST_BYTES = 256 * 1024

    fun parse(text: String, hostApi: SemVer = PluginApi.VERSION): ManifestParseResult {
        val root = try {
            Json.parseToJsonElement(text)
        } catch (e: IllegalArgumentException) {
            return failure(PluginErrorCode.MANIFEST_NOT_JSON, e.message?.lineSequence()?.firstOrNull().orEmpty().take(120))
        }
        val obj = root as? JsonObject ?: return failure(PluginErrorCode.MANIFEST_NOT_JSON, "root")
        return Reader(hostApi).read(obj)
    }

    private fun failure(code: PluginErrorCode, detail: String) =
        ManifestParseResult.Failure(listOf(PluginError(code, detail)))

    private val TOP_LEVEL_KEYS = setOf(
        // 仅给编辑器（JSON Schema）用，宿主不解析其内容。
        "\$schema",
        "manifestVersion", "id", "name", "version", "publisher", "repository",
        "description", "license", "engines", "runtime", "contributes", "configPage", "icon"
    )
    private val ENGINE_KEYS = setOf("pluginApi")
    private val RUNTIME_KEYS = setOf("type", "entry", "command", "dependencies", "resident")
    private val RUNTIME_DEPENDENCY_KEYS = setOf("tool", "file")
    private val CONTRIBUTION_KEYS = setOf("id", "file")
    private val TOOL_KEYS = setOf("id", "title", "description", "inputSchema", "handler")
    private val HOOK_KEYS = setOf("id", "event", "handler", "mode")
    private val UI_ENTRY_KEYS = setOf("id", "slot", "title", "page", "handler", "confirm", "icon")
    private val WORKSPACE_TOOL_KEYS = setOf("id", "title", "description", "icon", "category", "pageFile")
    private val PROVIDER_KEYS = setOf("id", "name", "description", "format", "baseUrl")
    private val EXECUTION_BACKEND_KEYS = setOf("id", "title", "description", "capabilities", "handlers")
    private val EXECUTION_BACKEND_CAPABILITY_KEYS = setOf(
        "filesystem", "command", "terminal", "fileWatch", "portForward",
        "isRemoteEnvironment", "workspaceDiscovery", "projectConfigLocation"
    )
    private val EXECUTION_BACKEND_HANDLER_KEYS = setOf(
        "fs", "exec", "execStream", "execHealth", "terminal",
        "workspaceList", "workspaceCreate", "workspaceDelete", "workspaceSelect"
    )
    private val WORKFLOW_KEYS = setOf("id", "title", "description", "capabilities", "handlers")
    private val WORKFLOW_CAPABILITY_KEYS = setOf("streaming", "tools", "compaction", "titleGeneration")
    private val WORKFLOW_HANDLER_KEYS = setOf("startTurn", "cancelTurn", "compact", "generateTitle")
    private val SESSION_KEYS = setOf("id", "title", "description", "handlers")
    private val SESSION_HANDLER_KEYS = setOf(
        "refresh", "readMessages", "createSession", "deleteSession", "renameSession", "pinSession"
    )
    private val SHIZUKU_KEYS = setOf("purpose")
    private val BACKUP_KEYS = setOf("purpose")
    private val MODEL_ACCESS_KEYS = setOf("purpose")
    private val OVERLAY_KEYS = setOf("purpose", "surfaces")
    private val OVERLAY_SURFACE_KEYS = setOf("id", "page", "width", "height", "draggable", "snap")

    private val PLUGIN_ID = Regex("^[a-z][a-z0-9-]{0,31}\\.[a-z][a-z0-9-]{0,31}$")
    private val PUBLISHER = Regex("^[a-z][a-z0-9-]{0,31}$")
    private val LOCAL_ID = Regex("^[A-Za-z][A-Za-z0-9_-]{0,63}$")
    private val HANDLER_NAME = Regex("^[A-Za-z][A-Za-z0-9_.-]{0,63}$")
    private val BUILTIN_ICON = Regex("^[a-z][a-z0-9-]{0,31}$")
    private val LANGUAGE_TAG = Regex("^[a-z]{2,3}(-[a-z0-9]{2,8})*$")

    private const val MAX_NAME = 80
    private const val MAX_DESCRIPTION = 600
    private const val MAX_COMMAND_PARTS = 16
    private const val MAX_COMMAND_PART_CHARS = 200
    private const val MAX_LICENSE = 100
    private const val MAX_REPOSITORY = 300
    private const val MAX_LOCALES = 16
    private const val MAX_IDS_PER_TYPE = 64
    private const val MAX_EXECUTION_BACKENDS = 32
    private const val MAX_WORKFLOWS = 32
    private const val MAX_SESSIONS = 32
    private const val MAX_PROVIDER_URL = 2048
    private const val MAX_SHIZUKU_PURPOSE = 200
    private const val MAX_BACKUP_PURPOSE = 200
    private const val MAX_MODEL_ACCESS_PURPOSE = 200
    private const val MAX_OVERLAY_PURPOSE = 200
    private const val MAX_WORKSPACE_TOOL_ICON = 32
    private const val MAX_WORKSPACE_TOOL_CATEGORY = 32
    private const val MAX_OVERLAY_SURFACES = 3
    private const val MIN_OVERLAY_EDGE = 48
    private const val MAX_OVERLAY_WIDTH = 720
    private const val MAX_OVERLAY_HEIGHT = 1200
    const val MAX_PATH_LENGTH = 200
    const val MAX_PATH_DEPTH = 8

    /** 插件 ID 同时用作安装目录名，读取已保存记录时也要用它拦住被篡改的值。 */
    fun isValidPluginId(id: String): Boolean = PLUGIN_ID.matches(id)

    /** 清单 `icon` 以 `.png` / `.webp` 结尾时按包内位图路径解析，其余值按内置图标名处理。 */
    fun isIconFilePath(value: String): Boolean {
        val lower = value.lowercase()
        return lower.endsWith(".png") || lower.endsWith(".webp")
    }

    /** 包内相对路径：不含绝对路径、`.` / `..` 段、空段、反斜杠与控制字符，长度与深度有限。 */
    fun isSafeRelativePath(path: String): Boolean {
        if (path.isEmpty() || path.length > MAX_PATH_LENGTH) return false
        if (path.startsWith('/') || path.contains('\\') || path.any { it.isISOControl() }) return false
        val segments = path.split('/')
        if (segments.size > MAX_PATH_DEPTH) return false
        return segments.none { it.isEmpty() || it == "." || it == ".." }
    }

    private class Reader(private val hostApi: SemVer) {
        private val errors = mutableListOf<PluginError>()

        private fun err(code: PluginErrorCode, detail: String = "") {
            errors += PluginError(code, detail)
        }

        fun read(obj: JsonObject): ManifestParseResult {
            obj.keys.filter { it !in TOP_LEVEL_KEYS }.forEach { err(PluginErrorCode.UNSUPPORTED_FIELD, it) }

            val manifestVersion = readManifestVersion(obj)
            val id = readPluginId(obj)
            val name = readLocalized(obj, "name", MAX_NAME)
            val version = readVersion(obj)
            val publisher = readPublisher(obj, id)
            val repository = readRepository(obj)
            val description = readLocalized(obj, "description", MAX_DESCRIPTION, allowNewlines = true)
            val license = readText(obj, "license", MAX_LICENSE)
            val engines = readEngines(obj)
            val runtime = readRuntime(obj)
            val contributes = readContributes(obj)
            val icon = readIcon(obj)

            val needsRuntime = contributes != null && (
                contributes.tools.isNotEmpty() || contributes.hooks.isNotEmpty() ||
                    contributes.uiEntries.any { it.handler != null } ||
                    contributes.executionBackends.isNotEmpty() ||
                    contributes.workflows.isNotEmpty() ||
                    contributes.sessions.isNotEmpty()
                )
            if (needsRuntime && runtime == null) missing("runtime")
            if (errors.isNotEmpty()) return ManifestParseResult.Failure(errors.toList())

            return ManifestParseResult.Success(
                PluginManifest(
                    manifestVersion = requireNotNull(manifestVersion),
                    id = requireNotNull(id),
                    name = requireNotNull(name),
                    version = requireNotNull(version),
                    publisher = requireNotNull(publisher),
                    repository = requireNotNull(repository),
                    description = requireNotNull(description),
                    license = requireNotNull(license),
                    pluginApi = requireNotNull(engines),
                    skills = requireNotNull(contributes).skills,
                    agents = contributes.agents,
                    workspaceTools = contributes.workspaceTools,
                    uiEntries = contributes.uiEntries,
                    runtime = runtime,
                    tools = contributes.tools,
                    hooks = contributes.hooks,
                    mcp = contributes.mcp,
                    providers = contributes.providers,
                    executionBackends = contributes.executionBackends,
                    workflows = contributes.workflows,
                    sessions = contributes.sessions,
                    shizuku = contributes.shizuku,
                    overlay = contributes.overlay,
                    backup = contributes.backup,
                    modelAccess = contributes.modelAccess,
                    configPage = contributes.configPage ?: readConfigPage(obj["configPage"], "configPage"),
                    icon = icon
                )
            )
        }

        private fun missing(path: String) = err(PluginErrorCode.FIELD_MISSING, path)

        private fun invalid(path: String) = err(PluginErrorCode.FIELD_INVALID, path)

        private fun JsonElement.stringOrNull(): String? =
            (this as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun hasControlChars(text: String, allowNewlines: Boolean = false) =
            text.any { it.isISOControl() && !(allowNewlines && it == '\n') }

        private fun readManifestVersion(obj: JsonObject): Int? {
            val raw = obj["manifestVersion"] ?: return null.also { missing("manifestVersion") }
            val value = (raw as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                ?: return null.also { invalid("manifestVersion") }
            if (value != 1) {
                err(PluginErrorCode.UNSUPPORTED_MANIFEST_VERSION, value.toString())
                return null
            }
            return value
        }

        private fun readPluginId(obj: JsonObject): String? {
            val raw = obj["id"] ?: return null.also { missing("id") }
            val id = raw.stringOrNull()
            if (id == null || !PLUGIN_ID.matches(id)) return null.also { invalid("id") }
            return id
        }

        private fun readPublisher(obj: JsonObject, id: String?): String? {
            val raw = obj["publisher"] ?: return null.also { missing("publisher") }
            val publisher = raw.stringOrNull()
            if (publisher == null || !PUBLISHER.matches(publisher)) return null.also { invalid("publisher") }
            // 发布者必须与插件 ID 的第一段一致，避免展示的发布者与 ID 命名空间不符
            if (id != null && id.substringBefore('.') != publisher) return null.also { invalid("publisher") }
            return publisher
        }

        private fun readVersion(obj: JsonObject): SemVer? {
            val raw = obj["version"] ?: return null.also { missing("version") }
            return raw.stringOrNull()?.let { SemVer.parse(it) } ?: null.also { invalid("version") }
        }

        /** 可选插件图标：内置标识符，或以 `.png` / `.webp` 结尾的包内相对路径。 */
        private fun readIcon(obj: JsonObject): String? {
            val raw = obj["icon"] ?: return null
            val text = raw.stringOrNull()?.trim()
            if (text.isNullOrEmpty()) return null.also { invalid("icon") }
            if (isIconFilePath(text)) {
                return if (isSafeRelativePath(text)) text else null.also { invalid("icon") }
            }
            // 不支持的图片后缀（如 .svg / .jpg）在这里明确拒绝，不静默当作内置图标名
            return if (BUILTIN_ICON.matches(text)) text else null.also { invalid("icon") }
        }

        private fun readText(obj: JsonObject, key: String, maxLength: Int): String? {
            val raw = obj[key] ?: return null.also { missing(key) }
            val text = raw.stringOrNull()?.trim()
            if (text.isNullOrEmpty() || text.length > maxLength || hasControlChars(text)) {
                return null.also { invalid(key) }
            }
            return text
        }

        private fun readRepository(obj: JsonObject): String? {
            val text = readText(obj, "repository", MAX_REPOSITORY) ?: return null
            val uri = try {
                URI(text)
            } catch (e: URISyntaxException) {
                return null.also { invalid("repository") }
            }
            val valid = uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null && uri.path.trim('/').isNotEmpty()
            return if (valid) text else null.also { invalid("repository") }
        }

        private fun readLocalized(
            obj: JsonObject,
            key: String,
            maxLength: Int,
            allowNewlines: Boolean = false
        ): LocalizedText? {
            val raw = obj[key] ?: return null.also { missing(key) }
            return parseLocalized(raw, key, maxLength, allowNewlines)
        }

        private fun parseLocalized(
            raw: JsonElement,
            path: String,
            maxLength: Int,
            allowNewlines: Boolean = false
        ): LocalizedText? {
            fun validText(text: String?): String? = text?.trim()?.takeIf {
                it.isNotEmpty() && it.length <= maxLength && !hasControlChars(it, allowNewlines)
            }

            raw.stringOrNull()?.let { single ->
                val text = validText(single) ?: return null.also { invalid(path) }
                return LocalizedText.single(text)
            }
            val map = raw as? JsonObject ?: return null.also { invalid(path) }
            if (map.isEmpty() || map.size > MAX_LOCALES) return null.also { invalid(path) }
            val values = LinkedHashMap<String, String>()
            for ((tag, element) in map) {
                val normalized = LocalizedText.normalize(tag)
                val tagOk = normalized == LocalizedText.DEFAULT_KEY || LANGUAGE_TAG.matches(normalized)
                val text = validText(element.stringOrNull())
                if (!tagOk || text == null || values.put(normalized, text) != null) {
                    return null.also { invalid("$path.$tag") }
                }
            }
            return LocalizedText(values)
        }

        /** 返回 pluginApi 范围原文。 */
        private fun readEngines(obj: JsonObject): String? {
            val raw = obj["engines"] ?: return null.also { missing("engines") }
            val engines = raw as? JsonObject ?: return null.also { invalid("engines") }
            engines.keys.filter { it !in ENGINE_KEYS }.forEach { err(PluginErrorCode.UNSUPPORTED_FIELD, "engines.$it") }

            val apiText = engines["pluginApi"]?.stringOrNull()?.trim()
            if (apiText == null) {
                if (engines["pluginApi"] == null) missing("engines.pluginApi") else invalid("engines.pluginApi")
                return null
            }
            val apiRange = SemVerRange.parse(apiText) ?: return null.also { invalid("engines.pluginApi") }
            if (!apiRange.matches(hostApi)) {
                err(PluginErrorCode.API_INCOMPATIBLE, apiText)
                return null
            }
            return apiText
        }

        /** 缺省视为纯声明式包；存在时必须指定运行时类型、包内入口与可选命令/依赖。 */
        private fun readRuntime(obj: JsonObject): PluginRuntimeRef? {
            val raw = obj["runtime"] ?: return null
            val runtime = raw as? JsonObject ?: return null.also { invalid("runtime") }
            runtime.keys.filter { it !in RUNTIME_KEYS }.forEach {
                err(PluginErrorCode.UNSUPPORTED_FIELD, "runtime.$it")
            }
            val type = runtime["type"]?.stringOrNull()?.trim()
            if (type == null) {
                if (runtime["type"] == null) missing("runtime.type") else invalid("runtime.type")
                return null
            }
            if (type !in PluginRuntimeTypes.ALL) {
                err(PluginErrorCode.UNSUPPORTED_CONTRIBUTION, "runtime.type")
                return null
            }
            val entry = runtime["entry"]?.stringOrNull()?.trim()
            if (entry == null) {
                if (runtime["entry"] == null) missing("runtime.entry") else invalid("runtime.entry")
                return null
            }
            if (!isSafeRelativePath(entry) || !entryMatchesType(entry, type)) {
                invalid("runtime.entry")
                return null
            }
            val command = readCommand(runtime["command"])
            val dependencies = readDependencies(runtime["dependencies"])
            if (command == null && runtime["command"] != null) return null
            if (dependencies == null && runtime["dependencies"] != null) return null
            val resident = when (val raw = runtime["resident"]) {
                null -> false
                else -> (raw as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                    ?: return null.also { invalid("runtime.resident") }
            }
            return PluginRuntimeRef(type, entry, command, dependencies, resident)
        }

        private fun entryMatchesType(entry: String, type: String): Boolean = when (type) {
            PluginRuntimeTypes.JAVASCRIPT ->
                entry.endsWith(".js", true) || entry.endsWith(".mjs", true) || entry.endsWith(".cjs", true)
            PluginRuntimeTypes.PYTHON -> entry.endsWith(".py", true)
            else -> false
        }

        /** 命令数组直接交给 execve，不经 shell；限制长度并禁止绝对路径与控制字符。 */
        private fun readCommand(raw: JsonElement?): List<String>? {
            if (raw == null) return null
            val array = raw as? JsonArray ?: return null.also { invalid("runtime.command") }
            if (array.isEmpty() || array.size > MAX_COMMAND_PARTS) {
                return null.also { invalid("runtime.command") }
            }
            val parts = array.map { item ->
                (item as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()
            }
            if (parts.any { part ->
                    part == null || part.isEmpty() || part.length > MAX_COMMAND_PART_CHARS ||
                        part.any { it.isISOControl() } || part.startsWith('/') || part.contains("..")
                }
            ) {
                invalid("runtime.command")
                return null
            }
            return parts.filterNotNull()
        }

        private fun readDependencies(raw: JsonElement?): PluginDependencyRef? {
            if (raw == null) return null
            val obj = raw as? JsonObject ?: return null.also { invalid("runtime.dependencies") }
            obj.keys.filter { it !in RUNTIME_DEPENDENCY_KEYS }.forEach {
                err(PluginErrorCode.UNSUPPORTED_FIELD, "runtime.dependencies.$it")
            }
            val tool = obj["tool"]?.stringOrNull()?.trim()
            val file = obj["file"]?.stringOrNull()?.trim()
            if (tool != "npm" && tool != "pip") {
                err(PluginErrorCode.UNSUPPORTED_CONTRIBUTION, "runtime.dependencies.tool")
                return null
            }
            if (file == null || !isSafeRelativePath(file) || !file.endsWith(
                    if (tool == "npm") ".json" else ".txt",
                    ignoreCase = true
                )
            ) {
                invalid("runtime.dependencies.file")
                return null
            }
            return PluginDependencyRef(tool, file)
        }

        private class Contributes(
            val skills: List<PluginContributionRef>,
            val agents: List<PluginContributionRef>,
            val workspaceTools: List<PluginWorkspaceToolRef>,
            val uiEntries: List<PluginUiEntryRef>,
            val tools: List<PluginToolRef>,
            val hooks: List<PluginHookRef>,
            val mcp: List<PluginMcpRef>,
            val providers: List<PluginProviderRef>,
            val executionBackends: List<PluginExecutionBackendRef>,
            val workflows: List<PluginWorkflowRef>,
            val sessions: List<PluginExternalSessionRef>,
            val shizuku: PluginShizukuRef?,
            val overlay: PluginOverlayRef?,
            val backup: PluginBackupRef?,
            val modelAccess: PluginModelAccessRef?,
            val configPage: String?
        )

        private fun readContributes(obj: JsonObject): Contributes? {
            val raw = obj["contributes"] ?: return null.also { missing("contributes") }
            val contributes = raw as? JsonObject ?: return null.also { invalid("contributes") }
            var ok = true
            contributes.keys.filter {
                it != "skills" && it != "agents" && it != "workspaceTools" && it != "uiEntries" &&
                    it != "tools" && it != "hooks" && it != "mcp" && it != "providers" &&
                    it != "executionBackends" && it != "workflows" && it != "sessions" &&
                    it != "shizuku" && it != "overlay" &&
                    it != "backup" && it != "modelAccess" && it != "configPage"
            }.forEach {
                err(PluginErrorCode.UNSUPPORTED_CONTRIBUTION, it)
                ok = false
            }
            val skills = readContributionList(contributes["skills"], "contributes.skills") ?: run { ok = false; emptyList() }
            val agents = readContributionList(contributes["agents"], "contributes.agents") ?: run { ok = false; emptyList() }
            val workspaceTools = readWorkspaceTools(contributes["workspaceTools"], "contributes.workspaceTools") ?: run { ok = false; emptyList() }
            val uiEntries = readUiEntries(contributes["uiEntries"], "contributes.uiEntries")
                ?: run { ok = false; emptyList() }
            val tools = readTools(contributes["tools"], "contributes.tools") ?: run { ok = false; emptyList() }
            val hooks = readHooks(contributes["hooks"], "contributes.hooks") ?: run { ok = false; emptyList() }
            val mcpRefs = readContributionList(contributes["mcp"], "contributes.mcp", extension = ".json")
                ?: run { ok = false; emptyList() }
            val mcp = mcpRefs.map { PluginMcpRef(it.id, it.file) }
            val providers = readProviders(contributes["providers"], "contributes.providers")
                ?: run { ok = false; emptyList() }
            val executionBackends = readExecutionBackends(contributes["executionBackends"], "contributes.executionBackends")
                ?: run { ok = false; emptyList() }
            val workflows = readWorkflows(contributes["workflows"], "contributes.workflows")
                ?: run { ok = false; emptyList() }
            val sessions = readSessions(contributes["sessions"], "contributes.sessions")
                ?: run { ok = false; emptyList() }
            val shizuku = readShizuku(contributes["shizuku"], "contributes.shizuku")
                ?: run {
                    if (contributes["shizuku"] != null) ok = false
                    null
                }
            val overlay = readOverlay(contributes["overlay"], "contributes.overlay")
                ?: run {
                    if (contributes["overlay"] != null) ok = false
                    null
                }
            val backup = readBackup(contributes["backup"], "contributes.backup")
                ?: run {
                    if (contributes["backup"] != null) ok = false
                    null
                }
            val modelAccess = readModelAccess(contributes["modelAccess"], "contributes.modelAccess")
                ?: run {
                    if (contributes["modelAccess"] != null) ok = false
                    null
                }
            if (hooks.any { it.event in PluginHookEvents.BACKUP } && backup == null) {
                missing("contributes.backup")
                ok = false
            }
            if (uiEntries.any { it.slot == PluginUiSlots.BACKUP_DATA }) {
                if (backup == null) {
                    missing("contributes.backup")
                    ok = false
                }
                if (hooks.none { it.event == PluginHookEvents.BACKUP_EXPORT_BEFORE }) {
                    missing("contributes.hooks")
                    ok = false
                }
            }
            val configPage = readConfigPage(contributes["configPage"], "contributes.configPage")
            if (ok && skills.isEmpty() && agents.isEmpty() && workspaceTools.isEmpty() && uiEntries.isEmpty() &&
                tools.isEmpty() && hooks.isEmpty() && mcp.isEmpty() && providers.isEmpty() &&
                executionBackends.isEmpty() && workflows.isEmpty() && sessions.isEmpty() &&
                shizuku == null && overlay == null &&
                backup == null && modelAccess == null && configPage == null
            ) {
                invalid("contributes")
                ok = false
            }
            return if (ok) Contributes(
                skills, agents, workspaceTools, uiEntries, tools, hooks, mcp, providers,
                executionBackends, workflows, sessions, shizuku, overlay, backup, modelAccess, configPage
            ) else null
        }

        private fun readConfigPage(raw: JsonElement?, path: String): String? {
            if (raw == null) return null
            val value = raw.stringOrNull() ?: return null.also { invalid(path) }
            if (value.isBlank() || !isSafeRelativePath(value)) return null.also { invalid(path) }
            val lower = value.lowercase()
            if (!lower.endsWith(".html") && !lower.endsWith(".htm")) return null.also { invalid(path) }
            return value
        }

        /** 缺省视为空列表；每项必须有合法局部 ID、标题、JSON Schema 文件与 handler 名。 */
        private fun readTools(raw: JsonElement?, path: String): List<PluginToolRef>? {
            if (raw == null) return emptyList()
            val array = raw as? JsonArray ?: return null.also { invalid(path) }
            if (array.size > MAX_IDS_PER_TYPE) return null.also { invalid(path) }
            val result = mutableListOf<PluginToolRef>()
            array.forEachIndexed { index, element ->
                val itemPath = "$path[$index]"
                val item = element as? JsonObject
                if (item == null) {
                    invalid(itemPath)
                    return@forEachIndexed
                }
                item.keys.filter { it !in TOOL_KEYS }.forEach {
                    err(PluginErrorCode.UNSUPPORTED_FIELD, "$itemPath.$it")
                }
                val id = item["id"]?.stringOrNull()?.takeIf { LOCAL_ID.matches(it) }
                val titleRaw = item["title"]
                val title = if (titleRaw != null) parseLocalized(titleRaw, "$itemPath.title", MAX_NAME) else null
                val descRaw = item["description"]
                val description = if (descRaw != null) {
                    parseLocalized(descRaw, "$itemPath.description", MAX_DESCRIPTION)
                } else {
                    LocalizedText.single("")
                }
                val inputSchema = item["inputSchema"]?.stringOrNull()
                val handler = item["handler"]?.stringOrNull()?.trim()
                var valid = true
                if (id == null) { invalid("$itemPath.id"); valid = false }
                if (title == null) { invalid("$itemPath.title"); valid = false }
                if (inputSchema == null || !isSafeRelativePath(inputSchema) || !inputSchema.endsWith(".json", ignoreCase = true)) {
                    invalid("$itemPath.inputSchema")
                    valid = false
                }
                if (handler == null || !HANDLER_NAME.matches(handler)) {
                    invalid("$itemPath.handler")
                    valid = false
                }
                if (description == null) {
                    invalid("$itemPath.description")
                    valid = false
                }
                if (valid && id != null && title != null && inputSchema != null && handler != null && description != null) {
                    if (result.any { it.id == id }) {
                        err(PluginErrorCode.DUPLICATE_ID, "$path.$id")
                    } else {
                        result += PluginToolRef(
                            id = id,
                            title = title,
                            description = description,
                            inputSchema = inputSchema,
                            handler = handler
                        )
                    }
                }
            }
            return result
        }

        /** 缺省视为空列表；每项声明插件提供的执行后端能力与 handler 映射。 */
        private fun readExecutionBackends(raw: JsonElement?, path: String): List<PluginExecutionBackendRef>? {
            if (raw == null) return emptyList()
            val array = raw as? JsonArray ?: return null.also { invalid(path) }
            if (array.size > MAX_EXECUTION_BACKENDS) return null.also { invalid(path) }
            val result = mutableListOf<PluginExecutionBackendRef>()
            var ok = true
            array.forEachIndexed { index, element ->
                val itemPath = "$path[$index]"
                val item = element as? JsonObject
                if (item == null) {
                    invalid(itemPath)
                    ok = false
                    return@forEachIndexed
                }
                item.keys.filter { it !in EXECUTION_BACKEND_KEYS }.forEach {
                    err(PluginErrorCode.UNSUPPORTED_FIELD, "$itemPath.$it")
                    ok = false
                }
                val id = item["id"]?.stringOrNull()?.takeIf { LOCAL_ID.matches(it) }
                val title = item["title"]?.let { parseLocalized(it, "$itemPath.title", MAX_NAME) }
                val descRaw = item["description"]
                val description = if (descRaw != null) {
                    parseLocalized(descRaw, "$itemPath.description", MAX_DESCRIPTION)
                } else {
                    LocalizedText.single("")
                }
                if (id == null) { invalid("$itemPath.id"); ok = false }
                if (title == null) { invalid("$itemPath.title"); ok = false }
                if (description == null) { ok = false }

                val caps = readExecutionBackendCapabilities(item["capabilities"], "$itemPath.capabilities")
                if (caps == null) ok = false
                val handlers = readExecutionBackendHandlers(item["handlers"], "$itemPath.handlers")
                if (handlers == null) ok = false

                if (caps != null && handlers != null) {
                    if (caps.filesystem && handlers.fs == null) {
                        missing("$itemPath.handlers.fs")
                        ok = false
                    }
                    if (caps.command && handlers.exec == null) {
                        missing("$itemPath.handlers.exec")
                        ok = false
                    }
                    if (caps.terminal && handlers.terminal == null) {
                        missing("$itemPath.handlers.terminal")
                        ok = false
                    }
                }

                if (ok && id != null && title != null && caps != null && handlers != null) {
                    if (result.any { it.id == id }) {
                        err(PluginErrorCode.DUPLICATE_ID, "$path.$id")
                        ok = false
                    } else {
                        result += PluginExecutionBackendRef(
                            id = id,
                            name = title,
                            description = description ?: LocalizedText.single(""),
                            capabilities = caps,
                            handlers = handlers
                        )
                    }
                }
            }
            return if (ok) result else null
        }

        private fun readExecutionBackendCapabilities(
            raw: JsonElement?,
            path: String
        ): PluginExecutionBackendCapabilitiesRef? {
            if (raw == null) return PluginExecutionBackendCapabilitiesRef()
            val obj = raw as? JsonObject ?: return null.also { invalid(path) }
            var ok = true
            obj.keys.filter { it !in EXECUTION_BACKEND_CAPABILITY_KEYS }.forEach {
                err(PluginErrorCode.UNSUPPORTED_FIELD, "$path.$it")
                ok = false
            }

            fun readBool(key: String, default: Boolean = false): Boolean {
                val elem = obj[key] ?: return default
                val value = (elem as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                if (value == null) {
                    invalid("$path.$key")
                    ok = false
                    return false
                }
                return value
            }

            val filesystem = readBool("filesystem", true)
            val command = readBool("command", true)
            val terminal = readBool("terminal")
            val fileWatch = readBool("fileWatch")
            val portForward = readBool("portForward")
            val isRemoteEnvironment = readBool("isRemoteEnvironment", true)

            val discovery = when (val elem = obj["workspaceDiscovery"]) {
                null -> PluginBackendDiscoveryModes.SCAN
                else -> elem.stringOrNull()
                    ?.takeIf { it in PluginBackendDiscoveryModes.ALL }
                    ?: run { invalid("$path.workspaceDiscovery"); ok = false; PluginBackendDiscoveryModes.SCAN }
            }
            val configLocation = when (val elem = obj["projectConfigLocation"]) {
                null -> PluginBackendConfigLocations.APP_PRIVATE_DIR
                else -> elem.stringOrNull()
                    ?.takeIf { it in PluginBackendConfigLocations.ALL }
                    ?: run { invalid("$path.projectConfigLocation"); ok = false; PluginBackendConfigLocations.APP_PRIVATE_DIR }
            }

            if (isRemoteEnvironment && configLocation == PluginBackendConfigLocations.WORKSPACE_DIR) {
                invalid("$path.projectConfigLocation")
                ok = false
            }
            if (fileWatch) {
                invalid("$path.fileWatch")
                ok = false
            }
            if (portForward) {
                invalid("$path.portForward")
                ok = false
            }

            return if (ok) {
                PluginExecutionBackendCapabilitiesRef(
                    filesystem = filesystem,
                    command = command,
                    terminal = terminal,
                    fileWatch = fileWatch,
                    portForward = portForward,
                    isRemoteEnvironment = isRemoteEnvironment,
                    workspaceDiscovery = discovery,
                    projectConfigLocation = configLocation
                )
            } else null
        }

        private fun readExecutionBackendHandlers(
            raw: JsonElement?,
            path: String
        ): PluginExecutionBackendHandlersRef? {
            if (raw == null) {
                missing(path)
                return null
            }
            val obj = raw as? JsonObject ?: return null.also { invalid(path) }
            var ok = true
            obj.keys.filter { it !in EXECUTION_BACKEND_HANDLER_KEYS }.forEach {
                err(PluginErrorCode.UNSUPPORTED_FIELD, "$path.$it")
                ok = false
            }

            fun readHandler(key: String): String? {
                val elem = obj[key] ?: return null
                val value = elem.stringOrNull()?.trim()?.takeIf { HANDLER_NAME.matches(it) }
                if (value == null) {
                    invalid("$path.$key")
                    ok = false
                }
                return value
            }

            val fs = readHandler("fs")
            val exec = readHandler("exec")
            val execStream = readHandler("execStream")
            val execHealth = readHandler("execHealth")
            val terminal = readHandler("terminal")
            val workspaceList = readHandler("workspaceList")
            val workspaceCreate = readHandler("workspaceCreate")
            val workspaceDelete = readHandler("workspaceDelete")
            val workspaceSelect = readHandler("workspaceSelect")

            return if (ok) {
                PluginExecutionBackendHandlersRef(
                    fs = fs,
                    exec = exec,
                    execStream = execStream,
                    execHealth = execHealth,
                    terminal = terminal,
                    workspaceList = workspaceList,
                    workspaceCreate = workspaceCreate,
                    workspaceDelete = workspaceDelete,
                    workspaceSelect = workspaceSelect
                )
            } else null
        }

        /** 缺省视为空列表；每项声明插件提供的 Agent 工作流能力与 handler 映射。 */
        private fun readWorkflows(raw: JsonElement?, path: String): List<PluginWorkflowRef>? {
            if (raw == null) return emptyList()
            val array = raw as? JsonArray ?: return null.also { invalid(path) }
            if (array.size > MAX_WORKFLOWS) return null.also { invalid(path) }
            val result = mutableListOf<PluginWorkflowRef>()
            var ok = true
            array.forEachIndexed { index, element ->
                val itemPath = "$path[$index]"
                val item = element as? JsonObject
                if (item == null) {
                    invalid(itemPath)
                    ok = false
                    return@forEachIndexed
                }
                item.keys.filter { it !in WORKFLOW_KEYS }.forEach {
                    err(PluginErrorCode.UNSUPPORTED_FIELD, "$itemPath.$it")
                    ok = false
                }
                val idRaw = item["id"]
                val id = if (idRaw == null) {
                    missing("$itemPath.id")
                    ok = false
                    null
                } else {
                    idRaw.stringOrNull()?.takeIf { LOCAL_ID.matches(it) } ?: run {
                        invalid("$itemPath.id")
                        ok = false
                        null
                    }
                }
                val titleRaw = item["title"]
                val title = if (titleRaw == null) {
                    missing("$itemPath.title")
                    ok = false
                    null
                } else {
                    parseLocalized(titleRaw, "$itemPath.title", MAX_NAME) ?: run {
                        ok = false
                        null
                    }
                }
                val descRaw = item["description"]
                val description = if (descRaw != null) {
                    parseLocalized(descRaw, "$itemPath.description", MAX_DESCRIPTION) ?: run {
                        ok = false
                        null
                    }
                } else {
                    LocalizedText.single("")
                }
                val capabilities = readWorkflowCapabilities(item["capabilities"], "$itemPath.capabilities")
                if (capabilities == null) ok = false
                val handlers = readWorkflowHandlers(item["handlers"], "$itemPath.handlers")
                if (handlers == null) ok = false

                if (ok && id != null && title != null && description != null && capabilities != null && handlers != null) {
                    if (result.any { it.id == id }) {
                        err(PluginErrorCode.DUPLICATE_ID, "$path.$id")
                        ok = false
                    } else {
                        result += PluginWorkflowRef(
                            id = id,
                            title = title,
                            description = description,
                            capabilities = capabilities,
                            handlers = handlers
                        )
                    }
                }
            }
            return if (ok) result else null
        }

        private fun readWorkflowCapabilities(
            raw: JsonElement?,
            path: String
        ): PluginWorkflowCapabilitiesRef? {
            if (raw == null) return PluginWorkflowCapabilitiesRef()
            val obj = raw as? JsonObject ?: return null.also { invalid(path) }
            var ok = true
            obj.keys.filter { it !in WORKFLOW_CAPABILITY_KEYS }.forEach {
                err(PluginErrorCode.UNSUPPORTED_FIELD, "$path.$it")
                ok = false
            }

            fun readBool(key: String, defaultValue: Boolean): Boolean {
                val elem = obj[key] ?: return defaultValue
                val value = (elem as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                if (value == null) {
                    invalid("$path.$key")
                    ok = false
                    return defaultValue
                }
                return value
            }

            val streaming = readBool("streaming", true)
            val tools = readBool("tools", true)
            val compaction = readBool("compaction", false)
            val titleGeneration = readBool("titleGeneration", false)

            return if (ok) {
                PluginWorkflowCapabilitiesRef(
                    streaming = streaming,
                    tools = tools,
                    compaction = compaction,
                    titleGeneration = titleGeneration
                )
            } else null
        }

        private fun readWorkflowHandlers(
            raw: JsonElement?,
            path: String
        ): PluginWorkflowHandlersRef? {
            if (raw == null) {
                missing(path)
                return null
            }
            val obj = raw as? JsonObject ?: return null.also { invalid(path) }
            var ok = true
            obj.keys.filter { it !in WORKFLOW_HANDLER_KEYS }.forEach {
                err(PluginErrorCode.UNSUPPORTED_FIELD, "$path.$it")
                ok = false
            }

            fun readRequiredHandler(key: String): String? {
                val elem = obj[key]
                if (elem == null) {
                    missing("$path.$key")
                    ok = false
                    return null
                }
                val value = elem.stringOrNull()?.trim()?.takeIf { HANDLER_NAME.matches(it) }
                if (value == null) {
                    invalid("$path.$key")
                    ok = false
                }
                return value
            }

            fun readOptionalHandler(key: String): String? {
                val elem = obj[key] ?: return null
                val value = elem.stringOrNull()?.trim()?.takeIf { HANDLER_NAME.matches(it) }
                if (value == null) {
                    invalid("$path.$key")
                    ok = false
                }
                return value
            }

            val startTurn = readRequiredHandler("startTurn")
            val cancelTurn = readRequiredHandler("cancelTurn")
            val compact = readOptionalHandler("compact")
            val generateTitle = readOptionalHandler("generateTitle")

            return if (ok && startTurn != null && cancelTurn != null) {
                PluginWorkflowHandlersRef(
                    startTurn = startTurn,
                    cancelTurn = cancelTurn,
                    compact = compact,
                    generateTitle = generateTitle
                )
            } else null
        }

        /** 缺省视为空列表；每项声明插件提供的外部会话源及 handler 映射。 */
        private fun readSessions(raw: JsonElement?, path: String): List<PluginExternalSessionRef>? {
            if (raw == null) return emptyList()
            val array = raw as? JsonArray ?: return null.also { invalid(path) }
            if (array.size > MAX_SESSIONS) return null.also { invalid(path) }
            val result = mutableListOf<PluginExternalSessionRef>()
            var ok = true
            array.forEachIndexed { index, element ->
                val itemPath = "$path[$index]"
                val item = element as? JsonObject
                if (item == null) {
                    invalid(itemPath)
                    ok = false
                    return@forEachIndexed
                }
                item.keys.filter { it !in SESSION_KEYS }.forEach {
                    err(PluginErrorCode.UNSUPPORTED_FIELD, "$itemPath.$it")
                    ok = false
                }
                val idRaw = item["id"]
                val id = if (idRaw == null) {
                    missing("$itemPath.id")
                    ok = false
                    null
                } else {
                    idRaw.stringOrNull()?.takeIf { LOCAL_ID.matches(it) } ?: run {
                        invalid("$itemPath.id")
                        ok = false
                        null
                    }
                }
                val titleRaw = item["title"]
                val title = if (titleRaw == null) {
                    missing("$itemPath.title")
                    ok = false
                    null
                } else {
                    parseLocalized(titleRaw, "$itemPath.title", MAX_NAME) ?: run {
                        ok = false
                        null
                    }
                }
                val descRaw = item["description"]
                val description = if (descRaw != null) {
                    parseLocalized(descRaw, "$itemPath.description", MAX_DESCRIPTION) ?: run {
                        ok = false
                        null
                    }
                } else {
                    LocalizedText.single("")
                }
                val handlers = readSessionHandlers(item["handlers"], "$itemPath.handlers")
                if (handlers == null) ok = false

                if (ok && id != null && title != null && description != null && handlers != null) {
                    if (result.any { it.id == id }) {
                        err(PluginErrorCode.DUPLICATE_ID, "$path.$id")
                        ok = false
                    } else {
                        result += PluginExternalSessionRef(
                            id = id,
                            title = title,
                            description = description,
                            handlers = handlers
                        )
                    }
                }
            }
            return if (ok) result else null
        }

        private fun readSessionHandlers(
            raw: JsonElement?,
            path: String
        ): PluginExternalSessionHandlersRef? {
            if (raw == null) {
                missing(path)
                return null
            }
            val obj = raw as? JsonObject ?: return null.also { invalid(path) }
            var ok = true
            obj.keys.filter { it !in SESSION_HANDLER_KEYS }.forEach {
                err(PluginErrorCode.UNSUPPORTED_FIELD, "$path.$it")
                ok = false
            }

            fun readRequiredHandler(key: String): String? {
                val elem = obj[key]
                if (elem == null) {
                    missing("$path.$key")
                    ok = false
                    return null
                }
                val value = elem.stringOrNull()?.trim()?.takeIf { HANDLER_NAME.matches(it) }
                if (value == null) {
                    invalid("$path.$key")
                    ok = false
                }
                return value
            }

            fun readOptionalHandler(key: String): String? {
                val elem = obj[key] ?: return null
                val value = elem.stringOrNull()?.trim()?.takeIf { HANDLER_NAME.matches(it) }
                if (value == null) {
                    invalid("$path.$key")
                    ok = false
                }
                return value
            }

            val refresh = readOptionalHandler("refresh")
            val readMessages = readRequiredHandler("readMessages")
            val createSession = readOptionalHandler("createSession")
            val deleteSession = readOptionalHandler("deleteSession")
            val renameSession = readOptionalHandler("renameSession")
            val pinSession = readOptionalHandler("pinSession")

            return if (ok && readMessages != null) {
                PluginExternalSessionHandlersRef(
                    refresh = refresh,
                    readMessages = readMessages,
                    createSession = createSession,
                    deleteSession = deleteSession,
                    renameSession = renameSession,
                    pinSession = pinSession
                )
            } else null
        }

        /** 缺省视为空列表；每项声明 OpenAI 兼容服务地址与模型。 */
        private fun readProviders(raw: JsonElement?, path: String): List<PluginProviderRef>? {
            if (raw == null) return emptyList()
            val array = raw as? JsonArray ?: return null.also { invalid(path) }
            if (array.size > MAX_IDS_PER_TYPE) return null.also { invalid(path) }
            val result = mutableListOf<PluginProviderRef>()
            var ok = true
            array.forEachIndexed { index, element ->
                val itemPath = "$path[$index]"
                val item = element as? JsonObject
                if (item == null) {
                    invalid(itemPath)
                    ok = false
                    return@forEachIndexed
                }
                item.keys.filter { it !in PROVIDER_KEYS }.forEach {
                    err(PluginErrorCode.UNSUPPORTED_FIELD, "$itemPath.$it")
                    ok = false
                }
                val id = item["id"]?.stringOrNull()?.takeIf { LOCAL_ID.matches(it) }
                val nameRaw = item["name"]
                val name = if (nameRaw != null) parseLocalized(nameRaw, "$itemPath.name", MAX_NAME) else null
                val descRaw = item["description"]
                val description = if (descRaw != null) {
                    parseLocalized(descRaw, "$itemPath.description", MAX_DESCRIPTION)
                } else {
                    LocalizedText.single("")
                }
                val format = item["format"]?.stringOrNull()?.takeIf { it in PluginProviderFormats.ALL }
                val baseUrl = readProviderBaseUrl(item["baseUrl"])
                if (id == null) { invalid("$itemPath.id"); ok = false }
                if (name == null) { invalid("$itemPath.name"); ok = false }
                if (description == null) { invalid("$itemPath.description"); ok = false }
                if (format == null) { invalid("$itemPath.format"); ok = false }
                if (baseUrl == null) { invalid("$itemPath.baseUrl"); ok = false }
                if (id != null && name != null && description != null && format != null && baseUrl != null) {
                    if (result.any { it.id == id }) {
                        err(PluginErrorCode.DUPLICATE_ID, "$path.$id")
                        ok = false
                    } else {
                        result += PluginProviderRef(id, name, description, format, baseUrl)
                    }
                }
            }
            return if (ok) result else null
        }

        /** 只接受 http/https 的绝对地址；拒绝账号、查询、片段与超长值。 */
        private fun readProviderBaseUrl(raw: JsonElement?): String? {
            val text = raw?.stringOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (text.length > MAX_PROVIDER_URL) return null
            val uri = try {
                URI(text)
            } catch (e: URISyntaxException) {
                return null
            }
            val valid = (uri.scheme == "http" || uri.scheme == "https") &&
                !uri.host.isNullOrBlank() && uri.userInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null
            return if (valid) text.trimEnd('/') else null
        }

        /** 缺省视为空列表；每项只允许 id/event/handler，event 必须在宿主支持的事件表内。 */
        private fun readHooks(raw: JsonElement?, path: String): List<PluginHookRef>? {
            if (raw == null) return emptyList()
            val array = raw as? JsonArray ?: return null.also { invalid(path) }
            if (array.size > MAX_IDS_PER_TYPE) return null.also { invalid(path) }
            val result = mutableListOf<PluginHookRef>()
            var ok = true
            array.forEachIndexed { index, element ->
                val itemPath = "$path[$index]"
                val item = element as? JsonObject
                if (item == null) {
                    invalid(itemPath)
                    ok = false
                    return@forEachIndexed
                }
                item.keys.filter { it !in HOOK_KEYS }.forEach {
                    err(PluginErrorCode.UNSUPPORTED_FIELD, "$itemPath.$it")
                    ok = false
                }
                val id = item["id"]?.stringOrNull()?.takeIf { LOCAL_ID.matches(it) }
                val event = item["event"]?.stringOrNull()?.takeIf { it in PluginHookEvents.ALL }
                val handler = item["handler"]?.stringOrNull()?.trim()?.takeIf { HANDLER_NAME.matches(it) }
                val mode = when (val raw = item["mode"]) {
                    null -> PluginHookModes.OBSERVE
                    else -> raw.stringOrNull()?.takeIf { it in PluginHookModes.ALL } ?: run {
                        invalid("$itemPath.mode")
                        ok = false
                        null
                    }
                }
                if (id == null) { invalid("$itemPath.id"); ok = false }
                if (event == null) { invalid("$itemPath.event"); ok = false }
                if (handler == null) { invalid("$itemPath.handler"); ok = false }
                if (event != null && mode != null) {
                    val expectedMode = when (event) {
                        in PluginHookEvents.TRANSFORM -> PluginHookModes.TRANSFORM
                        in PluginHookEvents.REVIEW -> PluginHookModes.REVIEW
                        else -> PluginHookModes.OBSERVE
                    }
                    if (mode != expectedMode) {
                        invalid("$itemPath.mode")
                        ok = false
                    }
                }
                if (id != null && event != null && handler != null && mode != null) {
                    if (result.any { it.id == id }) {
                        err(PluginErrorCode.DUPLICATE_ID, "$path.$id")
                        ok = false
                    } else {
                        result += PluginHookRef(id, event, handler, mode)
                    }
                }
            }
            return if (ok) result else null
        }

        /** 缺省表示不使用 Shizuku；声明只需给出用途。 */
        private fun readShizuku(raw: JsonElement?, path: String): PluginShizukuRef? {
            if (raw == null) return null
            val obj = raw as? JsonObject ?: return null.also { invalid(path) }
            obj.keys.filter { it !in SHIZUKU_KEYS }.forEach {
                err(PluginErrorCode.UNSUPPORTED_FIELD, "$path.$it")
            }
            val purpose = obj["purpose"]?.stringOrNull()?.trim()
            if (purpose.isNullOrEmpty() || purpose.length > MAX_SHIZUKU_PURPOSE) {
                invalid("$path.purpose")
                return null
            }
            return PluginShizukuRef(purpose)
        }

        /** 缺省表示不接收备份事件；声明只需给出用途，展示在使用者授权界面。 */
        private fun readBackup(raw: JsonElement?, path: String): PluginBackupRef? {
            if (raw == null) return null
            val obj = raw as? JsonObject ?: return null.also { invalid(path) }
            obj.keys.filter { it !in BACKUP_KEYS }.forEach {
                err(PluginErrorCode.UNSUPPORTED_FIELD, "$path.$it")
            }
            val purpose = obj["purpose"]?.stringOrNull()?.trim()
            if (purpose.isNullOrEmpty() || purpose.length > MAX_BACKUP_PURPOSE) {
                invalid("$path.purpose")
                return null
            }
            return PluginBackupRef(purpose)
        }

        /** 缺省表示不调用使用者供应商；声明只需给出用途，安装预览会展示。 */
        private fun readModelAccess(raw: JsonElement?, path: String): PluginModelAccessRef? {
            if (raw == null) return null
            val obj = raw as? JsonObject ?: return null.also { invalid(path) }
            obj.keys.filter { it !in MODEL_ACCESS_KEYS }.forEach {
                err(PluginErrorCode.UNSUPPORTED_FIELD, "$path.$it")
            }
            val purpose = obj["purpose"]?.stringOrNull()?.trim()
            if (purpose.isNullOrEmpty() || purpose.length > MAX_MODEL_ACCESS_PURPOSE) {
                invalid("$path.purpose")
                return null
            }
            return PluginModelAccessRef(purpose)
        }

        private fun readOverlay(raw: JsonElement?, path: String): PluginOverlayRef? {
            if (raw == null) return null
            val obj = raw as? JsonObject ?: return null.also { invalid(path) }
            var ok = true
            obj.keys.filter { it !in OVERLAY_KEYS }.forEach {
                err(PluginErrorCode.UNSUPPORTED_FIELD, "$path.$it")
                ok = false
            }
            val purpose = obj["purpose"]?.stringOrNull()?.trim()
            if (purpose.isNullOrEmpty() || purpose.length > MAX_OVERLAY_PURPOSE) {
                invalid("$path.purpose")
                ok = false
            }
            val surfaces = mutableListOf<PluginOverlaySurfaceRef>()
            val rawSurfaces = obj["surfaces"]
            if (rawSurfaces != null) {
                val array = rawSurfaces as? JsonArray
                if (array == null) {
                    invalid("$path.surfaces")
                    ok = false
                } else if (array.size > MAX_OVERLAY_SURFACES) {
                    invalid("$path.surfaces")
                    ok = false
                } else {
                    val seen = mutableSetOf<String>()
                    array.forEachIndexed { index, element ->
                        val itemPath = "$path.surfaces[$index]"
                        val item = element as? JsonObject
                        if (item == null) {
                            invalid(itemPath)
                            ok = false
                            return@forEachIndexed
                        }
                        item.keys.filter { it !in OVERLAY_SURFACE_KEYS }.forEach {
                            err(PluginErrorCode.UNSUPPORTED_FIELD, "$itemPath.$it")
                            ok = false
                        }
                        val id = item["id"]?.stringOrNull()?.takeIf { LOCAL_ID.matches(it) }
                        if (id == null || !seen.add(id)) {
                            invalid("$itemPath.id")
                            ok = false
                        }
                        val page = item["page"]?.stringOrNull()?.trim()
                        val isHtml = page != null && (
                            page.endsWith(".html", ignoreCase = true) || page.endsWith(".htm", ignoreCase = true)
                            )
                        if (page == null || !isSafeRelativePath(page) || !isHtml) {
                            invalid("$itemPath.page")
                            ok = false
                        }
                        val widthRaw = item["width"]
                        val width = if (widthRaw == null) {
                            240
                        } else {
                            (widthRaw as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                                ?: run { invalid("$itemPath.width"); ok = false; 0 }
                        }
                        val heightRaw = item["height"]
                        val height = if (heightRaw == null) {
                            320
                        } else {
                            (heightRaw as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                                ?: run { invalid("$itemPath.height"); ok = false; 0 }
                        }
                        if (width !in MIN_OVERLAY_EDGE..MAX_OVERLAY_WIDTH ||
                            height !in MIN_OVERLAY_EDGE..MAX_OVERLAY_HEIGHT
                        ) {
                            invalid("$itemPath.size")
                            ok = false
                        }
                        val draggableRaw = item["draggable"]
                        val draggable = if (draggableRaw == null) {
                            true
                        } else {
                            (draggableRaw as? JsonPrimitive)?.booleanOrNull
                                ?: run { invalid("$itemPath.draggable"); ok = false; false }
                        }
                        val snapRaw = item["snap"]
                        val snap = if (snapRaw == null) {
                            PluginOverlaySnap.NONE
                        } else {
                            (snapRaw as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                                ?.takeIf { it in PluginOverlaySnap.ALL }
                                ?: run { invalid("$itemPath.snap"); ok = false; PluginOverlaySnap.NONE }
                        }
                        if (id != null && page != null) {
                            surfaces += PluginOverlaySurfaceRef(id, page, width, height, draggable, snap)
                        }
                    }
                }
            }
            if (surfaces.isEmpty()) {
                invalid("$path.surfaces")
                ok = false
            }
            return if (ok && purpose != null) PluginOverlayRef(purpose, surfaces) else null
        }

        private fun readWorkspaceTools(raw: JsonElement?, path: String): List<PluginWorkspaceToolRef>? {
            if (raw == null) return emptyList()
            val array = raw as? JsonArray ?: return null.also { invalid(path) }
            if (array.size > MAX_IDS_PER_TYPE) return null.also { invalid(path) }
            val result = mutableListOf<PluginWorkspaceToolRef>()
            var ok = true
            array.forEachIndexed { index, element ->
                val itemPath = "$path[$index]"
                val item = element as? JsonObject
                if (item == null) {
                    invalid(itemPath)
                    ok = false
                    return@forEachIndexed
                }
                item.keys.filter { it !in WORKSPACE_TOOL_KEYS }.forEach {
                    err(PluginErrorCode.UNSUPPORTED_FIELD, "$itemPath.$it")
                    ok = false
                }
                val id = item["id"]?.stringOrNull()?.takeIf { LOCAL_ID.matches(it) }
                val titleRaw = item["title"]
                val title = if (titleRaw != null) parseLocalized(titleRaw, "$itemPath.title", MAX_NAME) else null
                val descRaw = item["description"]
                val description = if (descRaw != null) parseLocalized(descRaw, "$itemPath.description", MAX_DESCRIPTION) ?: LocalizedText.single("") else LocalizedText.single("")
                var icon = "play"
                val iconRaw = item["icon"]
                if (iconRaw != null) {
                    val text = readWorkspaceToolLabel(iconRaw, "$itemPath.icon", MAX_WORKSPACE_TOOL_ICON)
                    if (text == null) {
                        ok = false
                    } else {
                        icon = text
                    }
                }
                var category = "通用"
                val categoryRaw = item["category"]
                if (categoryRaw != null) {
                    val text = readWorkspaceToolLabel(categoryRaw, "$itemPath.category", MAX_WORKSPACE_TOOL_CATEGORY)
                    if (text == null) {
                        ok = false
                    } else {
                        category = text
                    }
                }
                val pageFile = item["pageFile"]?.stringOrNull()
                val isHtmlPage = pageFile != null && (
                    pageFile.endsWith(".html", ignoreCase = true) ||
                        pageFile.endsWith(".htm", ignoreCase = true)
                    )
                if (id == null) { invalid("$itemPath.id"); ok = false }
                if (title == null) { invalid("$itemPath.title"); ok = false }
                if (pageFile == null || !isSafeRelativePath(pageFile) || !isHtmlPage) {
                    invalid("$itemPath.pageFile")
                    ok = false
                }
                if (id != null && title != null && pageFile != null && ok) {
                    if (result.any { it.id == id }) {
                        err(PluginErrorCode.DUPLICATE_ID, "$path.$id")
                        ok = false
                    } else {
                        result += PluginWorkspaceToolRef(
                            id = id,
                            title = title,
                            description = description,
                            icon = icon,
                            category = category,
                            pageFile = pageFile
                        )
                    }
                }
            }
            return if (ok) result else null
        }

        /** workspaceTools 的 icon/category 是短标签：非空、有长度上限、不含控制字符。 */
        private fun readWorkspaceToolLabel(raw: JsonElement, path: String, maxLength: Int): String? {
            val text = raw.stringOrNull()?.trim()
            if (text.isNullOrEmpty() || text.length > maxLength || hasControlChars(text)) {
                invalid(path)
                return null
            }
            return text
        }

        /** 缺省视为空列表；每项必须有局部 ID、标题、已知槽位，以及 page / handler 二者之一。 */
        private fun readUiEntries(raw: JsonElement?, path: String): List<PluginUiEntryRef>? {
            if (raw == null) return emptyList()
            val array = raw as? JsonArray ?: return null.also { invalid(path) }
            if (array.size > MAX_IDS_PER_TYPE) return null.also { invalid(path) }
            val result = mutableListOf<PluginUiEntryRef>()
            var ok = true
            array.forEachIndexed { index, element ->
                val itemPath = "$path[$index]"
                val item = element as? JsonObject
                if (item == null) {
                    invalid(itemPath)
                    ok = false
                    return@forEachIndexed
                }
                item.keys.filter { it !in UI_ENTRY_KEYS }.forEach {
                    err(PluginErrorCode.UNSUPPORTED_FIELD, "$itemPath.$it")
                    ok = false
                }
                val id = item["id"]?.stringOrNull()?.takeIf { LOCAL_ID.matches(it) }
                val titleRaw = item["title"]
                val title = if (titleRaw != null) parseLocalized(titleRaw, "$itemPath.title", MAX_NAME) else null
                val slot = item["slot"]?.stringOrNull()?.takeIf { it in PluginUiSlots.ALL }
                val page = item["page"]?.stringOrNull()
                val handlerRaw = item["handler"]?.stringOrNull()
                val handler = handlerRaw?.trim()?.takeIf { HANDLER_NAME.matches(it) }
                val confirmRaw = item["confirm"]
                val confirm = if (confirmRaw != null) (confirmRaw as? JsonPrimitive)?.booleanOrNull else false
                val icon = item["icon"]?.stringOrNull()?.trim() ?: "book"
                if (id == null) { invalid("$itemPath.id"); ok = false }
                if (title == null) { invalid("$itemPath.title"); ok = false }
                if (slot == null) { invalid("$itemPath.slot"); ok = false }
                if (page != null && (!isSafeRelativePath(page) ||
                        !(page.endsWith(".html", ignoreCase = true) || page.endsWith(".htm", ignoreCase = true)))
                ) {
                    invalid("$itemPath.page")
                    ok = false
                }
                if (handlerRaw != null && handler == null) { invalid("$itemPath.handler"); ok = false }
                if (confirmRaw != null && confirm == null) { invalid("$itemPath.confirm"); ok = false }
                if (slot != null) {
                    if (slot in PluginUiSlots.DATA_ONLY) {
                        // 数据选项由宿主渲染开关，不接受页面、动作或确认框。
                        if (page != null) { invalid("$itemPath.page"); ok = false }
                        if (handlerRaw != null) { invalid("$itemPath.handler"); ok = false }
                        if (confirmRaw != null) { invalid("$itemPath.confirm"); ok = false }
                        if (result.count { it.slot == slot } >= 1) { invalid("$itemPath.slot"); ok = false }
                    } else {
                        val kinds = listOfNotNull(page, handler)
                        if (kinds.size != 1) { invalid(itemPath); ok = false }
                        if (confirm == true && handler == null) { invalid("$itemPath.confirm"); ok = false }
                        if (slot in PluginUiSlots.ACTION_ONLY && page != null) {
                            invalid("$itemPath.page")
                            ok = false
                        }
                        if (PluginUiSlots.isSettingsSlot(slot) && handler != null) {
                            invalid("$itemPath.slot")
                            ok = false
                        }
                        if (result.count { it.slot == slot } >= PluginUiSlots.MAX_ENTRIES_PER_PLUGIN) {
                            invalid("$itemPath.slot")
                            ok = false
                        }
                    }
                }
                if (icon.isEmpty() || icon.length > 32) {
                    invalid("$itemPath.icon")
                    ok = false
                }
                if (ok && id != null && title != null && slot != null) {
                    if (result.any { it.id == id }) {
                        err(PluginErrorCode.DUPLICATE_ID, "$path.$id")
                        ok = false
                    } else {
                        result += PluginUiEntryRef(
                            id = id,
                            slot = slot,
                            title = title,
                            page = page,
                            handler = handler,
                            confirm = confirm == true,
                            icon = icon
                        )
                    }
                }
            }
            return if (ok) result else null
        }

        /** 缺省视为空列表；存在但格式不合法返回 null。 */
        private fun readContributionList(
            raw: JsonElement?,
            path: String,
            extension: String = ".md"
        ): List<PluginContributionRef>? {
            if (raw == null) return emptyList()
            val array = raw as? JsonArray ?: return null.also { invalid(path) }
            if (array.size > MAX_IDS_PER_TYPE) return null.also { invalid(path) }
            val result = mutableListOf<PluginContributionRef>()
            var ok = true
            array.forEachIndexed { index, element ->
                val itemPath = "$path[$index]"
                val item = element as? JsonObject
                if (item == null) {
                    invalid(itemPath)
                    ok = false
                    return@forEachIndexed
                }
                item.keys.filter { it !in CONTRIBUTION_KEYS }.forEach {
                    err(PluginErrorCode.UNSUPPORTED_FIELD, "$itemPath.$it")
                    ok = false
                }
                val id = item["id"]?.stringOrNull()?.takeIf { LOCAL_ID.matches(it) }
                val file = item["file"]?.stringOrNull()
                if (id == null) { invalid("$itemPath.id"); ok = false }
                if (file == null || !isSafeRelativePath(file) || !file.endsWith(extension, ignoreCase = true)) {
                    invalid("$itemPath.file")
                    ok = false
                }
                if (id != null && file != null && ok) {
                    if (result.any { it.id == id }) {
                        err(PluginErrorCode.DUPLICATE_ID, "$path.$id")
                        ok = false
                    } else {
                        result += PluginContributionRef(id, file)
                    }
                }
            }
            return if (ok) result else null
        }

    }
}
