package com.aicode.feature.plugin.runtime

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.plugin.data.PluginPackageReader
import com.aicode.feature.plugin.domain.PluginRuntime
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/** 依赖准备结果；UI 按类型展示状态与原因。 */
sealed interface PluginDependencyResult {
    /** 依赖已就绪；[hash] 是依赖输入文件的摘要，文件变化后需要重新准备。 */
    data class Ready(val hash: String) : PluginDependencyResult

    /** 容器里缺少 node/npm 或 python3/venv，引导用户去终端「环境安装」。 */
    data class MissingRuntime(val message: String) : PluginDependencyResult

    /** 安装命令失败，[message] 带截断后的输出。 */
    data class Failed(val message: String) : PluginDependencyResult
}

/**
 * 在本地容器、插件自己的安装目录里准备依赖。
 *
 * 命令完全由宿主构造，不接受插件传入；插件目录在容器内可见（`.aicode` 绑定），
 * 所以 npm 的 `node_modules` 与 pip 的 `.venv` 都留在插件包目录里。
 */
@Singleton
class PluginDependencyPreparer @Inject constructor(
    private val installer: ContainerInstaller,
    private val engine: LinuxContainerEngine,
    private val containerSettingsRepository: ContainerSettingsRepository
) {

    suspend fun prepare(runtime: PluginRuntime): PluginDependencyResult {
        val dependency = runtime.dependencies
            ?: return PluginDependencyResult.Ready("")
        val profile = runtimeProfile()
        engine.notReadyHintFor(profile)?.let {
            return PluginDependencyResult.MissingRuntime(it)
        }

        val missing = if (dependency.tool == "npm") checkCommands("node", "npm")
        else checkCommands("python3")
        if (missing.isNotEmpty()) {
            return PluginDependencyResult.MissingRuntime(
                "容器缺少 ${missing.joinToString("、")}，请到终端执行「环境安装」后重试。"
            )
        }

        val dir = shellQuote(installer.containerPathFor(runtime.installDir))
        val file = shellQuote(installer.containerPathFor(dependency.file))
        val command = when (dependency.tool) {
            "npm" -> "cd $dir && if [ -f package-lock.json ]; then npm ci; else npm install --no-audit --no-fund; fi"
            "pip" -> "cd $dir && python3 -m venv .venv && .venv/bin/pip install --disable-pip-version-check -r $file"
            else -> return PluginDependencyResult.Failed("不支持的依赖工具: ${dependency.tool}")
        }

        FileLogger.i(TAG, "[${runtime.pluginId}] 准备依赖: ${dependency.tool} ${dependency.file.name}")
        val result = runCatching {
            engine.runCommandSyncWithExit(command, projectPath = null, timeoutMs = PREPARE_TIMEOUT_MS)
        }.getOrElse { error ->
            return PluginDependencyResult.Failed("依赖安装失败: ${error.message ?: error.javaClass.simpleName}")
        }

        if (result.exitCode != 0) {
            FileLogger.w(TAG, "[${runtime.pluginId}] 依赖安装失败 exitCode=${result.exitCode}")
            return PluginDependencyResult.Failed(tail(result.output))
        }
        val hash = sha256(dependency.file)
        FileLogger.i(TAG, "[${runtime.pluginId}] 依赖准备完成")
        return PluginDependencyResult.Ready(hash)
    }

    private suspend fun checkCommands(vararg commands: String): List<String> {
        val missing = mutableListOf<String>()
        commands.forEach { name ->
            val result = runCatching {
                engine.runCommandSyncWithExit(
                    "command -v ${shellQuote(name)}",
                    projectPath = null,
                    timeoutMs = CHECK_TIMEOUT_MS
                )
            }.getOrNull()
            if (result == null || result.exitCode != 0) missing += name
        }
        return missing
    }

    private fun sha256(file: java.io.File): String =
        PluginPackageReader.sha256Hex(file.readBytes())

    private fun tail(output: String): String {
        val lines = output.trim().lines().takeLast(MAX_TAIL_LINES).joinToString("\n")
        return lines.take(MAX_TAIL_CHARS).ifBlank { "依赖安装失败，未产生输出" }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private suspend fun runtimeProfile(): ContainerProfile {
        val id = containerSettingsRepository.defaultContainerIdFlow.first()
        val profiles = containerSettingsRepository.customProfilesFlow.first()
        return profiles.firstOrNull { it.id == id && it.mode == ExecutionMode.LOCAL_PROOT }
            ?: ContainerProfile.BUILTIN_ALPINE
    }

    private companion object {
        const val TAG = "PluginDeps"
        const val PREPARE_TIMEOUT_MS = 10 * 60_000L
        const val CHECK_TIMEOUT_MS = 30_000L
        const val MAX_TAIL_LINES = 20
        const val MAX_TAIL_CHARS = 2_000
    }
}
