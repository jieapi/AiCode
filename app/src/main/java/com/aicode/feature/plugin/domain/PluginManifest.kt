package com.aicode.feature.plugin.domain

/**
 * 宿主认识的插件能力。清单只能申请这里列出的能力，插件无法自定义新权限。
 * 本构建只实现“注册技能 / 注册子代理”两项声明式能力；其余能力在对应功能落地前一律按不支持拒绝。
 */
enum class PluginPermission(val id: String) {
    SKILLS_REGISTER("skills.register"),
    AGENTS_REGISTER("agents.register");

    companion object {
        fun fromId(id: String): PluginPermission? = entries.firstOrNull { it.id == id }
    }
}

/** 一条权限申请。[ids] 为该权限覆盖的本插件贡献局部 ID（`scope.ids`）。 */
data class PluginPermissionRequest(
    val permission: PluginPermission,
    val required: Boolean,
    val reason: LocalizedText,
    val ids: List<String>
)

/** `contributes` 里指向包内 Markdown 文件的一条贡献。[file] 相对于包根目录。 */
data class PluginContributionRef(val id: String, val file: String)

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
    val agents: List<PluginContributionRef>
) {
    /** 清单声明的贡献总数。 */
    val contributionCount: Int get() = skills.size + agents.size

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
