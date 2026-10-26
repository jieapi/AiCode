package com.aicode.feature.plugin

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 插件测试共用的清单 / 包构造器。 */
object PluginFixtures {

    const val PLUGIN_ID = "community.demo-kit"

    const val SKILL_FILE = "skills/notes/SKILL.md"
    const val AGENT_FILE = "agents/builder.md"

    val SKILL_MD = """
        |---
        |name: ignored-by-host
        |description: Take project notes
        |---
        |Use this skill to take notes.
    """.trimMargin()

    val AGENT_MD = """
        |---
        |description: Builds things
        |mode: plan
        |tools: [readFile, list]
        |---
        |You are a builder.
    """.trimMargin()

    /** 同时提供技能（必要能力）与子代理（可选能力）的合法清单。 */
    fun manifest(
        version: String = "1.0.0",
        pluginApi: String = "^1.0.0",
        agentsRequired: Boolean = false,
        extra: String = ""
    ): String = """
        {
          "manifestVersion": 1,
          "id": "$PLUGIN_ID",
          "name": {"default": "Demo Kit", "zh-CN": "演示套件"},
          "version": "$version",
          "publisher": "community",
          "repository": "https://example.com/community/demo-kit",
          "description": "Demo plugin",
          "license": "MIT",
          "engines": {"pluginApi": "$pluginApi"},
          "permissions": [
            {"id": "skills.register", "required": true, "reason": "register skills", "scope": {"ids": ["notes"]}},
            {"id": "agents.register", "required": $agentsRequired,
             "reason": {"default": "register agents", "zh": "注册子代理"}, "scope": {"ids": ["builder"]}}
          ],
          "contributes": {
            "skills": [{"id": "notes", "file": "$SKILL_FILE"}],
            "agents": [{"id": "builder", "file": "$AGENT_FILE"}]
          }$extra
        }
    """.trimIndent()

    fun validPackage(
        manifest: String = manifest(),
        skill: String = SKILL_MD,
        agent: String = AGENT_MD
    ): ByteArray = zip(
        "plugin.json" to manifest,
        SKILL_FILE to skill,
        AGENT_FILE to agent
    )

    /** 只贡献技能、只申请 skills.register 的清单。 */
    fun skillsOnlyManifest(): String = """
        {
          "manifestVersion": 1,
          "id": "$PLUGIN_ID",
          "name": "Demo Kit",
          "version": "1.0.0",
          "publisher": "community",
          "repository": "https://example.com/community/demo-kit",
          "description": "Demo plugin",
          "license": "MIT",
          "engines": {"pluginApi": "^1.0.0"},
          "permissions": [
            {"id": "skills.register", "required": true, "reason": "register skills", "scope": {"ids": ["notes"]}}
          ],
          "contributes": {"skills": [{"id": "notes", "file": "$SKILL_FILE"}]}
        }
    """.trimIndent()

    fun skillsOnlyPackage(): ByteArray = zip("plugin.json" to skillsOnlyManifest(), SKILL_FILE to SKILL_MD)

    fun zip(vararg entries: Pair<String, String>): ByteArray = zipBytes(entries.map { it.first to it.second.toByteArray() })

    fun zipBytes(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                // ZipOutputStream 默认写入当前时间（2 秒粒度），同样内容在不同时刻会打出不同字节，测试会随机失败
                zip.putNextEntry(ZipEntry(name).apply { time = FIXED_ENTRY_TIME })
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private const val FIXED_ENTRY_TIME = 946_684_800_000L
}
