package com.aicode.feature.terminal.domain

import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [TerminalSessionProvider] 的委托层：同时持有本地与远程两套实现，每次方法调用时按
 * [ExecutionModeHolder.currentMode] 转发到对应实现。
 *
 * 这样 Hilt 注入时机不再影响最终行为——无论 [TerminalSessionProvider] 在何时被首次注入，
 * 真正使用终端时才读取当前模式。
 */
@Singleton
class DelegatingTerminalSessionProvider @Inject constructor(
    private val modeHolder: ExecutionModeHolder,
    private val local: TerminalSessionManager,
    private val remote: RemoteTerminalSessionManager
) : TerminalSessionProvider {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun delegate(): TerminalSessionProvider =
        if (modeHolder.currentMode() == ExecutionMode.REMOTE_SSH) remote else local

    /**
     * 随执行模式切换重新订阅对应实现的事件流。不能简单 getter 转发 delegate()：订阅方
     * （AIAgentViewModel）只在启动时订阅一次，模式切换后旧订阅仍绑在原实现上，新模式的
     * tabFinishedEvents 无人消费，导致远程模式下 AI 后台命令结束的异步通知丢失。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override val tabFinishedEvents: SharedFlow<TabFinishedEvent> =
        modeHolder.mode
            .flatMapLatest { mode ->
                if (mode == ExecutionMode.REMOTE_SSH) remote.tabFinishedEvents else local.tabFinishedEvents
            }
            .shareIn(scope, SharingStarted.Eagerly, replay = 0)

    override suspend fun startBackgroundCommand(
        command: String,
        title: String?,
        notify: Boolean,
        sourceSessionId: String?
    ): String = delegate().startBackgroundCommand(command, title, notify, sourceSessionId)

    override suspend fun startPluginCommand(command: String, title: String?, pluginId: String): String =
        delegate().startPluginCommand(command, title, pluginId)

    override fun tabStatus(id: String): TerminalTabStatus? = delegate().tabStatus(id)

    override fun sendInput(id: String, input: String, appendNewline: Boolean): Boolean =
        delegate().sendInput(id, input, appendNewline)

    override fun writeToTab(id: String, text: String): Boolean =
        delegate().writeToTab(id, text)

    override fun writeBytesToTab(id: String, vararg bytes: Int): Boolean =
        delegate().writeBytesToTab(id, *bytes)

    override fun getTabOutput(id: String): String? = delegate().getTabOutput(id)

    override fun tabOutput(id: String): Flow<ByteArray>? = delegate().tabOutput(id)

    override fun listTabs(): List<TabInfo> = delegate().listTabs()

    override fun closeTab(id: String): Boolean = delegate().closeTab(id)
}
