package com.aicode.feature.plugin.runtime

import com.aicode.feature.agent.domain.container.BoundedOutput
import com.aicode.feature.agent.domain.shizuku.ShizukuManager
import com.aicode.feature.agent.domain.shizuku.ShizukuState
import com.aicode.feature.plugin.data.PluginShizukuPermissionRepository
import com.aicode.feature.plugin.domain.PluginManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/** 插件调用 Shizuku 的失败原因；调用方转成页面桥/进程错误码。 */
class PluginShizukuException(val code: String, message: String) : Exception(message)

data class PluginShizukuResult(val output: String, val exitCode: Int, val truncated: Boolean)

/**
 * 插件侧的 Shizuku 执行入口：复用 [ShizukuManager] 的 UserService 后端，不复制连接逻辑。
 *
 * 授权模型：插件必须在清单声明用途，使用者单独授权后即可执行任意命令；
 * 每次调用仍检查插件启用、使用者授权与 Shizuku 已就绪。
 */
@Singleton
class PluginShizukuRunner @Inject constructor(
    private val pluginManager: PluginManager,
    private val shizukuManager: ShizukuManager,
    private val permissionRepository: PluginShizukuPermissionRepository
) {

    suspend fun exec(pluginId: String, command: String, timeoutSeconds: Long): PluginShizukuResult {
        pluginManager.ensureLoaded()
        val plugin = pluginManager.plugins.value.firstOrNull { it.id == pluginId && it.isActive }
            ?: throw PluginShizukuException("PLUGIN_INACTIVE", "plugin is not active: $pluginId")
        plugin.content.shizuku
            ?: throw PluginShizukuException("SHIZUKU_NOT_DECLARED", "plugin did not declare shizuku")
        val trimmed = command.trim()
        if (trimmed.isEmpty()) throw PluginShizukuException("PARAM_INVALID", "empty command")
        if (trimmed.length > MAX_COMMAND_CHARS) {
            throw PluginShizukuException("PARAM_INVALID", "command too long")
        }
        if (!permissionRepository.isGranted(pluginId)) {
            throw PluginShizukuException("SHIZUKU_NOT_AUTHORIZED", "user did not grant shizuku for this plugin")
        }
        val state = shizukuManager.state.value
        if (state != ShizukuState.READY) {
            throw PluginShizukuException("SHIZUKU_NOT_READY", "shizuku state: $state")
        }
        val timeoutMs = timeoutSeconds.coerceIn(1L, MAX_TIMEOUT_SECONDS) * 1000L
        return try {
            val result = shizukuManager.runCommand(trimmed, timeoutMs)
            val bounded = BoundedOutput().apply { append(result.output) }
            PluginShizukuResult(bounded.build(), result.exitCode, bounded.truncated)
        } catch (e: PluginShizukuException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw PluginShizukuException("SHIZUKU_FAILED", e.message ?: e.javaClass.simpleName)
        }
    }

    private companion object {
        const val MAX_COMMAND_CHARS = 4_096
        const val MAX_TIMEOUT_SECONDS = 1_800L
    }
}
