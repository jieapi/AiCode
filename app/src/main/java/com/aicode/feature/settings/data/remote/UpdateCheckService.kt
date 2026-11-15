package com.aicode.feature.settings.data.remote

import android.content.Context
import android.os.Build
import com.aicode.R
import com.aicode.core.util.compareVersions
import com.aicode.core.util.parseVersionTag
import com.aicode.feature.settings.data.repository.UpdateChannel
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Release 里的一个更新包资产。 */
data class UpdateAsset(
    val name: String,
    val url: String,
    val size: Long,
    /**
     * GitHub API 的 `digest` 字段（形如 `sha256:...`）。较早的 release 可能没有该字段，
     * 缺失时不阻断下载，只跳过摘要校验（签名校验才是安全锚，见 [ApkVerifier]）。
     */
    val sha256: String?
)

/** 单个版本的更新日志与更新包。 */
data class VersionUpdate(
    val tag: String,
    val changelog: String,
    val asset: UpdateAsset? = null
)

/** 拉取到的更新信息：最新版本号 + 从当前版本到最新的更新日志。 */
data class UpdateInfo(
    val latestTag: String,
    val changelog: String,
    val updates: List<VersionUpdate>
) {
    /** 最新版本的更新包（应用内下载用）；该 Release 没挂 APK 资产时为 null。 */
    val latestAsset: UpdateAsset? get() = updates.firstOrNull()?.asset
}

/** 检查更新结果。 */
sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult
    data class NewVersion(val info: UpdateInfo) : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

/**
 * 从 GitHub Releases 拉取版本信息并生成更新日志。
 *
 * 稳定版通道过滤预发布（RC），最新版通道包含预发布；只统计严格高于当前版本的 release，
 * 更新日志按版本从高到低拼接（版本号 + 正文，轻量清理 markdown 标题/粗体）。
 * 结果不落盘，由调用方按需展示。
 */
