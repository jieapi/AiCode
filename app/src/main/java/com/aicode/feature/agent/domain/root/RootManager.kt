package com.aicode.feature.agent.domain.root

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.BoundedOutput
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Root 可用状态，供设置页展示与工具执行前判定。 */
enum class RootState {
    /** 未检测到 `su`，设备无 root（或 root 方案未提供 su）。 */
    UNAVAILABLE,

    /** 检测到 `su`，但本应用未获授权（用户拒绝或 root 管理器未放行）。 */
    DENIED,

    /** 就绪，可以 root 身份执行命令。 */
    READY
}

/** 一次 root 命令执行结果。[exitCode] 为负值表示超时或启动异常。 */
data class RootCommandResult(val output: String, val exitCode: Int)

/**
 * Root 后端：以超级用户（uid 0）身份执行命令。
 *
 * 与 [com.aicode.feature.agent.domain.shizuku.ShizukuManager]（adb shell，uid 2000）不同，
 * root 身份可访问系统受限目录（如 `/data/data`、`/data/adb`），能执行 shell 身份做不到的操作。
 *
 * 实现方式：直接通过 `su -c <command>` 起子进程。`su` 由 root 管理器
 * （Magisk / KernelSU / APatch 等）在 `PATH` 或固定路径提供，本类按候选路径探测。
 *
 * 与 Shizuku 的差异：root 没有可编程的授权 API，授权由 root 管理器自己的弹窗完成，
 * 因此 [refreshState] 探测或执行命令时会触发管理器的授权框，需要用户在设备上点「允许」。
 * 也正因如此，**不在构造时自动探测**（避免 App 一启动就弹 root 框），改由设置页或工具调用触发。
 */
