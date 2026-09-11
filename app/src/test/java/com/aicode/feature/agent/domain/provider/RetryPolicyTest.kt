package com.aicode.feature.agent.domain.provider

import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 触发重试的异常 → 用户可见错误摘要（[Throwable.toRetryErrorInfo]）的分类逻辑。
 */
class RetryPolicyTest {

    private fun httpError(code: Int): HttpException =
        HttpException(Response.error<Any>(code, "{}".toResponseBody(null)))

    @Test
    fun http_429_maps_to_rate_limit() {
        val info = httpError(429).toRetryErrorInfo()
        assertEquals(RetryErrorKind.RATE_LIMIT, info.kind)
        assertEquals(429, info.statusCode)
    }

    @Test
    fun http_5xx_maps_to_server_error() {
        assertEquals(RetryErrorKind.SERVER_ERROR, httpError(500).toRetryErrorInfo().kind)
        assertEquals(RetryErrorKind.SERVER_OVERLOADED, httpError(503).toRetryErrorInfo().kind)
        assertEquals(503, httpError(503).toRetryErrorInfo().statusCode)
        assertEquals(RetryErrorKind.SERVER_ERROR, httpError(502).toRetryErrorInfo().kind)
    }

    @Test
    fun timeout_exceptions_map_to_timeout() {
        assertEquals(RetryErrorKind.TIMEOUT, SocketTimeoutException().toRetryErrorInfo().kind)
        assertEquals(RetryErrorKind.TIMEOUT, InterruptedIOException().toRetryErrorInfo().kind)
    }

    @Test
    fun network_exceptions_map_to_specific_kinds() {
        assertEquals(RetryErrorKind.DNS_FAILED, UnknownHostException().toRetryErrorInfo().kind)
        assertEquals(RetryErrorKind.CONNECTION_REFUSED, ConnectException().toRetryErrorInfo().kind)
        assertEquals(RetryErrorKind.SSL_ERROR, SSLException("handshake failed").toRetryErrorInfo().kind)
        assertEquals(RetryErrorKind.CONNECTION_RESET, IOException("Connection reset by peer").toRetryErrorInfo().kind)
        assertEquals(RetryErrorKind.CONNECTION_RESET, IOException("unexpected end of stream").toRetryErrorInfo().kind)
        assertEquals(RetryErrorKind.NETWORK, IOException("SSE 流被中断").toRetryErrorInfo().kind)
    }

    @Test
    fun stream_api_codes_map_to_specific_kinds() {
        assertEquals(RetryErrorKind.RATE_LIMIT, StreamApiException("rate_limit_exceeded", "m").toRetryErrorInfo().kind)
        assertEquals(RetryErrorKind.RATE_LIMIT, StreamApiException("insufficient_quota", "m").toRetryErrorInfo().kind)
        assertEquals(RetryErrorKind.SERVER_OVERLOADED, StreamApiException("server_is_overloaded", "m").toRetryErrorInfo().kind)
        assertEquals(RetryErrorKind.SERVER_ERROR, StreamApiException("internal_error", "m").toRetryErrorInfo().kind)
        assertEquals(RetryErrorKind.UNKNOWN, StreamApiException("some_other", "m").toRetryErrorInfo().kind)
    }

    @Test
    fun status_code_null_for_non_http_errors() {
        assertNull(SocketTimeoutException().toRetryErrorInfo().statusCode)
        assertNull(IOException().toRetryErrorInfo().statusCode)
        assertNull(StreamApiException("server_is_overloaded", "m").toRetryErrorInfo().statusCode)
    }
}
