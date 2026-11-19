package com.aicode.feature.plugin.runtime

import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.LocalizedText
import com.aicode.feature.plugin.domain.PluginContent
import com.aicode.feature.plugin.domain.PluginExecutionBackendCapabilitiesRef
import com.aicode.feature.plugin.domain.PluginExecutionBackendDefinition
import com.aicode.feature.plugin.domain.PluginExecutionBackendHandlersRef
import com.aicode.feature.plugin.domain.PluginHostException
import com.aicode.feature.plugin.domain.PluginManager
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PluginWorkspaceHostServiceTest {

    private val backend = PluginExecutionBackendDefinition(
        pluginId = "demo.cloud",
        localId = "sandbox",
        name = LocalizedText.single("Cloud"),
        description = LocalizedText.single(""),
        capabilities = PluginExecutionBackendCapabilitiesRef(),
        handlers = PluginExecutionBackendHandlersRef(fs = "fs", exec = "exec"),
        runtime = mockk(),
        pluginVersion = "1.0.0"
    )

    private fun service(registry: PluginWorkspaceRegistry): PluginWorkspaceHostService {
        val plugin = mockk<InstalledPlugin> {
            every { id } returns "demo.cloud"
            every { isActive } returns true
            every { content } returns PluginContent(executionBackends = listOf(backend))
        }
        val manager = mockk<PluginManager> {
            every { ensureLoaded() } just Runs
            every { plugins } returns MutableStateFlow(listOf(plugin))
        }
        return PluginWorkspaceHostService(manager, registry)
    }

    private fun params(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun setAndUpsert_readNestedCapabilitiesAndKeepDefaults() {
        val registry = PluginWorkspaceRegistry()
        val service = service(registry)
        service.set("demo.cloud", params("""
            {"backendId":"demo.cloud:sandbox","items":[
              {"id":"locked","name":"Locked","path":"/locked",
               "canCreateChild":true,"canDelete":true,
               "capabilities":{"createChild":false,"delete":false,"git":false}},
              {"id":"default","name":"Default","path":"/default",
               "canCreateChild":false,"canDelete":false}
            ]}
        """))
        val workspaces = registry.workspacesFor(backend.key)
        assertFalse(workspaces[0].canCreateChild)
        assertFalse(workspaces[0].canDelete)
        assertTrue(workspaces[1].canCreateChild)
        assertTrue(workspaces[1].canDelete)
        service.upsert("demo.cloud", params("""
            {"backendId":"demo.cloud:sandbox","items":[
              {"id":"locked","name":"Locked","path":"/locked",
               "capabilities":{"createChild":true,"delete":false,"git":true}}
            ]}
        """))
        assertTrue(registry.workspacesFor(backend.key).first().canCreateChild)
        assertFalse(registry.workspacesFor(backend.key).first().canDelete)
    }

    @Test
    fun remove_acceptsWorkspaceIdsAndRejectsIdsAlias() {
        val registry = PluginWorkspaceRegistry()
        val service = service(registry)
        service.upsert("demo.cloud", params("""
            {"backendId":"demo.cloud:sandbox","items":[
              {"id":"ws-1","name":"One","path":"/one"},
              {"id":"ws-2","name":"Two","path":"/two"}
            ]}
        """))
        try {
            service.remove("demo.cloud", params("""{"backendId":"demo.cloud:sandbox","ids":["ws-1"]}"""))
            fail("expected workspaceIds requirement")
        } catch (e: PluginHostException) {
            assertEquals("PARAM_INVALID", e.code)
        }
        assertEquals(2, registry.workspacesFor(backend.key).size)
        val result = service.remove("demo.cloud", params("""
            {"backendId":"demo.cloud:sandbox","workspaceIds":["ws-1"]}
        """)) as JsonObject
        assertEquals(Json.parseToJsonElement("1"), result["count"])
        assertEquals(listOf("ws-2"), registry.workspacesFor(backend.key).map { it.id })
    }

    @Test
    fun upsert_requiresStableIdInsteadOfDerivingItFromPath() {
        val registry = PluginWorkspaceRegistry()
        try {
            service(registry).upsert("demo.cloud", params("""
                {"backendId":"demo.cloud:sandbox","workspaces":[
                  {"name":"One","path":"/one"}
                ]}
            """))
            fail("expected stable workspace id")
        } catch (e: PluginHostException) {
            assertEquals("WORKSPACE_INVALID", e.code)
        }
        assertTrue(registry.workspacesFor(backend.key).isEmpty())
    }

    @Test
    fun nestedCapabilities_rejectInvalidBooleanIncludingGit() {
        val registry = PluginWorkspaceRegistry()
        val service = service(registry)
        listOf("createChild", "delete", "git").forEach { capability ->
            try {
                service.upsert("demo.cloud", params("""
                    {"backendId":"demo.cloud:sandbox","items":[
                      {"id":"ws-1","name":"One","path":"/one","capabilities":{"$capability":"false"}}
                    ]}
                """))
                fail("expected invalid capability: $capability")
            } catch (e: PluginHostException) {
                assertEquals("PARAM_INVALID", e.code)
            }
        }
        assertTrue(registry.workspacesFor(backend.key).isEmpty())
    }
}
