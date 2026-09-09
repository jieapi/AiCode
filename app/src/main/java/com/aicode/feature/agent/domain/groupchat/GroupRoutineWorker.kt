package com.aicode.feature.agent.domain.groupchat

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * 群聊定时任务扫描 Worker：周期检查全部启用的 routine，到期的触发一次
 * （由 [GroupRoutineScheduler.runDueRoutines] 完成，触发消息进房间复用群聊 drive）。
 *
 * WorkManager 周期任务最小间隔 15 分钟，故 routine 的实际触发精度以此为下限
 * （`every 5m` 这类更密的表达式最密也只能 15 分钟一次）。
 */
@HiltWorker
class GroupRoutineWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParameters: WorkerParameters,
    private val scheduler: GroupRoutineScheduler
) : CoroutineWorker(context, workerParameters) {

    override suspend fun doWork(): Result {
        return try {
            scheduler.runDueRoutines()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "group-routines-scan"
        /** WorkManager 允许的最小周期。 */
        private const val PERIOD_MINUTES = 15L

        /** 调度周期扫描任务（幂等）。App 启动时调用。 */
        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<GroupRoutineWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
                    // routine 触发依赖 AI 网络调用，断网时跳过本轮（下次扫描再补）
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
