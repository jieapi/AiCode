package com.aicode.feature.agent.domain.subagent

import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.AgentConfigKind
import com.aicode.feature.workspace.domain.AgentConfigParseCache
import com.aicode.feature.workspace.domain.AgentConfigSnapshot

/** 子代理定义来源：一个目录下的 `*.md`，每个文件一个 agent。 */
interface AgentDefinitionSource {
    fun listDefinitions(): List<AgentDefinition>
}

/** 目录扫描：只取顶层 `*.md`，避免把技能目录等无关内容误当 agent 定义。 */
internal object AgentDefinitionDirectoryScanner {
    private const val TAG = "AgentDefinitionScanner"
    private val parsedSnapshots = AgentConfigParseCache<List<AgentDefinition>>()

    /**
     * 扫描 [root] 下顶层 `*.md`，每个文件一个定义。目录不存在时返回空列表；
     * 单个文件解析异常（IO 失败、frontmatter 非法等）只跳过该文件，不拖垮整个目录；
     * 扫描过程整体异常（远程工作区未连接等）时降级为空列表并记日志，不向上抛。
     */
    fun scan(provider: FileAccessProvider, root: String): List<AgentDefinition> {
        val snapshot = provider.agentConfigSnapshot(root, AgentConfigKind.AGENTS)
        return if (snapshot != null) fromSnapshot(snapshot) else scanDirectory(provider, root)
    }

    fun fromSnapshot(snapshot: AgentConfigSnapshot): List<AgentDefinition> = parsedSnapshots.get(snapshot) { files ->
        files.mapNotNull { (path, text) ->
            runCatching {
                AgentDefinitionParser.parseText(text, path.substringAfterLast('/').substringBeforeLast('.'), path)
            }.onFailure { FileLogger.w(TAG, "解析子代理定义失败: $path", it) }.getOrNull()
        }.sortedBy { it.name.lowercase() }
    }

    private fun scanDirectory(provider: FileAccessProvider, root: String): List<AgentDefinition> = runCatching {
        if (!provider.isDirectory(root)) return@runCatching emptyList()
        val base = root.trimEnd('/')
        provider.listFiles(root)
            .filter { !it.isDirectory && it.name.endsWith(".md", ignoreCase = true) }
            .mapNotNull { entry ->
                runCatching { AgentDefinitionParser.parse(provider, "$base/${entry.name}") }
                    .onFailure { FileLogger.w(TAG, "解析子代理定义失败，已跳过: ${entry.name}", it) }
                    .getOrNull()
            }
            .sortedBy { it.name.lowercase() }
    }.getOrElse { e ->
        FileLogger.w(TAG, "扫描子代理定义目录失败: $root", e)
        emptyList()
    }
}
