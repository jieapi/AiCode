package com.aicode.feature.agent.presentation

import com.aicode.feature.settings.data.repository.StartupSessionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StartupSessionRestoreTest {

    @Test
    fun activityRecreationRestoresSavedSessionEvenWhenStartupPrefersNewSession() {
        assertEquals(
            "session-a",
            resolveStartupSessionId(
                pendingSessionId = null,
                savedSessionId = "session-a",
                restoreSavedSession = true,
                savedSessionRunning = false,
                recentSessionId = "session-b",
                emptyRecentSessionId = "session-c",
                startupSessionMode = StartupSessionMode.NEW_SESSION
            )
        )
    }

    @Test
    fun coldLaunchWithNewSessionPreferenceUsesEmptySession() {
        assertEquals(
            "session-c",
            resolveStartupSessionId(
                pendingSessionId = null,
                savedSessionId = "session-a",
                restoreSavedSession = false,
                savedSessionRunning = false,
                recentSessionId = "session-b",
                emptyRecentSessionId = "session-c",
                startupSessionMode = StartupSessionMode.NEW_SESSION
            )
        )
    }

    @Test
    fun coldLaunchWithNewSessionPreferenceCreatesSessionWhenThereIsNoEmptySession() {
        assertNull(
            resolveStartupSessionId(
                pendingSessionId = null,
                savedSessionId = "session-a",
                restoreSavedSession = false,
                savedSessionRunning = false,
                recentSessionId = "session-b",
                emptyRecentSessionId = null,
                startupSessionMode = StartupSessionMode.NEW_SESSION
            )
        )
    }

    @Test
    fun coldLaunchWithRecentSessionPreferenceUsesSavedSessionWhenValid() {
        assertEquals(
            "session-a",
            resolveStartupSessionId(
                pendingSessionId = null,
                savedSessionId = "session-a",
                restoreSavedSession = false,
                savedSessionRunning = false,
                recentSessionId = "session-b",
                emptyRecentSessionId = null,
                startupSessionMode = StartupSessionMode.RECENT_SESSION
            )
        )
    }

    @Test
    fun pendingFollowSessionHasPriorityOverRestoredSession() {
        assertEquals(
            "session-pending",
            resolveStartupSessionId(
                pendingSessionId = "session-pending",
                savedSessionId = "session-a",
                restoreSavedSession = true,
                savedSessionRunning = true,
                recentSessionId = "session-b",
                emptyRecentSessionId = "session-c",
                startupSessionMode = StartupSessionMode.NEW_SESSION
            )
        )
    }

    @Test
    fun runningSavedSessionIsKeptWhenColdLaunchHasNoRestoreFlag() {
        assertEquals(
            "session-a",
            resolveStartupSessionId(
                pendingSessionId = null,
                savedSessionId = "session-a",
                restoreSavedSession = false,
                savedSessionRunning = true,
                recentSessionId = "session-b",
                emptyRecentSessionId = "session-c",
                startupSessionMode = StartupSessionMode.NEW_SESSION
            )
        )
    }
}
