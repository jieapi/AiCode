package com.aicode.feature.terminal.domain

import com.aicode.feature.agent.domain.container.BackendExecutionContext
import com.aicode.feature.agent.domain.container.ExecutionBackend
import com.aicode.feature.agent.domain.container.ExecutionBackendRegistry
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 后端选择只用于创建和当前上下文的列表；已签发句柄始终绑定其创建方。 */
@Singleton
class DelegatingTerminalSessionProvider internal constructor(
    private val currentBackend: () -> ExecutionBackend,
    private val scope: CoroutineScope,
    private val findBackend: (String) -> ExecutionBackend?
) : TerminalSessionProvider {

    @Inject
    constructor(
        executionBackendRegistry: ExecutionBackendRegistry,
        @Suppress("UNUSED_PARAMETER") local: TerminalSessionManager
    ) : this(
        executionBackendRegistry::currentBackend,
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        executionBackendRegistry::find
    )

    private class Owner(
        val backendId: String,
        val backendInstanceId: String,
        val provider: TerminalSessionProvider
    ) {
        val handles = mutableMapOf<String, String>()
        val pendingEvents = mutableMapOf<String, TabFinishedEvent>()
        var starting = 0
        var collector: Job? = null
    }

    private data class Binding(val owner: Owner, val remoteTabId: String)

    private val lock = Any()
    private val owners = mutableListOf<Owner>()
    private val bindings = mutableMapOf<String, Binding>()
    private val events = MutableSharedFlow<TabFinishedEvent>(extraBufferCapacity = 64)
    override val tabFinishedEvents: SharedFlow<TabFinishedEvent> = events.asSharedFlow()

    private fun selectedProvider(backend: ExecutionBackend): TerminalSessionProvider? {
        val context = BackendExecutionContext.binding() ?: return backend.terminalSessionProvider
        check(context.instanceId == backend.instanceId) { "Execution backend changed during the operation" }
        return context.terminalSessionProvider
    }

    private fun owner(backend: ExecutionBackend, provider: TerminalSessionProvider): Owner {
        return owners.firstOrNull {
            it.backendInstanceId == backend.instanceId && it.provider === provider
        } ?: Owner(backend.id, backend.instanceId, provider).also { owner ->
            owners.add(owner)
            // 先订阅再启动，避免极短命令在返回标签 ID 前完成。
            owner.collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                provider.tabFinishedEvents.collect { event ->
                    if (!isRegistered(owner)) return@collect
                    val mapped = synchronized(lock) {
                        val handle = owner.handles[event.tabId]
                        if (handle == null && owner.starting > 0) {
                            owner.pendingEvents[event.tabId] = event
                        }
                        handle?.let { event.copy(tabId = it) }
                    }
                    if (mapped != null) events.emit(mapped)
                }
            }
        }
    }

    private fun bind(owner: Owner, remoteTabId: String): String =
        owner.handles.getOrPut(remoteTabId) {
            UUID.randomUUID().toString().also { bindings[it] = Binding(owner, remoteTabId) }
        }

    private fun releaseIfEmpty(owner: Owner) {
        if (owner.starting == 0 && owner.handles.isEmpty()) {
            owners.remove(owner)
            owner.collector?.cancel()
        }
        if (owner.starting == 0) owner.pendingEvents.clear()
    }

    private suspend fun start(create: suspend (TerminalSessionProvider) -> String): String {
        val backend = currentBackend()
        val provider = selectedProvider(backend)
            ?: throw UnsupportedOperationException("当前执行后端「${backend.displayName}」不支持终端会话")
        val owner = synchronized(lock) { owner(backend, provider).also { it.starting++ } }
        try {
            val remoteTabId = create(owner.provider)
            val (handle, pending) = synchronized(lock) {
                val handle = bind(owner, remoteTabId)
                handle to owner.pendingEvents.remove(remoteTabId)?.copy(tabId = handle)
            }
            if (pending != null) events.emit(pending)
            return handle
        } finally {
            synchronized(lock) {
                owner.starting--
                releaseIfEmpty(owner)
            }
        }
    }

    override suspend fun startBackgroundCommand(
        command: String,
        title: String?,
        notify: Boolean,
        sourceSessionId: String?
    ): String = start { provider ->
        val dispatcher = if (provider is TerminalSessionManager || provider is RemoteTerminalSessionManager) {
            Dispatchers.Main
        } else {
            Dispatchers.IO
        }
        withContext(dispatcher) {
            provider.startBackgroundCommand(command, title, notify, sourceSessionId)
        }
    }

    override suspend fun startPluginCommand(command: String, title: String?, pluginId: String): String =
        start { provider ->
            val dispatcher = if (provider is TerminalSessionManager || provider is RemoteTerminalSessionManager) {
                Dispatchers.Main
            } else {
                Dispatchers.IO
            }
            withContext(dispatcher) { provider.startPluginCommand(command, title, pluginId) }
        }

    private fun isRegistered(owner: Owner): Boolean {
        val backend = findBackend(owner.backendId) ?: return false
        return backend.instanceId == owner.backendInstanceId && backend.terminalSessionProvider === owner.provider
    }

    private fun binding(id: String): Binding? {
        val binding = synchronized(lock) { bindings[id] } ?: return null
        if (isRegistered(binding.owner)) return binding
        synchronized(lock) {
            bindings.remove(id, binding)
            binding.owner.handles.remove(binding.remoteTabId)
            releaseIfEmpty(binding.owner)
        }
        return null
    }

    override fun tabStatus(id: String): TerminalTabStatus? =
        binding(id)?.let { it.owner.provider.tabStatus(it.remoteTabId) }

    override fun sendInput(id: String, input: String, appendNewline: Boolean): Boolean =
        binding(id)?.let { it.owner.provider.sendInput(it.remoteTabId, input, appendNewline) } ?: false

    override fun writeToTab(id: String, text: String): Boolean =
        binding(id)?.let { it.owner.provider.writeToTab(it.remoteTabId, text) } ?: false

    override fun writeBytesToTab(id: String, vararg bytes: Int): Boolean =
        binding(id)?.let { it.owner.provider.writeBytesToTab(it.remoteTabId, *bytes) } ?: false

    override fun getTabOutput(id: String): String? =
        binding(id)?.let { it.owner.provider.getTabOutput(it.remoteTabId) }

    override fun tabOutput(id: String): Flow<ByteArray>? =
        binding(id)?.let { it.owner.provider.tabOutput(it.remoteTabId) }

    override fun listTabs(): List<TabInfo> {
        val backend = currentBackend()
        val provider = selectedProvider(backend) ?: return emptyList()
        val owner = synchronized(lock) { owner(backend, provider).also { it.starting++ } }
        return try {
            val infos = (if (provider is TerminalSessionManager || provider is RemoteTerminalSessionManager) {
                provider.listTabs()
            } else {
                kotlinx.coroutines.runBlocking(Dispatchers.IO) { provider.listTabs() }
            }).filter { it.ownerPluginId == null }
            synchronized(lock) {
                val active = currentBackend()
                if (active.instanceId != owner.backendInstanceId || active.terminalSessionProvider !== owner.provider) {
                    emptyList()
                } else {
                    infos.map { it.copy(id = bind(owner, it.id)) }
                }
            }
        } finally {
            synchronized(lock) {
                owner.starting--
                releaseIfEmpty(owner)
            }
        }
    }

    internal suspend fun closeTabAsync(id: String): Boolean {
        val binding = synchronized(lock) { bindings[id] } ?: return false
        val provider = binding.owner.provider
        val dispatcher = if (provider is TerminalSessionManager || provider is RemoteTerminalSessionManager) {
            Dispatchers.Main
        } else {
            Dispatchers.IO
        }
        val closed = withContext(dispatcher) { provider.closeTab(binding.remoteTabId) }
        if (closed) {
            synchronized(lock) {
                bindings.remove(id)
                binding.owner.handles.remove(binding.remoteTabId)
                releaseIfEmpty(binding.owner)
            }
        }
        return closed
    }

    override fun closeTab(id: String): Boolean {
        val binding = binding(id) ?: return false
        if (!binding.owner.provider.closeTab(binding.remoteTabId)) return false
        synchronized(lock) {
            bindings.remove(id)
            binding.owner.handles.remove(binding.remoteTabId)
            releaseIfEmpty(binding.owner)
        }
        return true
    }
}
