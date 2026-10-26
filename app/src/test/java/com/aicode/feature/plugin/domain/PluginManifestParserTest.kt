package com.aicode.feature.plugin.domain

import com.aicode.feature.plugin.PluginFixtures
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
        assertEquals(setOf(PluginPermission.SKILLS_REGISTER), m.requiredPermissions)
        assertEquals("注册子代理", m.requestFor(PluginPermission.AGENTS_REGISTER)?.reason?.resolve("zh-CN"))
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
              "permissions": [
                {
                  "id": "skills.register",
                  "required": true,
                  "reason": "提供项目笔记技能",
                  "scope": {"ids": ["project-notes"]}
                },
                {
                  "id": "agents.register",
                  "required": false,
                  "reason": {"default": "Provide a build sub-agent", "zh": "提供构建子代理"},
                  "scope": {"ids": ["builder"]}
                }
              ],
              "contributes": {
                "skills": [{"id": "project-notes", "file": "skills/project-notes/SKILL.md"}],
                "agents": [{"id": "builder", "file": "agents/builder.md"}]
              }
            }
        """.trimIndent()

        val m = success(example)
        assertEquals(listOf("project-notes"), m.skills.map { it.id })
        assertEquals(listOf("builder"), m.agents.map { it.id })
        assertEquals("Provide a build sub-agent", m.requestFor(PluginPermission.AGENTS_REGISTER)?.reason?.resolve("en"))
        assertEquals("提供项目笔记技能", m.requestFor(PluginPermission.SKILLS_REGISTER)?.reason?.resolve("en"))
    }

    @Test
    fun notJson_isRejected() {
        assertRejected("not json", PluginErrorCode.MANIFEST_NOT_JSON)
        assertRejected("[]", PluginErrorCode.MANIFEST_NOT_JSON, "root")
    }

    @Test
    fun unknownTopLevelField_isRejectedNotIgnored() {
        assertRejected(base.replace("\"license\"", "\"official\": true, \"license\""), PluginErrorCode.UNSUPPORTED_FIELD, "official")
        assertRejected(PluginFixtures.manifest(extra = ",\"runtime\": {\"type\": \"javascript\", \"entry\": \"dist/main.js\"}"), PluginErrorCode.UNSUPPORTED_FIELD, "runtime")
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
    fun structuredUi_isOptionalButMustBeValidRange() {
        val withUi = base.replace("\"pluginApi\": \"^1.0.0\"", "\"pluginApi\": \"^1.0.0\", \"structuredUi\": \"^1.0.0\"")
        assertEquals("^1.0.0", success(withUi).structuredUi)
        assertNull(success(base).structuredUi)
        assertRejected(base.replace("\"pluginApi\": \"^1.0.0\"", "\"pluginApi\": \"^1.0.0\", \"structuredUi\": \"x\""), PluginErrorCode.FIELD_INVALID, "engines.structuredUi")
    }

    @Test
    fun unsupportedPermission_isRejected() {
        assertRejected(
            base.replace("\"skills.register\"", "\"workspace.commands\""),
            PluginErrorCode.UNSUPPORTED_PERMISSION,
            "workspace.commands"
        )
    }

    @Test
    fun unsupportedContribution_isRejected() {
        assertRejected(
            base.replace("\"contributes\": {", "\"contributes\": {\"tools\": [], "),
            PluginErrorCode.UNSUPPORTED_CONTRIBUTION,
            "tools"
        )
    }

    @Test
    fun permissionScope_mustMatchContributionsExactly() {
        // 贡献了 notes，但权限只覆盖别的 ID
        assertRejected(
            base.replace("\"scope\": {\"ids\": [\"notes\"]}", "\"scope\": {\"ids\": [\"other\"]}"),
            PluginErrorCode.PERMISSION_SCOPE_MISMATCH,
            "skills.notes"
        )
        // 权限声明了不存在的贡献
        assertRejected(
            base.replace("\"scope\": {\"ids\": [\"builder\"]}", "\"scope\": {\"ids\": [\"builder\", \"ghost\"]}"),
            PluginErrorCode.PERMISSION_SCOPE_MISMATCH,
            "agents.register.ghost"
        )
        // 有技能贡献却没有对应权限
        val noSkillPermission = base.replace(
            Regex("""\{"id": "skills.register".*?"scope": \{"ids": \["notes"]}},\s*""", RegexOption.DOT_MATCHES_ALL),
            ""
        )
        assertRejected(noSkillPermission, PluginErrorCode.PERMISSION_SCOPE_MISMATCH, "skills.notes")
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
        val duplicatePermission = base.replace(
            "{\"id\": \"agents.register\"",
            "{\"id\": \"skills.register\", \"required\": false, \"reason\": \"dup\", \"scope\": {\"ids\": [\"notes\"]}}, {\"id\": \"agents.register\""
        )
        assertRejected(duplicatePermission, PluginErrorCode.DUPLICATE_ID, "permissions.skills.register")
    }

    @Test
    fun contributes_needsAtLeastOneContribution() {
        val empty = base
            .replace("\"skills\": [{\"id\": \"notes\", \"file\": \"skills/notes/SKILL.md\"}],", "")
            .replace("\"agents\": [{\"id\": \"builder\", \"file\": \"agents/builder.md\"}]", "")
        assertTrue(errors(empty).any { it.code == PluginErrorCode.FIELD_INVALID || it.code == PluginErrorCode.PERMISSION_SCOPE_MISMATCH })
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
    fun pathHelper_matchesSpec() {
        assertTrue(PluginManifestParser.isSafeRelativePath("skills/a/SKILL.md"))
        assertTrue(!PluginManifestParser.isSafeRelativePath(""))
        assertTrue(!PluginManifestParser.isSafeRelativePath("a/../b"))
        assertTrue(!PluginManifestParser.isSafeRelativePath("a/b/c/d/e/f/g/h/i"))
        assertTrue(!PluginManifestParser.isSafeRelativePath("a\u0000b"))
        assertTrue(!PluginManifestParser.isSafeRelativePath("x".repeat(PluginManifestParser.MAX_PATH_LENGTH + 1)))
    }
}
