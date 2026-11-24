package com.aicode.feature.agent.presentation.run

import com.aicode.feature.agent.domain.container.ExecutionBackend
import com.aicode.feature.terminal.domain.RemoteTerminalSessionManager
import com.aicode.feature.terminal.domain.TerminalSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExecutionBackendSwitchCoordinator @Inject constructor(
    private val agentRunManager: AgentRunManager
) {
    suspend fun cleanup(backend: ExecutionBackend) {
        agentRunManager.stopAllAgentsAndJoin()
        val provider = backend.terminalSessionProvider ?: return
        val dispatcher = if (provider is TerminalSessionManager || provider is RemoteTerminalSessionManager) {
            Dispatchers.Main.immediate
        } else {
            Dispatchers.IO
        }
        withContext(dispatcher) {
            val tabIds = when (provider) {
                is TerminalSessionManager -> provider.tabs.value.map { it.id }
                is RemoteTerminalSessionManager -> provider.tabs.value.map { it.id }
                else -> provider.listTabs().map { it.id }
            }
            tabIds.forEach(provider::closeTab)
        }
    }
}
