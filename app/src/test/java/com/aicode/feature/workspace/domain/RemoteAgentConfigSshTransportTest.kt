package com.aicode.feature.workspace.domain

import android.content.Context
import com.aicode.feature.agent.domain.container.RemoteConnectionConfig
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import net.schmizz.sshj.connection.channel.direct.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteAgentConfigSshTransportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val connection = mockk<RemoteSshConnection>()
    private val cfg = mockk<RemoteConnectionConfig>()
    private val key = RemoteAgentConfigKey("host", 22, "user", "/home/user", "/srv/project", AgentConfigKind.AGENTS, "~/workspace/.aicode/agents", "/srv/project/.aicode/agents", 4)
    private fun transport(timeoutMillis: Long = 5000) = SshAgentConfigTransport(connection, { "/srv/project" }, timeoutMillis)
    private fun encoded(value: String) = Base64.getEncoder().encodeToString(value.toByteArray())

    @Before
    fun setUp() {
        every { connection.config } returns cfg
        every { connection.remoteHome } returns key.home
        every { connection.connectionGeneration } returns key.connectionGeneration
        every { cfg.host } returns key.host
        every { cfg.port } returns key.port
        every { cfg.username } returns key.username
        every { cfg.remoteWorkspacePath } returns key.workspace
    }

    private fun command(stdout: ByteArray, status: Int? = 0, stderr: ByteArray = ByteArray(0)): Session.Command {
        val session = mockk<Session.Command>(relaxed = true)
        every { session.inputStream } returns stdout.inputStream()
        every { session.errorStream } returns stderr.inputStream()
        every { session.outputStream } returns ByteArrayOutputStream()
        every { session.exitStatus } returns status
        every { session.exitSignal } returns null
        return session
    }

    @Test
    fun twoExecBatchesKeepStderrSeparateAndPassOnlyEncodedPathsOnStdin() {
        val hash = RemoteAgentConfigProtocol.sha256("body".toByteArray())
        val manifest = command("AICODE_CONFIG_1 M\nF\t${encoded("中文 name\n.md")}\t$hash\t4\nEND\n".toByteArray(), stderr = "warning\n".toByteArray())
        val body = command("AICODE_CONFIG_1 B\nB\t${encoded("中文 name\n.md")}\t${encoded("body")}\nEND\n".toByteArray())
        val stdin = ByteArrayOutputStream()
        every { body.outputStream } returns stdin
        every { connection.startExecSessionWithStdin(any()) } returnsMany listOf(manifest, body)
        val transport = transport()
        val fingerprints = transport.manifest(key)
        assertEquals(mapOf("中文 name\n.md" to "body"), transport.bodies(key, fingerprints))
        assertEquals(encoded("中文 name\n.md") + "\nEND\n", stdin.toString("UTF-8"))
        verify(exactly = 2) { connection.startExecSessionWithStdin(any()) }
        verify(exactly = 1) { manifest.close() }
        verify(exactly = 1) { body.close() }
    }

    @Test
    fun nonzeroOrMissingExitStatusIsNotAnEmptySnapshot() {
        listOf<Int?>(7, null).forEach { status ->
            val session = command("AICODE_CONFIG_1 M\nEND\n".toByteArray(), status)
            every { connection.startExecSessionWithStdin(any()) } returns session
            assertThrows(IOException::class.java) { transport().manifest(key) }
            verify(exactly = 1) { session.close() }
        }
    }

    @Test
    fun connectionSwitchOrAbaGenerationChangeIsRejectedAndClosesSession() {
        val session = command("AICODE_CONFIG_1 M\nEND\n".toByteArray())
        every { connection.startExecSessionWithStdin(any()) } answers {
            every { connection.connectionGeneration } returns 6
            session
        }
        assertThrows(RemoteAgentConfigIdentityChangedException::class.java) { transport().manifest(key) }
        verify(exactly = 1) { session.close() }
    }

    @Test
    fun timeoutClosesBlockedSession() {
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val session = command(ByteArray(0))
        every { session.inputStream } returns object : InputStream() {
            override fun read(): Int {
                release.await()
                return -1
            }
        }
        every { session.close() } answers { closed.countDown(); release.countDown(); Unit }
        every { connection.startExecSessionWithStdin(any()) } returns session
        try {
            val error = assertThrows(IOException::class.java) { transport(200).manifest(key) }
            assertTrue(error.message.orEmpty().contains("timed out"))
            assertTrue(closed.await(3, TimeUnit.SECONDS))
        } finally {
            release.countDown()
        }
    }

    @Test
    fun interruptClosesSessionInsteadOfReturningCachedStyleSuccess() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val session = command(ByteArray(0))
        every { session.inputStream } returns object : InputStream() {
            override fun read(): Int {
                entered.countDown()
                release.await()
                return -1
            }
        }
        every { session.close() } answers { closed.countDown(); release.countDown(); Unit }
        every { connection.startExecSessionWithStdin(any()) } returns session
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val caller = Thread {
            try {
                transport().manifest(key)
            } catch (e: Throwable) {
                failure.set(e)
            } finally {
                finished.countDown()
            }
        }
        try {
            caller.start()
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            caller.interrupt()
            assertTrue(finished.await(3, TimeUnit.SECONDS))
            assertTrue(failure.get() is InterruptedIOException)
            assertTrue(closed.await(3, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            caller.interrupt()
            caller.join(3000)
        }
    }

    @Test
    fun publicCacheMapsSelectedWorkspaceAndPreservesRootPrefixWithoutNetworkForLookup() {
        val context = mockk<Context>()
        val workspace = mockk<WorkspaceRepository>()
        every { context.cacheDir } returns temporary.root
        every { workspace.currentPath() } returns key.workspace
        val cache = RemoteAgentConfigCache(context, connection, workspace)
        val root = "~/workspace/.aicode/agents 中文\n "
        assertEquals(null, cache.cachedDirectory(root, AgentConfigKind.AGENTS))
        verify(exactly = 0) { connection.startExecSessionWithStdin(any()) }
        val session = command("AICODE_CONFIG_1 M\nEND\n".toByteArray())
        every { connection.startExecSessionWithStdin(any()) } returns session
        assertTrue(cache.directory(root, AgentConfigKind.AGENTS).files.isEmpty())
        verify(exactly = 1) { connection.startExecSessionWithStdin(match { it.endsWith("'/srv/project/.aicode/agents 中文\n '") }) }
    }
}
