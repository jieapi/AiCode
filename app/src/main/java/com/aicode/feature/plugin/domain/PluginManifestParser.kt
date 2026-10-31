package com.aicode.feature.plugin.domain

import java.net.URI
import java.net.URISyntaxException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
 * `plugin.json` 的严格解析器：未知字段、未实现的贡献类型与能力一律拒绝，不静默忽略。
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
        "manifestVersion", "id", "name", "version", "publisher", "repository",
        "description", "license", "engines", "permissions", "contributes"
    )
    private val ENGINE_KEYS = setOf("pluginApi", "structuredUi")
    private val PERMISSION_KEYS = setOf("id", "required", "reason", "scope")
    private val CONTRIBUTION_KEYS = setOf("id", "file")

    private val PLUGIN_ID = Regex("^[a-z][a-z0-9-]{0,31}\\.[a-z][a-z0-9-]{0,31}$")
    private val PUBLISHER = Regex("^[a-z][a-z0-9-]{0,31}$")
    private val LOCAL_ID = Regex("^[A-Za-z][A-Za-z0-9_-]{0,63}$")
    private val LANGUAGE_TAG = Regex("^[a-z]{2,3}(-[a-z0-9]{2,8})*$")

    private const val MAX_NAME = 80
    private const val MAX_DESCRIPTION = 600
    private const val MAX_REASON = 300
    private const val MAX_LICENSE = 100
    private const val MAX_REPOSITORY = 300
    private const val MAX_LOCALES = 16
    private const val MAX_IDS_PER_TYPE = 64
    const val MAX_PATH_LENGTH = 200
    const val MAX_PATH_DEPTH = 8

    /** 插件 ID 同时用作安装目录名，读取已保存记录时也要用它拦住被篡改的值。 */
    fun isValidPluginId(id: String): Boolean = PLUGIN_ID.matches(id)

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
            val permissions = readPermissions(obj)
            val contributes = readContributes(obj)

            if (permissions != null && contributes != null) crossCheck(permissions, contributes)
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
                    pluginApi = requireNotNull(engines).first,
                    structuredUi = engines.second,
                    permissions = requireNotNull(permissions),
                    skills = requireNotNull(contributes).skills,
                    agents = contributes.agents,
                    workspaceTools = contributes.workspaceTools
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

        /** 返回 (pluginApi 范围原文, structuredUi 范围原文或 null)。 */
        private fun readEngines(obj: JsonObject): Pair<String, String?>? {
            val raw = obj["engines"] ?: return null.also { missing("engines") }
            val engines = raw as? JsonObject ?: return null.also { invalid("engines") }
            engines.keys.filter { it !in ENGINE_KEYS }.forEach { err(PluginErrorCode.UNSUPPORTED_FIELD, "engines.$it") }

            val apiText = engines["pluginApi"]?.stringOrNull()?.trim()
            if (apiText == null) {
                if (engines["pluginApi"] == null) missing("engines.pluginApi") else invalid("engines.pluginApi")
                return null
            }
            val apiRange = SemVerRange.parse(apiText) ?: return null.also { invalid("engines.pluginApi") }
            val uiText = engines["structuredUi"]?.let { element ->
                val text = element.stringOrNull()?.trim()
                if (text == null || SemVerRange.parse(text) == null) {
                    invalid("engines.structuredUi")
                    return null
                }
                text
            }
            if (!apiRange.matches(hostApi)) {
                err(PluginErrorCode.API_INCOMPATIBLE, apiText)
                return null
            }
            return apiText to uiText
        }

        private fun readPermissions(obj: JsonObject): List<PluginPermissionRequest>? {
            val raw = obj["permissions"] ?: return null.also { missing("permissions") }
            val array = raw as? JsonArray ?: return null.also { invalid("permissions") }
            val result = mutableListOf<PluginPermissionRequest>()
            var ok = true
            array.forEachIndexed { index, element ->
                val request = readPermission(element, "permissions[$index]")
                if (request == null) {
                    ok = false
                } else if (result.any { it.permission == request.permission }) {
                    err(PluginErrorCode.DUPLICATE_ID, "permissions.${request.permission.id}")
                    ok = false
                } else {
                    result += request
                }
            }
            return if (ok) result else null
        }

        private fun readPermission(element: JsonElement, path: String): PluginPermissionRequest? {
            val obj = element as? JsonObject ?: return null.also { invalid(path) }
            obj.keys.filter { it !in PERMISSION_KEYS }.forEach { err(PluginErrorCode.UNSUPPORTED_FIELD, "$path.$it") }
            val missingKeys = PERMISSION_KEYS.filter { it !in obj }
            if (missingKeys.isNotEmpty()) {
                missingKeys.forEach { missing("$path.$it") }
                return null
            }

            val idText = obj.getValue("id").stringOrNull() ?: return null.also { invalid("$path.id") }
            val permission = PluginPermission.fromId(idText)
            if (permission == null) {
                err(PluginErrorCode.UNSUPPORTED_PERMISSION, idText)
                return null
            }
            val required = (obj.getValue("required") as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                ?: return null.also { invalid("$path.required") }
            val reason = parseLocalized(obj.getValue("reason"), "$path.reason", MAX_REASON) ?: return null
            val scope = obj.getValue("scope") as? JsonObject ?: return null.also { invalid("$path.scope") }
            scope.keys.filter { it != "ids" }.forEach { err(PluginErrorCode.UNSUPPORTED_FIELD, "$path.scope.$it") }
            val ids = readLocalIds(scope["ids"], "$path.scope.ids") ?: return null
            return PluginPermissionRequest(permission, required, reason, ids)
        }

        private fun readLocalIds(raw: JsonElement?, path: String): List<String>? {
            if (raw == null) return null.also { missing(path) }
            val array = raw as? JsonArray ?: return null.also { invalid(path) }
            if (array.isEmpty() || array.size > MAX_IDS_PER_TYPE) return null.also { invalid(path) }
            val ids = array.map { it.stringOrNull() }
            if (ids.any { it == null || !LOCAL_ID.matches(it) }) return null.also { invalid(path) }
            val distinct = ids.filterNotNull()
            if (distinct.toSet().size != distinct.size) return null.also { err(PluginErrorCode.DUPLICATE_ID, path) }
            return distinct
        }

        private class Contributes(
            val skills: List<PluginContributionRef>,
            val agents: List<PluginContributionRef>,
            val workspaceTools: List<PluginWorkspaceToolRef>
        )

        private fun readContributes(obj: JsonObject): Contributes? {
            val raw = obj["contributes"] ?: return null.also { missing("contributes") }
            val contributes = raw as? JsonObject ?: return null.also { invalid("contributes") }
            var ok = true
            contributes.keys.filter { it != "skills" && it != "agents" && it != "workspaceTools" }.forEach {
                err(PluginErrorCode.UNSUPPORTED_CONTRIBUTION, it)
                ok = false
            }
            val skills = readContributionList(contributes["skills"], "contributes.skills") ?: run { ok = false; emptyList() }
            val agents = readContributionList(contributes["agents"], "contributes.agents") ?: run { ok = false; emptyList() }
            val workspaceTools = readWorkspaceTools(contributes["workspaceTools"], "contributes.workspaceTools") ?: run { ok = false; emptyList() }
            if (ok && skills.isEmpty() && agents.isEmpty() && workspaceTools.isEmpty()) {
                invalid("contributes")
                ok = false
            }
            return if (ok) Contributes(skills, agents, workspaceTools) else null
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
                val id = item["id"]?.stringOrNull()?.takeIf { LOCAL_ID.matches(it) }
                val titleRaw = item["title"]
                val title = if (titleRaw != null) parseLocalized(titleRaw, "$itemPath.title", MAX_NAME) else null
                val descRaw = item["description"]
                val description = if (descRaw != null) parseLocalized(descRaw, "$itemPath.description", MAX_DESCRIPTION) ?: LocalizedText.single("") else LocalizedText.single("")
                val icon = item["icon"]?.stringOrNull() ?: "play"
                val category = item["category"]?.stringOrNull() ?: "通用"
                val pageFile = item["pageFile"]?.stringOrNull()
                if (id == null) { invalid("$itemPath.id"); ok = false }
                if (title == null) { invalid("$itemPath.title"); ok = false }
                if (pageFile == null || !isSafeRelativePath(pageFile) || !pageFile.endsWith(".json", ignoreCase = true)) {
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

        /** 缺省视为空列表；存在但格式不合法返回 null。 */
        private fun readContributionList(raw: JsonElement?, path: String): List<PluginContributionRef>? {
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
                if (file == null || !isSafeRelativePath(file) || !file.endsWith(".md", ignoreCase = true)) {
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

        /** 每类贡献的局部 ID 必须被对应的注册权限逐项覆盖，权限范围里也不能有不存在的 ID。 */
        private fun crossCheck(permissions: List<PluginPermissionRequest>, contributes: Contributes) {
            checkCoverage(PluginPermission.SKILLS_REGISTER, "skills", contributes.skills, permissions)
            checkCoverage(PluginPermission.AGENTS_REGISTER, "agents", contributes.agents, permissions)
            checkCoverage(PluginPermission.WORKSPACE_TOOLS, "workspaceTools", contributes.workspaceTools.map { PluginContributionRef(it.id, it.pageFile) }, permissions)
        }

        private fun checkCoverage(
            permission: PluginPermission,
            type: String,
            contributions: List<PluginContributionRef>,
            permissions: List<PluginPermissionRequest>
        ) {
            val covered = permissions.firstOrNull { it.permission == permission }?.ids.orEmpty().toSet()
            val declared = contributions.map { it.id }.toSet()
            (declared - covered).forEach { err(PluginErrorCode.PERMISSION_SCOPE_MISMATCH, "$type.$it") }
            (covered - declared).forEach { err(PluginErrorCode.PERMISSION_SCOPE_MISMATCH, "${permission.id}.$it") }
        }
    }
}
