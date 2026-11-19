package com.aicode.feature.agent.domain.container

import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.terminal.domain.DelegatingTerminalSessionProvider
import com.aicode.feature.terminal.domain.RemoteTerminalSessionManager
import com.aicode.feature.terminal.domain.TerminalSessionManager
import com.aicode.feature.terminal.domain.TerminalSessionProvider
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.LocalFileAccess
import com.aicode.feature.workspace.domain.RemoteSftpFileAccess
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionBackendRegistryTest {

    private val localEngine = mockk<LinuxContainerEngine>(relaxed = true) {
        every { initProgress } returns MutableStateFlow(ContainerInitState.Ready)
    }
    private val localFileAccess = mockk<LocalFileAccess>(relaxed = true)
    private val localTerminal = mockk<TerminalSessionManager>(relaxed = true)

    private val remoteEngine = mockk<RemoteSshEngine>(relaxed = true) {
        every { initProgress } returns MutableStateFlow(ContainerInitState.Idle)
    }
    private val remoteFileAccess = mockk<RemoteSftpFileAccess>(relaxed = true)
    private val remoteTerminal = mockk<RemoteTerminalSessionManager>(relaxed = true)
    private val remoteSshConnection = mockk<RemoteSshConnection>(relaxed = true) {
        every { config } returns null
    }

    private fun newRegistry(): ExecutionBackendRegistry {
        val localBackend = BuiltinLocalProotBackend(localEngine, localFileAccess, localTerminal)
        val remoteBackend = BuiltinRemoteSshBackend(
            remoteEngine,
            remoteFileAccess,
            remoteTerminal,
            remoteSshConnection
        )
        return ExecutionBackendRegistry(localBackend, remoteBackend)
    }

    private fun fakePluginBackend(
        id: String,
        commandEngine: CommandEngine = mockk(relaxed = true),
        fileAccess: FileAccessProvider = mockk(relaxed = true),
        terminalProvider: TerminalSessionProvider? = null
    ): ExecutionBackend = object : ExecutionBackend {
        override val id: String = id
        override val displayName: String = "Plugin $id"
        override val capabilities: BackendCapabilities = BackendCapabilities(
            filesystem = true,
            command = true,
            terminal = terminalProvider != null,
            isRemoteEnvironment = true,
            workspaceDiscovery = WorkspaceDiscoveryMode.REGISTER,
            projectConfigLocation = ProjectConfigLocation.APP_PRIVATE_DIR
        )
        override fun environmentDescription(): String = displayName
        override fun environmentCacheKey(): String = "cache:$id"
        override fun workspaceRootDisplay(workspacePath: String): String = workspacePath
        override fun projectConfigKey(workspacePath: String): String = "key:$workspacePath"
        override val commandEngine: CommandEngine = commandEngine
        override val fileAccess: FileAccessProvider = fileAccess
        override val terminalSessionProvider: TerminalSessionProvider? = terminalProvider
    }

    @Test
    fun defaultsToBuiltinLocalProotAndRegistersBuiltins() {
        val registry = newRegistry()
        assertEquals(
            listOf(
                ExecutionBackendRegistry.BUILTIN_LOCAL_PROOT_ID,
                ExecutionBackendRegistry.BUILTIN_REMOTE_SSH_ID
            ),
            registry.backends.value.map { it.id }
        )
        assertEquals(ExecutionBackendRegistry.BUILTIN_LOCAL_PROOT_ID, registry.activeBackendId.value)
        assertSame(localEngine, registry.currentBackend().commandEngine)
    }

    @Test
    fun setActiveBackend_switchesAndFallsBackOnUnknown() {
        val registry = newRegistry()
        assertTrue(registry.setActiveBackend(ExecutionBackendRegistry.BUILTIN_REMOTE_SSH_ID))
        assertEquals(ExecutionBackendRegistry.BUILTIN_REMOTE_SSH_ID, registry.activeBackendId.value)
        assertSame(remoteEngine, registry.currentBackend().commandEngine)

        assertFalse(registry.setActiveBackend("non.existent:backend"))
        assertEquals(ExecutionBackendRegistry.BUILTIN_LOCAL_PROOT_ID, registry.activeBackendId.value)
        assertSame(localEngine, registry.currentBackend().commandEngine)
    }

    @Test
    fun syncAndUnregisterPluginBackends_updatesRegistryAndFallsBackIfActiveRemoved() {
        val registry = newRegistry()
        val pluginCmd = mockk<CommandEngine>(relaxed = true)
        val backend = fakePluginBackend("demo.cloud:sandbox", commandEngine = pluginCmd)

        registry.syncPluginBackends("demo.cloud", listOf(backend))
        assertTrue(registry.backends.value.any { it.id == "demo.cloud:sandbox" })
        assertTrue(registry.setActiveBackend("demo.cloud:sandbox"))
        assertSame(pluginCmd, registry.currentBackend().commandEngine)

        registry.unregisterPlugin("demo.cloud")
        assertFalse(registry.backends.value.any { it.id == "demo.cloud:sandbox" })
        assertEquals(ExecutionBackendRegistry.BUILTIN_LOCAL_PROOT_ID, registry.activeBackendId.value)
    }

    @Test
    fun builtinInstancesSeparateProfilesWithoutChangingTheBackendDefinition() {
        val registry = newRegistry()
        val id = ExecutionBackendRegistry.BUILTIN_REMOTE_SSH_ID
        registry.updateBuiltinInstance(id, "server-a:user")
        registry.setActiveBackend(id)
        val first = registry.currentBackend()

        registry.updateBuiltinInstance(id, "server-b:user")
        val second = registry.currentBackend()

        assertEquals(id, first.id)
        assertEquals(id, second.id)
        assertFalse(first.instanceId == second.instanceId)
        assertFalse(first.projectConfigKey("/workspace") == second.projectConfigKey("/workspace"))
        assertEquals("$id:server-a:user", first.instanceId)
        assertEquals("$id:server-b:user", second.instanceId)
    }

    @Test
    fun register_doesNotAllowOverwritingBuiltins() {
        val registry = newRegistry()
        val spoofed = fakePluginBackend(ExecutionBackendRegistry.BUILTIN_LOCAL_PROOT_ID)
        registry.register(spoofed)
        assertSame(localEngine, registry.find(ExecutionBackendRegistry.BUILTIN_LOCAL_PROOT_ID)?.commandEngine)
    }

    @Test
    fun executionModeHolder_delegatesAndStaysBackwardCompatible() {
        val registry = newRegistry()
        val holder = ExecutionModeHolder(registry)

        assertEquals(ExecutionMode.LOCAL_PROOT, holder.currentMode())
        holder.setMode(ExecutionMode.REMOTE_SSH)
        assertEquals(ExecutionMode.REMOTE_SSH, holder.currentMode())
        assertEquals(ExecutionBackendRegistry.BUILTIN_REMOTE_SSH_ID, registry.activeBackendId.value)
        assertTrue(holder.capabilities().isRemoteEnvironment)

        val pluginBackend = fakePluginBackend("demo.cloud:sandbox")
        registry.register(pluginBackend)
        assertTrue(holder.setActiveBackend("demo.cloud:sandbox"))
        assertEquals(ExecutionMode.LOCAL_PROOT, holder.currentMode())
        assertSame(pluginBackend, holder.currentBackend())
        assertEquals(WorkspaceDiscoveryMode.REGISTER, holder.capabilities().workspaceDiscovery)
    }

    @Test
    fun delegatingCommandEngineAndTerminalProvider_routeDynamically() = runTest {
        val registry = newRegistry()
        val pluginEngine = mockk<CommandEngine>(relaxed = true)
        coEvery { pluginEngine.runCommandSync("pwd", "/proj", any()) } returns "/proj"
        val pluginBackend = fakePluginBackend("demo.cloud:sandbox", commandEngine = pluginEngine, terminalProvider = null)
        registry.register(pluginBackend)

        val delegatingCmd = DelegatingCommandEngine(registry)
        val delegatingTerminal = DelegatingTerminalSessionProvider(registry, localTerminal)

        registry.setActiveBackend("demo.cloud:sandbox")
        assertEquals("/proj", delegatingCmd.runCommandSync("pwd", "/proj"))
        coVerify(exactly = 1) { pluginEngine.runCommandSync("pwd", "/proj", any()) }

        assertTrue(delegatingTerminal.listTabs().isEmpty())
        assertFalse(delegatingTerminal.sendInput("tab-1", "ls"))
        val thrown = runCatching {
            delegatingTerminal.startBackgroundCommand("echo hi")
        }.exceptionOrNull()
        assertTrue(thrown is UnsupportedOperationException)
    }
}
