package com.aicode.feature.terminal.presentation

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.aicode.feature.agent.domain.container.ContainerInitState
import com.aicode.feature.agent.domain.container.ExecutionBackend
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.terminal.data.repository.TerminalSettings
import com.aicode.feature.terminal.data.repository.TerminalSettingsRepository
import com.aicode.feature.terminal.domain.TabInfo
import com.aicode.feature.terminal.domain.TerminalSessionProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class TerminalViewModelTest {
    @Test
    fun replacingBackendInstanceRebuildsItsSameIdMirrorWithoutClosingRemoteTab() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val oldStreamOpened = CountDownLatch(1)
        val oldStreamCancelled = CountDownLatch(1)
        val newInput = CountDownLatch(1)
        val a = provider("A")
        val b = provider("B")
        every { a.tabOutput(RAW_ID) } returns flow {
            try {
                oldStreamOpened.countDown()
                awaitCancellation()
            } finally {
                oldStreamCancelled.countDown()
            }
        }
        every { b.writeBytesToTab(RAW_ID, 65, 66) } answers {
            if (Looper.myLooper() != Looper.getMainLooper()) newInput.countDown()
            true
        }
        val active = MutableStateFlow(backend("instance-a", a))
        val modeHolder = mockk<ExecutionModeHolder> {
            every { activeBackend } returns active
            every { currentBackend() } answers { active.value }
        }
        val engine = mockk<LinuxContainerEngine> {
            every { initProgress } returns MutableStateFlow<ContainerInitState>(ContainerInitState.Idle)
        }
        val settings = mockk<TerminalSettingsRepository> {
            every { settingsFlow } returns MutableStateFlow(TerminalSettings())
        }
        val vm = TerminalViewModel(
            ApplicationProvider.getApplicationContext<Context>(), mockk(), mockk(), modeHolder, engine, settings
        )
        try {
            val old = vm.tabs.first { it.singleOrNull()?.title == "A" }.single()
            assertTrue(oldStreamOpened.await(5, TimeUnit.SECONDS))
            active.value = backend("instance-b", b)
            val current = vm.tabs.first { it.singleOrNull()?.title == "B" }.single()

            assertNotSame(old.session, current.session)
            assertNotEquals(RAW_ID, old.id)
            assertNotEquals(old.id, current.id)
            assertTrue(oldStreamCancelled.await(5, TimeUnit.SECONDS))
            vm.closeTab(old.id)
            assertEquals(current.id, vm.tabs.value.single().id)
            vm.activate(current.id)
            vm.write("AB")
            assertTrue(newInput.await(5, TimeUnit.SECONDS))
            verify(exactly = 0) { a.closeTab(any()) }
            verify(exactly = 0) { b.closeTab(any()) }
            verify(exactly = 0) { a.writeBytesToTab(any(), 65, 66) }
        } finally {
            vm.clearForTest()
            vm.tabs.value.forEach { it.session.finishIfRunning() }
            Dispatchers.resetMain()
        }
    }

    private fun TerminalViewModel.clearForTest() {
        javaClass.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(this@clearForTest)
    }

    private fun backend(instance: String, provider: TerminalSessionProvider): ExecutionBackend = mockk {
        every { id } returns "demo.test:terminal"
        every { instanceId } returns instance
        every { terminalSessionProvider } returns provider
    }

    private fun provider(title: String): TerminalSessionProvider = mockk(relaxed = true) {
        every { listTabs() } returns listOf(TabInfo(RAW_ID, title, true, true, null))
        every { tabOutput(RAW_ID) } returns flow { awaitCancellation() }
    }

    private companion object {
        const val RAW_ID = "term-1"
    }
}