@Singleton
class RootManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "RootManager"

        /**
         * `su` 常见路径。不同 root 方案位置不一：
         * Magisk 通常在 `/system/bin/su`（早期 `/sbin/su`）；KernelSU / APatch 亦为 `/system/bin/su`；
         * 部分方案（如旧 Magisk、Sui）在 `/su/bin/su` 或 `/debug_ramdisk/su`。
         */
        val SU_CANDIDATES = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/debug_ramdisk/su",
            "/system/sbin/su",
            "/vendor/bin/su"
        )

        /**
         * 探测超时（毫秒）。比命令执行宽松：探测会弹 root 授权框，需给用户留出点击时间。
         */
        const val PROBE_TIMEOUT_MS = 30_000L

        /** 命令超时上限（毫秒），与 [com.aicode.feature.agent.domain.container.CommandEngine.MAX_TIMEOUT_MS] 对齐。 */
        const val MAX_TIMEOUT_MS = 1_800_000L

        /** 命令超时（进程被强杀）时的退出码。 */
        const val EXIT_TIMEOUT = -1000

        /** 启动/读取异常时的退出码。 */
        const val EXIT_FAILURE = -1

        /** 输出读取线程的 join 上限（毫秒）。 */
        const val READER_JOIN_TIMEOUT_MS = 2_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(RootState.UNAVAILABLE)
    val state: StateFlow<RootState> = _state.asStateFlow()

    @Volatile
    private var suPath: String? = null

    /**
     * 重新探测并在后台发布当前状态。
     *
     * 非阻塞：探测要起进程（可能弹 root 授权框），故放到 IO 线程，调用方可直接在
     * 主线程的 UI 回调里调用。
     */
    fun refreshState() {
        scope.launch {
            runCatching { computeState() }
                .onSuccess { _state.value = it }
                .onFailure { FileLogger.w(TAG, "探测 root 状态失败: ${it.message}") }
        }
    }

    private suspend fun computeState(): RootState = withContext(Dispatchers.IO) {
        val su = resolveSu() ?: return@withContext RootState.UNAVAILABLE
        // 实际跑一次 `id` 验证真能拿到 root：仅有 su 文件不代表授权通过。
        val probe = execWithSu(su, "id", PROBE_TIMEOUT_MS)
        if (probe.exitCode == 0 && probe.output.contains("uid=0")) {
            RootState.READY
        } else {
            RootState.DENIED
        }
    }

    /** 定位 `su`：先查候选路径，再兜底 `which su`。找到即缓存。 */
    private fun resolveSu(): String? {
        suPath?.let { return it }
        for (path in SU_CANDIDATES) {
            if (File(path).exists()) {
                suPath = path
                return path
            }
        }
        val which = runCatching {
            val process = ProcessBuilder("sh", "-c", "which su")
                .redirectErrorStream(true)
                .start()
            val out = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            out.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        }.getOrNull()
        if (which != null && File(which).exists()) {
            suPath = which
            return which
        }
        return null
    }

    /**
     * 宿主视角的关键路径提示，供工具在检测到 AI 误用容器路径时给出纠正建议。
     *
     * Root 作用于宿主 Android，App 私有目录为 `<filesDir>`；而 Bash 等容器工具看到的
     * `~/workspace`、`/etc` 等是容器内路径，两者不同。
     */
    fun hostPathHint(): String {
        val files = context.filesDir.absolutePath
        return "宿主对应位置：工作区 $files/projects/<项目名>/；AI 配置 $files/aicode/；容器根文件系统 $files/rootfs/"
    }

    /**
     * 执行 root 命令。未检测到 `su` 时抛异常，由调用方转成工具错误。
     *
     * 不预先依赖 [state]：授权状态可能尚未探测（避免启动即弹框），此处直接调用，
     * 由 root 管理器在首次调用时弹框授权。
     */
    suspend fun runCommand(command: String, timeoutMs: Long): RootCommandResult {
        val su = resolveSu()
            ?: throw IllegalStateException("未检测到 su（设备可能未 root，或 root 方案未提供 su）")
        val timeout = timeoutMs.coerceIn(1_000L, MAX_TIMEOUT_MS)
        val result = withContext(Dispatchers.IO) { execWithSu(su, command, timeout) }
        // 用真实执行结果校正状态：root 授权由管理器弹窗掌管，状态缓存随时可能过期
        // （App 被系统回收后重建、用户在管理器里改了授权等），不能拿旧状态当门槛。
        updateStateFromResult(result)
        return result
    }

    /**
     * 状态未知时在后台补一次探测（不阻塞调用方）。
     *
     * 用于「首次调用/进程重建后 state 还是初值」的场景：此时不应拒绝执行，
     * 而是并行探测、同时照常执行命令。
     */
    fun probeInBackgroundIfUnknown() {
        if (_state.value != RootState.UNAVAILABLE) return
        refreshState()
    }

    /** 依据一次真实执行的结果刷新状态，避免状态与事实脱节。 */
    private fun updateStateFromResult(result: RootCommandResult) {
        val next = when {
            result.exitCode == 0 -> RootState.READY
            // su 被拒绝/无法取得 root 时通常无输出且非 0 退出；有输出则视为命令自身失败，不改状态
            result.output.isBlank() -> RootState.DENIED
            else -> return
        }
        if (next != _state.value) {
            _state.value = next
            FileLogger.d(TAG, "root 状态校正为 $next（exit=${result.exitCode}）")
        }
    }

    /**
     * 以 `su -c <command>` 执行并收集输出。
     *
     * 输出读取与等待结束必须并行：管道写满会阻塞子进程，先 waitFor 再读会死锁。
     * 同时用 [BoundedOutput] 限幅，避免超大输出撑爆内存。
     *
     * 注意：`destroyForcibly()` 只能杀掉 `su` 进程本身，其派生的孙进程可能残留——
     * 与 Shizuku 后端同样的取舍，超时场景调用方需知晓。
     */
    private fun execWithSu(su: String, command: String, timeoutMs: Long): RootCommandResult {
        var process: Process? = null
        return try {
            process = ProcessBuilder(su, "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = BoundedOutput()
            val reader = Thread {
                runCatching {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            output.append(line)
                            output.append("\n")
                        }
                    }
                }
            }
            reader.start()
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) process.destroyForcibly()
            reader.join(READER_JOIN_TIMEOUT_MS)
            val exitCode = if (finished) process.exitValue() else EXIT_TIMEOUT
            RootCommandResult(output.build(), exitCode)
        } catch (e: Exception) {
            RootCommandResult(e.message ?: "执行失败", EXIT_FAILURE)
        } finally {
            process?.destroy()
        }
    }
}
