package com.aicode.feature.terminal.domain

import com.aicode.feature.agent.domain.container.ExecutionBackendRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [TerminalSessionProvider] 的委托层：每次方法调用时按 [ExecutionBackendRegistry.currentBackend]
 * 转发到当前后端的 [com.aicode.feature.agent.domain.container.ExecutionBackend.terminalSessionProvider]。
 *
 * 当当前后端未提供终端能力（`terminalSessionProvider == null`）时，写/启动操作给出明确禁用提示，
 * 查询与关闭操作安全返回空/false。
 */
@Singleton
class DelegatingTerminalSessionProvider @Inject constructor(
    private val executionBackendRegistry: ExecutionBackendRegistry,
    @Suppress("unused") private val local: TerminalSessionManager
) : TerminalSessionProvider {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun delegateOrNull(): TerminalSessionProvider? =
        executionBackendRegistry.currentBackend().terminalSessionProvider

    private fun requireDelegate(): TerminalSessionProvider =
        delegateOrNull() ?: throw UnsupportedOperationException(
            "当前执行后端「${executionBackendRegistry.currentBackend().displayName}」不支持终端会话"
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    override val tabFinishedEvents: SharedFlow<TabFinishedEvent> =
        executionBackendRegistry.activeBackend
            .flatMapLatest { backend ->
                backend.terminalSessionProvider?.tabFinishedEvents ?: emptyFlow()
            }
            .shareIn(scope, SharingStarted.Eagerly, replay = 0)

    override suspend fun startBackgroundCommand(
        command: String,
        title: String?,
        notify: Boolean,
        sourceSessionId: String?
    ): String = requireDelegate().startBackgroundCommand(command, title, notify, sourceSessionId)

    override suspend fun startPluginCommand(command: String, title: String?, pluginId: String): String =
        requireDelegate().startPluginCommand(command, title, pluginId)

    override fun tabStatus(id: String): TerminalTabStatus? =
        delegateOrNull()?.tabStatus(id)

    override fun sendInput(id: String, input: String, appendNewline: Boolean): Boolean =
        delegateOrNull()?.sendInput(id, input, appendNewline) ?: false

    override fun writeToTab(id: String, text: String): Boolean =
        delegateOrNull()?.writeToTab(id, text) ?: false

    override fun writeBytesToTab(id: String, vararg bytes: Int): Boolean =
        delegateOrNull()?.writeBytesToTab(id, *bytes) ?: false

    override fun getTabOutput(id: String): String? =
        delegateOrNull()?.getTabOutput(id)

    override fun tabOutput(id: String): Flow<ByteArray>? =
        delegateOrNull()?.tabOutput(id)

    override fun listTabs(): List<TabInfo> =
        delegateOrNull()?.listTabs() ?: emptyList()

    override fun closeTab(id: String): Boolean =
        delegateOrNull()?.closeTab(id) ?: false
}
