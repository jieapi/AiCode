package com.aicode.feature.workspace.domain

import com.aicode.feature.agent.domain.container.BackendCapabilities
import com.aicode.feature.agent.domain.container.BuiltinLocalProotBackend
import com.aicode.feature.agent.domain.container.BuiltinRemoteSshBackend
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.container.ExecutionBackend
import com.aicode.feature.agent.domain.container.ExecutionBackendRegistry
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.agent.domain.container.ProjectConfigLocation
import com.aicode.feature.agent.domain.container.RemoteConnectionConfig
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.agent.domain.container.RemoteSshEngine
import com.aicode.feature.terminal.domain.RemoteTerminalSessionManager
import com.aicode.feature.terminal.domain.TerminalSessionManager
import com.aicode.feature.workspace.domain.LocalFileAccess
import com.aicode.feature.workspace.domain.RemoteSftpFileAccess
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.terminal.domain.TerminalSessionProvider
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.remote.RemoteAuth
import io.mockk.every
import io.mockk.mockk
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectAicodeRootTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun setup(
        remoteConfig: RemoteConnectionConfig? = null
    ): Triple<ExecutionBackendRegistry, ProjectAicodeRoot, File> {
        val aicodeDir = tmp.newFolder("aicode")
        val sshConnection = mockk<RemoteSshConnection> {
            every { config } returns remoteConfig
        }
        val localBackend = BuiltinLocalProotBackend(mockk<LinuxContainerEngine>(relaxed = true), mockk<LocalFileAccess>(relaxed = true), mockk<TerminalSessionManager>(relaxed = true))
        val remoteBackend = BuiltinRemoteSshBackend(mockk<RemoteSshEngine>(relaxed = true), mockk<RemoteSftpFileAccess>(relaxed = true), mockk<RemoteTerminalSessionManager>(relaxed = true), sshConnection)
        val registry = ExecutionBackendRegistry(localBackend, remoteBackend)
        val workspaceRepo = mockk<WorkspaceRepository> {
            every { currentPath() } returns "/ws/my-app"
        }
        val installer = mockk<ContainerInstaller> {
            every { this@mockk.aicodeDir } returns aicodeDir
        }
        val root = ProjectAicodeRoot(workspaceRepo, ExecutionModeHolder(registry), installer)
        return Triple(registry, root, aicodeDir)
    }

    @Test
    fun localBackend_resolvesInsideWorkspaceDir() {
        val (_, root, _) = setup()
        assertEquals(File("/ws/my-app", ".aicode"), root.current())
    }

    @Test
    fun remoteSshBackend_resolvesInAppPrivateDirWithHostPortKey() {
        val (registry, root, aicodeDir) = setup(
            RemoteConnectionConfig("10.0.0.1", 22, "dev", RemoteAuth.Password(""), "/srv")
        )
        registry.setActiveBackend(ExecutionBackendRegistry.BUILTIN_REMOTE_SSH_ID)

        val resolved = root.current()
        assertEquals(File(aicodeDir, "projects"), resolved.parentFile)
        assertTrue(resolved.name.startsWith("my-app-"))
        assertEquals(resolved.name, root.projectKey("/ws/my-app"))
    }

    @Test
    fun pluginBackend_respectsProjectConfigLocationAndProjectConfigKey() {
        val (registry, root, aicodeDir) = setup()
        val pluginBackend = object : ExecutionBackend {
            override val id: String = "demo.cloud:sandbox"
            override val displayName: String = "Cloud Sandbox"
            override val capabilities: BackendCapabilities = BackendCapabilities(
                isRemoteEnvironment = true,
                projectConfigLocation = ProjectConfigLocation.APP_PRIVATE_DIR
            )
            override fun environmentDescription(): String = displayName
            override fun environmentCacheKey(): String = id
            override fun workspaceRootDisplay(workspacePath: String): String = workspacePath
            override fun projectConfigKey(workspacePath: String): String = "custom-plugin-key"
            override val commandEngine: CommandEngine = mockk(relaxed = true)
            override val fileAccess: FileAccessProvider = mockk(relaxed = true)
            override val terminalSessionProvider: TerminalSessionProvider? = null
        }
        registry.register(pluginBackend)
        registry.setActiveBackend(pluginBackend.id)

        assertEquals(File(File(aicodeDir, "projects"), "custom-plugin-key"), root.current())
        assertEquals("custom-plugin-key", root.projectKey("/ws/my-app"))
    }
}
