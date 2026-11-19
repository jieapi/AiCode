package com.aicode.feature.plugin.domain

import com.aicode.feature.plugin.PluginFixtures
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private val base = PluginFixtures.manifest(pluginApi = "^1.0.0")

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
    fun providerContribution_isParsed() {
        val json = """
            {
              "manifestVersion": 1,
              "id": "demo.provider",
              "name": "Demo Provider",
              "version": "1.0.0",
              "publisher": "demo",
              "repository": "https://example.com/demo/provider",
              "description": "Provider demo",
              "license": "MIT",
              "engines": {"pluginApi": "^1.0.0"},
              "contributes": {
                "providers": [
                  {
                    "id": "gateway",
                    "name": {"zh-CN": "网关", "en": "Gateway"},
                    "format": "openai",
                    "baseUrl": "http://127.0.0.1:18080/v1"
                  }
                ]
              }
            }
        """.trimIndent()

        val m = success(json)
        assertEquals(1, m.providers.size)
        assertEquals("gateway", m.providers.single().id)
        assertEquals("http://127.0.0.1:18080/v1", m.providers.single().baseUrl)
    }

    @Test
    fun modelAccessContribution_isParsed() {
        val json = """
            {
              "manifestVersion": 1,
              "id": "demo.team",
              "name": "Team Chat",
              "version": "1.0.0",
              "publisher": "demo",
              "repository": "https://example.com/demo/team",
              "description": "Team chat demo",
              "license": "MIT",
              "engines": {"pluginApi": "^1.0.0"},
              "contributes": {
                "modelAccess": {"purpose": "在团队群聊中轮流调用多个模型"}
              }
            }
        """.trimIndent()

        val m = success(json)
        assertEquals("在团队群聊中轮流调用多个模型", m.modelAccess?.purpose)
        assertEquals(1, m.contributionCount)
    }

    @Test
    fun modelAccessRejectsUnknownFields() {
        val json = """
            {
              "manifestVersion": 1,
              "id": "demo.team",
              "name": "Team Chat",
              "version": "1.0.0",
              "publisher": "demo",
              "repository": "https://example.com/demo/team",
              "description": "Team chat demo",
              "license": "MIT",
              "engines": {"pluginApi": "^1.0.0"},
              "contributes": {
                "modelAccess": {"purpose": "团队群聊", "providers": ["openai"]}
              }
            }
        """.trimIndent()

        assertRejected(json, PluginErrorCode.UNSUPPORTED_FIELD, "contributes.modelAccess.providers")
    }

    @Test
    fun providerRejectsNonHttpBaseUrl() {
        val json = """
            {
              "manifestVersion": 1,
              "id": "demo.provider",
              "name": "Demo Provider",
              "version": "1.0.0",
              "publisher": "demo",
              "repository": "https://example.com/demo/provider",
              "description": "Provider demo",
              "license": "MIT",
              "engines": {"pluginApi": "^1.0.0"},
              "contributes": {
                "providers": [
                  {
                    "id": "gateway",
                    "name": "Gateway",
                    "format": "openai",
                    "baseUrl": "ftp://127.0.0.1:18080"
                  }
                ]
              }
            }
        """.trimIndent()

        assertRejected(json, PluginErrorCode.FIELD_INVALID, "contributes.providers[0].baseUrl")
    }

    @Test
    fun notJson_isRejected() {
        assertRejected("not json", PluginErrorCode.MANIFEST_NOT_JSON)
        assertRejected("[]", PluginErrorCode.MANIFEST_NOT_JSON, "root")
    }

    @Test
    fun unknownTopLevelField_isRejectedNotIgnored() {
        assertRejected(base.replace("\"license\"", "\"official\": true, \"license\""), PluginErrorCode.UNSUPPORTED_FIELD, "official")
        assertRejected(PluginFixtures.manifest(pluginApi = "^1.0.0", extra = ",\"autoUpdate\": true"), PluginErrorCode.UNSUPPORTED_FIELD, "autoUpdate")
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
        assertRejected(PluginFixtures.manifest(pluginApi = "^3.0.0"), PluginErrorCode.API_INCOMPATIBLE, "^3.0.0")
        assertRejected(PluginFixtures.manifest(pluginApi = "1.x"), PluginErrorCode.FIELD_INVALID, "engines.pluginApi")
        assertEquals("^1.0.0", success(base).pluginApi)
    }

    @Test
    fun unsupportedContribution_isRejected() {
        assertRejected(
            base.replace("\"contributes\": {", "\"contributes\": {\"unknownContribution\": [], "),
            PluginErrorCode.UNSUPPORTED_CONTRIBUTION,
            "unknownContribution"
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
    fun pluginLifecycleHooks_areParsed() {
        val manifest = success(
            hooksManifest(
                hooksBlock = """{"id": "on-installed", "event": "plugin.installed", "handler": "hooks.onInstalled"},
                    {"id": "on-uninstalled", "event": "plugin.uninstalled", "handler": "hooks.onUninstalled"},
                    {"id": "on-enabled", "event": "plugin.enabled", "handler": "hooks.onEnabled"},
                    {"id": "on-disabled", "event": "plugin.disabled", "handler": "hooks.onDisabled"}"""
            )
        )
        assertEquals(
            listOf(
                PluginHookRef("on-installed", "plugin.installed", "hooks.onInstalled", PluginHookModes.OBSERVE),
                PluginHookRef("on-uninstalled", "plugin.uninstalled", "hooks.onUninstalled", PluginHookModes.OBSERVE),
                PluginHookRef("on-enabled", "plugin.enabled", "hooks.onEnabled", PluginHookModes.OBSERVE),
                PluginHookRef("on-disabled", "plugin.disabled", "hooks.onDisabled", PluginHookModes.OBSERVE)
            ),
            manifest.hooks
        )
    }

    @Test
    fun pluginLifecycleHooksRequireObserveMode() {
        assertRejected(
            hooksManifest(
                hooksBlock = """{"id": "on-installed", "event": "plugin.installed", "handler": "hooks.onInstalled", "mode": "transform"}"""
            ),
            PluginErrorCode.FIELD_INVALID,
            "contributes.hooks[0].mode"
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
    fun workspaceToolsContribution_isParsed() {
        val tool = success(workspaceToolsManifest()).workspaceTools.single()
        assertEquals("build-apk", tool.id)
        assertEquals("构建 APK", tool.title.resolve("zh-CN"))
        assertEquals("hammer", tool.icon)
        assertEquals("构建", tool.category)
        assertEquals("pages/build-apk.html", tool.pageFile)
    }

    @Test
    fun workspaceToolLabels_useDefaultsAndTrim() {
        val trimmed = success(
            workspaceToolsManifest("\"title\": \"构建 APK\", \"icon\": \" hammer \", \"category\": \" 构建 \"")
        ).workspaceTools.single()
        assertEquals("hammer", trimmed.icon)
        assertEquals("构建", trimmed.category)

        val defaults = success(workspaceToolsManifest("\"title\": \"构建 APK\"")).workspaceTools.single()
        assertEquals("play", defaults.icon)
        assertEquals("通用", defaults.category)
    }

    @Test
    fun workspaceToolUnknownField_isRejected() {
        assertRejected(
            workspaceToolsManifest("\"title\": \"构建 APK\", \"handler\": \"tools.build\""),
            PluginErrorCode.UNSUPPORTED_FIELD,
            "contributes.workspaceTools[0].handler"
        )
    }

    @Test
    fun workspaceToolLabels_rejectOversizeAndControlChars() {
        assertRejected(
            workspaceToolsManifest("\"title\": \"构建 APK\", \"icon\": \"${"x".repeat(33)}\""),
            PluginErrorCode.FIELD_INVALID,
            "contributes.workspaceTools[0].icon"
        )
        assertRejected(
            workspaceToolsManifest("\"title\": \"构建 APK\", \"category\": \"${"类".repeat(33)}\""),
            PluginErrorCode.FIELD_INVALID,
            "contributes.workspaceTools[0].category"
        )
        assertRejected(
            workspaceToolsManifest("\"title\": \"构建 APK\", \"icon\": \"ha\\u0001mmer\""),
            PluginErrorCode.FIELD_INVALID,
            "contributes.workspaceTools[0].icon"
        )
        assertRejected(
            workspaceToolsManifest("\"title\": \"构建 APK\", \"category\": \"构\\u0001建\""),
            PluginErrorCode.FIELD_INVALID,
            "contributes.workspaceTools[0].category"
        )
        assertRejected(
            workspaceToolsManifest("\"title\": \"构建 APK\", \"icon\": \"  \""),
            PluginErrorCode.FIELD_INVALID,
            "contributes.workspaceTools[0].icon"
        )
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

    private fun backupHooksManifest(
        backupBlock: String = "\"backup\": {\"purpose\": \"把我自己的数据也备份一份\"},",
        hooksBlock: String = """{"id": "exported", "event": "backup.export.before", "handler": "hooks.exported"}"""
    ): String = """
        {
          "manifestVersion": 1,
          "id": "community.backup-kit",
          "name": "Backup Kit",
          "version": "1.0.0",
          "publisher": "community",
          "repository": "https://example.com/community/backup-kit",
          "description": "Backups",
          "license": "MIT",
          "engines": {"pluginApi": "^1.0.0"},
          "runtime": {"type": "javascript", "entry": "dist/main.js"},
          "contributes": {
            $backupBlock
            "hooks": [$hooksBlock]
          }
        }
    """.trimIndent()

    @Test
    fun backupHooks_areParsedWithDeclaration() {
        val m = success(backupHooksManifest())
        assertEquals(PluginBackupRef("把我自己的数据也备份一份"), m.backup)
        assertEquals(
            listOf(PluginHookRef("exported", "backup.export.before", "hooks.exported", PluginHookModes.OBSERVE)),
            m.hooks
        )
    }

    @Test
    fun backupHooks_requireDeclaration() {
        assertRejected(
            backupHooksManifest(backupBlock = ""),
            PluginErrorCode.FIELD_MISSING,
            "contributes.backup"
        )
    }

    @Test
    fun backupHooks_requireObserveMode() {
        assertRejected(
            backupHooksManifest(
                hooksBlock = """{"id": "exported", "event": "backup.export.before", "handler": "hooks.exported", "mode": "transform"}"""
            ),
            PluginErrorCode.FIELD_INVALID,
            "contributes.hooks[0].mode"
        )
    }

    @Test
    fun backupDeclaration_requiresPurpose() {
        assertRejected(
            backupHooksManifest(backupBlock = "\"backup\": {\"purpose\": \"\"},"),
            PluginErrorCode.FIELD_INVALID,
            "contributes.backup.purpose"
        )
    }

    @Test
    fun backupDeclaration_rejectsUnknownField() {
        assertRejected(
            backupHooksManifest(backupBlock = "\"backup\": {\"purpose\": \"备份\", \"path\": \"x\"},"),
            PluginErrorCode.UNSUPPORTED_FIELD,
            "contributes.backup.path"
        )
    }

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

    private fun workspaceToolsManifest(
        toolBody: String = "\"title\": \"构建 APK\", \"icon\": \"hammer\", \"category\": \"构建\""
    ): String = """
        {
          "manifestVersion": 1,
          "id": "community.workspace-kit",
          "name": "Workspace Kit",
          "version": "1.0.0",
          "publisher": "community",
          "repository": "https://example.com/community/workspace-kit",
          "description": "Workspace tools",
          "license": "MIT",
          "engines": {"pluginApi": "^1.0.0"},
          "contributes": {
            "workspaceTools": [
              {"id": "build-apk", $toolBody, "pageFile": "pages/build-apk.html"}
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
        slot: String = "settings.ai",
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
            "uiEntries": [
              {"id": "memory", "title": "记忆", "slot": "$slot", "page": "$page"}
            ]
          }
        }
    """.trimIndent()

    @Test
    fun settingsEntry_isParsedWithDefaultIcon() {
        val m = success(settingsManifest())
        assertEquals(listOf("memory"), m.uiEntries.map { it.id })
        assertEquals("settings.ai", m.uiEntries.single().slot)
        assertEquals("ui/memory.html", m.uiEntries.single().page)
        assertEquals("book", m.uiEntries.single().icon)
    }

    @Test
    fun uiEntries_acceptsDrawerAndHeaderSlots() {
        assertEquals("drawer.footer", success(settingsManifest(slot = "drawer.footer")).uiEntries.single().slot)
        assertEquals("chat.header.menu", success(settingsManifest(slot = "chat.header.menu")).uiEntries.single().slot)
        assertEquals("chat.welcome", success(settingsManifest(slot = "chat.welcome")).uiEntries.single().slot)
        assertEquals("chat.session.menu", success(settingsManifest(slot = "chat.session.menu")).uiEntries.single().slot)
        assertEquals(
            "files.tree.menu",
            success(
                entriesManifest(
                    """{"id":"f","title":"文件动作","slot":"files.tree.menu","handler":"actions.file"}""",
                    runtime = true
                )
            ).uiEntries.single().slot
        )
        assertEquals(
            "editor.selection.menu",
            success(
                entriesManifest(
                    """{"id":"e","title":"选区动作","slot":"editor.selection.menu","handler":"actions.selection"}""",
                    runtime = true
                )
            ).uiEntries.single().slot
        )
    }

    private fun entriesManifest(entries: String, runtime: Boolean = false): String {
        val runtimeBlock = if (runtime) {
            "\"runtime\": {\"type\": \"javascript\", \"entry\": \"dist/main.js\"},"
        } else {
            ""
        }
        return """
            {
              "manifestVersion": 1,
              "id": "demo.entries",
              "name": "Entries",
              "version": "1.0.0",
              "publisher": "demo",
              "repository": "https://example.com/demo/entries",
              "description": "Entries demo",
              "license": "MIT",
              "engines": {"pluginApi": "^1.0.0"},
              $runtimeBlock
              "contributes": {"uiEntries": [$entries]}
            }
        """.trimIndent()
    }

    @Test
    fun uiEntries_parsesActionEntryWithRuntime() {
        val m = success(
            entriesManifest(
                """{"id":"a","title":"同步","slot":"chat.session.menu","handler":"actions.sync","confirm":true}""",
                runtime = true
            )
        )
        val entry = m.uiEntries.single()
        assertEquals(PluginUiEntryKind.ACTION, entry.kind)
        assertEquals("actions.sync", entry.handler)
        assertTrue(entry.confirm)
    }

    @Test
    fun uiEntries_actionRequiresRuntime() {
        assertRejected(
            entriesManifest("""{"id":"a","title":"同步","slot":"drawer.footer","handler":"actions.sync"}"""),
            PluginErrorCode.FIELD_MISSING,
            "runtime"
        )
    }

    @Test
    fun uiEntries_rejectsEntryWithoutKind() {
        assertRejected(
            entriesManifest("""{"id":"x","title":"X","slot":"chat.welcome"}"""),
            PluginErrorCode.FIELD_INVALID,
            "contributes.uiEntries[0]"
        )
    }

    @Test
    fun uiEntries_rejectsPageInActionOnlySlot() {
        assertRejected(
            entriesManifest("""{"id":"p","title":"页","slot":"files.tree.menu","page":"p.html"}"""),
            PluginErrorCode.FIELD_INVALID,
            "contributes.uiEntries[0].page"
        )
    }

    private fun backupDataManifest(
        entry: String,
        backupBlock: String = "\"backup\": {\"purpose\": \"额外备份\"},",
        hooksBlock: String = """{"id": "export", "event": "backup.export.before", "handler": "hooks.exportBefore"}"""
    ): String = """
        {
          "manifestVersion": 1,
          "id": "demo.backup-data",
          "name": "Backup Data",
          "version": "1.0.0",
          "publisher": "demo",
          "repository": "https://example.com/demo/backup-data",
          "description": "Backup data demo",
          "license": "MIT",
          "engines": {"pluginApi": "^1.0.0"},
          "runtime": {"type": "javascript", "entry": "dist/main.js"},
          "contributes": {
            $backupBlock
            "hooks": [$hooksBlock],
            "uiEntries": [$entry]
          }
        }
    """.trimIndent()

    @Test
    fun uiEntries_acceptsBackupDataSlot() {
        val m = success(
            backupDataManifest(
                """{"id":"extra","title":"记忆 / 技能","slot":"backup.data","icon":"package"}"""
            )
        )
        val entry = m.uiEntries.single()
        assertEquals(PluginUiSlots.BACKUP_DATA, entry.slot)
        assertEquals(PluginUiEntryKind.DATA, entry.kind)
        assertNull(entry.page)
        assertNull(entry.handler)
    }

    @Test
    fun uiEntries_rejectsPageInBackupDataSlot() {
        assertRejected(
            backupDataManifest("""{"id":"p","title":"页","slot":"backup.data","page":"p.html"}"""),
            PluginErrorCode.FIELD_INVALID,
            "contributes.uiEntries[0].page"
        )
    }

    @Test
    fun uiEntries_rejectsHandlerInBackupDataSlot() {
        assertRejected(
            backupDataManifest("""{"id":"a","title":"动作","slot":"backup.data","handler":"actions.backup"}"""),
            PluginErrorCode.FIELD_INVALID,
            "contributes.uiEntries[0].handler"
        )
    }

    @Test
    fun backupData_requiresDeclarationAndExportHook() {
        assertRejected(
            backupDataManifest(
                """{"id":"extra","title":"扩展数据","slot":"backup.data"}""",
                backupBlock = ""
            ),
            PluginErrorCode.FIELD_MISSING,
            "contributes.backup"
        )
        assertRejected(
            backupDataManifest(
                """{"id":"extra","title":"扩展数据","slot":"backup.data"}""",
                hooksBlock = """{"id": "import", "event": "backup.import.finished", "handler": "hooks.onImport"}"""
            ),
            PluginErrorCode.FIELD_MISSING,
            "contributes.hooks"
        )
    }

    @Test
    fun uiEntries_rejectsHandlerInSettingsSlot() {
        assertRejected(
            entriesManifest(
                """{"id":"a","title":"动作","slot":"settings.ai","handler":"actions.sync"}""",
                runtime = true
            ),
            PluginErrorCode.FIELD_INVALID,
            "contributes.uiEntries[0].slot"
        )
    }

    @Test
    fun uiEntries_rejectsConfirmWithoutHandler() {
        assertRejected(
            entriesManifest("""{"id":"p","title":"页","slot":"chat.welcome","page":"p.html","confirm":true}"""),
            PluginErrorCode.FIELD_INVALID,
            "contributes.uiEntries[0].confirm"
        )
    }

    @Test
    fun uiEntries_rejectsUnknownSlot() {
        assertRejected(
            settingsManifest(slot = "settings.nowhere"),
            PluginErrorCode.FIELD_INVALID,
            "contributes.uiEntries[0].slot"
        )
    }

    @Test
    fun uiEntries_rejectsThirdEntryInSameSlot() {
        val text = base.replace(
            "\"contributes\": {",
            """"contributes": {
            "uiEntries": [
              {"id": "a", "title": "A", "slot": "drawer.footer", "page": "a.html"},
              {"id": "b", "title": "B", "slot": "drawer.footer", "page": "b.html"},
              {"id": "c", "title": "C", "slot": "drawer.footer", "page": "c.html"}
            ],"""
        )
        assertRejected(text, PluginErrorCode.FIELD_INVALID, "contributes.uiEntries[2].slot")
    }

    @Test
    fun settingsEntry_rejectsNonHtmlPage() {
        assertRejected(
            settingsManifest(page = "ui/memory.json"),
            PluginErrorCode.FIELD_INVALID,
            "contributes.uiEntries[0].page"
        )
    }

    @Test
    fun settingsEntry_requiresCompatiblePluginApi() {
        assertRejected(
            settingsManifest(pluginApi = "^2.0.0"),
            PluginErrorCode.API_INCOMPATIBLE
        )
    }

    @Test
    fun icon_acceptsBuiltinNameAndPackageImage() {
        val builtin = success(PluginFixtures.manifest(pluginApi = "^1.0.0", extra = ",\n  \"icon\": \"terminal\""))
        assertEquals("terminal", builtin.icon)

        val png = success(PluginFixtures.manifest(pluginApi = "^1.0.0", extra = ",\n  \"icon\": \"icons/logo.png\""))
        assertEquals("icons/logo.png", png.icon)

        val webp = success(PluginFixtures.manifest(pluginApi = "^1.0.0", extra = ",\n  \"icon\": \"icons/logo.WEBP\""))
        assertEquals("icons/logo.WEBP", webp.icon)
    }

    @Test
    fun icon_rejectsUnsupportedImageAndUnsafePath() {
        assertRejected(
            PluginFixtures.manifest(pluginApi = "^1.0.0", extra = ",\n  \"icon\": \"icons/logo.svg\""),
            PluginErrorCode.FIELD_INVALID,
            "icon"
        )
        assertRejected(
            PluginFixtures.manifest(pluginApi = "^1.0.0", extra = ",\n  \"icon\": \"../logo.png\""),
            PluginErrorCode.FIELD_INVALID,
            "icon"
        )
        assertRejected(
            PluginFixtures.manifest(pluginApi = "^1.0.0", extra = ",\n  \"icon\": \"\""),
            PluginErrorCode.FIELD_INVALID,
            "icon"
        )
    }

    @Test
    fun sessionSourceContribution_isRejectedAsUnsupported() {
        val json = """
            {
              "manifestVersion": 1,
              "id": "demo.remote",
              "name": "Remote",
              "version": "1.0.0",
              "publisher": "demo",
              "repository": "https://example.com/demo/remote",
              "description": "Remote sessions",
              "license": "MIT",
              "engines": {"pluginApi": "^1.0.0"},
              "contributes": {
                "sessionSources": [
                  {
                    "id": "ds-harness",
                    "title": {"default": "DS Harness Remote"},
                    "handlers": {
                      "listSessions": "sessionSource.listSessions",
                      "readSession": "sessionSource.readSession"
                    }
                  }
                ]
              }
            }
        """.trimIndent()

        assertRejected(json, PluginErrorCode.UNSUPPORTED_CONTRIBUTION, "sessionSources")
    }

    @Test
    fun executionBackendContribution_isParsed() {
        val json = """
            {
              "manifestVersion": 1,
              "id": "demo.remote",
              "name": "Remote Backend",
              "version": "1.0.0",
              "publisher": "demo",
              "repository": "https://example.com/demo/remote",
              "description": "Remote execution backend",
              "license": "MIT",
              "engines": {"pluginApi": "^1.0.0"},
              "runtime": {"type": "javascript", "entry": "index.js", "resident": true},
              "contributes": {
                "executionBackends": [
                  {
                    "id": "cloud-box",
                    "title": {"default": "Cloud Sandbox", "zh-CN": "云沙盒"},
                    "description": "Run commands in cloud sandbox",
                    "capabilities": {
                      "filesystem": true,
                      "command": true,
                      "terminal": true,
                      "fileWatch": false,
                      "portForward": false,
                      "isRemoteEnvironment": true,
                      "workspaceDiscovery": "hybrid",
                      "projectConfigLocation": "appPrivateDir"
                    },
                    "handlers": {
                      "fs": "backend.fs",
                      "exec": "backend.exec",
                      "execStream": "backend.execStream",
                      "execHealth": "backend.health",
                      "terminal": "backend.terminal",
                      "workspaceList": "backend.wsList",
                      "workspaceCreate": "backend.wsCreate",
                      "workspaceDelete": "backend.wsDelete",
                      "workspaceSelect": "backend.wsSelect"
                    }
                  }
                ]
              }
            }
        """.trimIndent()

        val m = success(json)
        assertEquals(1, m.executionBackends.size)
        val backend = m.executionBackends.single()
        assertEquals("cloud-box", backend.id)
        assertEquals("云沙盒", backend.name.resolve("zh-CN"))
        assertTrue(backend.capabilities.filesystem)
        assertTrue(backend.capabilities.command)
        assertTrue(backend.capabilities.terminal)
        assertTrue(backend.capabilities.isRemoteEnvironment)
        assertEquals("hybrid", backend.capabilities.workspaceDiscovery)
        assertEquals("appPrivateDir", backend.capabilities.projectConfigLocation)
        assertEquals("backend.fs", backend.handlers.fs)
        assertEquals("backend.execStream", backend.handlers.execStream)
        assertEquals("backend.terminal", backend.handlers.terminal)
        assertEquals(1, m.contributionCount)
    }

    @Test
    fun executionBackendContribution_validatesRuntimeAndHandlers() {
        val noRuntime = """
            {
              "manifestVersion": 1,
              "id": "demo.remote",
              "name": "Remote Backend",
              "version": "1.0.0",
              "publisher": "demo",
              "repository": "https://example.com/demo/remote",
              "description": "Remote execution backend",
              "license": "MIT",
              "engines": {"pluginApi": "^1.0.0"},
              "contributes": {
                "executionBackends": [
                  {
                    "id": "cloud-box",
                    "title": "Cloud Sandbox",
                    "capabilities": {"filesystem": true, "command": false},
                    "handlers": {"fs": "backend.fs"}
                  }
                ]
              }
            }
        """.trimIndent()
        assertRejected(noRuntime, PluginErrorCode.FIELD_MISSING, "runtime")

        val missingExecHandler = """
            {
              "manifestVersion": 1,
              "id": "demo.remote",
              "name": "Remote Backend",
              "version": "1.0.0",
              "publisher": "demo",
              "repository": "https://example.com/demo/remote",
              "description": "Remote execution backend",
              "license": "MIT",
              "engines": {"pluginApi": "^1.0.0"},
              "runtime": {"type": "javascript", "entry": "index.js"},
              "contributes": {
                "executionBackends": [
                  {
                    "id": "cloud-box",
                    "title": "Cloud Sandbox",
                    "capabilities": {"filesystem": true, "command": true},
                    "handlers": {"fs": "backend.fs"}
                  }
                ]
              }
            }
        """.trimIndent()
        assertRejected(missingExecHandler, PluginErrorCode.FIELD_MISSING, "contributes.executionBackends[0].handlers.exec")
    }

    @Test
    fun workflowContribution_isParsed() {
        val json = """
            {
              "manifestVersion": 1,
              "id": "demo.codex",
              "name": "Codex Workflow",
              "version": "1.0.0",
              "publisher": "demo",
              "repository": "https://example.com/demo/codex",
              "description": "Codex agent workflow",
              "license": "MIT",
              "engines": {"pluginApi": "^1.0.0"},
              "runtime": {"type": "javascript", "entry": "index.js", "resident": true},
              "contributes": {
                "workflows": [
                  {
                    "id": "codex-loop",
                    "title": {"default": "Codex Loop", "zh-CN": "Codex 工作流"},
                    "description": "Delegates turns to Codex CLI",
                    "capabilities": {
                      "streaming": true,
                      "tools": true,
                      "compaction": true,
                      "titleGeneration": false
                    },
                    "handlers": {
                      "startTurn": "wf.startTurn",
                      "cancelTurn": "wf.cancelTurn",
                      "compact": "wf.compact",
                      "generateTitle": "wf.title"
                    }
                  }
                ]
              }
            }
        """.trimIndent()

        val m = success(json)
        assertEquals(1, m.workflows.size)
        val wf = m.workflows.single()
        assertEquals("codex-loop", wf.id)
        assertEquals("Codex 工作流", wf.title.resolve("zh-CN"))
        assertEquals("Delegates turns to Codex CLI", wf.description.resolve("en"))
        assertTrue(wf.capabilities.streaming)
        assertTrue(wf.capabilities.tools)
        assertTrue(wf.capabilities.compaction)
        assertEquals(false, wf.capabilities.titleGeneration)
        assertEquals("wf.startTurn", wf.handlers.startTurn)
        assertEquals("wf.cancelTurn", wf.handlers.cancelTurn)
        assertEquals("wf.compact", wf.handlers.compact)
        assertEquals("wf.title", wf.handlers.generateTitle)
        assertEquals(1, m.contributionCount)
    }

    @Test
    fun workflowContribution_validatesRequiredFieldsAndRuntime() {
        val noRuntime = """
            {
              "manifestVersion": 1,
              "id": "demo.codex",
              "name": "Codex Workflow",
              "version": "1.0.0",
              "publisher": "demo",
              "repository": "https://example.com/demo/codex",
              "description": "Codex agent workflow",
              "license": "MIT",
              "engines": {"pluginApi": "^1.0.0"},
              "contributes": {
                "workflows": [
                  {
                    "id": "codex-loop",
                    "title": "Codex Loop",
                    "handlers": {
                      "startTurn": "wf.startTurn",
                      "cancelTurn": "wf.cancelTurn"
                    }
                  }
                ]
              }
            }
        """.trimIndent()
        assertRejected(noRuntime, PluginErrorCode.FIELD_MISSING, "runtime")

        val missingIdAndTitle = """
            {
              "manifestVersion": 1,
              "id": "demo.codex",
              "name": "Codex Workflow",
              "version": "1.0.0",
              "publisher": "demo",
              "repository": "https://example.com/demo/codex",
              "description": "Codex agent workflow",
              "license": "MIT",
              "engines": {"pluginApi": "^1.0.0"},
              "runtime": {"type": "javascript", "entry": "index.js"},
              "contributes": {
                "workflows": [
                  {
                    "handlers": {
                      "startTurn": "wf.startTurn",
                      "cancelTurn": "wf.cancelTurn"
                    }
                  }
                ]
              }
            }
        """.trimIndent()
        assertRejected(missingIdAndTitle, PluginErrorCode.FIELD_MISSING, "contributes.workflows[0].id")
        assertRejected(missingIdAndTitle, PluginErrorCode.FIELD_MISSING, "contributes.workflows[0].title")

        val missingHandlers = """
            {
              "manifestVersion": 1,
              "id": "demo.codex",
              "name": "Codex Workflow",
              "version": "1.0.0",
              "publisher": "demo",
              "repository": "https://example.com/demo/codex",
              "description": "Codex agent workflow",
              "license": "MIT",
              "engines": {"pluginApi": "^1.0.0"},
              "runtime": {"type": "javascript", "entry": "index.js"},
              "contributes": {
                "workflows": [
                  {
                    "id": "codex-loop",
                    "title": "Codex Loop",
                    "handlers": {
                      "startTurn": "wf.startTurn"
                    }
                  }
                ]
              }
            }
        """.trimIndent()
        assertRejected(missingHandlers, PluginErrorCode.FIELD_MISSING, "contributes.workflows[0].handlers.cancelTurn")

        val missingStartTurn = missingHandlers.replace("\"startTurn\": \"wf.startTurn\"", "\"cancelTurn\": \"wf.cancelTurn\"")
        assertRejected(missingStartTurn, PluginErrorCode.FIELD_MISSING, "contributes.workflows[0].handlers.startTurn")

        val unknownField = """
            {
              "manifestVersion": 1,
              "id": "demo.codex",
              "name": "Codex Workflow",
              "version": "1.0.0",
              "publisher": "demo",
              "repository": "https://example.com/demo/codex",
              "description": "Codex agent workflow",
              "license": "MIT",
              "engines": {"pluginApi": "^1.0.0"},
              "runtime": {"type": "javascript", "entry": "index.js"},
              "contributes": {
                "workflows": [
                  {
                    "id": "codex-loop",
                    "title": "Codex Loop",
                    "extra": true,
                    "handlers": {
                      "startTurn": "wf.startTurn",
                      "cancelTurn": "wf.cancelTurn"
                    }
                  }
                ]
              }
            }
        """.trimIndent()
        assertRejected(unknownField, PluginErrorCode.UNSUPPORTED_FIELD, "contributes.workflows[0].extra")
    }

    @Test
    fun sessionContribution_isParsed() {
        val json = """
            {
              "manifestVersion": 1,
              "id": "demo.remote",
              "name": "Remote Sessions",
              "version": "1.0.0",
              "publisher": "demo",
              "repository": "https://example.com/demo/remote",
              "description": "External sessions plugin",
              "license": "MIT",
              "engines": {"pluginApi": "^1.0.0"},
              "runtime": {"type": "javascript", "entry": "index.js", "resident": true},
              "contributes": {
                "sessions": [
                  {
                    "id": "harness",
                    "title": {"default": "Harness", "zh-CN": "外部会话"},
                    "description": "Stream remote harness sessions",
                    "handlers": {
                      "refresh": "sessions.refresh",
                      "readMessages": "sessions.readMessages",
                      "createSession": "sessions.create",
                      "deleteSession": "sessions.delete",
                      "renameSession": "sessions.rename",
                      "pinSession": "sessions.pin"
                    }
                  }
                ]
              }
            }
        """.trimIndent()

        val m = success(json)
        assertEquals(1, m.sessions.size)
        val session = m.sessions.single()
        assertEquals("harness", session.id)
        assertEquals("外部会话", session.title.resolve("zh-CN"))
        assertEquals("Stream remote harness sessions", session.description.resolve("en"))
        assertEquals("sessions.refresh", session.handlers.refresh)
        assertEquals("sessions.readMessages", session.handlers.readMessages)
        assertEquals("sessions.create", session.handlers.createSession)
        assertEquals("sessions.delete", session.handlers.deleteSession)
        assertEquals("sessions.rename", session.handlers.renameSession)
        assertEquals("sessions.pin", session.handlers.pinSession)
        assertEquals(1, m.contributionCount)
    }

    @Test
    fun sessionContribution_validatesRequiredFieldsAndRuntime() {
        val noRuntime = """
            {
              "manifestVersion": 1,
              "id": "demo.remote",
              "name": "Remote Sessions",
              "version": "1.0.0",
              "publisher": "demo",
              "repository": "https://example.com/demo/remote",
              "description": "External sessions plugin",
              "license": "MIT",
              "engines": {"pluginApi": "^1.0.0"},
              "contributes": {
                "sessions": [
                  {
                    "id": "harness",
                    "title": "Harness",
                    "handlers": {"readMessages": "sessions.readMessages"}
                  }
                ]
              }
            }
        """.trimIndent()
        assertRejected(noRuntime, PluginErrorCode.FIELD_MISSING, "runtime")

        val missingReadMessages = """
            {
              "manifestVersion": 1,
              "id": "demo.remote",
              "name": "Remote Sessions",
              "version": "1.0.0",
              "publisher": "demo",
              "repository": "https://example.com/demo/remote",
              "description": "External sessions plugin",
              "license": "MIT",
              "engines": {"pluginApi": "^1.0.0"},
              "runtime": {"type": "javascript", "entry": "index.js"},
              "contributes": {
                "sessions": [
                  {
                    "id": "harness",
                    "title": "Harness",
                    "handlers": {"refresh": "sessions.refresh"}
                  }
                ]
              }
            }
        """.trimIndent()
        assertRejected(missingReadMessages, PluginErrorCode.FIELD_MISSING, "contributes.sessions[0].handlers.readMessages")

        val tooMany = (0 until 33).joinToString(",") { idx ->
            """{"id": "s$idx", "title": "S$idx", "handlers": {"readMessages": "sessions.readMessages"}}"""
        }
        val overLimit = """
            {
              "manifestVersion": 1,
              "id": "demo.remote",
              "name": "Remote Sessions",
              "version": "1.0.0",
              "publisher": "demo",
              "repository": "https://example.com/demo/remote",
              "description": "External sessions plugin",
              "license": "MIT",
              "engines": {"pluginApi": "^1.0.0"},
              "runtime": {"type": "javascript", "entry": "index.js"},
              "contributes": {"sessions": [$tooMany]}
            }
        """.trimIndent()
        assertRejected(overLimit, PluginErrorCode.FIELD_INVALID, "contributes.sessions")
    }

    private fun backendManifest(backends: String): String {
        val root = Json.parseToJsonElement(base) as JsonObject
        return buildJsonObject {
            root.forEach { (key, value) -> put(key, value) }
            put("runtime", buildJsonObject {
                put("type", "javascript")
                put("entry", "index.js")
            })
            put("contributes", buildJsonObject {
                put("executionBackends", Json.parseToJsonElement("[$backends]"))
            })
        }.toString()
    }

    @Test
    fun executionBackend_defaultsMatchApiContract() {
        assertEquals(SemVer(1, 0, 0), PluginApi.VERSION)
        val backend = """{"id":"cloud","title":"Cloud","handlers":{"fs":"fs","exec":"exec"}}"""
        val expected = PluginExecutionBackendCapabilitiesRef(
            filesystem = true,
            command = true,
            terminal = false,
            fileWatch = false,
            portForward = false,
            isRemoteEnvironment = true,
            workspaceDiscovery = "scan",
            projectConfigLocation = "appPrivateDir"
        )
        assertEquals(expected, success(backendManifest(backend)).executionBackends.single().capabilities)
        assertEquals(expected, success(backendManifest(backend.replace("\"handlers\"", "\"capabilities\":{},\"handlers\"")))
            .executionBackends.single().capabilities)
        assertEquals("workspaceDir", success(backendManifest(backend.replace(
            "\"handlers\"", "\"capabilities\":{\"isRemoteEnvironment\":false,\"projectConfigLocation\":\"workspaceDir\"},\"handlers\""
        ))).executionBackends.single().capabilities.projectConfigLocation)
        success(backendManifest("""{"id":"cloud","title":"Cloud","capabilities":{"filesystem":false,"command":false},"handlers":{}}"""))
    }

    @Test
    fun executionBackend_rejectsNameUnsupportedCapabilitiesAndConfigAliases() {
        val backend = """{"id":"cloud","title":"Cloud","handlers":{"fs":"fs","exec":"exec"}}"""
        assertRejected(backendManifest(backend.replace("\"title\"", "\"name\"")),
            PluginErrorCode.UNSUPPORTED_FIELD, "contributes.executionBackends[0].name")
        listOf("fileWatch", "portForward").forEach { capability ->
            assertRejected(backendManifest(backend.replace("\"handlers\"", "\"capabilities\":{\"$capability\":true},\"handlers\"")),
                PluginErrorCode.FIELD_INVALID, "contributes.executionBackends[0].capabilities.$capability")
        }
        listOf("", "\"isRemoteEnvironment\":true,").forEach { remote ->
            assertRejected(backendManifest(backend.replace("\"handlers\"", "\"capabilities\":{$remote\"projectConfigLocation\":\"workspaceDir\"},\"handlers\"")),
                PluginErrorCode.FIELD_INVALID, "contributes.executionBackends[0].capabilities.projectConfigLocation")
        }
        listOf("workspace_dir", "app_private_dir", "workspacedir", "appprivatedir").forEach { location ->
            assertRejected(backendManifest(backend.replace("\"handlers\"", "\"capabilities\":{\"projectConfigLocation\":\"$location\"},\"handlers\"")),
                PluginErrorCode.FIELD_INVALID, "contributes.executionBackends[0].capabilities.projectConfigLocation")
        }
    }

    @Test
    fun executionBackend_requiresCapabilityHandlersAndLimitsEntriesTo32() {
        val backend = """{"id":"cloud","title":"Cloud","handlers":{"fs":"fs","exec":"exec"}}"""
        assertRejected(backendManifest(backend.replace("\"fs\":\"fs\",", "")),
            PluginErrorCode.FIELD_MISSING, "contributes.executionBackends[0].handlers.fs")
        assertRejected(backendManifest(backend.replace(",\"exec\":\"exec\"", "")),
            PluginErrorCode.FIELD_MISSING, "contributes.executionBackends[0].handlers.exec")
        assertRejected(backendManifest(backend.replace("\"handlers\"", "\"capabilities\":{\"terminal\":true},\"handlers\"")),
            PluginErrorCode.FIELD_MISSING, "contributes.executionBackends[0].handlers.terminal")
        val entries = (0 until 33).map { backend.replace("\"cloud\"", "\"cloud$it\"") }
        assertEquals(32, success(backendManifest(entries.take(32).joinToString(","))).executionBackends.size)
        assertRejected(backendManifest(entries.joinToString(",")),
            PluginErrorCode.FIELD_INVALID, "contributes.executionBackends")
    }

    @Test
    fun sharedSdkContractFixtures_areParsedByHost() {
        val relativePath = "plugins/sdk/test/fixtures/contracts.json"
        val fixtureFile = listOf(File(relativePath), File("../$relativePath")).firstOrNull { it.isFile }
            ?: error("Missing shared contract fixtures: $relativePath")
        val fixtures = Json.parseToJsonElement(fixtureFile.readText()) as JsonObject
        val valid = fixtures.getValue("validManifests") as JsonArray
        val invalid = fixtures.getValue("invalidManifests") as JsonArray
        assertTrue("validManifests must not be empty", valid.isNotEmpty())
        assertTrue("invalidManifests must not be empty", invalid.isNotEmpty())
        valid.forEachIndexed { index, manifest ->
            val result = parse(manifest.toString())
            assertTrue("validManifests[$index]: $result", result is ManifestParseResult.Success)
            assertEquals("^1.0.0", (result as ManifestParseResult.Success).manifest.pluginApi)
        }
        invalid.forEachIndexed { index, manifest ->
            assertTrue("invalidManifests[$index]", parse(manifest.toString()) is ManifestParseResult.Failure)
        }
    }
}
