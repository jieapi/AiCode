package com.aicode.feature.backup.data

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aicode.core.util.FileLogger
import com.aicode.feature.backup.domain.BackupManager
import com.aicode.feature.backup.domain.BackupOptions
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * WebDAV 自动备份同步 Worker：按用户设定的间隔周期执行，本地生成备份并上传到云端
 * `AiCode/` 目录。任务在用户开启「自动备份同步」时调度，关闭后取消。
 *
 * Worker 内复查一次开关与配置，避免调度后用户又关闭导致的误触发。
 */
@HiltWorker
class WebDavAutoSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParameters: WorkerParameters,
    private val repository: WebDavSettingsRepository,
    private val client: WebDavClient,
    private val backupManager: BackupManager
) : CoroutineWorker(context, workerParameters) {

    override suspend fun doWork(): Result {
        val config = repository.snapshot()
        if (!config.autoSync || !config.isConfigured) return Result.success()

        return try {
            val options = BackupOptions(
                providers = repository.syncProvidersFlow.first(),
                remoteConnections = repository.syncRemoteFlow.first(),
                chatHistory = repository.syncChatHistoryFlow.first(),
                mcpServers = repository.syncMcpFlow.first(),
                permissionRules = repository.syncPermissionsFlow.first(),
                appSettings = repository.syncAppSettingsFlow.first(),
                workspaceFiles = repository.syncWorkspaceFilesFlow.first()
            )
            val tempFile = File(applicationContext.cacheDir, "webdav-auto-${System.currentTimeMillis()}.tar.gz")
            tempFile.outputStream().use { out -> backupManager.export(null, options, out) }
            val result = client.upload(config, tempFile, REMOTE_FILE_PATH)
            tempFile.delete()
            result.fold(
                onSuccess = {
                    repository.setLastSyncAt(System.currentTimeMillis())
                    Result.success()
                },
                onFailure = {
                    FileLogger.e(TAG, "WebDAV 自动同步失败", it)
                    Result.retry()
                }
            )
        } catch (e: Exception) {
            FileLogger.e(TAG, "WebDAV 自动同步异常", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "WebDavAutoSync"
        private const val UNIQUE_NAME = "webdav-auto-sync"

        /** 云端根目录下自动创建的文件夹与备份文件名。 */
        private const val REMOTE_FILE_PATH = "AiCode/aicode-backup.tar.gz"

        /** 将「数值 + 单位」换算为分钟，并夹到 WorkManager 的合法下限（15 分钟）。 */
        private fun toMinutes(value: Int, unit: SyncIntervalUnit): Long {
            val minutes = when (unit) {
                SyncIntervalUnit.MINUTE -> value.toLong()
                SyncIntervalUnit.HOUR -> value * 60L
                SyncIntervalUnit.DAY -> value * 60L * 24L
            }
            return minutes.coerceAtLeast(15L)
        }

        /** 调度周期任务（幂等）。开启自动同步时调用。 */
        fun schedule(context: Context, value: Int, unit: SyncIntervalUnit) {
            val request = PeriodicWorkRequestBuilder<WebDavAutoSyncWorker>(
                toMinutes(value, unit), TimeUnit.MINUTES
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        /** 取消周期任务。关闭自动同步时调用。 */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
