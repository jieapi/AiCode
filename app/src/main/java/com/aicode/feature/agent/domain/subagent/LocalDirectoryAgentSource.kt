package com.aicode.feature.agent.domain.subagent

import com.aicode.feature.workspace.domain.LocalFileAccess
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全局子代理定义来源：`filesDir/aicode/agents`（容器内 `/root/.aicode/agents`），跨项目、跨升级保留。
 * 始终落在 App 私有目录，与本机执行模式无关，故固定走本地文件访问。
 */
@Singleton
class LocalDirectoryAgentSource @Inject constructor(
    private val localFileAccess: LocalFileAccess
) : AgentDefinitionSource {

    val agentsRoot: String = "${WorkspacePathMapper.AICODE_ROOT}/agents"

    override fun listDefinitions(): List<AgentDefinition> =
        AgentDefinitionDirectoryScanner.scan(localFileAccess, agentsRoot)
}
