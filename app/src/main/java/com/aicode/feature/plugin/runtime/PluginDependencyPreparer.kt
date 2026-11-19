package com.aicode.feature.plugin.runtime

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.plugin.data.PluginPackageReader
import com.aicode.feature.plugin.domain.PluginRuntime
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** 依赖准备结果；UI 按类型展示状态与原因。 */
sealed interface PluginDependencyResult {
    /** 依赖已就绪；[hash] 是依赖输入文件的摘要，文件变化后需要重新准备。 */
    data class Ready(val hash: String) : PluginDependencyResult

    /** 容器里缺少 node/npm 或 python3/venv，引导用户去终端「环境安装」。 */
    data class MissingRuntime(val message: String) : PluginDependencyResult

    /** 安装命令失败，[message] 带截断后的输出。 */
    data class Failed(val message: String, val code: String? = null) : PluginDependencyResult
}

/**
 * 在本地容器准备插件运行时依赖。
 *
 * 依赖清单由宿主读取和裁剪，npm 只看到生产 dependencies；容器只执行固定的安装命令，
 * 不负责解析插件包里的 devDependencies，也不接收插件提供的 shell 片段。
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

        return when (dependency.tool) {
            "npm" -> prepareNpm(runtime)
            "pip" -> preparePip(runtime, dependency.file)
            else -> PluginDependencyResult.Failed("不支持的依赖工具: ${dependency.tool}")
        }
    }

    private suspend fun prepareNpm(runtime: PluginRuntime): PluginDependencyResult {
        val dependencyFile = runtime.dependencies?.file
            ?: return PluginDependencyResult.Failed("缺少 npm 依赖清单")
        val tempDir = File(runtime.installDir.parentFile, ".deps-${UUID.randomUUID()}")
        return try {
            val packageJson = productionPackageJson(dependencyFile)
            if (!tempDir.mkdirs()) throw IOException("无法创建 npm 依赖暂存目录")
            File(tempDir, "package.json").writeText(packageJson)
            val tempPath = shellQuote(installer.containerPathFor(tempDir))
            val command = "cd $tempPath && npm install --no-save --package-lock=false --no-audit --no-fund"
            FileLogger.i(TAG, "[${runtime.pluginId}] 准备依赖: npm ${dependencyFile.name}")
            val result = runCommand(command)
            if (result.exitCode != 0) {
                FileLogger.w(TAG, "[${runtime.pluginId}] 依赖安装失败 exitCode=${result.exitCode}")
                return PluginDependencyResult.Failed(tail(result.output))
            }
            val installed = File(tempDir, "node_modules")
            if (!installed.isDirectory) return PluginDependencyResult.Failed("npm 安装完成但未生成 node_modules")
            replaceNodeModules(runtime.installDir, installed)
            PluginDependencyResult.Ready(sha256(dependencyFile))
        } catch (e: Exception) {
            FileLogger.w(TAG, "[${runtime.pluginId}] 依赖准备失败", e)
            PluginDependencyResult.Failed("依赖安装失败: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            deleteTree(tempDir)
        }
    }

    private suspend fun preparePip(runtime: PluginRuntime, dependencyFile: File): PluginDependencyResult {
        val dir = shellQuote(installer.containerPathFor(runtime.installDir))
        val file = shellQuote(installer.containerPathFor(dependencyFile))
        val command = "cd $dir && python3 -m venv .venv && .venv/bin/pip install --disable-pip-version-check -r $file"
        FileLogger.i(TAG, "[${runtime.pluginId}] 准备依赖: pip ${dependencyFile.name}")
        val result = runCommand(command)
        if (result.exitCode != 0) {
            FileLogger.w(TAG, "[${runtime.pluginId}] 依赖安装失败 exitCode=${result.exitCode}")
            return PluginDependencyResult.Failed(tail(result.output))
        }
        return PluginDependencyResult.Ready(sha256(dependencyFile))
    }

    private fun productionPackageJson(file: File): String {
        val source = Json.parseToJsonElement(file.readText()).jsonObject
        val production = buildJsonObject {
            source["name"]?.let { put("name", it) }
            source["version"]?.let { put("version", it) }
            put("private", true)
            put("dependencies", source["dependencies"] ?: JsonObject(emptyMap()))
        }
        return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), production)
    }

    private suspend fun runCommand(command: String): CommandResult {
        return try {
            val result = engine.runCommandSyncWithExit(
                command,
                projectPath = null,
                timeoutMs = PREPARE_TIMEOUT_MS
            )
            CommandResult(result.exitCode ?: 1, result.output)
        } catch (error: Exception) {
            CommandResult(1, error.message ?: error.javaClass.simpleName)
        }
    }

    private data class CommandResult(val exitCode: Int, val output: String)

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

    private fun replaceNodeModules(pluginDir: File, source: File) {
        val target = File(pluginDir, "node_modules")
        deleteTree(target)
        source.copyRecursively(target, overwrite = true)
    }

    private fun deleteTree(file: File) {
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return
        Files.walkFileTree(file.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun sha256(file: File): String = PluginPackageReader.sha256Hex(file.readBytes())

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
