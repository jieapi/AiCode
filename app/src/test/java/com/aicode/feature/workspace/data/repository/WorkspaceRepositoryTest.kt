package com.aicode.feature.workspace.data.repository

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aicode.feature.agent.domain.container.BuiltinLocalProotBackend
import com.aicode.feature.agent.domain.container.BuiltinRemoteSshBackend
import com.aicode.feature.agent.domain.container.ExecutionBackendRegistry
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.agent.domain.container.RemoteSshEngine
import com.aicode.feature.terminal.domain.RemoteTerminalSessionManager
import com.aicode.feature.terminal.domain.TerminalSessionManager
import com.aicode.feature.workspace.domain.LocalFileAccess
import com.aicode.feature.workspace.domain.RemoteSftpFileAccess
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.credentials.data.CredentialInjectSettingsRepository
import com.aicode.feature.credentials.domain.repository.CredentialRepository
import com.aicode.feature.plugin.domain.LocalizedText
import com.aicode.feature.plugin.domain.PluginExecutionBackendCapabilitiesRef
import com.aicode.feature.plugin.domain.PluginExecutionBackendDefinition
import com.aicode.feature.plugin.domain.PluginExecutionBackendHandlersRef
import com.aicode.feature.plugin.domain.PluginRuntime
import com.aicode.feature.plugin.runtime.BinaryResult
import com.aicode.feature.plugin.runtime.PluginExecutionBackendAdapter
import com.aicode.feature.plugin.runtime.PluginRuntimeManager
import com.aicode.feature.plugin.runtime.PluginWorkspaceRegistry
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.workspace.domain.PathHomeResolver
import com.aicode.feature.workspace.domain.model.Workspace
import com.aicode.feature.workspace.domain.model.WorkspaceRef
import com.aicode.feature.workspace.domain.model.WorkspaceType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceRepositoryTest {

    private lateinit var context: Context
    private lateinit var backendRegistry: ExecutionBackendRegistry
    private lateinit var pluginWorkspaceRegistry: PluginWorkspaceRegistry
    private lateinit var remoteSshConnection: RemoteSshConnection
    private lateinit var pathHomeResolver: PathHomeResolver
    private lateinit var sessionUseCase: SessionUseCase
    private lateinit var generalSettingsRepository: GeneralSettingsRepository
    private lateinit var credentialInjectSettings: CredentialInjectSettingsRepository
    private lateinit var credentialRepository: CredentialRepository
    private lateinit var repoScope: CoroutineScope

    private val runtime = PluginRuntime(
        pluginId = "demo.cloud",
        type = "javascript",
        installDir = File("/tmp/demo"),
        entryFile = File("/tmp/demo/index.js"),
        command = listOf("node", "index.js"),
        resident = true
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "projects").deleteRecursively()
        remoteSshConnection = mockk(relaxed = true) {
            every { config } returns null
            every { isConnected() } returns false
        }
        val localBackend = BuiltinLocalProotBackend(mockk<LinuxContainerEngine>(relaxed = true), mockk<LocalFileAccess>(relaxed = true), mockk<TerminalSessionManager>(relaxed = true))
        val remoteBackend = BuiltinRemoteSshBackend(
            mockk<RemoteSshEngine>(relaxed = true),
            mockk<RemoteSftpFileAccess>(relaxed = true),
            mockk<RemoteTerminalSessionManager>(relaxed = true),
            remoteSshConnection
        )
        backendRegistry = ExecutionBackendRegistry(localBackend, remoteBackend)
        pluginWorkspaceRegistry = PluginWorkspaceRegistry()
        pathHomeResolver = mockk(relaxed = true) {
            every { expandHome(any()) } answers { firstArg() }
        }
        sessionUseCase = mockk(relaxed = true)
        generalSettingsRepository = mockk(relaxed = true) {
            every { deleteExternalWorkspaceSessionsFlow } returns flowOf(false)
        }
        credentialInjectSettings = mockk(relaxed = true)
        credentialRepository = mockk(relaxed = true)
        repoScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        repoScope.cancel()
    }

    private fun createRepo(): WorkspaceRepository = WorkspaceRepository(
        context = context,
        executionBackendRegistry = backendRegistry,
        pluginWorkspaceRegistry = pluginWorkspaceRegistry,
        remoteSshConnection = remoteSshConnection,
        pathHomeResolver = pathHomeResolver,
        sessionUseCase = sessionUseCase,
        generalSettingsRepository = generalSettingsRepository,
        credentialInjectSettings = credentialInjectSettings,
        credentialRepository = credentialRepository,
        scope = repoScope
    )

    private fun pluginDefinition(
        discovery: String,
        withCreateHandler: Boolean = true,
        withListHandler: Boolean = discovery != "register"
    ) = PluginExecutionBackendDefinition(
        pluginId = "demo.cloud",
        localId = "sandbox",
        name = LocalizedText.single("Cloud Sandbox"),
        description = LocalizedText.single("Cloud Sandbox"),
        capabilities = PluginExecutionBackendCapabilitiesRef(
            filesystem = true,
            command = true,
            terminal = true,
            isRemoteEnvironment = true,
            workspaceDiscovery = discovery,
            projectConfigLocation = "app_private_dir"
        ),
        handlers = PluginExecutionBackendHandlersRef(
            fs = "backend.fs",
            exec = "backend.exec",
            workspaceList = if (withListHandler) "backend.wsList" else null,
            workspaceCreate = if (withCreateHandler) "backend.wsCreate" else null,
            workspaceDelete = "backend.wsDelete",
            workspaceSelect = "backend.wsSelect"
        ),
        runtime = runtime,
        pluginVersion = "1.0.0"
    )

    private fun workspacesResult(vararg items: Pair<String, String>): BinaryResult = BinaryResult(
        meta = buildJsonObject {
            put("workspaces", buildJsonArray {
                items.forEach { (id, name) ->
                    add(buildJsonObject {
                        put("id", id)
                        put("name", name)
                        put("path", "/remote/$id")
                    })
                }
            })
        }
    )

    @Test
    fun initialize_registerModeEmptyList_doesNotCreateLocalFallbackAndReactsToPush() = runTest {
        val runtimeManager = mockk<PluginRuntimeManager>(relaxed = true)
        val adapter = PluginExecutionBackendAdapter(pluginDefinition("register"), runtimeManager)
        backendRegistry.register(adapter)
        backendRegistry.setActiveBackend(adapter.id)

        val repo = createRepo()
        repo.initialize()

        assertTrue(repo.workspaces.value.isEmpty())
        assertNull(repo.current.value)
        val projectsDir = File(context.filesDir, "projects")
        assertTrue(projectsDir.listFiles().isNullOrEmpty())

        val pushed = Workspace(
            name = "alpha",
            path = "/sandbox/alpha",
            type = WorkspaceType.REMOTE,
            id = "ws-alpha",
            backendId = adapter.id
        )
        pluginWorkspaceRegistry.set(adapter.id, listOf(pushed))

        withTimeout(2_000) {
            repo.current.first { it == pushed }
        }
        assertEquals(listOf(pushed), repo.workspaces.value)
        assertEquals(pushed, repo.current.value)
        coVerify(exactly = 1) {
            runtimeManager.invokeBinary(
                pluginId = "demo.cloud",
                version = "1.0.0",
                runtime = runtime,
                handler = "backend.wsSelect",
                args = match { it["workspaceId"]?.toString()?.contains("ws-alpha") == true },
                payload = any(),
                method = any(),
                timeoutMs = any()
            )
        }
    }

    @Test
    fun registerMode_removalFallsBackToNextAvailableWorkspace() = runTest {
        val runtimeManager = mockk<PluginRuntimeManager>(relaxed = true)
        val adapter = PluginExecutionBackendAdapter(pluginDefinition("register"), runtimeManager)
        backendRegistry.register(adapter)
        backendRegistry.setActiveBackend(adapter.id)

        val ws1 = Workspace(name = "one", path = "/s/1", type = WorkspaceType.REMOTE, id = "id-1", backendId = adapter.id)
        val ws2 = Workspace(name = "two", path = "/s/2", type = WorkspaceType.REMOTE, id = "id-2", backendId = adapter.id)
        pluginWorkspaceRegistry.set(adapter.id, listOf(ws1, ws2))

        val repo = createRepo()
        repo.initialize()
        assertEquals(ws1, repo.current.value)

        pluginWorkspaceRegistry.remove(adapter.id, listOf("id-1"))

        withTimeout(2_000) {
            repo.current.first { it == ws2 }
        }
        assertEquals(listOf(ws2), repo.workspaces.value)
        assertEquals(ws2, repo.current.value)
    }

    @Test
    fun scanMode_pluginBackendCallsListAndCreateFallbackWhenEmpty() = runTest {
        val runtimeManager = mockk<PluginRuntimeManager>(relaxed = true)
        var listed = false
        coEvery {
            runtimeManager.invokeBinary("demo.cloud", "1.0.0", runtime, "backend.wsList", any(), any(), any(), any())
        } answers {
            if (!listed) {
                BinaryResult(meta = buildJsonObject { put("workspaces", JsonArray(emptyList())) })
            } else {
                workspacesResult("ws-default" to "default")
            }
        }
        coEvery {
            runtimeManager.invokeBinary("demo.cloud", "1.0.0", runtime, "backend.wsCreate", any(), any(), any(), any())
        } answers {
            listed = true
            BinaryResult(meta = buildJsonObject {
                put("workspace", buildJsonObject {
                    put("id", "ws-default")
                    put("name", "default")
                    put("path", "/remote/ws-default")
                })
            })
        }

        val adapter = PluginExecutionBackendAdapter(pluginDefinition("scan"), runtimeManager)
        backendRegistry.register(adapter)
        backendRegistry.setActiveBackend(adapter.id)

        val repo = createRepo()
        repo.initialize()

        assertEquals(1, repo.workspaces.value.size)
        assertEquals("ws-default", repo.current.value?.id)
        assertEquals("/remote/ws-default", repo.currentPath())
    }

    @Test
    fun hybridMode_mergesScannedAndRegisteredWithRegisteredPriority() = runTest {
        val runtimeManager = mockk<PluginRuntimeManager>(relaxed = true)
        coEvery {
            runtimeManager.invokeBinary("demo.cloud", "1.0.0", runtime, "backend.wsList", any(), any(), any(), any())
        } returns workspacesResult("ws-1" to "scanned-one", "ws-2" to "scanned-two")

        val adapter = PluginExecutionBackendAdapter(pluginDefinition("hybrid"), runtimeManager)
        backendRegistry.register(adapter)
        backendRegistry.setActiveBackend(adapter.id)

        val registeredOverride = Workspace(
            name = "registered-one",
            path = "/custom/ws-1",
            type = WorkspaceType.REMOTE,
            id = "ws-1",
            backendId = adapter.id
        )
        val registeredExtra = Workspace(
            name = "registered-three",
            path = "/custom/ws-3",
            type = WorkspaceType.REMOTE,
            id = "ws-3",
            backendId = adapter.id
        )
        pluginWorkspaceRegistry.set(adapter.id, listOf(registeredOverride, registeredExtra))

        val repo = createRepo()
        repo.initialize()

        assertEquals(listOf("ws-1", "ws-2", "ws-3"), repo.workspaces.value.map { it.id })
        assertEquals("registered-one", repo.workspaces.value.first().name)
    }

    @Test
    fun selectCreateAndDelete_dispatchToPluginBackendAndPersistByInstanceId() = runTest {
        val runtimeManager = mockk<PluginRuntimeManager>(relaxed = true)
        coEvery {
            runtimeManager.invokeBinary("demo.cloud", "1.0.0", runtime, "backend.wsCreate", any(), any(), any(), any())
        } returns BinaryResult(meta = buildJsonObject {
            put("workspace", buildJsonObject {
                put("id", "ws-new")
                put("name", "my-app")
                put("path", "/sandbox/my-app")
            })
        })
        coEvery {
            runtimeManager.invokeBinary("demo.cloud", "1.0.0", runtime, "backend.wsDelete", any(), any(), any(), any())
        } returns BinaryResult(meta = buildJsonObject { put("ok", true) })

        val adapter = PluginExecutionBackendAdapter(pluginDefinition("register"), runtimeManager)
        backendRegistry.register(adapter)
        backendRegistry.setActiveBackend(adapter.id)

        val initialWs = Workspace(name = "first", path = "/sandbox/first", type = WorkspaceType.REMOTE, id = "ws-first", backendId = adapter.id)
        pluginWorkspaceRegistry.set(adapter.id, listOf(initialWs))

        val repo = createRepo()
        repo.initialize()
        assertEquals("ws-first", repo.current.value?.id)

        val created = repo.createWorkspace("my-app")
        assertNotNull(created)
        assertEquals("ws-new", created?.id)
        assertTrue(repo.workspaces.value.any { it.id == "ws-new" })

        repo.selectWorkspace(checkNotNull(created).ref)
        assertEquals("ws-new", repo.current.value?.id)

        // 切换回本地后重新初始化，各自选中互不覆盖
        backendRegistry.setActiveBackend(ExecutionBackendRegistry.BUILTIN_LOCAL_PROOT_ID)
        repo.initialize()
        assertEquals("default", repo.current.value?.name)

        backendRegistry.setActiveBackend(adapter.id)
        repo.initialize()
        assertEquals("ws-new", repo.current.value?.id)

        repo.deleteWorkspace(checkNotNull(created).ref)
        assertFalse(repo.workspaces.value.any { it.id == "ws-new" })
        assertEquals("ws-first", repo.current.value?.id)
        coVerify { sessionUseCase.deleteSessionsByWorkspace(checkNotNull(created).ref) }
        coVerify(exactly = 0) { sessionUseCase.deleteSessionsByWorkspace(any<String>()) }
    }

    @Test
    fun sameNameSelectsByRefAndInvalidOrProtectedTargetsDoNotDelete() = runTest {
        val runtimeManager = mockk<PluginRuntimeManager>(relaxed = true)
        val adapter = PluginExecutionBackendAdapter(pluginDefinition("register"), runtimeManager)
        backendRegistry.register(adapter)
        backendRegistry.setActiveBackend(adapter.id)
        val first = Workspace(name = "same", path = "/same/path", id = "id-1", backendId = adapter.id)
        val protected = first.copy(id = "id-2", canDelete = false)
        pluginWorkspaceRegistry.set(adapter.id, listOf(first, protected))
        val repo = createRepo()
        repo.initialize()

        repo.selectWorkspace(protected.ref)
        assertEquals(protected.ref, repo.current.value?.ref)
        repo.selectWorkspace(WorkspaceRef("wrong-instance", "id-1"))
        assertEquals(protected.ref, repo.current.value?.ref)
        repo.selectWorkspace(first.ref)
        assertEquals(first.ref, repo.current.value?.ref)

        repo.deleteWorkspace(WorkspaceRef("wrong-instance", "id-1"))
        repo.deleteWorkspace(first.ref.copy(backendInstanceId = "another-instance"))
        repo.deleteWorkspace(protected.ref)
        assertEquals(listOf(first.ref, protected.ref), repo.workspaces.value.map { it.ref })
        coVerify(exactly = 0) {
            runtimeManager.invokeBinary("demo.cloud", "1.0.0", runtime, "backend.wsDelete", any(), any(), any(), any())
        }
        coVerify(exactly = 0) { sessionUseCase.deleteSessionsByWorkspace(any<WorkspaceRef>()) }
    }

    @Test
    fun lateScanAfterBackendSwitchCannotOverwriteCurrentOrWorkspaces() = runTest {
        val runtimeManager = mockk<PluginRuntimeManager>(relaxed = true)
        val scanStarted = CompletableDeferred<Unit>()
        val scanResult = CompletableDeferred<BinaryResult>()
        coEvery {
            runtimeManager.invokeBinary("demo.cloud", "1.0.0", runtime, "backend.wsList", any(), any(), any(), any())
        } coAnswers {
            scanStarted.complete(Unit)
            scanResult.await()
        }
        val adapter = PluginExecutionBackendAdapter(pluginDefinition("scan"), runtimeManager)
        backendRegistry.register(adapter)
        backendRegistry.setActiveBackend(adapter.id)
        val repo = createRepo()
        val stale = async { runCatching { repo.initialize() } }
        try {
            scanStarted.await()
            backendRegistry.setActiveBackend(ExecutionBackendRegistry.BUILTIN_LOCAL_PROOT_ID)
            repo.initialize()
            val selected = checkNotNull(repo.current.value)
            scanResult.complete(workspacesResult("stale" to "stale"))
            assertTrue(stale.await().isFailure)
            assertEquals(selected, repo.current.value)
            assertEquals(listOf(selected), repo.workspaces.value)
        } finally {
            scanResult.cancel()
        }
    }

    @Test
    fun builtinWorkspaceIdsAreStableWithinAnInstanceAndSeparatedAcrossProfiles() = runTest {
        val repo = createRepo()
        repo.initialize()
        val first = checkNotNull(repo.current.value)
        repo.refreshAvailability()
        assertEquals(first.ref, repo.current.value?.ref)

        backendRegistry.updateBuiltinInstance(ExecutionBackendRegistry.BUILTIN_LOCAL_PROOT_ID, "another-profile")
        repo.initialize()
        val second = checkNotNull(repo.current.value)
        assertEquals(first.path, second.path)
        assertNotEquals(first.ref, second.ref)
        assertNotEquals(second.path, second.id)
        repo.deleteWorkspace(first.ref)
        assertTrue(File(second.path).isDirectory)
    }
}
