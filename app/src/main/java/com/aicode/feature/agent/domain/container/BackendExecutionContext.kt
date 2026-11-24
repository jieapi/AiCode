package com.aicode.feature.agent.domain.container

import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.terminal.domain.TerminalSessionProvider
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asContextElement

class BackendExecutionBinding(
    private val backend: ExecutionBackend
) {
    val instanceId: String = backend.instanceId
    private val environmentCacheKey: String = backend.environmentCacheKey()
    val commandEngine: CommandEngine = backend.commandEngine
    val fileAccess: FileAccessProvider = backend.fileAccess
    val terminalSessionProvider: TerminalSessionProvider? = backend.terminalSessionProvider

    fun checkActive(registry: ExecutionBackendRegistry) = checkActive(registry.currentBackend())

    fun checkActive(current: ExecutionBackend) {
        check(current === backend && current.instanceId == instanceId && current.environmentCacheKey() == environmentCacheKey) {
            "Execution backend changed during the operation"
        }
    }
}

object BackendExecutionContext {
    private val current = ThreadLocal<BackendExecutionBinding?>()

    fun binding(): BackendExecutionBinding? = current.get()

    fun bind(backend: ExecutionBackend): ThreadContextElement<BackendExecutionBinding?> =
        current.asContextElement(BackendExecutionBinding(backend))
}
