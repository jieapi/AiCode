package com.aicode.feature.agent.domain.shizuku

import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Shizuku UserService：由 Shizuku 在 shell（uid 2000）进程中实例化，代 App 执行 shell 命令。
 *
 * 本进程不是合法的 Android 应用进程，只能用 Java/系统级 API，不能用 Context 相关能力
 * （getContentResolver / registerReceiver 等），故实现只用 [ProcessBuilder]。
 *
 * 类名与构造器会被 Shizuku 反射加载，不可被 R8 混淆（见 proguard-rules.pro）。
 */
class ShizukuShellService : IShizukuShellService.Stub() {

    /** Shizuku 保留的销毁入口：unbindUserService(remove=true) 时触发，结束本进程。 */
    override fun destroy() {
        System.exit(0)
    }

    override fun exec(command: String, timeoutMs: Int): Bundle {
        val result = Bundle()
        var process: Process? = null
        var outputFile: File? = null
        try {
            val target = createOutputFile()
            outputFile = target
            val running = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            process = running
            // 输出读取与等待结束必须并行：管道写满会阻塞子进程，先 waitFor 再读会死锁。
            // 输出落盘后只回传 FD：Binder 事务缓冲上限 1MB，直接放进 Bundle 只能截断输出。
            val reader = Thread {
                runCatching {
                    running.inputStream.use { input ->
                        FileOutputStream(target).use { out -> input.copyTo(out) }
                    }
                }
            }
            reader.start()
            val finished = running.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            if (!finished) running.destroyForcibly()
            reader.join(READER_JOIN_TIMEOUT_MS)
            val descriptor = ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY)
            // 打开后立即 unlink：FD 仍可读，App 崩溃或提前退出也不会留下临时文件。
            target.delete()
            result.putParcelable(KEY_OUTPUT_FD, descriptor)
            result.putInt(KEY_EXIT_CODE, if (finished) running.exitValue() else EXIT_TIMEOUT)
        } catch (e: Exception) {
            outputFile?.delete()
            result.putString(KEY_OUTPUT, e.message ?: "执行失败")
            result.putInt(KEY_EXIT_CODE, EXIT_FAILURE)
        } finally {
            process?.destroy()
        }
        return result
    }

    private fun createOutputFile(): File {
        val dir = TEMP_DIRS.map(::File).firstOrNull { it.isDirectory && it.canWrite() }
            ?: throw IOException("没有可写的临时目录")
        return File.createTempFile("aicode-shizuku-", ".out", dir)
    }

    companion object {
        const val KEY_OUTPUT = "output"
        const val KEY_OUTPUT_FD = "outputFd"
        const val KEY_EXIT_CODE = "exitCode"

        /** 命令超时（进程被强杀）时的退出码。 */
        const val EXIT_TIMEOUT = -1000

        /** 启动/读取异常时的退出码。 */
        const val EXIT_FAILURE = -1

        private const val READER_JOIN_TIMEOUT_MS = 2_000L

        private val TEMP_DIRS = listOf(
            System.getProperty("java.io.tmpdir").orEmpty(),
            "/data/local/tmp"
        ).filter { it.isNotEmpty() }
    }
}