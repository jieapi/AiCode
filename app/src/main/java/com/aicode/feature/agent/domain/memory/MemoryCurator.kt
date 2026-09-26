package com.aicode.feature.agent.domain.memory

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.provider.AIProvider
import com.aicode.feature.agent.domain.prompt.PromptFileResolver
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MemoryCurator"

/** 单次抽取的上限条数（与提示词约定一致）。 */
private const val MAX_CANDIDATES = 3

/** 一轮对话送给整理器的上下文上限（字符）：只看最近发生的事，控制成本。 */
private const val MAX_TRANSCRIPT_CHARS = 12_000

/**
 * 引擎级记忆兜底：一轮对话结束后，用轻量模型静默抽取值得长期记住的事实，
 * 直接写入 [MemoryRepository]。主模型忘了调用 memory 工具时由此兜底；
 * 全程静默失败，绝不影响对话主流程。
 */
@Singleton
class MemoryCurator @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val promptFileResolver: PromptFileResolver
) {
    /** 提示词文件名，与 [PromptFileResolver.resolve] 的路径约定一致。 */
    private fun prompt(): String = promptFileResolver.resolve("agent/memory-curator.md")

    /**
     * 抽取并落盘本轮对话的记忆。
     * @param provider 由调用方解析好的轻量 provider（压缩专用模型或当前聊天模型回退）。
     * @param transcript 本轮对话文本（"用户: …/助手: …" 行）。
     * @return 本次实际写入的记忆条数（失败为 0）。
     */
    suspend fun curate(
        provider: AIProvider,
        sessionId: String,
        projectRoot: String?,
        transcript: String
    ): Int = runCatching {
        if (transcript.isBlank()) return@runCatching 0
        val systemPrompt = prompt().replace(LEADING_COMMENT, "").trim()
        if (systemPrompt.isEmpty()) return@runCatching 0

        val response = provider.complete(
            systemPrompt = systemPrompt,
            messages = listOf(AgentMessage.UserMessage(content = transcript.take(MAX_TRANSCRIPT_CHARS))),
            tools = emptyList()
        )
        val candidates = parseCandidates(response.content)
        var saved = 0
        for (c in candidates) {
            val ok = memoryRepository.saveMemory(
                name = c.name,
                description = c.description,
                content = c.content,
                scope = c.scope,
                projectRoot = projectRoot
            )
            if (ok) saved++
        }
        if (saved > 0) FileLogger.i(TAG, "会话 $sessionId 自动沉淀 $saved 条记忆")
        saved
    }.onFailure { e ->
        FileLogger.w(TAG, "自动记忆整理失败（静默忽略）: ${e.message}")
    }.getOrDefault(0)

    /** 解析整理器输出；格式不合法/越界条目一律丢弃，宁缺毋滥。 */
    private fun parseCandidates(content: String): List<Candidate> {
        val start = content.indexOf('[')
        val end = content.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val arr = runCatching {
            Json.parseToJsonElement(content.substring(start, end + 1)).jsonArray
        }.getOrNull() ?: return emptyList()

        return arr.asSequence()
            .mapNotNull { el -> runCatching { el.jsonObject }.getOrNull() }
            .mapNotNull { obj ->
                runCatching {
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    val description = obj["description"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    val body = obj["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    val isProject = obj["scope"]?.jsonPrimitive?.contentOrNull?.trim()
                        ?.equals("project", ignoreCase = true) == true
                    if (name.isEmpty() || body.isEmpty() || description.isEmpty()) return@runCatching null
                    Candidate(
                        name = name,
                        description = description.take(200),
                        content = body.take(2000),
                        scope = if (isProject) MemoryScope.PROJECT else MemoryScope.GLOBAL
                    )
                }.getOrNull()
            }
            .filter { isValidName(it.name) }
            .take(MAX_CANDIDATES)
            .toList()
    }

    /** 与记忆文件名规则对齐：小写英文/数字/下划线/连字符，长度 1..64。 */
    private fun isValidName(name: String): Boolean =
        name.length <= 64 && name.matches(Regex("[a-z0-9][a-z0-9_-]*"))

    private data class Candidate(
        val name: String,
        val description: String,
        val content: String,
        val scope: MemoryScope
    )

    private companion object {
        val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")
    }
}
