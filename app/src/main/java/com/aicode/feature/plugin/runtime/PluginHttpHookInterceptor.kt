package com.aicode.feature.plugin.runtime

import com.aicode.core.util.FileLogger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer

/**
 * 在 provider HTTP 请求实际发出前执行 `agent.http.before`。
 *
 * 该拦截器挂在 AgentModule 的共享 OkHttpClient 上，因此覆盖对话、标题生成、压缩、
 * 模型列表/测试与图片请求。hook 在 OkHttp 线程上通过 [runBlocking] 等待，超时与错误
 * 由 [PluginHookDispatcher.beforeHttpRequest] 内部兜底，最坏情况保持原请求继续。
 */
@Singleton
class PluginHttpHookInterceptor @Inject constructor(
    private val dispatcher: PluginHookDispatcher
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method != "POST") return chain.proceed(request)
        val originalBody = request.body ?: return chain.proceed(request)
        val declaredLength = originalBody.contentLength()
        if (declaredLength > PluginHookTransform.MAX_HTTP_BODY_BYTES) return chain.proceed(request)

        val buffer = Buffer()
        try {
            originalBody.writeTo(buffer)
        } catch (e: Exception) {
            FileLogger.w(TAG, "读取请求体失败，跳过 HTTP hook: ${e.javaClass.simpleName}")
            return chain.proceed(request)
        }
        if (buffer.size > PluginHookTransform.MAX_HTTP_BODY_BYTES) return chain.proceed(request)
        val bodyText = buffer.readUtf8()
        if (!bodyText.trimStart().startsWith("{")) return chain.proceed(request)
        if (chain.call().isCanceled()) return chain.proceed(request)

        val headers = request.headers.toMultimap()
            .mapValues { (_, values) -> values.joinToString(", ") }
        val result = try {
            runBlocking {
                dispatcher.beforeHttpRequest(
                    url = request.url.toString(),
                    method = request.method,
                    headers = headers,
                    body = bodyText
                )
            }
        } catch (e: Exception) {
            FileLogger.w(TAG, "HTTP hook 执行失败，保持原请求: ${e.javaClass.simpleName}")
            return chain.proceed(request)
        }
        if (result.url == null && result.headers == null && result.body == null) {
            return chain.proceed(request)
        }

        val builder = request.newBuilder()
        result.url?.toHttpUrlOrNull()?.let { url ->
            if (url.scheme == "http" || url.scheme == "https") builder.url(url)
        }
        result.headers?.let { replacement ->
            val headersBuilder = Headers.Builder()
            replacement.forEach { (name, value) -> headersBuilder.add(name, value) }
            builder.headers(headersBuilder.build())
        }
        val finalBody = result.body ?: bodyText
        val contentType = originalBody.contentType() ?: JSON_MEDIA_TYPE
        builder.method(request.method, finalBody.toRequestBody(contentType))
        FileLogger.d(
            TAG,
            "HTTP hook 已改写请求 host=${request.url.host} " +
                "url=${result.url != null} headers=${result.headers != null} body=${result.body != null}"
        )
        return chain.proceed(builder.build())
    }

    private companion object {
        const val TAG = "PluginHttpHook"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
