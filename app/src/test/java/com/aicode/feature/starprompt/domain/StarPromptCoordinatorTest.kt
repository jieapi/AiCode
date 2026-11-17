package com.aicode.feature.starprompt.domain

import com.aicode.feature.starprompt.data.StarPromptRecord
import com.aicode.feature.starprompt.data.StarPromptRepository
import com.aicode.feature.starprompt.data.StarPromptStatus
import com.aicode.feature.starprompt.data.StarPromptStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StarPromptCoordinatorTest {

    private class FakeStarPromptStore(
        initial: StarPromptRecord = StarPromptRecord()
    ) : StarPromptStore {
        val state = MutableStateFlow(initial)
        override val recordFlow: Flow<StarPromptRecord> = state

        override suspend fun addForegroundDuration(deltaMs: Long) {
            if (deltaMs <= 0L) return
            state.update { cur ->
                if (cur.status != StarPromptStatus.TRACKING) return@update cur
                val updated = (cur.accumulatedForegroundMs + deltaMs)
                    .coerceAtMost(StarPromptRepository.TRIGGER_FOREGROUND_MS)
                cur.copy(accumulatedForegroundMs = updated)
            }
        }

        override suspend fun markSnoozed(nowMs: Long) {
            state.update { cur ->
                if (cur.status == StarPromptStatus.STARRED || cur.status == StarPromptStatus.DISMISSED) {
                    return@update cur
                }
                val delayMs = StarPromptRepository.snoozeDelayMsFor(cur.snoozeCount)
                cur.copy(
                    status = StarPromptStatus.SNOOZED,
                    snoozeCount = cur.snoozeCount + 1,
                    nextPromptAtMs = nowMs + delayMs
                )
            }
        }

        override suspend fun markStarred() {
            state.update { it.copy(status = StarPromptStatus.STARRED) }
        }

        override suspend fun markDismissed() {
            state.update { it.copy(status = StarPromptStatus.DISMISSED) }
        }
    }

    private fun defaultGate() = StarPromptUiGate(
        agreementAccepted = true,
        onboardingActive = false,
        updateDialogVisible = false,
        pluginActionPending = false,
        activeSlotPageOpen = false,
        drawerOpen = false,
        currentRoute = StarPromptUiGate.CHAT_ROUTE
    )

    private fun TestScope.createCoordinator(
        store: FakeStarPromptStore,
        isForeground: MutableStateFlow<Boolean>,
        aiBusy: MutableStateFlow<Boolean>,
        wallClockStartMs: Long = 1_700_000_000_000L
    ): StarPromptCoordinator = StarPromptCoordinator(
        store = store,
        aiBusyFlow = aiBusy,
        isForegroundFlow = isForeground,
        scope = backgroundScope,
        elapsedRealtimeMs = { testScheduler.currentTime },
        wallClockMs = { wallClockStartMs + testScheduler.currentTime },
        triggerThresholdMs = StarPromptRepository.TRIGGER_FOREGROUND_MS,
        foregroundSettleMs = StarPromptCoordinator.DEFAULT_FOREGROUND_SETTLE_MS,
        persistIntervalMs = StarPromptCoordinator.DEFAULT_PERSIST_INTERVAL_MS,
        idleDebounceMs = StarPromptCoordinator.DEFAULT_IDLE_DEBOUNCE_MS
    )

    @Test
    fun `triggers after accumulated 3 hours across foreground sessions when AI is idle`() = runTest {
        val store = FakeStarPromptStore()
        val foreground = MutableStateFlow(true)
        val aiBusy = MutableStateFlow(false)
        val coordinator = createCoordinator(store, foreground, aiBusy)
        coordinator.updateUiGate(defaultGate())
        runCurrent()

        // 第一段前台使用 2 小时后切后台，不应触发
        advanceTimeBy(2L * 60 * 60 * 1000)
        runCurrent()
        assertFalse(coordinator.dialogVisible.value)

        foreground.value = false
        runCurrent()
        assertEquals(2L * 60 * 60 * 1000, store.state.value.accumulatedForegroundMs)
        assertFalse(coordinator.dialogVisible.value)

        // 切回前台再使用 59 分 59 秒，仍未到 3 小时
        foreground.value = true
        advanceTimeBy(59L * 60 * 1000 + 59_000L)
        runCurrent()
        assertFalse(coordinator.dialogVisible.value)

        // 补齐最后 1 秒 + 1.5 秒空闲防抖，应弹出
        advanceTimeBy(1_000L)
        runCurrent()
        assertFalse(coordinator.dialogVisible.value) // 仍在 1.5s 防抖窗口内

        advanceTimeBy(1_500L)
        runCurrent()
        assertTrue(coordinator.dialogVisible.value)
    }

    @Test
    fun `does not trigger while AI is working and shows once AI becomes idle`() = runTest {
        val store = FakeStarPromptStore(
            StarPromptRecord(
                status = StarPromptStatus.TRACKING,
                accumulatedForegroundMs = StarPromptRepository.TRIGGER_FOREGROUND_MS
            )
        )
        val foreground = MutableStateFlow(true)
        val aiBusy = MutableStateFlow(true)
        val coordinator = createCoordinator(store, foreground, aiBusy)
        coordinator.updateUiGate(defaultGate())

        // 过完前台 30s settle 期，但 AI 仍在工作 -> 不应弹出
        advanceTimeBy(60_000L)
        runCurrent()
        assertFalse(coordinator.dialogVisible.value)

        // AI 短暂空闲 1s 后又开始工作 -> 防抖被打断，不应弹出
        aiBusy.value = false
        advanceTimeBy(1_000L)
        runCurrent()
        aiBusy.value = true
        advanceTimeBy(5_000L)
        runCurrent()
        assertFalse(coordinator.dialogVisible.value)

        // AI 彻底空闲并维持 1.5s -> 弹出
        aiBusy.value = false
        advanceTimeBy(1_500L)
        runCurrent()
        assertTrue(coordinator.dialogVisible.value)
    }

    @Test
    fun `snooze delays follow 3, 7, 14 days and repeat every 14 days`() = runTest {
        val day = StarPromptRepository.DAY_MS
        val wallStart = 1_700_000_000_000L
        val store = FakeStarPromptStore(
            StarPromptRecord(
                status = StarPromptStatus.TRACKING,
                accumulatedForegroundMs = StarPromptRepository.TRIGGER_FOREGROUND_MS
            )
        )
        val foreground = MutableStateFlow(true)
        val aiBusy = MutableStateFlow(false)
        val coordinator = createCoordinator(store, foreground, aiBusy, wallStart)
        coordinator.updateUiGate(defaultGate())

        // 首次弹出（30s settle + 1.5s debounce）
        advanceTimeBy(31_500L)
        runCurrent()
        assertTrue(coordinator.dialogVisible.value)

        // 第 1 次点「下次一定」-> 推迟 3 天
        coordinator.onSnooze()
        runCurrent()
        assertFalse(coordinator.dialogVisible.value)
        assertEquals(1, store.state.value.snoozeCount)

        advanceTimeBy(3L * day - 60_000L)
        runCurrent()
        assertFalse(coordinator.dialogVisible.value)

        advanceTimeBy(60_000L + 30_000L + 1_500L)
        runCurrent()
        assertTrue(coordinator.dialogVisible.value)

        // 第 2 次点「下次一定」-> 推迟 7 天
        coordinator.onSnooze()
        runCurrent()
        assertEquals(2, store.state.value.snoozeCount)

        advanceTimeBy(7L * day - 60_000L)
        runCurrent()
        assertFalse(coordinator.dialogVisible.value)

        advanceTimeBy(60_000L + 30_000L + 1_500L)
        runCurrent()
        assertTrue(coordinator.dialogVisible.value)

        // 第 3 次点「下次一定」-> 推迟 14 天
        coordinator.onSnooze()
        runCurrent()
        assertEquals(3, store.state.value.snoozeCount)

        advanceTimeBy(14L * day - 60_000L)
        runCurrent()
        assertFalse(coordinator.dialogVisible.value)

        advanceTimeBy(60_000L + 30_000L + 1_500L)
        runCurrent()
        assertTrue(coordinator.dialogVisible.value)

        // 第 4 次点「下次一定」-> 仍按每 14 天周期弹出
        coordinator.onSnooze()
        runCurrent()
        assertEquals(4, store.state.value.snoozeCount)

        advanceTimeBy(14L * day + 30_000L + 1_500L)
        runCurrent()
        assertTrue(coordinator.dialogVisible.value)
    }

    @Test
    fun `starred and dismissed prevent all future prompts`() = runTest {
        val day = StarPromptRepository.DAY_MS

        // 测试 Starred
        val storeStarred = FakeStarPromptStore(
            StarPromptRecord(
                status = StarPromptStatus.TRACKING,
                accumulatedForegroundMs = StarPromptRepository.TRIGGER_FOREGROUND_MS
            )
        )
        val fg1 = MutableStateFlow(true)
        val busy1 = MutableStateFlow(false)
        val c1 = createCoordinator(storeStarred, fg1, busy1)
        c1.updateUiGate(defaultGate())
        advanceTimeBy(31_500L)
        runCurrent()
        assertTrue(c1.dialogVisible.value)

        c1.onGoToStar()
        runCurrent()
        assertFalse(c1.dialogVisible.value)
        assertEquals(StarPromptStatus.STARRED, storeStarred.state.value.status)

        advanceTimeBy(30L * day)
        runCurrent()
        assertFalse(c1.dialogVisible.value)

        // 测试 Dismissed
        val storeDismissed = FakeStarPromptStore(
            StarPromptRecord(
                status = StarPromptStatus.TRACKING,
                accumulatedForegroundMs = StarPromptRepository.TRIGGER_FOREGROUND_MS
            )
        )
        val fg2 = MutableStateFlow(true)
        val busy2 = MutableStateFlow(false)
        val c2 = createCoordinator(storeDismissed, fg2, busy2)
        c2.updateUiGate(defaultGate())
        advanceTimeBy(31_500L)
        runCurrent()
        assertTrue(c2.dialogVisible.value)

        c2.onDismissForever()
        runCurrent()
        assertFalse(c2.dialogVisible.value)
        assertEquals(StarPromptStatus.DISMISSED, storeDismissed.state.value.status)

        advanceTimeBy(30L * day)
        runCurrent()
        assertFalse(c2.dialogVisible.value)
    }

    @Test
    fun `respects UI gate and prompts when returning to idle chat route`() = runTest {
        val store = FakeStarPromptStore(
            StarPromptRecord(
                status = StarPromptStatus.TRACKING,
                accumulatedForegroundMs = StarPromptRepository.TRIGGER_FOREGROUND_MS
            )
        )
        val foreground = MutableStateFlow(true)
        val aiBusy = MutableStateFlow(false)
        val coordinator = createCoordinator(store, foreground, aiBusy)

        // 停留在终端或设置页 -> 不弹
        coordinator.updateUiGate(defaultGate().copy(currentRoute = "terminal"))
        advanceTimeBy(40_000L)
        runCurrent()
        assertFalse(coordinator.dialogVisible.value)

        // 切回聊天页但抽屉还开着 -> 不弹
        coordinator.updateUiGate(defaultGate().copy(drawerOpen = true))
        advanceTimeBy(5_000L)
        runCurrent()
        assertFalse(coordinator.dialogVisible.value)

        // 关闭抽屉 -> 1.5s 后弹出
        coordinator.updateUiGate(defaultGate())
        advanceTimeBy(1_500L)
        runCurrent()
        assertTrue(coordinator.dialogVisible.value)
    }

    @Test
    fun `snoozeDelayMsFor returns 3, 7, 14 and caps at 14`() {
        val day = StarPromptRepository.DAY_MS
        assertEquals(3L * day, StarPromptRepository.snoozeDelayMsFor(0))
        assertEquals(7L * day, StarPromptRepository.snoozeDelayMsFor(1))
        assertEquals(14L * day, StarPromptRepository.snoozeDelayMsFor(2))
        assertEquals(14L * day, StarPromptRepository.snoozeDelayMsFor(3))
        assertEquals(14L * day, StarPromptRepository.snoozeDelayMsFor(10))
    }

    @Test
    fun `manual trigger shows in settings route and snoozing manual preview preserves tracking progress`() = runTest {
        val store = FakeStarPromptStore(
            StarPromptRecord(
                status = StarPromptStatus.TRACKING,
                accumulatedForegroundMs = 1_000L
            )
        )
        val foreground = MutableStateFlow(true)
        val aiBusy = MutableStateFlow(false)
        val coordinator = createCoordinator(store, foreground, aiBusy)
        val settingsGate = defaultGate().copy(currentRoute = "settings")
        coordinator.updateUiGate(settingsGate)
        runCurrent()

        coordinator.showManual()
        runCurrent()
        assertTrue(coordinator.dialogVisible.value)
        assertTrue(coordinator.isManualPrompt.value)
        assertTrue(settingsGate.allowsPrompt(manual = true))
        assertFalse(settingsGate.allowsPrompt(manual = false))

        // 手动预览点「下次一定」只关弹窗，不破坏原本 TRACKING 进度
        coordinator.onSnooze()
        runCurrent()
        assertFalse(coordinator.dialogVisible.value)
        assertFalse(coordinator.isManualPrompt.value)
        assertEquals(StarPromptStatus.TRACKING, store.state.value.status)

        // 再次手动打开并点「前往 GitHub 点 Star」则持久化为 STARRED
        coordinator.showManual()
        runCurrent()
        assertTrue(coordinator.dialogVisible.value)
        coordinator.onGoToStar()
        runCurrent()
        assertFalse(coordinator.dialogVisible.value)
        assertEquals(StarPromptStatus.STARRED, store.state.value.status)
    }
}
