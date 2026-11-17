package com.aicode.feature.workspace.domain

import com.aicode.feature.agent.domain.container.BackendCapabilities
import com.aicode.feature.agent.domain.container.BuiltinLocalProotBackend
import com.aicode.feature.agent.domain.container.BuiltinRemoteSshBackend
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.ExecutionBackend
import com.aicode.feature.agent.domain.container.ExecutionBackendRegistry
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.agent.domain.container.RemoteSshEngine
import com.aicode.feature.terminal.domain.RemoteTerminalSessionManager
import com.aicode.feature.terminal.domain.TerminalSessionManager
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.terminal.domain.TerminalSessionProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PathHomeResolver：home 取源（远程/本地/回退）与 ~ 前缀展开。
 * ExecutionModeHolder 用真实实现（纯内存，无 Android 依赖），RemoteSshConnection 用 mockk 控制 remoteHome。
 */
class PathHomeResolverTest {

    private val remoteSshConnection: RemoteSshConnection = mockk()

    private fun newResolver(mode: ExecutionMode): PathHomeResolver {
        val holder = ExecutionModeHolder().apply { setMode(mode) }
        return PathHomeResolver(holder, remoteSshConnection)
    }

    // ---------- home()：取源与回退 ----------

    @Test
    fun home_localMode_containerHomeUnknown_fallsBackToRoot() {
        val resolver = newResolver(ExecutionMode.LOCAL_PROOT)
        every { remoteSshConnection.remoteHome } returns null
        resolver.containerHome = null
        assertEquals("/root", resolver.home())
    }

    @Test
    fun home_localMode_returnsContainerHome() {
        val resolver = newResolver(ExecutionMode.LOCAL_PROOT)
        // 远程 home 即使有值，本地模式也不采用
        every { remoteSshConnection.remoteHome } returns "/home/remote"
        resolver.containerHome = "/data/container-home"
        assertEquals("/data/container-home", resolver.home())
    }

    @Test
    fun home_remoteMode_remoteHomeUnknown_fallsBackToRoot() {
        val resolver = newResolver(ExecutionMode.REMOTE_SSH)
        every { remoteSshConnection.remoteHome } returns null
        // 本地 containerHome 即使有值，远程模式也不采用
        resolver.containerHome = "/data/container-home"
        assertEquals("/root", resolver.home())
    }

    @Test
    fun home_remoteMode_returnsRemoteHome() {
        val resolver = newResolver(ExecutionMode.REMOTE_SSH)
        every { remoteSshConnection.remoteHome } returns "/home/dev"
        assertEquals("/home/dev", resolver.home())
    }

    @Test
    fun home_remotePluginBackend_ignoresLocalAndSshHomeWithoutCrashing() {
        val localBackend = BuiltinLocalProotBackend(mockk<LinuxContainerEngine>(relaxed = true), mockk<LocalFileAccess>(relaxed = true), mockk<TerminalSessionManager>(relaxed = true))
        val remoteBackend = BuiltinRemoteSshBackend(mockk<RemoteSshEngine>(relaxed = true), mockk<RemoteSftpFileAccess>(relaxed = true), mockk<RemoteTerminalSessionManager>(relaxed = true), remoteSshConnection)
        val registry = ExecutionBackendRegistry(localBackend, remoteBackend)
        val pluginBackend = object : ExecutionBackend {
            override val id: String = "demo.cloud:sandbox"
            override val displayName: String = "Cloud Sandbox"
            override val capabilities: BackendCapabilities = BackendCapabilities(isRemoteEnvironment = true)
            override fun environmentDescription(): String = displayName
            override fun environmentCacheKey(): String = id
            override fun workspaceRootDisplay(workspacePath: String): String = workspacePath
            override fun projectConfigKey(workspacePath: String): String = workspacePath
            override val commandEngine: CommandEngine = mockk(relaxed = true)
            override val fileAccess: FileAccessProvider = mockk(relaxed = true)
            override val terminalSessionProvider: TerminalSessionProvider? = null
        }
        registry.register(pluginBackend)
        registry.setActiveBackend(pluginBackend.id)
        every { remoteSshConnection.remoteHome } returns "/home/stale-ssh"

        val resolver = PathHomeResolver(ExecutionModeHolder(registry), remoteSshConnection).apply {
            containerHome = "/data/local-proot"
        }

        assertEquals("/root", resolver.home())
    }

    // ---------- aicodeRoot()：执行环境 AI 配置根 ----------

    @Test
    fun aicodeRoot_localMode() {
        val resolver = newResolver(ExecutionMode.LOCAL_PROOT)
        resolver.containerHome = "/root"
        assertEquals("/root/.aicode", resolver.aicodeRoot())
    }

    @Test
    fun aicodeRoot_remoteMode_usesRemoteHome() {
        val resolver = newResolver(ExecutionMode.REMOTE_SSH)
        every { remoteSshConnection.remoteHome } returns "/home/dev/"
        assertEquals("/home/dev/.aicode", resolver.aicodeRoot())
    }

    // ---------- expandHome()：~ 展开 ----------

    @Test
    fun expandHome_tildeOnly_returnsHome() {
        val resolver = newResolver(ExecutionMode.LOCAL_PROOT)
        every { remoteSshConnection.remoteHome } returns null
        resolver.containerHome = "/root"
        assertEquals("/root", resolver.expandHome("~"))
    }

    @Test
    fun expandHome_tildeSlash_appendsToHome() {
        val resolver = newResolver(ExecutionMode.LOCAL_PROOT)
        resolver.containerHome = "/root"
        assertEquals("/root/workspace", resolver.expandHome("~/workspace"))
        assertEquals("/root/.aicode/skills", resolver.expandHome("~/.aicode/skills"))
    }

    @Test
    fun expandHome_homeEndsWithSlash_noDoubleSlash() {
        val resolver = newResolver(ExecutionMode.LOCAL_PROOT)
        resolver.containerHome = "/root/"
        assertEquals("/root/x", resolver.expandHome("~/x"))
    }

    @Test
    fun expandHome_remoteMode_usesRemoteHome() {
        val resolver = newResolver(ExecutionMode.REMOTE_SSH)
        every { remoteSshConnection.remoteHome } returns "/home/dev/"
        assertEquals("/home/dev/workspace", resolver.expandHome("~/workspace"))
    }

    @Test
    fun expandHome_otherPaths_unchanged() {
        val resolver = newResolver(ExecutionMode.LOCAL_PROOT)
        resolver.containerHome = "/root"
        assertEquals("/etc/hosts", resolver.expandHome("/etc/hosts"))
        assertEquals("src/Main.kt", resolver.expandHome("src/Main.kt"))
        assertEquals("", resolver.expandHome(""))
        assertEquals("~~/x", resolver.expandHome("~~/x"))
    }
}
