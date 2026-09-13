package com.aicode.feature.agent.domain.subagent

import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 项目级子代理定义来源：`<projectRoot>/.aicode/agents/`，随工作区走，可 git 追踪。
 * 以容器路径经 [FileAccessProvider] 访问：本地映射到宿主工作区，远程经 SSH 落到远程工作区。
 */
@Singleton
class ProjectDirectoryAgentSource @Inject constructor(
    private val fileAccess: FileAccessProvider
) : AgentDefinitionSource {

    val agentsRoot: String = "${WorkspacePathMapper.CONTAINER_ROOT}/.aicode/agents"

    override fun listDefinitions(): List<AgentDefinition> =
        AgentDefinitionDirectoryScanner.scan(fileAccess, agentsRoot)
}
