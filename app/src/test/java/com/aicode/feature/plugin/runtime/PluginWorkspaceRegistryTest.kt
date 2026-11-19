package com.aicode.feature.plugin.runtime

import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.LocalizedText
import com.aicode.feature.plugin.domain.PluginContent
import com.aicode.feature.plugin.domain.PluginExecutionBackendCapabilitiesRef
import com.aicode.feature.plugin.domain.PluginExecutionBackendDefinition
import com.aicode.feature.plugin.domain.PluginExecutionBackendHandlersRef
import com.aicode.feature.plugin.domain.PluginHostException
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginRuntime
import com.aicode.feature.workspace.domain.model.Workspace
import com.aicode.feature.workspace.domain.model.WorkspaceType
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PluginWorkspaceRegistryTest {

    private fun ws(
        id: String,
        name: String = id,
        path: String = "/ws/$id",
        group: String = ""
    ) = Workspace(
        name = name,
        path = path,
        type = WorkspaceType.REMOTE,
        available = true,
        id = id,
        backendId = "ignored",
        group = group,
        canCreateChild = false,
        canDelete = true
    )

    @Test
    fun registry_upsertRemoveSetAndClearPlugin() {
        val registry = PluginWorkspaceRegistry()
        val backendA = "demo.cloud:a"
        val backendB = "demo.cloud:b"
        val otherBackend = "other.plugin:x"

        registry.upsert(backendA, listOf(ws("1", "One"), ws("2", "Two")))
        assertEquals(listOf("1", "2"), registry.workspacesFor(backendA).map { it.id })
        assertTrue(registry.workspacesFor(backendA).all { it.backendId == backendA })

        registry.upsert(backendA, listOf(ws("2", "Two Updated"), ws("3", "Three")))
        assertEquals(listOf("1", "2", "3"), registry.workspacesFor(backendA).map { it.id })
        assertEquals("Two Updated", registry.workspacesFor(backendA)[1].name)

        registry.remove(backendA, listOf("1", "3"))
        assertEquals(listOf("2"), registry.workspacesFor(backendA).map { it.id })

        registry.set(backendB, listOf(ws("b1"), ws("b2"), ws("b1", name = "b1-new")))
        assertEquals(listOf("b1", "b2"), registry.workspacesFor(backendB).map { it.id })
        assertEquals("b1-new", registry.workspacesFor(backendB).first().name)

        registry.set(otherBackend, listOf(ws("o1")))
        registry.clearPlugin("demo.cloud")

        assertTrue(registry.workspacesFor(backendA).isEmpty())
        assertTrue(registry.workspacesFor(backendB).isEmpty())
        assertEquals(listOf("o1"), registry.workspacesFor(otherBackend).map { it.id })
    }

    @Test
    fun hostService_validatesOwnershipAndActiveState() {
        val registry = PluginWorkspaceRegistry()
        val runtime = PluginRuntime(
            pluginId = "demo.cloud",
            type = "javascript",
            installDir = File("/tmp/plugin"),
            entryFile = File("/tmp/plugin/index.js"),
            command = listOf("node", "index.js")
        )
        val backendDef = PluginExecutionBackendDefinition(
            pluginId = "demo.cloud",
            localId = "sandbox",
            name = LocalizedText.single("Sandbox"),
            description = LocalizedText.single("desc"),
            capabilities = PluginExecutionBackendCapabilitiesRef(
                filesystem = true,
                command = true,
                isRemoteEnvironment = true,
                workspaceDiscovery = "register"
            ),
            handlers = PluginExecutionBackendHandlersRef(fs = "fs", exec = "exec"),
            runtime = runtime,
            pluginVersion = "1.0.0"
        )
        var isActive = true
        val plugin = mockk<InstalledPlugin> {
            every { id } returns "demo.cloud"
            every { this@mockk.isActive } answers { isActive }
            every { content } returns PluginContent(executionBackends = listOf(backendDef))
        }
        val pluginManager = mockk<PluginManager> {
            every { ensureLoaded() } just Runs
            every { plugins } returns MutableStateFlow(listOf(plugin))
        }
        val service = PluginWorkspaceHostService(pluginManager, registry)

        service.upsert(
            "demo.cloud",
            buildJsonObject {
                put("backendId", "demo.cloud:sandbox")
                put("items", buildJsonArray {
                    add(buildJsonObject {
                        put("id", "ws-1")
                        put("name", "Workspace 1")
                        put("path", "/remote/ws-1")
                        put("group", "Team A")
                        put("capabilities", buildJsonObject { put("createChild", false) })
                    })
                })
            }
        )
        val stored = registry.workspacesFor("demo.cloud:sandbox").single()
        assertEquals("ws-1", stored.id)
        assertEquals("Team A", stored.group)
        assertEquals(WorkspaceType.REMOTE, stored.type)
        assertEquals(false, stored.canCreateChild)

        service.set(
            "demo.cloud",
            buildJsonObject {
                put("backendId", "demo.cloud:sandbox")
                put("items", buildJsonArray {
                    add(buildJsonObject {
                        put("id", "ws-2")
                        put("name", "Workspace 2")
                        put("path", "/remote/ws-2")
                    })
                })
            }
        )
        assertEquals(listOf("ws-2"), registry.workspacesFor("demo.cloud:sandbox").map { it.id })

        service.remove(
            "demo.cloud",
            buildJsonObject {
                put("backendId", "demo.cloud:sandbox")
                put("workspaceIds", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("ws-2")) })
            }
        )
        assertTrue(registry.workspacesFor("demo.cloud:sandbox").isEmpty())

        try {
            service.upsert(
                "demo.cloud",
                buildJsonObject {
                    put("backendId", "other.plugin:sandbox")
                    put("items", buildJsonArray {})
                }
            )
            fail("expected FORBIDDEN")
        } catch (e: PluginHostException) {
            assertEquals("FORBIDDEN", e.code)
        }

        isActive = false
        try {
            service.upsert(
                "demo.cloud",
                buildJsonObject {
                    put("backendId", "demo.cloud:sandbox")
                    put("items", buildJsonArray {})
                }
            )
            fail("expected PLUGIN_INACTIVE")
        } catch (e: PluginHostException) {
            assertEquals("PLUGIN_INACTIVE", e.code)
        }
    }
}
