package com.aicode.feature.plugin.domain

import com.aicode.feature.plugin.PluginFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginManifestParserTest {

    private fun parse(text: String) = PluginManifestParser.parse(text)

    private fun success(text: String): PluginManifest {
        val result = parse(text)
        check(result is ManifestParseResult.Success) { "应解析成功: $result" }
        return result.manifest
    }

    private fun errors(text: String): List<PluginError> {
        val result = parse(text)
        check(result is ManifestParseResult.Failure) { "应解析失败" }
        return result.errors
    }

    private fun assertRejected(text: String, code: PluginErrorCode, detail: String? = null) {
        val found = errors(text)
        assertTrue(
            "期望 $code${detail?.let { "($it)" }.orEmpty()}，实际 $found",
            found.any { it.code == code && (detail == null || it.detail == detail) }
        )
    }

    private val base = PluginFixtures.manifest()

    @Test
    fun valid_manifestIsParsed() {
        val m = success(base)
        assertEquals("community.demo-kit", m.id)
        assertEquals(SemVer(1, 0, 0), m.version)
        assertEquals("community", m.publisher)
        assertEquals("演示套件", m.name.resolve("zh-CN"))
        assertEquals("Demo Kit", m.name.resolve("en"))
        assertEquals(listOf(PluginContributionRef("notes", "skills/notes/SKILL.md")), m.skills)
        assertEquals(listOf(PluginContributionRef("builder", "agents/builder.md")), m.agents)
        assertEquals(2, m.contributionCount)
    }

    /** 与 docs-site 的《开发插件》里的示例清单保持一致：文档示例必须能被真实解析器接受。 */
    @Test
    fun documentedExample_isAccepted() {
        val example = """
            {
              "manifestVersion": 1,
              "id": "community.demo-kit",
              "name": {"default": "Demo Kit", "zh-CN": "演示套件"},
              "version": "1.0.0",
              "publisher": "community",
              "repository": "https://github.com/example/demo-kit",
              "description": "为项目笔记和构建任务提供技能与子代理",
              "license": "MIT",
              "engines": {"pluginApi": "^1.0.0"},
              "contributes": {
                "skills": [{"id": "project-notes", "file": "skills/project-notes/SKILL.md"}],
                "agents": [{"id": "builder", "file": "agents/builder.md"}]
              }
            }
        """.trimIndent()

        val m = success(example)
        assertEquals(listOf("project-notes"), m.skills.map { it.id })
        assertEquals(listOf("builder"), m.agents.map { it.id })
    }

    @Test
    fun notJson_isRejected() {
        assertRejected("not json", PluginErrorCode.MANIFEST_NOT_JSON)
        assertRejected("[]", PluginErrorCode.MANIFEST_NOT_JSON, "root")
    }

    @Test
    fun unknownTopLevelField_isRejectedNotIgnored() {
        assertRejected(base.replace("\"license\"", "\"official\": true, \"license\""), PluginErrorCode.UNSUPPORTED_FIELD, "official")
        assertRejected(PluginFixtures.manifest(extra = ",\"autoUpdate\": true"), PluginErrorCode.UNSUPPORTED_FIELD, "autoUpdate")
    }

    @Test
    fun manifestVersion_mustBeOne() {
        assertRejected(base.replace("\"manifestVersion\": 1", "\"manifestVersion\": 2"), PluginErrorCode.UNSUPPORTED_MANIFEST_VERSION, "2")
        assertRejected(base.replace("\"manifestVersion\": 1", "\"manifestVersion\": \"1\""), PluginErrorCode.FIELD_INVALID, "manifestVersion")
        assertRejected(base.replace("\"manifestVersion\": 1,", ""), PluginErrorCode.FIELD_MISSING, "manifestVersion")
    }

    @Test
    fun identity_isValidated() {
        assertRejected(base.replace("\"community.demo-kit\"", "\"Community.Demo\""), PluginErrorCode.FIELD_INVALID, "id")
        assertRejected(base.replace("\"community.demo-kit\"", "\"demo\""), PluginErrorCode.FIELD_INVALID, "id")
        assertRejected(base.replace("\"publisher\": \"community\"", "\"publisher\": \"other\""), PluginErrorCode.FIELD_INVALID, "publisher")
        assertRejected(base.replace("\"version\": \"1.0.0\"", "\"version\": \"1.0\""), PluginErrorCode.FIELD_INVALID, "version")
    }

    @Test
    fun repository_mustBePlainHttps() {
        listOf(
            "http://example.com/a/b",
            "https://user:pw@example.com/a/b",
            "https://example.com/a/b?x=1",
            "https://example.com/a/b#frag",
            "https://example.com",
            "git@example.com:a/b.git"
        ).forEach { url ->
            assertRejected(
                base.replace("https://example.com/community/demo-kit", url),
                PluginErrorCode.FIELD_INVALID,
                "repository"
            )
        }
    }

    @Test
    fun apiRange_mustContainHostVersion() {
        assertRejected(PluginFixtures.manifest(pluginApi = "^2.0.0"), PluginErrorCode.API_INCOMPATIBLE, "^2.0.0")
        assertRejected(PluginFixtures.manifest(pluginApi = "1.x"), PluginErrorCode.FIELD_INVALID, "engines.pluginApi")
        assertEquals("^1.0.0", success(base).pluginApi)
    }

    @Test
    fun unsupportedContribution_isRejected() {
        assertRejected(
            base.replace("\"contributes\": {", "\"contributes\": {\"providers\": [], "),
            PluginErrorCode.UNSUPPORTED_CONTRIBUTION,
            "providers"
        )
    }

    @Test
    fun contributionFile_mustBeSafeMarkdownPath() {
        listOf("../escape.md", "/abs.md", "a\\\\b.md", "skills//x.md", "skills/x.txt", "./x.md").forEach { path ->
            assertRejected(
                base.replace("skills/notes/SKILL.md", path),
                PluginErrorCode.FIELD_INVALID,
                "contributes.skills[0].file"
            )
        }
    }

    @Test
    fun duplicateIds_areRejected() {
        assertRejected(
            base.replace(
                "\"skills\": [{\"id\": \"notes\", \"file\": \"skills/notes/SKILL.md\"}]",
                "\"skills\": [{\"id\": \"notes\", \"file\": \"a.md\"}, {\"id\": \"notes\", \"file\": \"b.md\"}]"
            ),
            PluginErrorCode.DUPLICATE_ID,
            "contributes.skills.notes"
        )
    }

    @Test
    fun contributes_needsAtLeastOneContribution() {
        val empty = base
            .replace("\"skills\": [{\"id\": \"notes\", \"file\": \"skills/notes/SKILL.md\"}],", "")
            .replace("\"agents\": [{\"id\": \"builder\", \"file\": \"agents/builder.md\"}]", "")
        assertTrue(errors(empty).any { it.code == PluginErrorCode.FIELD_INVALID })
    }

    @Test
    fun localizedText_tagsAndLengthAreValidated() {
        assertRejected(base.replace("\"zh-CN\"", "\"Chinese!\""), PluginErrorCode.FIELD_INVALID, "name.Chinese!")
        assertRejected(base.replace("\"name\": {\"default\": \"Demo Kit\", \"zh-CN\": \"演示套件\"}", "\"name\": \"${"x".repeat(81)}\""), PluginErrorCode.FIELD_INVALID, "name")
        assertRejected(base.replace("\"name\": {\"default\": \"Demo Kit\", \"zh-CN\": \"演示套件\"}", "\"name\": {}"), PluginErrorCode.FIELD_INVALID, "name")
    }

    @Test
    fun multipleProblems_areAllReported() {
        val broken = base
            .replace("\"license\": \"MIT\"", "\"license\": \"\"")
            .replace("\"id\": \"community.demo-kit\"", "\"id\": \"BAD\"")
        val found = errors(broken).map { it.detail }
        assertTrue("id" in found && "license" in found)
    }

    @Test
    fun toolsContribution_isParsed() {
        val manifest = success(toolsManifest())
        assertEquals(PluginRuntimeRef("javascript", "dist/main.js"), manifest.runtime)
        assertEquals(1, manifest.tools.size)
        assertEquals("echo", manifest.tools.single().id)
        assertEquals("tools.echo", manifest.tools.single().handler)
        assertEquals(1, manifest.contributionCount)
    }

    @Test
    fun toolsRequireRuntime() {
        assertRejected(toolsManifest(runtimeBlock = ""), PluginErrorCode.FIELD_MISSING, "runtime")
    }

    @Test
    fun unknownRuntimeType_isRejected() {
        assertRejected(
            toolsManifest(runtimeBlock = "\"runtime\": {\"type\": \"lua\", \"entry\": \"dist/main.js\"},"),
            PluginErrorCode.UNSUPPORTED_CONTRIBUTION,
            "runtime.type"
        )
    }

    @Test
    fun pythonRuntime_isParsed() {
        val manifest = success(
            toolsManifest(runtimeBlock = "\"runtime\": {\"type\": \"python\", \"entry\": \"dist/main.py\"},")
        )
        assertEquals(PluginRuntimeRef("python", "dist/main.py"), manifest.runtime)
    }

    @Test
    fun runtimeEntryMustMatchType() {
        assertRejected(
            toolsManifest(runtimeBlock = "\"runtime\": {\"type\": \"javascript\", \"entry\": \"dist/main.py\"},"),
            PluginErrorCode.FIELD_INVALID,
            "runtime.entry"
        )
    }

    @Test
    fun runtimeDependencies_areParsed() {
        val manifest = success(
            toolsManifest(
                runtimeBlock = "\"runtime\": {\"type\": \"javascript\", \"entry\": \"dist/main.js\", " +
                    "\"dependencies\": {\"tool\": \"npm\", \"file\": \"package.json\"}},"
            )
        )
        assertEquals(PluginDependencyRef("npm", "package.json"), manifest.runtime?.dependencies)
    }

    @Test
    fun dependencyFileExtension_mustMatchTool() {
        assertRejected(
            toolsManifest(
                runtimeBlock = "\"runtime\": {\"type\": \"javascript\", \"entry\": \"dist/main.js\", " +
                    "\"dependencies\": {\"tool\": \"npm\", \"file\": \"requirements.txt\"}},"
            ),
            PluginErrorCode.FIELD_INVALID,
            "runtime.dependencies.file"
        )
    }

    @Test
    fun commandWithAbsolutePath_isRejected() {
        assertRejected(
            toolsManifest(
                runtimeBlock = "\"runtime\": {\"type\": \"javascript\", \"entry\": \"dist/main.js\", " +
                    "\"command\": [\"/usr/bin/node\", \"dist/main.js\"]},"
            ),
            PluginErrorCode.FIELD_INVALID,
            "runtime.command"
        )
    }

    @Test
    fun invalidToolHandler_isRejected() {
        assertRejected(toolsManifest(handler = "bad handler"), PluginErrorCode.FIELD_INVALID, "contributes.tools[0].handler")
    }

    @Test
    fun hooksContribution_isParsed() {
        val manifest = success(hooksManifest())
        assertEquals(PluginRuntimeRef("javascript", "dist/main.js"), manifest.runtime)
        assertEquals(listOf(PluginHookRef("on-start", "agent.run.started", "hooks.onStart")), manifest.hooks)
        assertEquals(1, manifest.contributionCount)
    }

    @Test
    fun newReadonlyHookEvents_areAccepted() {
        val manifest = success(
            hooksManifest(
                hooksBlock = """{"id": "on-request", "event": "agent.request.started", "handler": "hooks.onRequest"},
                    {"id": "on-tool", "event": "agent.tool.finished", "handler": "hooks.onTool"},
                    {"id": "on-compact", "event": "agent.compaction", "handler": "hooks.onCompact"}"""
            )
        )
        assertEquals(3, manifest.hooks.size)
    }

    @Test
    fun transformHooks_areParsed() {
        val manifest = success(
            hooksManifest(
                hooksBlock = """{"id": "guard", "event": "agent.tool.before", "handler": "hooks.guard", "mode": "transform"},
                    {"id": "redact", "event": "agent.tool.after", "handler": "hooks.redact", "mode": "transform"}"""
            )
        )
        assertEquals(
            listOf(
                PluginHookRef("guard", "agent.tool.before", "hooks.guard", PluginHookModes.TRANSFORM),
                PluginHookRef("redact", "agent.tool.after", "hooks.redact", PluginHookModes.TRANSFORM)
            ),
            manifest.hooks
        )
    }

    @Test
    fun httpHook_isParsed() {
        val manifest = success(
            hooksManifest(
                hooksBlock = """{"id": "proxy", "event": "agent.http.before", "handler": "hooks.proxy", "mode": "transform"}"""
            )
        )
        assertEquals(
            listOf(PluginHookRef("proxy", "agent.http.before", "hooks.proxy", PluginHookModes.TRANSFORM)),
            manifest.hooks
        )
    }

    @Test
    fun appStartedHook_isParsed() {
        val manifest = success(
            hooksManifest(
                hooksBlock = """{"id": "boot", "event": "app.started", "handler": "hooks.boot"}"""
            )
        )
        assertEquals(
            listOf(PluginHookRef("boot", "app.started", "hooks.boot", PluginHookModes.OBSERVE)),
            manifest.hooks
        )
    }

    @Test
    fun appStartedRequiresObserveMode() {
        assertRejected(
            hooksManifest(
                hooksBlock = """{"id": "boot", "event": "app.started", "handler": "hooks.boot", "mode": "transform"}"""
            ),
            PluginErrorCode.FIELD_INVALID,
            "contributes.hooks[0].mode"
        )
        assertRejected(
            hooksManifest(
                hooksBlock = """{"id": "boot", "event": "app.started", "handler": "hooks.boot", "mode": "review"}"""
            ),
            PluginErrorCode.FIELD_INVALID,
            "contributes.hooks[0].mode"
        )
    }

    @Test
    fun residentRuntime_isParsed() {
        val manifest = success(
            hooksManifest(
                runtimeBlock = """"runtime": {"type": "javascript", "entry": "dist/main.js", "resident": true},"""
            )
        )
        assertEquals(
            PluginRuntimeRef("javascript", "dist/main.js", resident = true),
            manifest.runtime
        )
    }

    @Test
    fun invalidResident_isRejected() {
        assertRejected(
            hooksManifest(
                runtimeBlock = """"runtime": {"type": "javascript", "entry": "dist/main.js", "resident": "yes"},"""
            ),
            PluginErrorCode.FIELD_INVALID,
            "runtime.resident"
        )
    }

    @Test
    fun permissionReviewHook_isParsed() {
        val manifest = success(
            hooksManifest(
                hooksBlock = """{"id": "reviewer", "event": "agent.permission.review", "handler": "hooks.review", "mode": "review"}"""
            )
        )
        assertEquals(
            listOf(PluginHookRef("reviewer", "agent.permission.review", "hooks.review", PluginHookModes.REVIEW)),
            manifest.hooks
        )
    }

    @Test
    fun permissionReviewRequiresReviewMode() {
        assertRejected(
            hooksManifest(
                hooksBlock = """{"id": "reviewer", "event": "agent.permission.review", "handler": "hooks.review", "mode": "transform"}"""
            ),
            PluginErrorCode.FIELD_INVALID,
            "contributes.hooks[0].mode"
        )
        assertRejected(
            hooksManifest(
                hooksBlock = """{"id": "reviewer", "event": "agent.permission.review", "handler": "hooks.review"}"""
            ),
            PluginErrorCode.FIELD_INVALID,
            "contributes.hooks[0].mode"
        )
        assertRejected(
            hooksManifest(
                hooksBlock = """{"id": "guard", "event": "agent.tool.before", "handler": "hooks.guard", "mode": "review"}"""
            ),
            PluginErrorCode.FIELD_INVALID,
            "contributes.hooks[0].mode"
        )
    }

    @Test
    fun transformModeOnObserveEvent_isRejected() {
        assertRejected(
            hooksManifest(
                hooksBlock = """{"id": "on-start", "event": "agent.run.started", "handler": "hooks.onStart", "mode": "transform"}"""
            ),
            PluginErrorCode.FIELD_INVALID,
            "contributes.hooks[0].mode"
        )
    }

    @Test
    fun transformEventRequiresTransformMode() {
        assertRejected(
            hooksManifest(
                hooksBlock = """{"id": "guard", "event": "agent.tool.before", "handler": "hooks.guard"}"""
            ),
            PluginErrorCode.FIELD_INVALID,
            "contributes.hooks[0].mode"
        )
    }

    @Test
    fun unknownHookMode_isRejected() {
        assertRejected(
            hooksManifest(
                hooksBlock = """{"id": "on-start", "event": "agent.run.started", "handler": "hooks.onStart", "mode": "write"}"""
            ),
            PluginErrorCode.FIELD_INVALID,
            "contributes.hooks[0].mode"
        )
    }

    @Test
    fun unknownHookEvent_isRejected() {
        assertRejected(
            hooksManifest(
                hooksBlock = """{"id": "on-start", "event": "agent.run.paused", "handler": "hooks.onStart"}"""
            ),
            PluginErrorCode.FIELD_INVALID,
            "contributes.hooks[0].event"
        )
    }

    @Test
    fun hooksRequireRuntime() {
        assertRejected(hooksManifest(runtimeBlock = ""), PluginErrorCode.FIELD_MISSING, "runtime")
    }

    @Test
    fun duplicateHookIds_areRejected() {
        assertRejected(
            hooksManifest(
                hooksBlock = """{"id": "on-start", "event": "agent.run.started", "handler": "hooks.onStart"},
                    {"id": "on-start", "event": "agent.run.finished", "handler": "hooks.onFinish"}"""
            ),
            PluginErrorCode.DUPLICATE_ID,
            "contributes.hooks.on-start"
        )
    }

    @Test
    fun mcpContribution_isParsed() {
        val manifest = success(mcpManifest())
        assertEquals(1, manifest.mcp.size)
        assertEquals("project-context", manifest.mcp.single().id)
        assertEquals("mcp/project-context.json", manifest.mcp.single().file)
    }

    @Test
    fun pathHelper_matchesSpec() {
        assertTrue(PluginManifestParser.isSafeRelativePath("skills/a/SKILL.md"))
        assertTrue(!PluginManifestParser.isSafeRelativePath(""))
        assertTrue(!PluginManifestParser.isSafeRelativePath("a/../b"))
        assertTrue(!PluginManifestParser.isSafeRelativePath("a/b/c/d/e/f/g/h/i"))
        assertTrue(!PluginManifestParser.isSafeRelativePath("a\u0000b"))
        assertTrue(!PluginManifestParser.isSafeRelativePath("x".repeat(PluginManifestParser.MAX_PATH_LENGTH + 1)))
    }

    private fun hooksManifest(
        runtimeBlock: String = "\"runtime\": {\"type\": \"javascript\", \"entry\": \"dist/main.js\"},",
        hooksBlock: String = """{"id": "on-start", "event": "agent.run.started", "handler": "hooks.onStart"}"""
    ): String = """
        {
          "manifestVersion": 1,
          "id": "community.hook-kit",
          "name": "Hook Kit",
          "version": "1.0.0",
          "publisher": "community",
          "repository": "https://example.com/community/hook-kit",
          "description": "Hooks",
          "license": "MIT",
          "engines": {"pluginApi": "^1.0.0"},
          $runtimeBlock
          "contributes": {
            "hooks": [$hooksBlock]
          }
        }
    """.trimIndent()

    private fun mcpManifest(): String = """
        {
          "manifestVersion": 1,
          "id": "community.mcp-kit",
          "name": "MCP Kit",
          "version": "1.0.0",
          "publisher": "community",
          "repository": "https://example.com/community/mcp-kit",
          "description": "MCP",
          "license": "MIT",
          "engines": {"pluginApi": "^1.0.0"},
          "contributes": {
            "mcp": [
              {"id": "project-context", "file": "mcp/project-context.json"}
            ]
          }
        }
    """.trimIndent()

    private fun toolsManifest(
        runtimeBlock: String = "\"runtime\": {\"type\": \"javascript\", \"entry\": \"dist/main.js\"},",
        handler: String = "tools.echo"
    ): String = """
        {
          "manifestVersion": 1,
          "id": "community.tool-kit",
          "name": "Tool Kit",
          "version": "1.0.0",
          "publisher": "community",
          "repository": "https://example.com/community/tool-kit",
          "description": "Tools",
          "license": "MIT",
          "engines": {"pluginApi": "^1.0.0"},
          $runtimeBlock
          "contributes": {
            "tools": [
              {"id": "echo", "title": "Echo", "description": "Echo text", "inputSchema": "schemas/echo.json", "handler": "$handler"}
            ]
          }
        }
    """.trimIndent()

    private fun settingsManifest(
        anchor: String = "settings.ai",
        page: String = "ui/memory.html",
        pluginApi: String = "^1.0.0"
    ): String = """
        {
          "manifestVersion": 1,
          "id": "demo.memory",
          "name": "Memory",
          "version": "1.0.0",
          "publisher": "demo",
          "repository": "https://example.com/demo/memory",
          "description": "Memory demo",
          "license": "MIT",
          "engines": {"pluginApi": "$pluginApi"},
          "contributes": {
            "settingsPages": [
              {"id": "memory", "title": "记忆", "anchor": "$anchor", "page": "$page"}
            ]
          }
        }
    """.trimIndent()

    @Test
    fun settingsPage_isParsedWithDefaultIcon() {
        val m = success(settingsManifest())
        assertEquals(listOf("memory"), m.settingsPages.map { it.id })
        assertEquals("settings.ai", m.settingsPages.single().anchor)
        assertEquals("ui/memory.html", m.settingsPages.single().page)
        assertEquals("book", m.settingsPages.single().icon)
    }

    @Test
    fun settingsPage_rejectsUnknownAnchor() {
        assertRejected(
            settingsManifest(anchor = "settings.nowhere"),
            PluginErrorCode.FIELD_INVALID,
            "contributes.settingsPages[0].anchor"
        )
    }

    @Test
    fun settingsPage_rejectsNonHtmlPage() {
        assertRejected(
            settingsManifest(page = "ui/memory.json"),
            PluginErrorCode.FIELD_INVALID,
            "contributes.settingsPages[0].page"
        )
    }

    @Test
    fun settingsPage_requiresCompatiblePluginApi() {
        assertRejected(
            settingsManifest(pluginApi = "^2.0.0"),
            PluginErrorCode.API_INCOMPATIBLE
        )
    }
}
