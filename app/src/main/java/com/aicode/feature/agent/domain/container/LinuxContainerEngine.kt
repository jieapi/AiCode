package com.aicode.feature.agent.domain.container

import com.aicode.core.util.FileLogger
import com.aicode.R
import com.aicode.feature.settings.data.repository.ExecutionMode
import dagger.hilt.android.qualifiers.ApplicationContext
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地 PRoot 容器命令执行后端，实现 [CommandEngine]。
 *
 * 原有逻辑零变化——仅加 `: CommandEngine` 并给公开方法补 `override`。
 * [CommandEvent] 与 [CommandResult] 已提升为顶层类型（见 [CommandEngine.kt]），
 * 本类不再自行定义。PRoot 专属方法（[startStdioProcess]/[buildProotInvocation]/
 * [incPromptInFlight] 等）不属于接口，仅供本地 MCP stdio / 凭据 helper / 终端 PTY 使用。
 */
/**
 * 一次 PRoot 调用的完整描述：可执行文件 + 参数列表 + 环境变量。
 *
 * [argv] 的第 0 个元素即 proot 二进制路径，其余为参数。
 *
 * 两种消费方都要拿到「完整 argv」：
 *  - [ProcessBuilder] 直接接收 argv 列表；
 *  - Termux TerminalSession 的 cmd 仅用于 execvp 查找可执行文件，真正的 argv 由它的
 *    args 参数原样构成（native execvp(cmd, argv)，不会自动补 argv[0]），故 args 必须
 *    也是「含 argv[0]=proot 二进制」的完整 argv，否则选项整体错位一位、proot 会把
 *    rootfs 路径误当客户机程序（"is not a regular file"）。[executable] 只用于前者的 cmd 槽位。
 */
data class ProotInvocation(
    val argv: List<String>,
    val env: Map<String, String>
) {
    val executable: String get() = argv.first()

    /**
     * 供 Termux [com.termux.terminal.TerminalSession] 使用的完整环境数组（"KEY=VALUE"）。
     *
     * [ProcessBuilder] 的 environment() 初始即为父进程（App）环境的副本，再叠加 [env]，
     * 因此 proot 能拿到 ANDROID_ROOT/ANDROID_DATA/LD_LIBRARY_PATH 等系统变量；但
     * TerminalSession 接收的是「完整、不继承父进程」的环境数组，只喂 [env] 会让 proot
     * 这个动态链接的 Android 可执行文件因缺系统环境而 exec 失败、瞬间退出（终端表现为
     * 「会话已结束」而日志无其他报错）。故在此显式合并父进程环境，复刻 ProcessBuilder 语义。
     */
    val ptyEnvArray: Array<String>
        get() = (System.getenv() + env).map { "${it.key}=${it.value}" }.toTypedArray()
}

