package com.aicode.feature.plugin.presentation

import com.aicode.feature.agent.domain.container.ExecutionBackendRegistry
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.plugin.domain.PluginFileWatchSource
import com.aicode.feature.plugin.domain.PluginHostPaths
import com.aicode.feature.plugin.domain.PluginWatchTarget
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.PathHomeResolver
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Singleton
class PluginPageEnvironment @Inject constructor(
    private val workspaceRepository: WorkspaceRepository,
    private val modeHolder: ExecutionModeHolder,
    private val connection: RemoteSshConnection,
    private val pathMapper: WorkspacePathMapper,
    private val homeResolver: PathHomeResolver,
    private val watchSource: PluginFileWatchSource
) {
    val changes: Flow<String> = combine(
        workspaceRepository.current,
        modeHolder.activeBackend,
        connection.connectionState
    ) { _, _, _ -> contextKey() }.distinctUntilChanged()

    fun contextKey(): String {
        val backend = modeHolder.currentBackend()
        val identity = listOf(
            backend.id,
            backend.environmentCacheKey(),
            workspaceRepository.currentPath()
        ).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun workspaceName(): String = workspaceRepository.currentPath()
        .trimEnd('/')
        .substringAfterLast('/')
        .ifBlank { "workspace" }

    /**
     * 当前工作区在插件可见路径空间里的根：远程直接用远端绝对路径，本地容器用 `$HOME/workspace`，
     * 其它后端使用 [com.aicode.feature.agent.domain.container.ExecutionBackend.workspaceRootDisplay]。
     * 本地 `currentPath()` 是宿主路径，不能直接给插件。
     */
    fun workspaceRoot(): String {
        val backend = modeHolder.currentBackend()
        val currentPath = workspaceRepository.currentPath()
        return when {
            backend.capabilities.isRemoteEnvironment ->
                currentPath.ifBlank { backend.workspaceRootDisplay(currentPath) }
            backend.id == ExecutionBackendRegistry.BUILTIN_LOCAL_PROOT_ID ->
                homeResolver.home().trimEnd('/') + "/workspace"
            else ->
                backend.workspaceRootDisplay(currentPath).ifBlank {
                    homeResolver.home().trimEnd('/') + "/workspace"
                }
        }
    }

    fun watch(paths: PluginHostPaths, path: String, recursive: Boolean): Flow<JsonObject> {
        val key = contextKey()
        val isRemote = modeHolder.capabilities().isRemoteEnvironment
        val target = paths.watchTarget(
            path = path,
            localFile = { containerPath ->
                if (!isRemote) {
                    pathMapper.resolveFile(containerPath)
                } else {
                    null
                }
            },
            recursive = recursive
        )
        val root = when (target) {
            is PluginWatchTarget.Local -> target.file.absolutePath
            is PluginWatchTarget.Remote -> target.path
        }.trimEnd('/')
        val logicalRoot = path.trimEnd('/')
        return watchSource.watch(target, recursive).map { batch ->
            val changes = batch.changes.mapNotNull { change ->
                val actual = change.hostPath.trimEnd('/')
                val relative = when {
                    actual == root -> ""
                    actual.startsWith("$root/") -> actual.removePrefix("$root/")
                    else -> return@mapNotNull null
                }
                buildJsonObject {
                    put("path", if (relative.isEmpty()) logicalRoot else "$logicalRoot/$relative")
                    put("kind", change.kind.name.lowercase())
                }
            }
            buildJsonObject {
                put("contextKey", key)
                put("changes", JsonArray(changes))
                put("truncated", batch.truncated)
            }
        }
    }
}
