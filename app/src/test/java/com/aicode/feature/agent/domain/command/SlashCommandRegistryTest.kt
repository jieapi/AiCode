package com.aicode.feature.agent.domain.command

import android.content.Context
import com.aicode.R
import com.aicode.feature.agent.domain.skill.Skill
import com.aicode.feature.agent.domain.skill.SkillRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 斜杠命令注册表：菜单合并（内置命令在前 + 技能在后，去重排序）、发送时解析（含参数）与技能提示词构造。
 */
class SlashCommandRegistryTest {

    private val context = mockk<Context>()
    private val skillRepository = mockk<SkillRepository>()

    /** 构造时 init 会自动 refresh，默认给出空结果，避免与具体测试的 stub 竞态。 */
    @Before
    fun setUp() {
        every { skillRepository.listSkills() } returns emptyList()
    }

    private fun action(cmdName: String, descRes: Int, takesArgs: Boolean = false) =
        object : ActionCommandHandler {
            override val name = cmdName
            override val descriptionRes = descRes
            override val acceptsArgs = takesArgs
            override fun execute(context: SlashCommandContext, args: String) = Unit
        }

    private fun skill(skillName: String, description: String, instructions: String = "body") =
        Skill(name = skillName, description = description, instructions = instructions)

    private val usage = action("usage", R.string.slash_command_usage_desc)
    private val compress = action("compress", R.string.slash_command_compress_desc)
    private val init = action("init", R.string.slash_command_init_desc)

    private fun registry(vararg handlers: ActionCommandHandler) =
        SlashCommandRegistry(handlers.toSet(), skillRepository, context)

    private fun prepare(registry: SlashCommandRegistry, skills: List<Skill>) {
        every { context.getString(R.string.slash_command_usage_desc) } returns "用量"
        every { context.getString(R.string.slash_command_compress_desc) } returns "压缩上下文"
        every { context.getString(R.string.slash_command_init_desc) } returns "初始化项目"
        every { skillRepository.listSkills() } returns skills
        runBlocking { registry.refresh() }
    }

    // ---- 菜单合并 ----

    @Test
    fun commands_builtinsFirstThenSkills() {
        val registry = registry(usage, compress, init)
        prepare(registry, listOf(skill("pdf-report", "生成 PDF 报告"), skill("aaa", "最前")))

        // 内置命令一组（组内字母序）在前，技能一组（组内字母序）在后，两组不交叉。
        assertEquals(
            listOf("compress", "init", "usage", "aaa", "pdf-report"),
            registry.commands.value.map { it.name }
        )
    }

    @Test
    fun commands_builtinWinsOverSameNameSkill() {
        val registry = registry(usage)
        prepare(registry, listOf(skill("usage", "同名技能")))

        val matched = registry.commands.value.filter { it.name == "usage" }
        assertEquals(1, matched.size)
        assertEquals(SlashCommandKind.ACTION, matched.single().kind)
    }

    @Test
    fun commands_marksKindsAndArgs() {
        val registry = registry(usage)
        prepare(registry, listOf(skill("pdf-report", "生成 PDF")))

        val builtin = registry.commands.value.first { it.name == "usage" }
        assertEquals(SlashCommandKind.ACTION, builtin.kind)
        assertEquals(false, builtin.acceptsArgs)

        val skillCmd = registry.commands.value.first { it.name == "pdf-report" }
        assertEquals(SlashCommandKind.SKILL, skillCmd.kind)
        assertTrue(skillCmd.acceptsArgs)
    }

    // ---- 解析 ----

    @Test
    fun resolve_builtinExact() {
        val registry = registry(usage)
        prepare(registry, emptyList())

        val resolved = registry.resolve("/usage")
        assertTrue(resolved is SlashCommandRegistry.ResolvedCommand.Action)
        assertEquals("usage", resolved!!.name)
        assertEquals("", resolved.args)
    }

    @Test
    fun resolve_builtinWithArgsRejectedWhenArgsNotAccepted() {
        val registry = registry(usage)
        prepare(registry, emptyList())

        assertNull(registry.resolve("/usage now"))
    }

    @Test
    fun resolve_argAcceptingBuiltinKeepsArgs() {
        val registry = registry(action("echo", R.string.slash_command_usage_desc, takesArgs = true))
        prepare(registry, emptyList())

        val resolved = registry.resolve("/echo  hello world ")
        assertTrue(resolved is SlashCommandRegistry.ResolvedCommand.Action)
        assertEquals("hello world", resolved!!.args)
    }

    @Test
    fun resolve_skillCaseInsensitiveWithArgs() {
        val registry = registry()
        prepare(registry, listOf(skill("pdf-report", "生成 PDF", instructions = "make \$ARGUMENTS")))

        val resolved = registry.resolve("/PDF-Report a4")
        assertTrue(resolved is SlashCommandRegistry.ResolvedCommand.SkillCommand)
        assertEquals("pdf-report", resolved!!.name)
        assertEquals("a4", resolved.args)
    }

    @Test
    fun resolve_unknownOrNonSlash_returnsNull() {
        val registry = registry(usage)
        prepare(registry, listOf(skill("pdf-report", "生成 PDF")))

        assertNull(registry.resolve("/nope"))
        assertNull(registry.resolve("hello"))
        assertNull(registry.resolve("/"))
    }

    // ---- 技能提示词 ----

    @Test
    fun buildSkillPrompt_replacesPlaceholder() {
        val s = skill("x", "d", instructions = "run with \$ARGUMENTS now")
        assertEquals("run with a4 now", SlashCommandRegistry.buildSkillPrompt(s, "a4"))
    }

    @Test
    fun buildSkillPrompt_appendsArgsWhenNoPlaceholder() {
        val s = skill("x", "d", instructions = "do it")
        assertEquals("do it\n\n用户附加输入：a4", SlashCommandRegistry.buildSkillPrompt(s, "a4"))
    }

    @Test
    fun buildSkillPrompt_noArgsKeepsBody() {
        val s = skill("x", "d", instructions = "do it")
        assertEquals("do it", SlashCommandRegistry.buildSkillPrompt(s, ""))
    }
}