@Singleton
class LinuxContainerEngine @Inject constructor(
    @param:ApplicationContext private val context: android.content.Context,
    private val containerInstaller: ContainerInstaller,
    private val containerOsDetector: ContainerOsDetector,
    private val containerSettingsRepository: com.aicode.feature.settings.data.repository.ContainerSettingsRepository,
    private val pathHomeResolver: com.aicode.feature.workspace.domain.PathHomeResolver
) : CommandEngine {
    /** 容器初始化的实时进度，供所有入口（终端页/AI/后台终端/MCP）共享同一份状态。 */
    private val _initProgress = MutableStateFlow<ContainerInitState>(ContainerInitState.Idle)
    override val initProgress: StateFlow<ContainerInitState> = _initProgress.asStateFlow()

    /** 串行化容器初始化（含后台 initScope 内的任务创建与执行），避免多入口并发重复解压/配置。 */
    private val initMutex = Mutex()

    /**
     * 容器初始化的独立协程作用域：不随任何页面/调用方取消而中断，
     * 保证退出终端页后初始化仍在后台继续，下次进入可复用或等待其完成。
     */
    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 当前在途的初始化 job（受 [initMutex] 保护），完成后置 null。 */
    private var initJob: Job? = null

    /**
     * 当前选中的 profile（缓存自 [containerSettingsRepository.activeProfileIdFlow]，避免同步读 DataStore）。
     * 启动首帧为内置 Alpine（等同改动前）；profile 切换后由 flow collector 更新。fallback 到内置保证安全。
     */
    @Volatile
    private var currentProfile: ContainerProfile = ContainerProfile.BUILTIN_ALPINE

    init {
        CoroutineScope(Dispatchers.IO).launch {
            containerSettingsRepository.activeProfileIdFlow.collect { id ->
                currentProfile = resolveProfile(id)
            }
        }
    }

    /**
     * 按 id 解析 profile：从持久化列表找，找不到回退列表第一个，再兜底内置 Alpine（保证引擎始终可用）。
     * 列表里持久化的 Alpine 覆盖项优先于 [ContainerProfile.BUILTIN_ALPINE] 常量（用户编辑过的配置生效）。
     */
    private suspend fun resolveProfile(id: String): ContainerProfile {
        val profiles = containerSettingsRepository.customProfilesFlow.first()
        return profiles.firstOrNull { it.id == id }
            ?: profiles.firstOrNull()
            ?: ContainerProfile.BUILTIN_ALPINE
    }

    /**
     * 当前正在途的凭据请求计数（自定义 credential helper 阻塞等 app 弹窗回填的对数）。
     *
     * 由 [com.aicode.feature.credentials.data.CredentialRequestBridge] 在收到 helper 的 cred-req 时 inc、
     * 写回 cred-resp 时 dec。[launchKillWatchdog] 据此放宽超时——helper 在途时用户的 git 命令是「正等
     * 用户填凭据」而非「卡死」，watchdog 不应按常规超时强杀，详见其改造注释。
     */
    private val credentialPromptInFlight = java.util.concurrent.atomic.AtomicInteger(0)

    /** 凭据请求进入途（bridge 收到 helper 的 cred-req 时调）。 */
    fun incPromptInFlight() { credentialPromptInFlight.incrementAndGet() }

    /** 凭据请求结束途（bridge 写回 cred-resp 时调）。 */
    fun decPromptInFlight() { credentialPromptInFlight.decrementAndGet() }

    companion object {
        private const val TAG = "LinuxContainerEngine"

        /** 命令默认超时（毫秒）：未显式指定时套用，避免命令卡死时永久占用会话。 */
        const val DEFAULT_TIMEOUT_MS = 120_000L

        /** 命令超时上限（毫秒）：再大的请求也会被钳到此值，防止事实上的“无限等待”。 */
        const val MAX_TIMEOUT_MS = 1_800_000L

        /** 超时后给进程的优雅退出宽限（毫秒），过后强杀。 */
        private const val TIMEOUT_KILL_GRACE_MS = 200L

        /**
         * 基础包配置版本。对应 assets/aicode/provision.sh 的版本：改脚本（包清单/安装逻辑/镜像源）时同步 +1，
         * 触发在设备上重新执行该脚本（apk add 幂等，已装包跳过）。
         * 独立于 [ContainerInstaller] 的 rootfs INSTALL_VERSION：rootfs 版本升级会删 rootfs
         * （连带清掉本标记），故新 rootfs 必然重跑配置；同 rootfs 下改脚本则靠本版本号触发。
         * 标记文件由脚本自身写入（内容为下方版本号，或自定义镜像用户选择手动安装时的跳过标记
         * provision-script-skipped），App 端仅读它判断是否完成。
         */
        private const val PROVISION_VERSION = "provision-script-v9"
    }

    /** 标记基础包已按 [PROVISION_VERSION] 配置完成（内容为版本号，或用户手动安装的跳过标记），按当前 profile 的 rootfs 目录存放（内置/自定义各自独立）。 */
    private fun provisionMarker(profile: ContainerProfile): java.io.File =
        java.io.File(containerInstaller.rootfsDirFor(profile), ".provisioned")

    /**
     * 在容器内流式执行命令：每读到一行就 emit 一个 [CommandEvent.Line]，命令结束 emit
     * [CommandEvent.Exit]。首次调用会触发 rootfs 安装（幂等）。
     *
     * 与 [runCommandSync] 共用同一进程构建逻辑，区别仅在于输出按行实时下发，
     * 让终端能看到执行「过程」而非只有最终结果。
     *
     * [timeoutMs] 为命令最长执行时间（毫秒），默认 [DEFAULT_TIMEOUT_MS]，上限 [MAX_TIMEOUT_MS]。
     * 超时后强制终止子进程并在末尾追加一行超时提示，[CommandEvent.Exit] 退出码记为 null。
     * 由于 readLine 是阻塞读，单靠协程超时无法打断，这里用独立看门狗 destroy 进程来解除阻塞。
     */
    override fun runCommandStream(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): Flow<CommandEvent> = flow {
        // 未就绪（rootfs 未解压或基础包未配置）时不自动初始化，直接提示用户去终端页完成初始化。
        notReadyHint()?.let {
            emit(CommandEvent.Line(it))
            emit(CommandEvent.Exit(null))
            return@flow
        }
        emitAll(streamExecNoInstall(command, projectPath, timeoutMs))
    }.flowOn(Dispatchers.IO)
    /**
     * 在容器内流式执行命令的「裸」实现：每读到一行就 emit [CommandEvent.Line]，命令结束 emit
     * [CommandEvent.Exit]。**不触发懒安装**（不调 [ensureInstalled]），假定 rootfs 已就绪。
     *
     * 抽出此方法供 [runCommandStream]（先 [ensureInstalled]）与初始化脚本执行（先
     * [ContainerInstaller.installRootfsIfNeed]，不能再触发 ensureInstalled 否则递归）共用，
     * 让初始化也能逐行拿到输出以更新进度。
     *
     * [timeoutMs] 为命令最长执行时间（毫秒），默认 [DEFAULT_TIMEOUT_MS]，上限 [MAX_TIMEOUT_MS]。
     * 超时后强制终止子进程并在末尾追加一行超时提示，[CommandEvent.Exit] 退出码记为 null。
     * 由于 readLine 是阻塞读，单靠协程超时无法打断，这里用独立看门狗 destroy 进程来解除阻塞。
     */
    private fun streamExecNoInstall(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): Flow<CommandEvent> = flow {
        val effectiveTimeout = timeoutMs.coerceIn(1L, MAX_TIMEOUT_MS)
        FileLogger.d(TAG, "执行命令(流式) cwd=$projectPath timeout=${effectiveTimeout}ms: $command")
        val process = startContainerProcess(command, projectPath)
        val timedOut = AtomicBoolean(false)
        // 看门狗跑在独立 scope（独立 Job）上：若放进包裹 emit 的 coroutineScope 里，emit 的
        // Job 与 flow 收集者不一致会触发「Flow invariant is violated」。这里仅用它在超时时杀进程。
        val watchScope = CoroutineScope(Dispatchers.IO + Job())
        val watchdog = launchKillWatchdog(watchScope, process, effectiveTimeout, timedOut, command)
        val cancellationHook = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException && process.isAlive) {
                FileLogger.i(TAG, "命令被取消，终止进程: $command")
                runCatching { process.destroy() }
                runCatching { process.destroyForcibly() }
            }
        }
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                emit(CommandEvent.Line(line!!))
            }
            val exitCode = process.waitFor()
            watchdog.cancel()
            if (timedOut.get()) {
                FileLogger.w(TAG, "命令超时(${effectiveTimeout}ms)已终止: $command")
                emit(CommandEvent.Line(timeoutNotice(effectiveTimeout)))
                emit(CommandEvent.Exit(null))
            } else {
                if (exitCode != 0) FileLogger.w(TAG, "命令退出码=$exitCode: $command")
                else FileLogger.v(TAG, "命令完成(退出码 0): $command")
                emit(CommandEvent.Exit(exitCode))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 看门狗超时 destroy 进程会关闭 stdout 管道，使阻塞中的 readLine 抛 IOException
            //（而非返回 null）。若不在此吸收，异常会让 flow 异常终止、CommandEvent.Exit 不再 emit，
            // 上层 executeStream 的 collect 随即中断，已逐行展示给用户的输出在最终 ToolResult 里丢失。
            // 故此处按是否超时分流：超时则 emit 超时提示 + Exit(null)，保留已 emit 的各行；
            // 非 IO 异常也转成一行提示 + Exit，避免 flow 异常终止丢掉已输出内容。
            watchdog.cancel()
            if (timedOut.get()) {
                FileLogger.w(TAG, "命令超时(${effectiveTimeout}ms)已终止(readLine 异常): $command", e)
                emit(CommandEvent.Line(timeoutNotice(effectiveTimeout)))
                emit(CommandEvent.Exit(null))
            } else {
                FileLogger.e(TAG, "命令读输出异常(已保留此前输出): $command", e)
                emit(CommandEvent.Line("[命令执行异常：${e.message}]"))
                emit(CommandEvent.Exit(null))
            }
        } finally {
            // 协程取消（用户离开页面等）时确保子进程被回收，避免泄漏
            cancellationHook?.dispose()
            watchdog.cancel()
            watchScope.cancel()
            runCatching { reader.close() }
            runCatching { process.destroy() }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 在容器内同步执行命令并返回输出。首次调用会触发 rootfs 安装（幂等）。
     *
     * [timeoutMs] 为命令最长执行时间（毫秒），默认 [DEFAULT_TIMEOUT_MS]，上限 [MAX_TIMEOUT_MS]。
     * 超时后强制终止子进程，返回已收集到的部分输出并在末尾追加超时提示。
     */
    override suspend fun runCommandSync(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): String = withContext(Dispatchers.IO) {
        // 未就绪时不自动初始化，直接返回引导文案，由用户去终端页完成初始化。
        notReadyHint()?.let { return@withContext it }
        execCaptured(command, projectPath, timeoutMs).output
    }

    /**
     * 同 [runCommandSync]，但一并返回退出码（超时/异常时为 null）。供需要据退出码判成败的调用方
     * 使用——如 git 写操作：git 非零退出码并非进程崩溃，[runCommandSync] 仅返回文本会让上层误报成功。
     */
    override suspend fun runCommandSyncWithExit(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult = withContext(Dispatchers.IO) {
        // 未就绪时不自动初始化，直接返回引导文案，由用户去终端页完成初始化。
        notReadyHint()?.let { return@withContext CommandResult(it, null) }
        val r = execCaptured(command, projectPath, timeoutMs)
        CommandResult(r.output, r.exitCode)
    }

    /** 一次容器内执行的内部结果：限幅后的完整输出 + 退出码（超时/异常时为 null）。 */
    private data class ExecResult(val output: String, val exitCode: Int?)

    /**
     * 仅在容器已就绪（rootfs 已安装）时执行命令；不会触发 rootfs 解压。
     *
     * 供只读工具做性能增强使用：例如 search 可优先用 rg，但不能因为一次自动批准的搜索
     * 隐式解压容器或联网安装环境。未安装时返回 null。
     */
    override suspend fun runCommandSyncIfReady(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult? {
        if (!containerInstaller.isInstalledFor(currentProfile)) return null
        val result = execCaptured(command, projectPath, timeoutMs)
        return CommandResult(result.output, result.exitCode)
    }

    override suspend fun runCommandSyncUnbounded(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult = withContext(Dispatchers.IO) {
        notReadyHint()?.let { return@withContext CommandResult(it, null) }
        val r = execCaptured(command, projectPath, timeoutMs, unbounded = true)
        CommandResult(r.output, r.exitCode)
    }

    /**
     * 在容器内同步执行命令并捕获输出。**假定 rootfs 已安装**（不做懒安装/配置），
     * 供 [runCommandSync]（先 [ensureInstalled]）与初始化脚本执行（先 [installRootfsIfNeed]）复用，
     * 避免配置流程反向触发 [ensureInstalled] 形成递归。
     *
     * [timeoutMs] 为命令最长执行时间（毫秒），钳到 [MAX_TIMEOUT_MS]。超时则强杀进程，
     * 返回已收集的部分输出并在末尾追加超时提示，[ExecResult.exitCode] 记为 null。
     */
    private suspend fun execCaptured(
        command: String,
        projectPath: String?,
        timeoutMs: Long,
        unbounded: Boolean = false
    ): ExecResult = withContext(Dispatchers.IO) {
        try {
            val effectiveTimeout = timeoutMs.coerceIn(1L, MAX_TIMEOUT_MS)
            FileLogger.d(TAG, "执行命令(同步) cwd=$projectPath timeout=${effectiveTimeout}ms: $command")
            val process = startContainerProcess(command, projectPath)
            val timedOut = AtomicBoolean(false)
            val cancellationHook = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                if (cause is CancellationException && process.isAlive) {
                    FileLogger.i(TAG, "命令被取消，终止进程: $command")
                    runCatching { process.destroy() }
                    runCatching { process.destroyForcibly() }
                }
            }

            // 限幅累积：超大输出只保留开头+结尾，避免撑爆内存与模型上下文。
            // 不限幅模式（unbounded）供需要完整输出的调用方使用（如 git diff），由上层自行兜底。
            val output = if (unbounded) BoundedOutput(Int.MAX_VALUE, Int.MAX_VALUE) else BoundedOutput()
            // 看门狗与读循环并发：超时则 destroy 进程，使阻塞的 readLine 立即返回 null 退出循环。
            var exitCode: Int? = null
            try {
                coroutineScope {
                    val watchdog = launchKillWatchdog(this, process, effectiveTimeout, timedOut, command)
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    try {
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            output.append(line!!)
                            output.append("\n")
                        }
                        exitCode = process.waitFor()
                    } finally {
                        watchdog.cancel()
                        runCatching { reader.close() }
                    }
                }
            } finally {
                cancellationHook?.dispose()
                runCatching { process.destroy() }
            }

            if (timedOut.get()) {
                FileLogger.w(TAG, "命令超时(${effectiveTimeout}ms)已终止: $command")
                output.append(timeoutNotice(effectiveTimeout))
                output.append("\n")
                ExecResult(output.build(), null)
            } else {
                FileLogger.v(TAG, "命令完成(退出码 $exitCode，输出 ${output.totalChars} 字符): $command")
                ExecResult(output.build(), exitCode)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "执行命令异常: $command", e)
            ExecResult("Error: ${e.message}", null)
        }
    }

    /**
     * 启动看门狗：等待 [timeoutMs] 后若进程仍存活，则标记超时并优雅→强制终止，借此解除
     * 调用方阻塞中的 readLine。返回的 [Job] 由调用方在正常结束时 cancel 掉。
     *
     * **凭据弹窗在途时暂停**：到点若 [credentialPromptInFlight] > 0（自定义 git credential helper
     * 正阻塞等 app 弹窗回填用户的凭据请求），watchdog 不杀——这条 git 命令是「正等用户填凭据」
     * 而非「卡死」，按常规超时强杀会让用户离开几分钟回来发现推送已失败、得重来。改为每轮重查：
     * 在途则宽限一段（1min）再查，直至不在途才杀；绝对上限 [MAX_TIMEOUT_MS]（30min）即便在途
     * 也兜底杀，避免事实无限等待。终端 PTY 路径不经 watchdog（[TerminalSessionManager] 裸 tty），天然最耐等。
     */
    private fun launchKillWatchdog(
        scope: CoroutineScope,
        process: Process,
        timeoutMs: Long,
        timedOut: AtomicBoolean,
        command: String
    ): Job = scope.launch {
        var remaining = timeoutMs
        var totalWaited = 0L
        while (true) {
            delay(remaining)
            if (!process.isAlive) return@launch
            totalWaited += remaining
            val inflight = credentialPromptInFlight.get()
            if (inflight > 0 && totalWaited < MAX_TIMEOUT_MS) {
                // 凭据弹窗在途：宽限 1min（不超过绝对上限），再回查。
                FileLogger.i(TAG, "凭据弹窗在途(${inflight})，watchdog 暂缓，再等 60000ms: $command")
                remaining = minOf(60_000L, MAX_TIMEOUT_MS - totalWaited)
                continue
            }
            // 不在途，或已达 30min 绝对上限：正常超时终止。
            timedOut.set(true)
            FileLogger.w(TAG, "命令执行超过 ${timeoutMs}ms（累计等待 ${totalWaited}ms，inflight=${inflight}），终止进程: $command")
            runCatching { process.destroy() }
            delay(TIMEOUT_KILL_GRACE_MS)
            if (process.isAlive) runCatching { process.destroyForcibly() }
            return@launch
        }
    }

    /** 超时提示行（拼进输出，喂回模型/展示给用户）。 */
    private fun timeoutNotice(timeoutMs: Long): String =
        "[命令执行超时：超过 ${timeoutMs}ms 已被强制终止]"

    /**
     * 启动进程。rootfs/proot 安装就绪则用 PRoot 进入容器；
     * 否则回退到 Android 原生 shell（rootfs 缺失时的兜底）。
     */
    private fun startContainerProcess(command: String, projectPath: String?): Process {
        val profile = currentProfile
        val useProot = containerInstaller.isInstalledFor(profile)

        val processBuilder = if (useProot) {
            buildProcessBuilder(buildProotInvocation(command, projectPath))
        } else {
            FileLogger.w(TAG, "PRoot 未安装，回退到原生 shell")
            buildNativeProcess(command, projectPath)
        }

        // Redirect stderr to stdout so we capture everything in one stream
        processBuilder.redirectErrorStream(true)
        return processBuilder.start()
    }

    /** 容器是否已安装就绪（按当前 profile）。 */
    override fun isContainerInstalled(): Boolean = containerInstaller.isInstalledFor(currentProfile)

    /**
     * 基础包是否已配置完成（按当前 profile 的标记）。首次进入终端时由初始化菜单
     * （assets/aicode/provision.sh）引导安装，完成后脚本写标记；未完成时不影响进入 shell。
     */
    override fun isProvisioned(): Boolean = isProvisionedFor(currentProfile)

    /** 按指定 profile 判断基础包配置是否完成（[defaultShell] 据此选 bash/sh）。 */
    fun isProvisionedFor(profile: ContainerProfile): Boolean {
        val marker = provisionMarker(profile)
        return marker.exists() && marker.readText().trim() == PROVISION_VERSION
    }

    /**
     * 容器未就绪（rootfs 未解压）时返回引导文案，就绪返回 null。
     * 命令执行入口据此不自动初始化、直接失败并引导用户去终端页完成初始化。
     */
    override fun notReadyHint(): String? = notReadyHintFor(currentProfile)

    /** 按指定 profile 判断容器是否就绪（MCP 按运行时容器启动前检查用）。 */
    fun notReadyHintFor(profile: ContainerProfile): String? {
        if (containerInstaller.isInstalledFor(profile)) return null
        return context.getString(R.string.container_not_ready_hint)
    }

    /**
     * 容器默认命令 shell：优先用 profile 指定的 [ContainerProfile.shellPath]；未指定时
     * 按 provision 状态选 `/bin/bash`（脚本已安装 bash）或 `/bin/sh`。
     */
    override fun defaultShell(): String {
        val profile = currentProfile
        profile.shellPath?.takeIf { it.isNotBlank() }?.let { return it }
        return if (isProvisioned()) "/bin/bash" else "/bin/sh"
    }

    /**
     * 幂等地确保容器可用：解压 rootfs/proot（首次耗时）。所有容器（内置/自定义）一致——
     * 基础工具安装不在此阶段自动执行，由进入终端时的初始化菜单（assets/aicode/provision.sh）
     * 引导用户选择，装包失败也不阻塞进入 shell。
     * 仅由终端页（[TerminalSessionManager]）作为唯一初始化入口调用；命令执行入口不再自动触发。
     *
     * 耗时初始化（解压）在引擎级 [initScope] 中执行，不随调用方（终端页 viewModelScope）
     * 取消而中断——退出终端页后初始化仍在后台继续，下次进入可复用同一 job 或等待其完成。
     * 全程通过 [initProgress] 上报阶段进度。rootfs 解压失败时置 [ContainerInitState.Failed]
     * 并抛异常（不静默置 Ready），让终端页进入失败态、可重试。
     */
    override suspend fun ensureInstalled() {
        val profile = currentProfile
        // 每次进入终端页前确保提取最新的内置文档
        containerInstaller.extractDocs()
        if (containerInstaller.isInstalledFor(profile)) {
            // 旧 rootfs（如已导入的 Ubuntu 26.04）不会重新解压，这里兜底巡检修复已知兼容性问题
            containerInstaller.repairRootfsCompatibility(containerInstaller.rootfsDirFor(profile))
            _initProgress.value = ContainerInitState.Ready
            refreshContainerHome()
            detectAndCacheOsIfNeeded(profile)
            return
        }
        // 启动或复用后台初始化 job；initMutex 只保护 job 的创建/复用，真正的耗时工作在 initScope 里跑。
        val job = initMutex.withLock {
            val existing = initJob
            if (existing == null || !existing.isActive) {
                initJob = initScope.launch { doInit(profile) }
            }
            initJob!!
        }
        // 等待完成；若调用方（终端页）被取消，join 抛 CancellationException，但后台 job 继续执行。
        job.join()
        if (containerInstaller.isInstalledFor(profile)) {
            _initProgress.value = ContainerInitState.Ready
            refreshContainerHome()
            detectAndCacheOsIfNeeded(profile)
        } else {
            val reason = "容器未安装（缺少 rootfs/proot）"
            _initProgress.value = ContainerInitState.Failed(reason)
            throw IllegalStateException(reason)
        }
    }

    /** 在 [initScope] 中真正执行一次性初始化：解压 rootfs（基础工具由进入终端时的初始化菜单引导安装）。 */
    private suspend fun doInit(profile: ContainerProfile) {
        // installRootfsIfNeed 在真正解压/部署时回调更新进度（已安装则快路径不回调）
        containerInstaller.installRootfsIfNeed(profile) { _initProgress.value = it }
    }

    /** 查容器内 $HOME 并缓存到 [com.aicode.feature.workspace.domain.PathHomeResolver]，供各工具展开 ~。 */
    private suspend fun refreshContainerHome() {
        runCatching {
            val result = execCaptured("echo \$HOME", projectPath = null, timeoutMs = 3000)
            val home = result.output.trim().ifEmpty { null }
            if (home != null) pathHomeResolver.containerHome = home
        }.onFailure { FileLogger.w(TAG, "查容器 \$HOME 失败", it) }
    }

    /**
     * 容器首次运行后识别系统类型（读 /etc/os-release 的 ID 字段）并写入缓存；
     * 已有缓存或识别失败跳过，下次运行再试。远程 SSH 无本地 rootfs，不检测。
     */
    private suspend fun detectAndCacheOsIfNeeded(profile: ContainerProfile) {
        if (profile.mode == ExecutionMode.REMOTE_SSH) return
        if (containerOsDetector.cachedOs(profile.id) != null) return
        runCatching {
            val result = execCaptured(
                "if [ -f /etc/os-release ]; then . /etc/os-release 2>/dev/null; echo \"\$ID\"; fi",
                projectPath = null,
                timeoutMs = 3000
            )
            val osId = result.output.trim().lowercase().ifBlank { null }
            if (osId != null && result.exitCode == 0) {
                containerOsDetector.cacheOs(profile.id, osId)
                FileLogger.i(TAG, "容器系统识别: ${profile.id} -> $osId")
            }
        }.onFailure { FileLogger.w(TAG, "容器系统识别失败: ${profile.id}", it) }
    }

    /**
     * 以长驻进程方式在容器内执行命令（如 `sshd -D`）。调用前需保证容器已安装。
     * 与 [runCommandStream] 不同，这里不读取/消费输出流，由调用方决定如何处理（通常丢弃）。
     */
    fun startProotProcess(command: String, projectPath: String?): Process {
        val pb = buildProcessBuilder(buildProotInvocation(command, projectPath))
        pb.redirectErrorStream(true)
        return pb.start()
    }

    /**
     * 以长驻进程方式在容器内启动一个程序并保留**分离的** stdin/stdout/stderr，供调用方
     * 双向流式通信（如 MCP stdio server：往 stdin 写 JSON-RPC、从 stdout 读 JSON-RPC）。
     *
     * 与 [startProotProcess] 的关键区别：**不** redirectErrorStream，stderr 独立保留，
     * 保证 stdout 是干净的协议流（server 的日志走 stderr，不会污染 JSON-RPC）。
     *
     * [program] 为容器内可执行文件名/路径（如 `npx`），[programArgs] 为其参数，二者经
     * `/bin/sh -c 'exec "$0" "$@"'` 逐项透传，避免 shell 引号/转义问题。[extraEnv] 叠加到
     * 容器默认环境（覆盖同名项）。调用前需保证容器已安装（[ensureInstalled]）。
     */
    fun startStdioProcess(
        program: String,
        programArgs: List<String>,
        projectPath: String?,
        extraEnv: Map<String, String> = emptyMap(),
        profile: ContainerProfile = currentProfile
    ): Process {
        val invocation = buildStdioInvocation(program, programArgs, projectPath, extraEnv, profile)
        val pb = buildProcessBuilder(invocation)
        // 刻意不 redirectErrorStream：stdout 留给 JSON-RPC，stderr 由调用方单独消费。
        return pb.start()
    }

    /**
     * 构造「在容器内直接 exec 某程序（保留分离流）」的 PRoot 调用。
     * 用 `sh -c 'exec "$0" "$@"' program arg1 arg2 …` 把参数原样交给 execvp，规避引号问题。
     */
    private fun buildStdioInvocation(
        program: String,
        programArgs: List<String>,
        projectPath: String?,
        extraEnv: Map<String, String>,
        profile: ContainerProfile
    ): ProotInvocation {
        val argv = buildBaseProotArgv(projectPath, profile)
        argv.add("/bin/sh")
        argv.add("-c")
        argv.add("exec \"\$0\" \"\$@\"")
        argv.add(program)
        argv.addAll(programArgs)
        return ProotInvocation(argv, buildContainerEnv() + profile.env + extraEnv)
    }

    /**
     * 构造进入容器执行 [command] 的完整 PRoot 调用（argv + env）。
     * 暴露给终端会话：Termux TerminalSession 需要把可执行文件与参数分开传入。
     */
    fun buildProotInvocation(command: String, projectPath: String?): ProotInvocation {
        val argv = buildBaseProotArgv(projectPath, currentProfile)
        // 用 [defaultShell]：bash 装好后 AI 命令与终端会话都走 bash；装机期间/失败回退 /bin/sh。
        argv.add(defaultShell())
        argv.add("-c")
        argv.add(command)
        return ProotInvocation(argv, buildContainerEnv() + currentProfile.env)
    }

    /**
     * 构造 PRoot 调用的公共前缀 argv：proot 二进制 + rootfs + 标准绑定 + 伪 root + 工作区绑定，
     * 但**不含**最终的客户机命令（由各调用方自行追加 `/bin/sh -c …` 或 `exec` 形式）。
     */
    private fun buildBaseProotArgv(projectPath: String?, profile: ContainerProfile): MutableList<String> {
        val rootfs = containerInstaller.rootfsDirFor(profile).absolutePath
        val prootBin = containerInstaller.prootBin.absolutePath

        val argv = mutableListOf(
            prootBin,
            "-r", rootfs,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/system",  // 绑定 /system 让宿主动态库可用
            "-0"              // 伪 root，apk 等需要
        )

        // 把当前工作区目录绑定到容器内 ~/workspace（即 /root/workspace），使命令与文件工具作用于同一目录
        if (projectPath != null) {
            argv.add("-b")
            argv.add("$projectPath:/root/workspace")
            argv.add("-w")
            argv.add("/root/workspace")
        }

        // 把 AI 配置目录绑定到容器内 /root/.aicode（读写）：内含 skills/（load_skill 读到的指令常引用
        // skill 目录里的脚本，AI 用 execute_command 执行 `python /root/.aicode/skills/<name>/x.py` 等）与
        // mcp.json（MCP 配置）。宿主物理目录独立于 rootfs，容器升级重装不丢用户数据。
        // 基础解释器 python3(3.12) 与 git 由进入终端时的初始化菜单（provision.sh）安装；
        // node 等其他运行时仍由 skill / 用户自行保证。proot 的 -b 要求源路径存在，故先确保目录已建。
        val aicodeDir = containerInstaller.aicodeDir.apply { mkdirs() }
        argv.add("-b")
        argv.add("${aicodeDir.absolutePath}:/root/.aicode")

        // 自定义 profile 的额外绑定与参数（内置 profile 这俩为空，此段无操作，等价于改动前）
        for (b in profile.extraBindings) {
            argv.add("-b")
            argv.add(b)
        }
        argv.addAll(profile.extraArgs)

        return argv
    }

    /** 容器内进程的标准环境变量（proot loader / 动态库 / PATH / HOME 等）。 */
    private fun buildContainerEnv(): Map<String, String> {
        return mapOf(
            // Android proot 必需的环境变量
            "PROOT_TMP_DIR" to containerInstaller.prootTmpDir.absolutePath, // Android 没有 /tmp
            // Termux proot 的 loader 分离，必须用 PROOT_LOADER/_32 指向，否则无法注入子进程而起不来。
            "PROOT_LOADER" to containerInstaller.prootLoader.absolutePath,
            "PROOT_LOADER_32" to containerInstaller.prootLoader32.absolutePath,
            // Termux proot 动态链接 libtalloc.so.2 / libandroid-shmem.so，需让 linker64 能找到它们；
            // libc.so/liblog.so 走系统默认路径(/system/lib64)。
            "LD_LIBRARY_PATH" to "${containerInstaller.prootLibDir.absolutePath}:/system/lib64:/system/lib",
            // 说明（statx / seccomp）：旧 proot 5.1.0 的 seccomp 过滤表没有 statx，Node 用 statx 解析
            // 模块路径会拿到未翻译的 ~/workspace/xxx → ENOENT「Cannot find module」。Termux proot
            // (5.1.107.x) 的 seccomp 过滤表已包含 statx，默认 seccomp 模式即可正确翻译，故此处
            // **刻意不设 PROOT_NO_SECCOMP**——这正是 Termux 自己用 proot 的方式；强制全量 ptrace
            // (PROOT_NO_SECCOMP=1) 反而在本设备触发过 ptrace(PEEKDATA) I/O error。
            "PATH" to "/usr/bin:/bin:/usr/sbin:/sbin",
            "HOME" to "/root",
            // 宿主进程环境的 TMPDIR 指向 App 缓存目录（/data/user/0/<pkg>/cache），容器内 /data 未挂载、
            // 该路径不存在——mktemp/dpkg 等会因找不到临时目录失败，故显式覆盖为容器内 /tmp。
            "TMPDIR" to "/tmp",
            // git 全局配置指向持久挂载里的 .gitconfig（/root/.aicode 绑定到宿主 filesDir/aicode，
            // 跨 rootfs 升级不丢）。git-credentials 同放该目录，credential.helper=store 经此读；
            // credential.helper 由 provision.sh 经 includeIf 限定 /root/workspace/ 加载（最小化注入）。
            "GIT_CONFIG_GLOBAL" to "/root/.aicode/.gitconfig",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8"
            // 全局 HTTP 代理：开启时注入 HTTP_PROXY/HTTPS_PROXY/ALL_PROXY/NO_PROXY，
            // 容器内 curl/git/npm/pip 等一律走代理；关闭或配置不完整时为空 map 不影响直连。
        ) + com.aicode.core.net.AppProxy.proxyEnv(context)
    }

    private fun buildProcessBuilder(invocation: ProotInvocation): ProcessBuilder {
        val processBuilder = ProcessBuilder(invocation.argv)
        processBuilder.environment().putAll(invocation.env)
        return processBuilder
    }

    private fun buildNativeProcess(command: String, projectPath: String?): ProcessBuilder {
        // Fallback to Android's native shell
        val processBuilder = ProcessBuilder("/system/bin/sh", "-c", command)
        if (projectPath != null) {
            processBuilder.directory(java.io.File(projectPath))
        }
        return processBuilder
    }
}
