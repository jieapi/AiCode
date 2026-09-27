package com.aicode.feature.agent.domain.memory

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.memory.MemorySource
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
            messages = listOf(AgentMessage.UserMessage(content = transcript.takeLast(MAX_TRANSCRIPT_CHARS))),
            tools = emptyList()
        )
        val candidates = parseCandidates(response.content)
        var saved = 0
        for (c in candidates) {
            // 已有同名记忆不覆盖：主模型当轮写的完整版本不该被 curator 的截断版覆盖。
            val existing = memoryRepository.loadContent(c.name, projectRoot)
            if (existing != null) {
                FileLogger.i(TAG, "记忆「${c.name}」已存在，跳过自动覆盖")
                continue
            }
            // PROJECT 但无工作区时降级为 GLOBAL（静默场景下降级比丢弃合理）。
            val effectiveScope = if (c.scope == MemoryScope.PROJECT && projectRoot.isNullOrBlank()) {
                MemoryScope.GLOBAL
            } else {
                c.scope
            }
            val ok = memoryRepository.saveMemory(
                name = c.name,
                description = c.description,
                content = c.content,
                scope = effectiveScope,
                projectRoot = projectRoot
            )
            if (ok) saved++
        }
        if (saved > 0) {
            FileLogger.i(TAG, "会话 $sessionId 自动沉淀 $saved 条记忆")
        }
        saved
    }.onFailure { e ->
        FileLogger.w(TAG, "自动记忆整理失败（静默忽略）: ${e.message}", e)
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
            .filter { MemorySource.sanitizeName(it.name).isNotEmpty() }
            .take(MAX_CANDIDATES)
            .toList()
    }

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
