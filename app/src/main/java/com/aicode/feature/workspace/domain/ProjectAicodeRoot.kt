package com.aicode.feature.workspace.domain

import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 项目级 `.aicode` 配置目录的归属解析。
 *
 * - 本地模式：配置随工作区走（`<workspacePath>/.aicode`），可被 AI 直接编辑、可 git 追踪；
 * - 远程模式：工作区在 SSH 服务器上，[java.io.File] 无法在本地创建该目录（写入必失败），
 *   故改存 App 私有目录 `filesDir/aicode/projects/<项目名-标识哈希>/`。标识基于
 *   「IP:端口:远程路径」，不同服务器上的同名路径不会互相覆盖。
 *
 * 与项目级记忆 [com.aicode.feature.agent.domain.memory.ProjectMemorySource] 使用同一套标识算法。
 */
@Singleton
class ProjectAicodeRoot @Inject constructor(
    private val workspaceRepository: WorkspaceRepository,
    private val executionModeHolder: ExecutionModeHolder,
    private val containerInstaller: ContainerInstaller,
    private val remoteSshConnection: RemoteSshConnection
) {
    /** 当前工作区的项目级配置目录。 */
    fun current(): File = forPath(workspaceRepository.currentPath())

    /** 指定工作区路径对应的项目级配置目录。 */
    fun forPath(workspacePath: String): File =
        if (isRemote()) {
            File(File(containerInstaller.aicodeDir, PROJECTS_DIR), projectKey(workspacePath))
        } else {
            File(workspacePath, AICODE_DIR)
        }

    /** 远程项目级资源在私有目录下的标识：项目名 + 「IP:端口:路径」哈希前 8 位。 */
    fun projectKey(workspacePath: String): String {
        val name = workspacePath.trimEnd('/').substringAfterLast('/').ifBlank { "default" }
        val cfg = remoteSshConnection.config
        val identity = if (cfg != null) "${cfg.host}:${cfg.port}:$workspacePath" else workspacePath
        val digest = MessageDigest.getInstance("MD5").digest(identity.toByteArray(Charsets.UTF_8))
        val hash = BigInteger(1, digest).toString(16).padStart(32, '0').take(8)
        return "$name-$hash"
    }

    private fun isRemote(): Boolean =
        executionModeHolder.currentMode() == ExecutionMode.REMOTE_SSH

    private companion object {
        const val AICODE_DIR = ".aicode"
        const val PROJECTS_DIR = "projects"
    }
}
