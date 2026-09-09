package com.aicode.core.test

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 测试基建冒烟：验证 MockK + kotlinx-coroutines-test 在当前 JVM 环境可用
 * （依赖拉取、字节码 agent、协程虚拟时间都能正常工作）。
 */
class MockkSmokeTest {

    private interface Greeter {
        suspend fun greet(name: String): String
    }

    @Test
    fun mockkAndRunTest_workTogether() = runTest {
        val greeter = mockk<Greeter>()
        coEvery { greeter.greet(any()) } returns "hello"

        assertEquals("hello", greeter.greet("world"))
    }
}