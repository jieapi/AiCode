package com.aicode.feature.settings.data.remote

import android.content.Context
import com.aicode.core.net.RepoDataFetcher
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class SponsorCatalogData(
    val methods: List<SponsorMethod> = emptyList(),
    val users: List<SponsorUser> = emptyList()
)

@Serializable
data class SponsorMethod(
    val id: String,
    val name: Map<String, String> = emptyMap(),
    val subtitle: Map<String, String> = emptyMap(),
    val url: String? = null,
    val qrImageUrl: String? = null,
    val icon: String? = null
)

@Serializable
data class SponsorUser(
    val name: String,
    val avatar: String? = null,
    val url: String? = null
)

/**
 * 赞助数据实时拉取服务：每次打开赞助页面时直接请求仓库 `data/sponsors.json`，
 * 不读写本地缓存、不在应用启动时预热；保留多节点（jsDelivr CDN + GitHub raw）兜底与默认赞助方式。
 */
@Singleton
class SponsorCatalog @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    suspend fun fetchLatest(): Result<SponsorCatalogData> = withContext(Dispatchers.IO) {
        val urls = RepoDataFetcher(context).buildCandidateUrls(REMOTE_PATH)
        var lastError: Throwable? = null

        for (url in urls) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .cacheControl(CacheControl.FORCE_NETWORK)
                    .header("User-Agent", "aicode-android")
                    .get()
                    .build()

                RepoDataFetcher.DEFAULT_CLIENT.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        val parsed = parseSponsorCatalog(body)
                        if (parsed != null) {
                            return@withContext Result.success(parsed)
                        }
                        lastError = IllegalStateException("Invalid sponsors json from $url")
                        FileLogger.d(TAG, "赞助配置格式无效 url=$url")
                    } else {
                        lastError = IllegalStateException("HTTP ${response.code}")
                        FileLogger.d(TAG, "赞助配置请求失败 url=$url code=${response.code}")
                    }
                }
            } catch (e: Throwable) {
                lastError = e
                FileLogger.d(TAG, "赞助配置连接异常 url=$url: ${e.message}")
            }
        }

        val error = lastError ?: IllegalStateException("Failed to fetch $REMOTE_PATH")
        FileLogger.w(TAG, "实时拉取赞助配置失败", error)
        Result.failure(error)
    }

    companion object {
        private const val TAG = "SponsorCatalog"
        const val REMOTE_PATH = "data/sponsors.json"
        const val DEFAULT_AFDIAN_URL = "https://ifdian.net/a/jieapi"
        private val parserJson = Json { ignoreUnknownKeys = true }

        internal val DEFAULT_METHODS = listOf(
            SponsorMethod(
                id = "afdian",
                name = mapOf("zh" to "爱发电", "en" to "Afdian"),
                url = DEFAULT_AFDIAN_URL,
                icon = "heart"
            )
        )

        internal fun parseSponsorCatalog(raw: String?): SponsorCatalogData? {
            if (raw.isNullOrBlank()) return null
            val parsed = runCatching { parserJson.decodeFromString<SponsorCatalogData>(raw) }.getOrNull()
                ?: return null
            val methods = parsed.methods.filter { it.id.isNotBlank() }
            val users = parsed.users.filter { it.name.isNotBlank() }
            if (methods.isEmpty() && users.isEmpty()) return null
            return SponsorCatalogData(
                methods = methods.ifEmpty { DEFAULT_METHODS },
                users = users
            )
        }
    }
}
