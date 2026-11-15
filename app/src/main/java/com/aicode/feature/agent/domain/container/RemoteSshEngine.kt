package com.aicode.feature.agent.domain.container

import com.aicode.core.util.BoundedLineReader
import com.aicode.core.util.FileLogger
import com.aicode.core.util.LINE_TRUNCATED_NOTE
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.tool.explorer.shellQuote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

private const val TAG = "RemoteSshEngine"

/**
 * [CommandEngine] 的远程 SSH 实现：用 sshj exec channel 在远程服务器上执行命令。
 *
 * 共享一个 [RemoteSshConnection]（持有 sshj [SSHClient]）执行命令；文件读写由 [RemoteSftpFileAccess]
 * 走另一条独立的 SFTP transport（见 [RemoteSshConnection.sftp]），两者隔离互不影响。
 *
 * 与 [LinuxContainerEngine] 的语义对应：
 * - [ensureInstalled]：建立/维持 SSH 连接（对应本地解压 rootfs）；
 * - [isContainerInstalled]：SSH 连接是否存活（对应本地 rootfs 是否就绪）；
 * - [isProvisioned]：恒 true（远程工具由用户自行保证，对应本地 apk 装包完成）；
 * - [defaultShell]：/bin/bash（远程服务器通常有 bash）。
 */