@Singleton
class UpdateCheckService @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    suspend fun checkForUpdate(currentVersion: String, channel: UpdateChannel): UpdateCheckResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = okhttp3.Request.Builder()
                    .url("$GITHUB_RELEASES_API?per_page=50")
                    .header("Accept", "application/vnd.github+json")
                    .build()
                SHARED_CLIENT.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@use UpdateCheckResult.Error("HTTP ${resp.code}")
                    }
                    val body = resp.body?.string().orEmpty()
                    val array = runCatching { JsonParser.parseString(body).asJsonArray }
                        .getOrNull()
                        ?: return@use UpdateCheckResult.Error(context.getString(R.string.about_parse_version_failed))

                    val releases = array.mapNotNull { el ->
                        val obj = el.asJsonObject
                        val tag = obj.get("tag_name")?.asString ?: return@mapNotNull null
                        val version = parseVersionTag(tag) ?: return@mapNotNull null
                        ReleaseInfo(
                            version = version,
                            rawTag = tag,
                            notes = obj.get("body")?.asString.orEmpty(),
                            prerelease = obj.get("prerelease")?.asBoolean ?: false,
                            assets = parseAssets(obj)
                        )
                    }

                    val updates = releases
                        .filter { if (channel == UpdateChannel.STABLE) !it.prerelease else true }
                        .filter { compareVersions(it.version, currentVersion) > 0 }
                        .sortedWith { a, b -> compareVersions(b.version, a.version) }

                    if (updates.isEmpty()) {
                        UpdateCheckResult.UpToDate
                    } else {
                        val versionUpdates = updates.map { toVersionUpdate(it) }
                        UpdateCheckResult.NewVersion(
                            UpdateInfo(
                                latestTag = versionUpdates.first().tag,
                                changelog = versionUpdates.joinToString("\n\n") { "${it.tag}\n${it.changelog}" },
                                updates = versionUpdates
                            )
                        )
                    }
                }
            }.getOrElse { UpdateCheckResult.Error(it.message ?: context.getString(R.string.about_network_error)) }
        }

    private fun toVersionUpdate(release: ReleaseInfo): VersionUpdate {
        val notes = release.notes.trim()
        return VersionUpdate(
            tag = release.rawTag,
            changelog = if (notes.isEmpty()) {
                context.getString(R.string.about_no_changelog)
            } else {
                cleanMarkdown(notes)
            },
            asset = selectUpdateAsset(release.assets, release.rawTag, preferredAbiToken(Build.SUPPORTED_ABIS.toList()))
        )
    }

    /** 轻量清理 markdown：去掉行首标题符与粗体标记，并删除 GitHub 自动生成的标题/链接行，保留纯文本便于直接阅读。 */
    private fun cleanMarkdown(text: String): String = text
        .lines()
        .map { it.trimEnd().replace("**", "") }
        .map { it.replace(Regex("^#{1,6}\\s+"), "") }
        .filterNot { line ->
            val t = line.trim()
            t.startsWith("What's Changed") ||
                t.startsWith("What's New") ||
                t.startsWith("Full Changelog:")
        }
        .joinToString("\n")

    private data class ReleaseInfo(
        val version: String,
        val rawTag: String,
        val notes: String,
        val prerelease: Boolean,
        val assets: List<UpdateAsset>
    )

    /** 解析 Release 的资产列表；`digest` 只认 sha256，其它算法（或缺失）一律置 null。 */
    private fun parseAssets(obj: com.google.gson.JsonObject): List<UpdateAsset> =
        obj.getAsJsonArray("assets")?.mapNotNull { el ->
            val asset = runCatching { el.asJsonObject }.getOrNull() ?: return@mapNotNull null
            val name = asset.get("name")?.asString ?: return@mapNotNull null
            val url = asset.get("browser_download_url")?.asString ?: return@mapNotNull null
            UpdateAsset(
                name = name,
                url = url,
                size = asset.get("size")?.asLong ?: 0L,
                sha256 = asset.get("digest")?.asString
                    ?.takeIf { it.startsWith(SHA256_PREFIX, ignoreCase = true) }
                    ?.substring(SHA256_PREFIX.length)
                    ?.takeIf { it.isNotBlank() }
            )
        } ?: emptyList()

    private companion object {
        const val GITHUB_RELEASES_API = "https://api.github.com/repos/jieapi/aicode/releases"
        const val SHA256_PREFIX = "sha256:"
        val SHARED_CLIENT by lazy {
            okhttp3.OkHttpClient.Builder()
                .proxyAuthenticator(com.aicode.core.net.AppProxy.okHttpAuthenticator)
                .build()
        }
    }
}

/**
 * 设备主 ABI 对应的资产名架构段：按 [supportedAbis] 的优先顺序取第一个支持的架构
 * （`arm64-v8a` → `arm64`，`x86_64` → `x86_64`）。都不支持时返回 null，调用方退回 universal 包。
 */
internal fun preferredAbiToken(supportedAbis: List<String>): String? =
    supportedAbis.firstNotNullOfOrNull { abi ->
        when (abi.lowercase()) {
            "arm64-v8a" -> "arm64"
            "x86_64" -> "x86_64"
            else -> null
        }
    }

/**
 * 从 Release 资产里挑出更新包。
 *
 * 资产命名由 CI 决定（`aicode-<架构>-<版本>.apk`，见 `.github/workflows/android-release.yml`）。
 * 同一 Release 里还混着 `mapping.txt.gz`，所以只认 `.apk` 结尾；精确名没命中时依次退让为
 * 「含架构段」→ `universal`，都不命中返回 null（该版本没有可用安装包，只能去浏览器下载）。
 * 一律按 API 返回的资产名匹配，不自己拼下载 URL——CI 曾因段序拼错导致资产匹配不上。
 */
internal fun selectUpdateAsset(assets: List<UpdateAsset>, rawTag: String, abiToken: String?): UpdateAsset? {
    val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
    if (abiToken != null) {
        apks.firstOrNull { it.name.equals("aicode-$abiToken-$rawTag.apk", ignoreCase = true) }?.let { return it }
        apks.firstOrNull { it.name.startsWith("aicode-$abiToken-", ignoreCase = true) }?.let { return it }
    }
    return apks.firstOrNull { it.name.startsWith("aicode-universal-", ignoreCase = true) }
}
