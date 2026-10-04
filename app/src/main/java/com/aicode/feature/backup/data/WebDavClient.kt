package com.aicode.feature.backup.data

import com.aicode.core.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** WebDAV 操作失败，携带可直接展示的原因。 */
class WebDavException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 极简 WebDAV 客户端：仅覆盖本功能需要的能力——连接测试（PROPFIND）、上传文件（PUT）、
 * 删除文件（DELETE）。不做完整的 WebDAV 目录管理。
 *
 * 鉴权用 HTTP Basic（绝大多数 WebDAV 服务如 Nextcloud、坚果云、群晖均支持）。
 */
@Singleton
class WebDavClient @Inject constructor() {

    private val client: OkHttpClient = DEFAULT_CLIENT

    /** 连接测试：对目标 URL 发 PROPFIND（Depth: 0），2xx/207 视为成功。 */
    suspend fun test(config: WebDavConfig): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(config.url.isNotBlank()) { "WebDAV 地址未填写" }
            val request = baseRequest(config)
                .url(config.url)
                .method("PROPFIND", EMPTY_BODY)
                .header("Depth", "0")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 207) {
                    throw WebDavException("服务器返回 ${response.code} ${response.message}")
                }
            }
        }.onFailure { FileLogger.e(TAG, "WebDAV 连接测试失败", it) }
    }

    /** 上传文件到 [remoteFileName]（相对配置的目录 URL，可含子目录如 `AiCode/xxx`）。
     *  若含子目录，先 MKCOL 逐级创建（已存在返回 405 也视为成功）。 */
    suspend fun upload(config: WebDavConfig, localFile: File, remoteFileName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(config.url.isNotBlank()) { "WebDAV 地址未填写" }
                ensureDirectories(config, remoteFileName)
                val target = buildTargetUrl(config.url, remoteFileName)
                val request = baseRequest(config)
                    .url(target)
                    .put(localFile.asRequestBody("application/octet-stream".toMediaType()))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw WebDavException("上传失败：服务器返回 ${response.code} ${response.message}")
                    }
                }
            }.onFailure { FileLogger.e(TAG, "WebDAV 上传失败", it) }
        }

    /** 为 [remoteFileName] 中的子目录逐级 MKCOL（如 `AiCode/backup.tar.gz` → 创建 `AiCode/`）。 */
    private fun ensureDirectories(config: WebDavConfig, remoteFileName: String) {
        val parts = remoteFileName.split('/').dropLast(1).filter { it.isNotEmpty() }
        var accum = ""
        for (part in parts) {
            accum = if (accum.isEmpty()) part else "$accum/$part"
            val dirUrl = buildTargetUrl(config.url, accum) + "/"
            val request = baseRequest(config).url(dirUrl).method("MKCOL", EMPTY_BODY).build()
            runCatching {
                client.newCall(request).execute().use { response ->
                    // 201 创建成功；405 已存在；两者都视为可用。
                    if (response.code != 201 && response.code != 405 && !response.isSuccessful) {
                        throw WebDavException("创建目录失败：服务器返回 ${response.code}")
                    }
                }
            }.getOrElse { throw it }
        }
    }

    private fun baseRequest(config: WebDavConfig): Request.Builder {
        val builder = Request.Builder()
        if (config.username.isNotBlank() || config.password.isNotBlank()) {
            builder.header("Authorization", Credentials.basic(config.username, config.password))
        }
        return builder
    }

    /** 拼接目录 URL 与文件名，容忍目录末尾有无 `/`。 */
    private fun buildTargetUrl(baseUrl: String, fileName: String): String {
        val base = baseUrl.trimEnd('/')
        return "$base/$fileName"
    }

    companion object {
        private const val TAG = "WebDavClient"

        private val EMPTY_BODY = ByteArray(0).toRequestBodyCompat()

        private val DEFAULT_CLIENT: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }

        private fun ByteArray.toRequestBodyCompat() =
            okhttp3.RequestBody.create("application/xml; charset=utf-8".toMediaType(), this)
    }
}
