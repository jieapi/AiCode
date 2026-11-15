package com.aicode.feature.plugin.runtime

import com.aicode.feature.plugin.domain.PluginHostException
import com.aicode.feature.plugin.domain.PluginHostPaths
import com.aicode.feature.plugin.domain.PluginManager
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** 校验后的请求描述；只为 [PluginHttpService] 与单测使用。 */
internal data class PluginHttpRequest(
    val url: HttpUrl,
    val method: String,
    val headers: Map<String, String>,
    val body: String?,
    val timeoutSeconds: Long
)

/**
 * 插件的通用 HTTP 请求能力：只允许 http/https、不跟随重定向、不注入任何宿主凭据。
 *
 * 每次调用都用注入的 [OkHttpClient] 克隆一个独立 client（关闭重定向、套用本次超时），
 * 请求在 IO 线程执行。有 body 且未显式设置 Content-Type 时按 `application/json` 发送；
 * 响应体最多读 1 MiB，超出截断并置 `truncated`。参数问题抛 `PARAM_MISSING` /
 * `PARAM_INVALID`，网络失败统一抛 `HTTP_FAILED`。
 */
@Singleton
class PluginHttpService @Inject constructor(
    private val pluginManager: PluginManager,
    private val client: OkHttpClient
) {

    private val plainClient: OkHttpClient = OkHttpClient.Builder()
        // 复用连接池与调度器，但不复用 provider 拦截器：插件 HTTP 不应触发 agent.http.before，
        // 否则插件自己的 hook 调 host.http.request 会递归。
        .dispatcher(client.dispatcher)
        .connectionPool(client.connectionPool)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun request(pluginId: String, params: JsonObject): JsonElement {
        ensureActive(pluginId)
        val request = parseRequest(params)
        return withContext(Dispatchers.IO) { execute(request) }
    }

    /** 校验并规范化参数；与网络执行分离，便于单测覆盖各字段边界。 */
    internal fun parseRequest(params: JsonObject): PluginHttpRequest {
        val rawUrl = PluginHostPaths.requireString(params, "url").trim()
        if (rawUrl.length > MAX_URL_CHARS) throw paramInvalid("url too long")
        if (!rawUrl.startsWith("http://", ignoreCase = true) &&
            !rawUrl.startsWith("https://", ignoreCase = true)
        ) {
            throw paramInvalid("url must use http or https")
        }
        val url = rawUrl.toHttpUrlOrNull() ?: throw paramInvalid("url is malformed")

        val method = (optionalString(params, "method")?.trim()?.uppercase(Locale.ROOT)) ?: "GET"
        if (method !in METHODS) throw paramInvalid("unsupported method: $method")

        val body = optionalString(params, "body")
        if (body != null) {
            if (method == "GET" || method == "HEAD") throw paramInvalid("$method does not accept a body")
            if (body.toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES) throw paramInvalid("body too large")
        }

        val headers = when (val rawHeaders = params["headers"]) {
            null, JsonNull -> emptyMap<String, String>()
            is JsonObject -> parseHeaders(rawHeaders)
            else -> throw paramInvalid("headers must be an object")
        }

        val timeoutSeconds = when (val rawTimeout = params["timeout"]) {
            null, JsonNull -> DEFAULT_TIMEOUT_SECONDS
            is JsonPrimitive -> rawTimeout.longOrNull
                ?.takeIf { it in MIN_TIMEOUT_SECONDS..MAX_TIMEOUT_SECONDS }
                ?: throw paramInvalid("timeout must be $MIN_TIMEOUT_SECONDS..$MAX_TIMEOUT_SECONDS seconds")
            else -> throw paramInvalid("timeout must be a number")
        }

        return PluginHttpRequest(
            url = url,
            method = method,
            headers = headers,
            body = body,
            timeoutSeconds = timeoutSeconds
        )
    }

    /** 把状态码、响应头与已读取的字节装成 JSON；超过上限的响应体按字节截断。 */
    internal fun buildResponse(status: Int, headers: Map<String, String>, body: ByteArray): JsonObject {
        val truncated = body.size > MAX_RESPONSE_BYTES
        val bytes = if (truncated) body.copyOf(MAX_RESPONSE_BYTES) else body
        return buildJsonObject {
            put("status", status)
            put("headers", buildJsonObject { headers.forEach { (name, value) -> put(name, value) } })
            put("body", String(bytes, Charsets.UTF_8))
            put("truncated", truncated)
        }
    }

    private fun execute(request: PluginHttpRequest): JsonElement {
        val httpClient = plainClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(request.timeoutSeconds, TimeUnit.SECONDS)
            .build()
        val builder = Request.Builder().url(request.url)
        if (request.headers.isNotEmpty()) {
            val headers = Headers.Builder()
            request.headers.forEach { (name, value) -> headers.add(name, value) }
            builder.headers(headers.build())
        }
        builder.method(request.method, requestBody(request))
        return try {
            httpClient.newCall(builder.build()).execute().use { response ->
                val headers = response.headers.toMultimap()
                    .mapValues { (_, values) -> values.joinToString(", ") }
                buildResponse(
                    status = response.code,
                    headers = headers,
                    body = response.peekBody(MAX_RESPONSE_BYTES + 1L).bytes()
                )
            }
        } catch (e: IOException) {
            throw PluginHostException("HTTP_FAILED", e.message ?: e.javaClass.simpleName)
        }
    }

    private fun requestBody(request: PluginHttpRequest): RequestBody? {
        val text = request.body
        if (text == null) {
            // OkHttp 要求 POST/PUT/PATCH 必须带请求体；无 body 时发空体，避免直接抛异常。
            return if (request.method in BODY_REQUIRED_METHODS) EMPTY_BODY else null
        }
        val hasContentType = request.headers.keys.any { it.equals("Content-Type", ignoreCase = true) }
        return text.toRequestBody(if (hasContentType) null else JSON_MEDIA_TYPE)
    }

    private fun parseHeaders(raw: JsonObject): Map<String, String> {
        if (raw.size > MAX_HEADERS) throw paramInvalid("too many headers")
        val headers = LinkedHashMap<String, String>()
        raw.forEach { (name, value) ->
            if (!HEADER_NAME.matches(name)) throw paramInvalid("invalid header name: $name")
            if (name.lowercase(Locale.ROOT) in FORBIDDEN_HEADERS) throw paramInvalid("forbidden header: $name")
            val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw paramInvalid("header value must be a string: $name")
            if (text.length > MAX_HEADER_VALUE_CHARS) throw paramInvalid("header value too long: $name")
            // 与 OkHttp 可写字符集对齐：只允许可见 ASCII 与制表符，避免换行注入或运行期抛错。
            if (!text.all { it == '\t' || it in ' '..'~' }) throw paramInvalid("invalid header value: $name")
            headers[name] = text
        }
        return headers
    }

    private fun optionalString(params: JsonObject, key: String): String? {
        val raw = params[key] ?: return null
        return (raw as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw paramInvalid("$key must be a string")
    }

    private fun ensureActive(pluginId: String) {
        pluginManager.ensureLoaded()
        pluginManager.plugins.value.firstOrNull { it.id == pluginId && it.isActive }
            ?: throw PluginHostException("PLUGIN_INACTIVE", "plugin is not active: $pluginId")
    }

    private fun paramInvalid(message: String): PluginHostException =
        PluginHostException("PARAM_INVALID", message)

    private companion object {
        const val MAX_URL_CHARS = 4_096
        const val MAX_HEADERS = 64
        const val MAX_HEADER_VALUE_CHARS = 8_192
        const val MAX_BODY_BYTES = 1024 * 1024
        const val MAX_RESPONSE_BYTES = 1024 * 1024
        const val DEFAULT_TIMEOUT_SECONDS = 30L
        const val MIN_TIMEOUT_SECONDS = 1L
        const val MAX_TIMEOUT_SECONDS = 60L
        val HEADER_NAME = Regex("^[A-Za-z0-9-]{1,64}$")
        val FORBIDDEN_HEADERS = setOf("host", "content-length", "connection", "transfer-encoding", "expect", "upgrade")
        val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")
        val BODY_REQUIRED_METHODS = setOf("POST", "PUT", "PATCH")
        val EMPTY_BODY: RequestBody = "".toRequestBody()
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
