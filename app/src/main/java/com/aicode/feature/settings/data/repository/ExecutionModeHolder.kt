package com.aicode.feature.settings.data.repository

import com.aicode.feature.agent.domain.container.BackendCapabilities
import com.aicode.feature.agent.domain.container.BuiltinLocalProotBackend
import com.aicode.feature.agent.domain.container.BuiltinRemoteSshBackend
import com.aicode.feature.agent.domain.container.ExecutionBackend
import com.aicode.feature.agent.domain.container.ExecutionBackendRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 执行模式与激活执行后端的同步缓存：委托给 [ExecutionBackendRegistry]，
 * 同时保留 [mode] / [currentMode] / [setMode] 以兼容尚未迁移的调用方与纯单元测试。
 */
@Singleton
class ExecutionModeHolder private constructor(
    private val backendRegistry: ExecutionBackendRegistry?,
    private val _mode: MutableStateFlow<ExecutionMode>
) {
    @Inject
    constructor(backendRegistry: ExecutionBackendRegistry) : this(
        backendRegistry,
        MutableStateFlow(backendIdToMode(backendRegistry.activeBackendId.value))
    )

    /** 兼容纯单元测试无参构造。 */
    constructor() : this(null, MutableStateFlow(ExecutionMode.LOCAL_PROOT))

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    val mode: StateFlow<ExecutionMode> = _mode.asStateFlow()

    val activeBackend: StateFlow<ExecutionBackend>
        get() = checkNotNull(backendRegistry) {
            "ExecutionBackendRegistry is not attached to standalone ExecutionModeHolder"
        }.activeBackend

    init {
        if (backendRegistry != null) {
            syncScope.launch {
                backendRegistry.activeBackendId.collect { id ->
                    _mode.value = backendIdToMode(id)
                }
            }
        }
    }

    fun setMode(mode: ExecutionMode) {
        if (backendRegistry != null) {
            backendRegistry.setActiveBackend(modeToBackendId(mode))
        } else {
            _mode.value = mode
        }
    }

    fun currentMode(): ExecutionMode =
        backendRegistry?.let { backendIdToMode(it.activeBackendId.value) } ?: _mode.value

    fun currentBackend(): ExecutionBackend =
        checkNotNull(backendRegistry) {
            "ExecutionBackendRegistry is not attached to standalone ExecutionModeHolder"
        }.currentBackend()

    fun setActiveBackend(id: String): Boolean =
        backendRegistry?.setActiveBackend(id) ?: false

    fun capabilities(): BackendCapabilities =
        backendRegistry?.currentBackend()?.capabilities ?: when (_mode.value) {
            ExecutionMode.LOCAL_PROOT -> BuiltinLocalProotBackend.CAPABILITIES
            ExecutionMode.REMOTE_SSH -> BuiltinRemoteSshBackend.CAPABILITIES
        }

    private companion object {
        fun modeToBackendId(mode: ExecutionMode): String = when (mode) {
            ExecutionMode.LOCAL_PROOT -> ExecutionBackendRegistry.BUILTIN_LOCAL_PROOT_ID
            ExecutionMode.REMOTE_SSH -> ExecutionBackendRegistry.BUILTIN_REMOTE_SSH_ID
        }

        fun backendIdToMode(backendId: String): ExecutionMode =
            if (backendId == ExecutionBackendRegistry.BUILTIN_REMOTE_SSH_ID) {
                ExecutionMode.REMOTE_SSH
            } else {
                ExecutionMode.LOCAL_PROOT
            }
    }
}
