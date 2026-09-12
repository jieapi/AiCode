package com.aicode.feature.agent.domain.subagent

import com.aicode.feature.workspace.domain.FileAccessProvider

/** 子代理定义来源：一个目录下的 `*.md`，每个文件一个 agent。 */
interface AgentDefinitionSource {
    fun listDefinitions(): List<AgentDefinition>
}

/** 目录扫描：只取顶层 `*.md`，避免把技能目录等无关内容误当 agent 定义。 */
internal object AgentDefinitionDirectoryScanner {
    fun scan(provider: FileAccessProvider, root: String): List<AgentDefinition> {
        if (!provider.isDirectory(root)) return emptyList()
        val base = root.trimEnd('/')
        return provider.listFiles(root)
            .filter { !it.isDirectory && it.name.endsWith(".md", ignoreCase = true) }
            .mapNotNull { entry -> AgentDefinitionParser.parse(provider, "$base/${entry.name}") }
            .sortedBy { it.name.lowercase() }
    }
}
