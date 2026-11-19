package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import com.aicode.feature.agent.domain.container.ExecutionBackendRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.executionModeDataStore by preferencesDataStore(
    name = "execution_mode_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/** 执行环境模式。 */
enum class ExecutionMode {
    /** 本地 PRoot 容器（原有行为）。 */
    LOCAL_PROOT,
    /** 远程 SSH 服务器。 */
    REMOTE_SSH
}

/**
 * 持久化当前执行模式与激活的执行后端 ID。
 *
 * 兼容旧版 [MODE_KEY]：未写入 [ACTIVE_BACKEND_ID_KEY] 时，按 `LOCAL_PROOT` -> `"builtin:local-proot"`、
 * `REMOTE_SSH` -> `"builtin:remote-ssh"` 映射。
 */
@Singleton
class ExecutionModeRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val MODE_KEY = stringPreferencesKey("execution_mode")
        val ACTIVE_BACKEND_ID_KEY = stringPreferencesKey("active_backend_id")
    }

    /** 当前执行模式；无值时默认本地 PRoot。 */
    val executionModeFlow: Flow<ExecutionMode> = context.executionModeDataStore.data.map { prefs ->
        val backendId = prefs[ACTIVE_BACKEND_ID_KEY]?.takeIf { it.isNotBlank() }
        when {
            backendId == ExecutionBackendRegistry.BUILTIN_REMOTE_SSH_ID -> ExecutionMode.REMOTE_SSH
            backendId != null -> ExecutionMode.LOCAL_PROOT
            else -> prefs[MODE_KEY]?.let {
                runCatching { ExecutionMode.valueOf(it) }.getOrNull()
            } ?: ExecutionMode.LOCAL_PROOT
        }
    }

    /** 当前激活的执行后端 ID；兼容旧 [MODE_KEY]，缺省返回 `"builtin:local-proot"`。 */
    val activeBackendIdFlow: Flow<String> = context.executionModeDataStore.data.map { prefs ->
        val storedBackendId = prefs[ACTIVE_BACKEND_ID_KEY]?.trim().orEmpty()
        if (storedBackendId.isNotEmpty()) {
            storedBackendId
        } else {
            when (prefs[MODE_KEY]?.let { runCatching { ExecutionMode.valueOf(it) }.getOrNull() }) {
                ExecutionMode.REMOTE_SSH -> ExecutionBackendRegistry.BUILTIN_REMOTE_SSH_ID
                ExecutionMode.LOCAL_PROOT, null -> ExecutionBackendRegistry.BUILTIN_LOCAL_PROOT_ID
            }
        }
    }

    suspend fun setExecutionMode(mode: ExecutionMode) {
        val backendId = when (mode) {
            ExecutionMode.LOCAL_PROOT -> ExecutionBackendRegistry.BUILTIN_LOCAL_PROOT_ID
            ExecutionMode.REMOTE_SSH -> ExecutionBackendRegistry.BUILTIN_REMOTE_SSH_ID
        }
        context.executionModeDataStore.edit {
            it[MODE_KEY] = mode.name
            it[ACTIVE_BACKEND_ID_KEY] = backendId
        }
    }

    suspend fun setActiveBackendId(id: String) {
        val trimmed = id.trim().ifEmpty { ExecutionBackendRegistry.BUILTIN_LOCAL_PROOT_ID }
        val mappedMode = if (trimmed == ExecutionBackendRegistry.BUILTIN_REMOTE_SSH_ID) {
            ExecutionMode.REMOTE_SSH
        } else {
            ExecutionMode.LOCAL_PROOT
        }
        context.executionModeDataStore.edit {
            it[ACTIVE_BACKEND_ID_KEY] = trimmed
            it[MODE_KEY] = mappedMode.name
        }
    }
}

/**
 * 归一化远程工作区根路径。旧版把默认值硬编码为 `/home/<用户名>/workspace`（对 home 不在
 * `/home/<用户名>` 的用户，如 root 的 `/root`，是错的）；`~/workspace` 则是符号链接占用的路径，
 * 不能当工作区根（会与链接相撞形成自引用）。这两类残留值与空值一律改回默认值。
 */
internal fun normalizeRemoteWorkspacePath(stored: String?, username: String): String {
    val v = stored?.trim().orEmpty()
    return if (v.isEmpty() || v == "/home/$username/workspace" || v == "~/workspace") {
        DEFAULT_REMOTE_WORKSPACE_ROOT
    } else {
        v
    }
}

/**
 * 远程工作区根目录的默认值。**不能用 `~/workspace`**——那是「当前工作区」符号链接占用的路径，
 * 同名会让根目录与链接相撞。
 */
internal const val DEFAULT_REMOTE_WORKSPACE_ROOT = "~/.aicode/workspaces"