class RemoteSshEngine @Inject constructor(
    private val connection: RemoteSshConnection
) : CommandEngine {

    private val _initProgress = MutableStateFlow<ContainerInitState>(ContainerInitState.Idle)
    override val initProgress: StateFlow<ContainerInitState> = _initProgress.asStateFlow()

    private val connectMutex = Mutex()

    override fun runCommandStream(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): Flow<CommandEvent> = flow {
        ensureInstalled()
        emitAll(streamExec(command, projectPath, timeoutMs))
    }.flowOn(Dispatchers.IO)

    private fun streamExec(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): Flow<CommandEvent> = flow {
        val effectiveTimeout = timeoutMs.coerceIn(1L, CommandEngine.MAX_TIMEOUT_MS)
        FileLogger.d(TAG, "执行命令(远程流式) cwd=$projectPath timeout=${effectiveTimeout}ms: ${sanitizeCommandForLog(command)}")
        val session = connection.startExecSession(buildCdCommand(command, projectPath))
        val timedOut = AtomicBoolean(false)
        val watchScope = CoroutineScope(Dispatchers.IO + Job())
        val watchdog = watchScope.launch {
            delay(effectiveTimeout)
            if (session.isOpen) {
                timedOut.set(true)
                FileLogger.w(TAG, "命令超时(${effectiveTimeout}ms)已终止: ${sanitizeCommandForLog(command)}")
                runCatching { session.close() }
            }
        }
        val cancellationHook = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException && session.isOpen) {
                FileLogger.i(TAG, "命令被取消，关闭 session: ${sanitizeCommandForLog(command)}")
                runCatching { session.close() }
            }
        }
        val reader = BoundedLineReader(InputStreamReader(session.inputStream))
        try {
            while (true) {
                val line = reader.readLine() ?: break
                emit(CommandEvent.Line(if (line.truncated) "${line.text}\n$LINE_TRUNCATED_NOTE" else line.text))
            }
            val exitCode = session.exitStatus
            watchdog.cancel()
            if (timedOut.get()) {
                emit(CommandEvent.Line("[命令执行超时：超过 ${effectiveTimeout}ms 已被强制终止]"))
                emit(CommandEvent.Exit(null))
            } else {
                if (exitCode != 0) FileLogger.w(TAG, "命令退出码=$exitCode: ${sanitizeCommandForLog(command)}")
                else FileLogger.v(TAG, "命令完成(退出码 0): ${sanitizeCommandForLog(command)}")
                emit(CommandEvent.Exit(exitCode))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            watchdog.cancel()
            if (timedOut.get()) {
                emit(CommandEvent.Line("[命令执行超时：超过 ${effectiveTimeout}ms 已被强制终止]"))
                emit(CommandEvent.Exit(null))
            } else {
                FileLogger.e(TAG, "命令读输出异常(已保留此前输出): ${sanitizeCommandForLog(command)}", e)
                emit(CommandEvent.Line("[命令执行异常：${e.message}]"))
                emit(CommandEvent.Exit(null))
            }
        } finally {
            cancellationHook?.dispose()
            watchdog.cancel()
            watchScope.cancel()
            runCatching { reader.close() }
            runCatching { session.close() }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun runCommandSync(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): String = withContext(Dispatchers.IO) {
        ensureInstalled()
        execCaptured(command, projectPath, timeoutMs).output
    }

    override suspend fun runCommandSyncWithExit(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult = withContext(Dispatchers.IO) {
        ensureInstalled()
        execCaptured(command, projectPath, timeoutMs)
    }

    override suspend fun runCommandSyncIfReady(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult? {
        if (!isContainerInstalled()) return null
        return runCatching { execCaptured(command, projectPath, timeoutMs) }
            .getOrElse {
                FileLogger.w(TAG, "远程命令执行失败(连接可能已断): ${sanitizeCommandForLog(command)}", it)
                null
            }
    }

    override suspend fun runCommandSyncUnbounded(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult = withContext(Dispatchers.IO) {
        ensureInstalled()
        execCaptured(command, projectPath, timeoutMs, unbounded = true)
    }

    private suspend fun execCaptured(
        command: String,
        projectPath: String?,
        timeoutMs: Long,
        unbounded: Boolean = false
    ): CommandResult = withContext(Dispatchers.IO) {
        val effectiveTimeout = timeoutMs.coerceIn(1L, CommandEngine.MAX_TIMEOUT_MS)
        FileLogger.d(TAG, "执行命令(远程同步) cwd=$projectPath timeout=${effectiveTimeout}ms: ${sanitizeCommandForLog(command)}")
        val session = connection.startExecSession(buildCdCommand(command, projectPath))
        val output = if (unbounded) BoundedOutput.hardCapped(MAX_UNBOUNDED_CHARS) else BoundedOutput()
        var exitCode: Int? = null
        try {
            coroutineScope {
                val watchdog = launch {
                    delay(effectiveTimeout)
                    if (session.isOpen) {
                        FileLogger.w(TAG, "命令超时(${effectiveTimeout}ms)已终止: ${sanitizeCommandForLog(command)}")
                        runCatching { session.close() }
                    }
                }
                val reader = BoundedLineReader(InputStreamReader(session.inputStream))
                try {
                    while (true) {
                        val line = reader.readLine() ?: break
                        output.append(if (line.truncated) "${line.text}\n$LINE_TRUNCATED_NOTE" else line.text)
                        output.append("\n")
                    }
                } finally {
                    watchdog.cancel()
                    runCatching { reader.close() }
                }
                // 并发读 stderr 合并进 output：本地引擎 redirectErrorStream(true) 是合并语义，
                // 远程若不合并，命令报错（如 rg 未安装时的 command not found）只写 stderr 会被静默丢弃。
                val stderrJob = launch {
                    val errReader = BoundedLineReader(InputStreamReader(session.errorStream))
                    try {
                        while (true) {
                            val errLine = errReader.readLine() ?: break
                            output.append(if (errLine.truncated) "${errLine.text}\n$LINE_TRUNCATED_NOTE" else errLine.text)
                            output.append("\n")
                        }
                    } finally {
                        runCatching { errReader.close() }
                    }
                }
                stderrJob.join()
                // sshj 的 exitStatus 在流 EOF 后未必就绪，close 后才保证有值
                runCatching { session.close() }
                exitCode = session.exitStatus
            }
        } finally {
            runCatching { session.close() }
        }
        FileLogger.v(TAG, "命令完成(远程, 退出码 $exitCode，输出 ${output.totalChars} 字符): ${sanitizeCommandForLog(command)}")
        CommandResult(output.build(), exitCode, output.truncated)
    }

    override fun isContainerInstalled(): Boolean = connection.isConnected()

    override fun isProvisioned(): Boolean = true

    override fun defaultShell(): String = "/bin/bash"

    override suspend fun ensureInstalled() = connectMutex.withLock {
        if (connection.isConnected()) {
            _initProgress.value = ContainerInitState.Ready
            return@withLock
        }
        _initProgress.value = ContainerInitState.InstallingPackages(line = "正在连接 SSH 服务器…")
        try {
            connection.connect()
            _initProgress.value = ContainerInitState.Ready
        } catch (e: Exception) {
            FileLogger.e(TAG, "SSH 连接失败", e)
            val friendly = friendlySshError(e)
            _initProgress.value = ContainerInitState.Failed(friendly)
            throw RuntimeException(friendly, e)
        }
    }

    /** 拼接 cd 到 projectPath 再执行 command 的完整命令；projectPath 为 null 则直接执行。
     *  优先 cd 到 ~/workspace（符号链接），让 AI 执行 pwd 时看到 ~/workspace 而非真实路径。
     *  ~/workspace 不存在（符号链接未建成）时 fallback 到 projectPath。 */
    private fun buildCdCommand(command: String, projectPath: String?): String {
        val prefix = gitCredentialEnvPrefix()
        if (projectPath == null) return prefix + command
        return prefix + "cd ~/workspace 2>/dev/null || cd ${shellQuote(projectPath)} 2>/dev/null; $command"
    }

    /**
     * 凭证经 GIT_CONFIG_* 环境变量按命令注入，includeIf 限定到工作区根。
     *
     * 不用 GIT_CONFIG_GLOBAL 接管全局配置：那会把 App 生成的文件当成唯一全局配置，
     * `git config --global`（署名、代理等）也写进该文件，注入时整份重写就会丢失；
     * 同时读不到 ~/.config/git/config 与系统级配置。环境变量只影响当前 git 进程，
     * 用户自己的全局配置照常生效。未注入时返回空串，命令完全走用户自己的 git 配置。
     */
    private fun gitCredentialEnvPrefix(): String {
        val wsRoot = connection.gitCredentialWorkspaceRoot?.trimEnd('/') ?: return ""
        val key = shellQuote("includeIf.gitdir:$wsRoot/.path")
        return "export GIT_CONFIG_COUNT=1; " +
            "export GIT_CONFIG_KEY_0=$key; " +
            "export GIT_CONFIG_VALUE_0=\"\${HOME}/.aicode/gitconfig.credential\"; "
    }
}
