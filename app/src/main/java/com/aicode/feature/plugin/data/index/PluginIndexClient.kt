package com.aicode.feature.plugin.data.index

import android.content.Context
import com.aicode.feature.plugin.domain.SemVer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.Serializable
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request

data class PluginIndexSnapshot(
    val index: PluginIndexDocument,
    val stale: Boolean,
    val fetchedAt: Long
)

data class PluginReleaseAsset(
    val version: String,
    val name: String,
    val url: String,
    val size: Long
)

data class DownloadedPluginPackage(
    val file: File,
    val asset: PluginReleaseAsset
)

class PluginIndexException(message: String, cause: Throwable? = null) : IOException(message, cause)

class NoMatchingReleaseException(message: String) : IOException(message)

@Singleton
class PluginIndexClient @Inject constructor(
    @param:ApplicationContext private val context: Context,
    client: OkHttpClient
) {
    private val http = client.newBuilder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .cache(null)
        .build()

    private val cacheRoot = File(context.filesDir, "plugin-index-cache")

    private val hostAppVersion: SemVer? = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName?.let(SemVer::parse)
    }.getOrNull()

    suspend fun fetchIndex(source: PluginIndexSource, forceRefresh: Boolean = false): PluginIndexSnapshot =
        withContext(Dispatchers.IO) {
            val cached = cacheFile(source)
            val bytes = try {
                getBytes(source.indexUrl, MAX_INDEX_BYTES, noCache = forceRefresh)
            } catch (e: IOException) {
                if (forceRefresh) throw e
                val fallback = readCache(cached) ?: throw e
                return@withContext PluginIndexSnapshot(fallback, stale = true, fetchedAt = cached.lastModified())
            }
            val index = PluginIndexParser.parse(bytes)
            writeCache(cached, bytes)
            PluginIndexSnapshot(index, stale = false, fetchedAt = System.currentTimeMillis())
        }

    fun isCompatible(plugin: PluginIndexPlugin): Boolean {
        val minimum = plugin.minAppVersion?.let { SemVer.parse(it) ?: return false } ?: return true
        val current = hostAppVersion ?: return true
        return current >= minimum
    }

    /**
     * 解析插件对应的 Release 资产。
     * version 为空时取 latest Release 中 `{id}_*.zip` 的最高版本；有 version 时精确查找 `{id}_{version}.zip`。
     */
    suspend fun resolveRelease(plugin: PluginIndexPlugin): PluginReleaseAsset = withContext(Dispatchers.IO) {
        val (owner, repo) = parseGitHubRepo(plugin.repository)
            ?: throw NoMatchingReleaseException("不是 GitHub 仓库: ${plugin.repository}")
        val expectedName = plugin.version?.let { assetName(plugin.id, it) }
        val releases = if (plugin.version == null) {
            listOf(apiGet<GitHubRelease>("$GITHUB_API/repos/$owner/$repo/releases/latest"))
        } else {
            apiGet<List<GitHubRelease>>("$GITHUB_API/repos/$owner/$repo/releases?per_page=100")
        }
        val candidates = releases
            .filterNot { it.draft }
            .flatMap { release -> release.assets.map { asset -> release to asset } }
        val matched = when {
            expectedName != null -> candidates.firstOrNull { it.second.name == expectedName }
            else -> candidates
                .mapNotNull { pair ->
                    val version = assetVersion(plugin.id, pair.second.name) ?: return@mapNotNull null
                    pair to version
                }
                .maxByOrNull { SemVer.parse(it.second) ?: SemVer(0, 0, 0) }
                ?.let { it.first }
        } ?: throw NoMatchingReleaseException("未找到插件资产: ${expectedName ?: "${plugin.id}_*.zip"}")
        val asset = matched.second
        val version = expectedName?.let { plugin.version!! } ?: assetVersion(plugin.id, asset.name)!!
        PluginReleaseAsset(
            version = version,
            name = asset.name,
            url = asset.browserDownloadUrl,
            size = asset.size
        )
    }

    suspend fun download(asset: PluginReleaseAsset): DownloadedPluginPackage = withContext(Dispatchers.IO) {
        if (asset.size <= 0 || asset.size > MAX_ARCHIVE_BYTES) {
            throw PluginIndexException("资产大小非法: ${asset.size}")
        }
        val bytes = getBytes(asset.url, asset.size, noCache = true, expectedSize = asset.size)
        val file = File(context.cacheDir, "plugin-${sha256(bytes).take(16)}.zip")
        file.writeBytes(bytes)
        DownloadedPluginPackage(file, asset)
    }

    private fun assetName(id: String, version: String): String = "${id}_$version.zip"

    private fun assetVersion(id: String, name: String): String? {
        val prefix = "${id}_"
        if (!name.startsWith(prefix) || !name.endsWith(".zip")) return null
        val version = name.removePrefix(prefix).removeSuffix(".zip")
        return version.takeIf { !it.startsWith("v") && SemVer.parse(it) != null }
    }

    private fun parseGitHubRepo(repository: String): Pair<String, String>? {
        val uri = runCatching { URI(repository) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host != "github.com") return null
        val segments = uri.path.trim('/').split('/')
        if (segments.size != 2 || segments.any { it.isBlank() }) return null
        return segments[0] to segments[1].removeSuffix(".git")
    }

    private inline fun <reified T> apiGet(url: String): T {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "aicode-plugin-index")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw NoMatchingReleaseException("GitHub Release 请求失败: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw NoMatchingReleaseException("GitHub Release 响应为空")
            return try {
                PluginIndexJson.decodeFromString<T>(body)
            } catch (e: Exception) {
                throw NoMatchingReleaseException("GitHub Release 响应解析失败")
            }
        }
    }

    private fun getBytes(
        url: String,
        maxBytes: Long,
        noCache: Boolean,
        expectedSize: Long? = null
    ): ByteArray {
        val builder = Request.Builder().url(url).header("User-Agent", "aicode-plugin-index")
        if (noCache) builder.cacheControl(CacheControl.Builder().noCache().build())
        http.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}: $url")
            val body = response.body ?: throw IOException("空响应: $url")
            val declared = body.contentLength()
            if (declared > maxBytes) throw IOException("响应超过大小限制: $url")
            if (expectedSize != null && declared > 0 && declared != expectedSize) {
                throw IOException("响应大小 $declared 与声明 $expectedSize 不一致")
            }
            val stream = body.byteStream()
            val output = java.io.ByteArrayOutputStream(if (declared in 1..maxBytes) declared.toInt() else 8192)
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) throw IOException("响应超过大小限制: $url")
                output.write(buffer, 0, read)
            }
            if (expectedSize != null && total != expectedSize) {
                throw IOException("响应大小 $total 与声明 $expectedSize 不一致")
            }
            return output.toByteArray()
        }
    }

    private fun cacheFile(source: PluginIndexSource): File = File(File(cacheRoot, source.id), "index.json")

    private fun writeCache(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeBytes(bytes)
        temp.renameTo(file)
    }

    private fun readCache(file: File): PluginIndexDocument? {
        if (!file.isFile) return null
        return runCatching { PluginIndexParser.parse(file.readBytes()) }.getOrNull()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Serializable
    private data class GitHubRelease(
        @SerialName("tag_name") val tagName: String = "",
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<GitHubAsset> = emptyList()
    )

    @Serializable
    private data class GitHubAsset(
        val name: String,
        @SerialName("browser_download_url") val browserDownloadUrl: String,
        val size: Long = 0
    )

    private companion object {
        const val GITHUB_API = "https://api.github.com"
        const val MAX_INDEX_BYTES = 2L * 1024 * 1024
        const val MAX_ARCHIVE_BYTES = 16L * 1024 * 1024
    }
}
