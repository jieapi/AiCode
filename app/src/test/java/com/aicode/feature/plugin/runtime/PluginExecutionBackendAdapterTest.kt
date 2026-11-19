package com.aicode.feature.plugin.runtime

import com.aicode.feature.agent.domain.container.BuiltinLocalProotBackend
import com.aicode.feature.agent.domain.container.BuiltinRemoteSshBackend
import com.aicode.feature.agent.domain.container.CommandEvent
import com.aicode.feature.agent.domain.container.ContainerInitState
import com.aicode.feature.agent.domain.container.ExecutionBackendRegistry
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.agent.domain.container.ProjectConfigLocation
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.agent.domain.container.RemoteSshEngine
import com.aicode.feature.agent.domain.container.WorkspaceDiscoveryMode
import com.aicode.feature.terminal.domain.RemoteTerminalSessionManager
import com.aicode.feature.terminal.domain.TerminalSessionManager
import com.aicode.feature.workspace.domain.LocalFileAccess
import com.aicode.feature.workspace.domain.RemoteSftpFileAccess
import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.LocalizedText
import com.aicode.feature.plugin.domain.PluginContent
import com.aicode.feature.plugin.domain.PluginExecutionBackendCapabilitiesRef
import com.aicode.feature.plugin.domain.PluginExecutionBackendDefinition
import com.aicode.feature.plugin.domain.PluginExecutionBackendHandlersRef
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginRuntime
import com.aicode.feature.workspace.domain.FileReadTooLargeException
import com.aicode.feature.workspace.domain.model.WorkspaceType
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.NoSuchFileException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PluginExecutionBackendAdapterTest {

    private val runtime = PluginRuntime(
        pluginId = "demo.cloud",
        type = "javascript",
        installDir = File("/tmp/demo"),
        entryFile = File("/tmp/demo/index.js"),
        command = listOf("node", "index.js"),
        resident = true
    )

    private fun definition(terminal: Boolean = true) = PluginExecutionBackendDefinition(
        pluginId = "demo.cloud",
        localId = "sandbox",
        name = LocalizedText.single("Cloud Sandbox"),
        description = LocalizedText.single("Run in cloud"),
        capabilities = PluginExecutionBackendCapabilitiesRef(
            filesystem = true,
            command = true,
            terminal = terminal,
            fileWatch = false,
            portForward = false,
            isRemoteEnvironment = true,
            workspaceDiscovery = "register",
            projectConfigLocation = "appPrivateDir"
        ),
        handlers = PluginExecutionBackendHandlersRef(
            fs = "backend.fs",
            exec = "backend.exec",
            execStream = "backend.execStream",
            execHealth = "backend.health",
            terminal = if (terminal) "backend.terminal" else null,
            workspaceList = "backend.wsList",
            workspaceCreate = "backend.wsCreate",
            workspaceDelete = "backend.wsDelete",
            workspaceSelect = "backend.wsSelect"
        ),
        runtime = runtime,
        pluginVersion = "1.2.0"
    )

    @Test
    fun metadataCapabilitiesAndTerminalAvailability() {
        val runtimeManager = mockk<PluginRuntimeManager>(relaxed = true)
        val withoutTerminal = PluginExecutionBackendAdapter(definition(terminal = false), runtimeManager)
        assertEquals("demo.cloud:sandbox", withoutTerminal.id)
        assertEquals("Cloud Sandbox", withoutTerminal.displayName)
        assertEquals(WorkspaceDiscoveryMode.REGISTER, withoutTerminal.capabilities.workspaceDiscovery)
        assertEquals(ProjectConfigLocation.APP_PRIVATE_DIR, withoutTerminal.capabilities.projectConfigLocation)
        assertTrue(withoutTerminal.capabilities.isRemoteEnvironment)
        assertFalse(withoutTerminal.capabilities.fileWatch)
        assertFalse(withoutTerminal.capabilities.portForward)
        val workspaceConfig = PluginExecutionBackendAdapter(
            definition().copy(capabilities = PluginExecutionBackendCapabilitiesRef(
                isRemoteEnvironment = false,
                projectConfigLocation = "workspaceDir"
            )),
            runtimeManager
        )
        assertEquals(ProjectConfigLocation.WORKSPACE_DIR, workspaceConfig.capabilities.projectConfigLocation)
        assertNull(withoutTerminal.terminalSessionProvider)
        assertEquals("plugin:demo.cloud:sandbox:1.2.0", withoutTerminal.environmentCacheKey())
        assertTrue(withoutTerminal.projectConfigKey("/ws/app").startsWith("app-"))

        val withTerminal = PluginExecutionBackendAdapter(definition(terminal = true), runtimeManager)
        assertTrue(withTerminal.terminalSessionProvider != null)
    }

    @Test
    fun commandEngine_executesSyncStreamAndHealthCheck() = runTest {
        val runtimeManager = mockk<PluginRuntimeManager>()
        coEvery {
            runtimeManager.invokeBinary("demo.cloud", "1.2.0", runtime, "backend.health", any(), any(), "backend.invoke", any())
        } returns BinaryResult(meta = buildJsonObject {
            put("ready", true)
            put("shell", "/bin/bash")
        })
        coEvery {
            runtimeManager.invokeBinary("demo.cloud", "1.2.0", runtime, "backend.exec", any(), any(), "backend.invoke", any())
        } returns BinaryResult(meta = buildJsonObject {
            put("output", "hello world\n")
            put("exitCode", 0)
        })
        every {
            runtimeManager.openBinaryStream(any(), any(), any(), any<String>(), any(), any(), any(), any())
        } returns flowOf(
            BinaryStreamChunk(payload = "a\nb\n".toByteArray())
        )
        every {
            runtimeManager.openBinaryStream("demo.cloud", "1.2.0", runtime, "backend.execStream", any(), any(), "backend.invoke")
        } returns flowOf(
            BinaryStreamChunk(meta = buildJsonObject { put("type", "line") }, payload = "line-1\nline-2".toByteArray()),
            BinaryStreamChunk(meta = buildJsonObject { put("type", "exit"); put("exitCode", 0) })
        )

        val adapter = PluginExecutionBackendAdapter(definition(), runtimeManager)
        val engine = adapter.commandEngine

        assertFalse(engine.isContainerInstalled())
        engine.ensureInstalled()
        assertTrue(engine.isContainerInstalled())
        assertEquals("/bin/bash", engine.defaultShell())
        assertEquals(ContainerInitState.Ready, engine.initProgress.value)

        val syncResult = engine.runCommandSyncWithExit("echo hi", "/ws")
        assertEquals("hello world\n", syncResult.output)
        assertEquals(0, syncResult.exitCode)

        val events = engine.runCommandStream("echo stream", "/ws").toList()
        assertEquals(
            listOf(CommandEvent.Line("line-1"), CommandEvent.Line("line-2"), CommandEvent.Exit(0)),
            events
        )
    }

    @Test
    fun commandEngine_streamFallsBackToSyncWhenBinaryUnavailable() = runTest {
        val runtimeManager = mockk<PluginRuntimeManager>()
        every {
            runtimeManager.openBinaryStream("demo.cloud", "1.2.0", runtime, "backend.execStream", any(), any(), "backend.invoke")
        } returns flow {
            throw PluginProcessException("PLUGIN_IPC_UNAVAILABLE", "no ipc")
        }
        coEvery {
            runtimeManager.invokeBinary("demo.cloud", "1.2.0", runtime, "backend.exec", any(), any(), "backend.invoke", any())
        } returns BinaryResult(meta = buildJsonObject {
            put("output", "fallback-line")
            put("exitCode", 7)
        })

        val adapter = PluginExecutionBackendAdapter(definition(), runtimeManager)
        val events = adapter.commandEngine.runCommandStream("ls").toList()
        assertEquals(listOf(CommandEvent.Line("fallback-line"), CommandEvent.Exit(7)), events)
    }

    @Test
    fun fileAccess_coversCoreOperationsAndErrorMapping() {
        val runtimeManager = mockk<PluginRuntimeManager>()
        coEvery {
            runtimeManager.invokeBinary("demo.cloud", "1.2.0", runtime, "backend.fs", any(), any(), "backend.invoke", any())
        } answers {
            val args = arg<JsonObject>(4)
            val payload = arg<ByteArray>(5)
            val op = (args["op"] as JsonPrimitive).contentOrNull
            val path = (args["path"] as JsonPrimitive).contentOrNull.orEmpty()
            when (op) {
                "readFile" -> {
                    if (path == "/missing.txt") throw PluginProcessException("NOT_FOUND", "missing")
                    BinaryResult(payload = "a\nb\n".toByteArray())
                }
                "writeFile", "writeBytes", "mkdirs", "delete", "deleteRecursively", "rename", "copy", "move" ->
                    BinaryResult(meta = buildJsonObject { put("ok", true); put("written", payload.size) })
                "stat" -> BinaryResult(meta = buildJsonObject {
                    put("exists", path != "/missing.txt")
                    put("isFile", path.endsWith(".txt"))
                    put("isDirectory", !path.endsWith(".txt"))
                    put("size", 4L)
                    put("lastModified", 1234L)
                    put("permissions", "rw-")
                })
                "permissions" -> BinaryResult(meta = buildJsonObject { put("permissions", "rw-") })
                "listFilesRecursive" -> BinaryResult(meta = buildJsonObject {
                    put("paths", buildJsonArray { add(JsonPrimitive("a.txt")) })
                })
                "fingerprint" -> BinaryResult(meta = buildJsonObject { put("unsupported", true) })
                "readBytes" -> BinaryResult(payload = byteArrayOf(1, 2, 3, 4))
                "listFiles" -> BinaryResult(meta = buildJsonObject {
                    put("entries", buildJsonArray {
                        add(buildJsonObject {
                            put("name", "a.txt")
                            put("isDirectory", false)
                            put("size", 4L)
                            put("lastModified", 10L)
                            put("permissions", "rw-")
                        })
                    })
                })
                "resolvePath" -> BinaryResult(meta = buildJsonObject { put("path", "/canonical$path") })
                else -> error("Unexpected filesystem operation: $op")
            }
        }

        every {
            runtimeManager.openBinaryStream(any(), any(), any(), any<String>(), any(), any(), any(), any())
        } answers {
            val args = arg<JsonObject>(4)
            val payload = if (args["op"]?.jsonPrimitive?.content == "readStream") {
                "a\nb\n".toByteArray()
            } else {
                byteArrayOf(1, 2, 3, 4)
            }
            flowOf(BinaryStreamChunk(payload = payload))
        }
        coEvery {
            runtimeManager.uploadBinary(any(), any(), any(), any(), any(), any(), any(), any())
        } returns BinaryResult(meta = buildJsonObject { put("ok", true); put("bytesWritten", 3) })

        val fs = PluginExecutionBackendAdapter(definition(), runtimeManager).fileAccess
        assertEquals("a\nb\n", fs.readFile("/a.txt"))
        assertEquals(listOf("a", "b"), fs.readLines("/a.txt").toList())
        assertTrue(fs.exists("/a.txt"))
        assertTrue(fs.isFile("/a.txt"))
        assertFalse(fs.isDirectory("/a.txt"))
        assertEquals(4L, fs.fileSize("/a.txt"))
        assertEquals(1234L, fs.lastModified("/a.txt"))
        assertEquals("rw-", fs.permissions("/a.txt"))
        assertEquals("/canonical/link", fs.resolvePath("/link", true))
        assertEquals("a.txt", fs.listFiles("/").single().name)
        assertEquals(listOf("a.txt"), fs.listFilesRecursive("/", 2))
        fs.writeFile("/out.txt", "hello")
        fs.writeBytes("/out.txt", byteArrayOf(1))
        fs.mkdirs("/dir")
        fs.delete("/out.txt")
        fs.deleteRecursively("/dir")
        fs.rename("/a.txt", "/b.txt")
        fs.copy("/a.txt", "/b.txt", overwrite = false)
        fs.move("/a.txt", "/b.txt", overwrite = true)
        assertTrue(fs.fingerprint("/a.txt")!!.startsWith("sha256:"))
        assertEquals(listOf<Byte>(1, 2, 3, 4), fs.readBytes("/a.txt", 4).toList())
        assertEquals(3L, fs.writeStream("/out.txt", ByteArrayInputStream(byteArrayOf(7, 8, 9))))
        assertEquals("/", fs.parentPath("/a.txt"))
        assertEquals("/ws/a.txt", fs.toDisplayPath("/ws/a.txt"))
        coVerify {
            runtimeManager.invokeBinary("demo.cloud", "1.2.0", runtime, "backend.fs",
                match { it["op"] == JsonPrimitive("permissions") }, any(), "backend.invoke", any())
            runtimeManager.invokeBinary("demo.cloud", "1.2.0", runtime, "backend.fs",
                match { it["op"] == JsonPrimitive("copy") && it["newPath"] == JsonPrimitive("/b.txt") &&
                    it["overwrite"] == JsonPrimitive(false) }, any(), "backend.invoke", any())
        }

        val localCopy = fs.copyToLocal("/a.txt")
        try {
            assertEquals("a\nb\n", localCopy.readText())
        } finally {
            localCopy.delete()
        }

        try {
            fs.readFile("/missing.txt")
            fail("expected NoSuchFileException")
        } catch (_: NoSuchFileException) {
        }

        try {
            fs.readBytes("/a.txt", 2)
            fail("expected FileReadTooLargeException")
        } catch (_: FileReadTooLargeException) {
        }
    }

    @Test
    fun terminalAndWorkspaceAndRegistrar_workEndToEnd() = runTest {
        val runtimeManager = mockk<PluginRuntimeManager>()
        coEvery {
            runtimeManager.invokeBinary("demo.cloud", "1.2.0", runtime, "backend.terminal", any(), any(), "backend.invoke", any())
        } answers {
            val args = arg<JsonObject>(4)
            when ((args["op"] as JsonPrimitive).contentOrNull) {
                "start" -> BinaryResult(meta = buildJsonObject { put("tabId", "tab-9") })
                "status" -> BinaryResult(meta = buildJsonObject { put("exists", true); put("running", true) })
                "send", "write", "writeBytes", "close" -> BinaryResult(meta = buildJsonObject { put("ok", true) })
                "read" -> BinaryResult(meta = buildJsonObject { put("output", "term-out") })
                "list" -> BinaryResult(meta = buildJsonObject {
                    put("tabs", buildJsonArray {
                        add(buildJsonObject {
                            put("id", "tab-9")
                            put("title", "Build")
                            put("running", true)
                        })
                        add(buildJsonObject {
                            put("id", "tab-plugin")
                            put("title", "Hidden")
                            put("ownerPluginId", "demo.cloud")
                        })
                    })
                })
                else -> BinaryResult()
            }
        }
        coEvery {
            runtimeManager.invokeBinary("demo.cloud", "1.2.0", runtime, "backend.wsList", any(), any(), "backend.invoke", any())
        } returns BinaryResult(meta = buildJsonObject {
            put("workspaces", buildJsonArray {
                add(buildJsonObject {
                    put("id", "ws-1")
                    put("name", "Project 1")
                    put("path", "/cloud/p1")
                    put("canCreateChild", true)
                    put("canDelete", true)
                    put("capabilities", buildJsonObject {
                        put("createChild", false)
                        put("delete", false)
                        put("git", true)
                    })
                })
            })
        })

        val def = definition()
        val adapter = PluginExecutionBackendAdapter(def, runtimeManager)
        val term = adapter.terminalSessionProvider!!
        assertEquals("tab-9", term.startBackgroundCommand("npm run dev", "Dev", notify = true))
        assertEquals(true, term.tabStatus("tab-9")?.running)
        assertTrue(term.sendInput("tab-9", "echo 1"))
        assertEquals("term-out", term.getTabOutput("tab-9"))
        assertEquals(listOf("tab-9"), term.listTabs().map { it.id })
        assertTrue(term.closeTab("tab-9"))

        val listed = adapter.listWorkspaces()
        assertEquals(1, listed.size)
        assertEquals(WorkspaceType.REMOTE, listed.single().type)
        assertEquals("demo.cloud:sandbox", listed.single().backendId)
        assertFalse(listed.single().canCreateChild)
        assertFalse(listed.single().canDelete)

        val localBackend = BuiltinLocalProotBackend(
            localEngine = mockk<LinuxContainerEngine>(relaxed = true),
            localFileAccess = mockk<LocalFileAccess>(relaxed = true),
            localTerminalManager = mockk<TerminalSessionManager>(relaxed = true)
        )
        val remoteBackend = BuiltinRemoteSshBackend(
            remoteEngine = mockk<RemoteSshEngine>(relaxed = true),
            remoteFileAccess = mockk<RemoteSftpFileAccess>(relaxed = true),
            remoteTerminalManager = mockk<RemoteTerminalSessionManager>(relaxed = true),
            remoteSshConnection = mockk<RemoteSshConnection>(relaxed = true)
        )
        val backendRegistry = ExecutionBackendRegistry(localBackend, remoteBackend)
        val workspaceRegistry = PluginWorkspaceRegistry()
        var active = true
        val plugin = mockk<InstalledPlugin> {
            every { id } returns "demo.cloud"
            every { isActive } answers { active }
            every { content } returns PluginContent(executionBackends = listOf(def))
        }
        val pluginManager = mockk<PluginManager> {
            every { ensureLoaded() } just Runs
            every { plugins } returns MutableStateFlow(listOf(plugin))
        }

        val registrar = PluginExecutionBackendRegistrar(pluginManager, runtimeManager, backendRegistry, workspaceRegistry)
        registrar.sync("demo.cloud")
        assertTrue(backendRegistry.find("demo.cloud:sandbox") != null)

        workspaceRegistry.set("demo.cloud:sandbox", listed)
        active = false
        registrar.sync("demo.cloud")
        assertNull(backendRegistry.find("demo.cloud:sandbox"))
        assertTrue(workspaceRegistry.workspacesFor("demo.cloud:sandbox").isEmpty())
    }
}
