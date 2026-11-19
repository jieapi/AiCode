package com.aicode.feature.workspace.domain

import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.container.ProjectConfigLocation
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 项目级 `.aicode` 配置目录的归属解析。
 *
 * - 本地工作区随目录走（`<workspacePath>/.aicode`），可被 AI 直接编辑、可 git 追踪；
 * - 远端或声明为 [ProjectConfigLocation.APP_PRIVATE_DIR] 的后端：宿主 [java.io.File] 无法在本地直接创建目录，
 *   故改存 App 私有目录 `filesDir/aicode/projects/<项目名-标识哈希>/`，标识由后端 [projectKey] 生成。
 *
 * 与项目级记忆 [com.aicode.feature.agent.domain.memory.ProjectMemorySource] 使用同一套标识算法。
 */
@Singleton
class ProjectAicodeRoot @Inject constructor(
    private val workspaceRepository: WorkspaceRepository,
    private val executionModeHolder: ExecutionModeHolder,
    private val containerInstaller: ContainerInstaller
) {
    /** 当前工作区的项目级配置目录。 */
    fun current(): File = forPath(workspaceRepository.currentPath())

    /** 指定工作区路径对应的项目级配置目录。 */
    fun forPath(workspacePath: String): File {
        val backend = executionModeHolder.currentBackend()
        return if (backend.capabilities.projectConfigLocation == ProjectConfigLocation.APP_PRIVATE_DIR) {
            File(File(containerInstaller.aicodeDir, PROJECTS_DIR), backend.projectConfigKey(workspacePath))
        } else {
            File(workspacePath, AICODE_DIR)
        }
    }

    /** 远程/私有项目级资源在私有目录下的标识：由当前执行后端按身份与路径生成。 */
    fun projectKey(workspacePath: String): String =
        executionModeHolder.currentBackend().projectConfigKey(workspacePath)

    private companion object {
        const val AICODE_DIR = ".aicode"
        const val PROJECTS_DIR = "projects"
    }
}
