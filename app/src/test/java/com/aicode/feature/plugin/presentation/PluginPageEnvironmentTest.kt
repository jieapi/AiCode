package com.aicode.feature.plugin.presentation

import com.aicode.core.watch.ChangeKind
import com.aicode.core.watch.ChangeRoot
import com.aicode.core.watch.FileChange
import com.aicode.core.watch.FileChangeBatch
import com.aicode.feature.agent.domain.container.BackendCapabilities
import com.aicode.feature.agent.domain.container.BuiltinLocalProotBackend
import com.aicode.feature.agent.domain.container.BuiltinRemoteSshBackend
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.ConnectionState
import com.aicode.feature.agent.domain.container.ExecutionBackend
import com.aicode.feature.agent.domain.container.ExecutionBackendRegistry
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.agent.domain.container.RemoteConnectionConfig
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.agent.domain.container.RemoteSshEngine
import com.aicode.feature.terminal.domain.RemoteTerminalSessionManager
import com.aicode.feature.terminal.domain.TerminalSessionManager
import com.aicode.feature.workspace.domain.LocalFileAccess
import com.aicode.feature.workspace.domain.RemoteSftpFileAccess
import com.aicode.feature.plugin.domain.PluginFileWatchSource
import com.aicode.feature.plugin.domain.PluginHostPaths
import com.aicode.feature.plugin.domain.PluginWatchTarget
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.terminal.domain.TerminalSessionProvider
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.PathHomeResolver
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import com.aicode.feature.workspace.domain.model.Workspace
import com.aicode.feature.workspace.domain.remote.RemoteAuth
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PluginPageEnvironmentTest {
    private val workspace = mockk<WorkspaceRepository>()
    private val connection = mockk<RemoteSshConnection>()
    private val mapper = mockk<WorkspacePathMapper>()
    private val homeResolver = mockk<PathHomeResolver>()
    private val source = mockk<PluginFileWatchSource>()
    private val paths = mockk<PluginHostPaths>()
    private val current = MutableStateFlow<Workspace?>(null)
    private val connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    private var path = "/projects/first"
    private var config: RemoteConnectionConfig? = null
    private lateinit var backendRegistry: ExecutionBackendRegistry
    private lateinit var mode: ExecutionModeHolder

    @Before
    fun setUp() {
        every { workspace.current } returns current
        every { workspace.currentPath() } answers { path }
        every { connection.connectionState } returns connectionState
        every { connection.config } answers { config }
        every { connection.remoteHome } returns null
        every { homeResolver.home() } returns "/root"
        val localBackend = BuiltinLocalProotBackend(mockk<LinuxContainerEngine>(relaxed = true), mockk<LocalFileAccess>(relaxed = true), mockk<TerminalSessionManager>(relaxed = true))
        val remoteBackend = BuiltinRemoteSshBackend(mockk<RemoteSshEngine>(relaxed = true), mockk<RemoteSftpFileAccess>(relaxed = true), mockk<RemoteTerminalSessionManager>(relaxed = true), connection)
        backendRegistry = ExecutionBackendRegistry(localBackend, remoteBackend)
        mode = ExecutionModeHolder(backendRegistry)
    }

    private fun environment() = PluginPageEnvironment(workspace, mode, connection, mapper, homeResolver, source)

    private fun remote(host: String = "server-a", port: Int = 22, username: String = "developer") =
        RemoteConnectionConfig(host, port, username, RemoteAuth.Password(""), "/projects")

    @Test
    fun contextKey_isStableAndSeparatesWorkspaceModeServerAndUser() {
        val environment = environment()
        val first = environment.contextKey()
        assertEquals(first, environment.contextKey())
        assertEquals(64, first.length)
        path = "/projects/second"
        assertNotEquals(first, environment.contextKey())
        val local = environment.contextKey()
        mode.setMode(ExecutionMode.REMOTE_SSH)
        config = remote()
        val remoteKey = environment.contextKey()
        assertNotEquals(local, remoteKey)
        config = remote(host = "server-b")
        assertNotEquals(remoteKey, environment.contextKey())
        config = remote(port = 2222)
        assertNotEquals(remoteKey, environment.contextKey())
        config = remote(username = "other")
        assertNotEquals(remoteKey, environment.contextKey())
    }

    @Test
    fun changes_reactsToSamePathModeAndServerSwitches() = runTest {
        val environment = environment()
        val keys = mutableListOf<String>()
        backgroundScope.launch { environment.changes.collect { keys += it } }
        runCurrent()
        assertEquals(1, keys.size)
        mode.setMode(ExecutionMode.REMOTE_SSH)
        config = remote()
        runCurrent()
        assertEquals(2, keys.size)
        config = remote(host = "server-b")
        connectionState.value = ConnectionState.CONNECTED
        runCurrent()
        assertEquals(3, keys.size)
        path = "/projects/second"
        current.value = Workspace("second", path)
        runCurrent()
        assertEquals(4, keys.size)
        connectionState.value = ConnectionState.DISCONNECTED
        runCurrent()
        assertEquals(4, keys.size)
    }

    @Test
    fun workspaceRoot_usesContainerHomeLocallyAndRemotePathRemotely() {
        val environment = environment()
        assertEquals("/root/workspace", environment.workspaceRoot())

        mode.setMode(ExecutionMode.REMOTE_SSH)
        path = "/home/developer/projects"
        assertEquals("/home/developer/projects", environment.workspaceRoot())
    }

    @Test
    fun localEvents_onlyExposeLogicalPathsAndPreserveDirtySignals() = runTest {
        val root = File("/private/plugin-data/records")
        val target = PluginWatchTarget.Local(root)
        every { paths.watchTarget("/private/plugin-data/records", any(), true) } returns target
        val changes = listOf(
            FileChange(ChangeRoot.OTHER, root.path, root.path, ChangeKind.CREATED),
            FileChange(ChangeRoot.OTHER, "${root.path}/item.json", "unused", ChangeKind.MODIFIED),
            FileChange(ChangeRoot.OTHER, "/private/outside", "unused", ChangeKind.DELETED)
        )
        every { source.watch(target, true) } returns flowOf(FileChangeBatch(changes, truncated = true))

        val payload = environment().watch(paths, "/private/plugin-data/records", true).single()
        val events = payload["changes"]!!.jsonArray
        assertEquals(listOf("/private/plugin-data/records", "/private/plugin-data/records/item.json"),
            events.map { it.jsonObject["path"]!!.jsonPrimitive.content })
        assertEquals("modified", events.last().jsonObject["kind"]!!.jsonPrimitive.content)
        assertTrue(payload["truncated"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(payload.toString().contains("/private/outside"))
    }

    @Test
    fun remoteEvents_useTheSubscribedPathAndCurrentContextKey() = runTest {
        mode.setMode(ExecutionMode.REMOTE_SSH)
        config = remote()
        val target = PluginWatchTarget.Remote("~/workspace/settings")
        every { paths.watchTarget("~/workspace/settings", any(), false) } answers {
            assertEquals(null, secondArg<(String) -> File?>().invoke("~/workspace/settings"))
            target
        }
        every { source.watch(target, false) } returns flowOf(FileChangeBatch(listOf(
            FileChange(ChangeRoot.OTHER, "~/workspace/settings/theme.json", "unused", ChangeKind.CREATED)
        )))
        val environment = environment()
        val payload = environment.watch(paths, "~/workspace/settings", false).single()
        assertEquals(environment.contextKey(), payload["contextKey"]!!.jsonPrimitive.content)
        assertEquals("~/workspace/settings/theme.json",
            payload["changes"]!!.jsonArray.single().jsonObject["path"]!!.jsonPrimitive.content)
    }

    @Test
    fun localTargetResolution_usesTheExistingContainerPathMapper() = runTest {
        val root = File("/projects/first/config")
        every { mapper.resolveFile("~/workspace/config") } returns root
        every { paths.watchTarget("~/workspace/config", any(), false) } answers {
            assertEquals(root, secondArg<(String) -> File?>().invoke("~/workspace/config"))
            PluginWatchTarget.Local(root)
        }
        every { source.watch(PluginWatchTarget.Local(root), false) } returns flowOf(FileChangeBatch(emptyList()))
        val payload = environment().watch(paths, "~/workspace/config", false).single()
        assertTrue(payload["changes"]!!.jsonArray.isEmpty())
    }

    @Test
    fun pluginBackend_updatesContextKeyChangesAndWorkspaceRoot() = runTest {
        val pluginBackend = object : ExecutionBackend {
            override val id: String = "demo.cloud:sandbox"
            override val displayName: String = "Cloud Sandbox"
            override val capabilities: BackendCapabilities = BackendCapabilities(isRemoteEnvironment = true)
            override fun environmentDescription(): String = displayName
            override fun environmentCacheKey(): String = "plugin:demo.cloud:sandbox:1.0.0"
            override fun workspaceRootDisplay(workspacePath: String): String = workspacePath.trim()
            override fun projectConfigKey(workspacePath: String): String = "sandbox-key"
            override val commandEngine: CommandEngine = mockk(relaxed = true)
            override val fileAccess: FileAccessProvider = mockk(relaxed = true)
            override val terminalSessionProvider: TerminalSessionProvider? = null
        }
        backendRegistry.register(pluginBackend)

        val environment = environment()
        val keys = mutableListOf<String>()
        backgroundScope.launch { environment.changes.collect { keys += it } }
        runCurrent()
        val localKey = environment.contextKey()

        mode.setActiveBackend(pluginBackend.id)
        path = "/sandbox/repo-1"
        runCurrent()

        assertEquals(2, keys.size)
        assertNotEquals(localKey, environment.contextKey())
        assertEquals("/sandbox/repo-1", environment.workspaceRoot())
    }
}
