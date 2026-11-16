package com.aicode.feature.workspace.domain

import java.io.IOException
import java.io.InterruptedIOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [withIdentityChangeRetry] 的重试语义：连接身份变化（刚好连上 / 重连）时用新键重读，
 * 仍持续变化才向外抛；其它 IOException 立即上抛；被中断时转成 InterruptedIOException。
 */
class RemoteAgentConfigIdentityRetryTest {

    @Test
    fun retriesUntilSuccessAndSleepsBetweenAttempts() {
        val sleeps = ArrayList<Long>()
        var attempts = 0
        val result = withIdentityChangeRetry(sleepMillis = { sleeps.add(it) }) {
            attempts++
            if (attempts < 3) throw RemoteAgentConfigIdentityChangedException()
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, attempts)
        assertEquals(listOf(AGENT_CONFIG_IDENTITY_RETRY_DELAY_MILLIS, AGENT_CONFIG_IDENTITY_RETRY_DELAY_MILLIS), sleeps)
    }

    @Test
    fun givesUpAfterMaxAttempts() {
        val sleeps = ArrayList<Long>()
        var attempts = 0
        val error = assertThrows(RemoteAgentConfigIdentityChangedException::class.java) {
            withIdentityChangeRetry(sleepMillis = { sleeps.add(it) }) {
                attempts++
                throw RemoteAgentConfigIdentityChangedException()
            }
        }
        assertEquals(AGENT_CONFIG_IDENTITY_MAX_ATTEMPTS, attempts)
        assertEquals(AGENT_CONFIG_IDENTITY_MAX_ATTEMPTS - 1, sleeps.size)
        assertTrue(error is IOException)
    }

    @Test
    fun otherFailuresPropagateImmediatelyWithoutRetry() {
        val sleeps = ArrayList<Long>()
        var attempts = 0
        val error = assertThrows(IOException::class.java) {
            withIdentityChangeRetry(sleepMillis = { sleeps.add(it) }) {
                attempts++
                throw IOException("offline")
            }
        }
        assertEquals("offline", error.message)
        assertEquals(1, attempts)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun interruptDuringBackoffBecomesInterruptedIOException() {
        val error = assertThrows(InterruptedIOException::class.java) {
            withIdentityChangeRetry(sleepMillis = { throw InterruptedException("cancel") }) {
                throw RemoteAgentConfigIdentityChangedException()
            }
        }
        assertEquals("Remote configuration identity retry interrupted", error.message)
        // 保留中断标记供上层判断取消；测试内自行清理避免污染其它用例。
        assertTrue(Thread.interrupted())
    }
}
