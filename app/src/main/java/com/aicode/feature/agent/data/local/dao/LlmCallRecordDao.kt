package com.aicode.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aicode.feature.agent.data.local.entity.LlmCallRecordEntity
import kotlinx.coroutines.flow.Flow

/** 按天（本地时区）聚合的单日调用统计，供趋势图使用。day 为本地时区下的 epoch 天序号。 */
data class DayCallStats(
    val day: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long,
    val calls: Int,
    val errors: Int,
    val avgTtfbMillis: Double?,
    val avgDurationMillis: Double?,
    val retryCount: Long = 0,
    val cancelled: Int = 0
)

/** 按渠道（provider）聚合的调用统计，供渠道排行使用。 */
data class ProviderCallStats(
    val providerId: String?,
    val providerName: String?,
    val calls: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long,
    val errors: Int,
    val retryCount: Long = 0
)

/** 按模型聚合的调用统计，供模型排行使用。 */
data class ModelCallStats(
    val model: String?,
    val calls: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long,
    /** 写入缓存的 token 数之和（Anthropic cache_creation），按单独单价计入费用估算。 */
    val cacheCreationTokens: Long,
    val avgTtfbMillis: Double?,
    val avgDurationMillis: Double?,
    val errors: Int,
    val retryCount: Long = 0
)

/**
 * 按「渠道 + 模型」聚合的用量，专供周期总费用估算。
 * 单价由渠道（自定义元数据以「提供商ID:模型名」为键）与模型共同决定，
 * 只按模型聚合会丢掉渠道维度、算不到自定义单价。
 */
data class ModelProviderCostStats(
    val providerId: String?,
    val model: String?,
    val inputTokens: Long,
    val cachedInputTokens: Long,
    val outputTokens: Long,
    val cacheCreationTokens: Long
)

/** 当前周期的整体汇总，供概览卡片使用。 */
data class CallSummary(
    val calls: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long,
    val cacheCreationTokens: Long,
    val errors: Int,
    val avgTtfbMillis: Double?,
    val avgDurationMillis: Double?,
    val retryCount: Long = 0,
    val outputTokensPerSecond: Double? = null
)

/** 调用明细分页记录：单次调用 + 渠道名（LEFT JOIN ai_providers 取，渠道被删除后仍显示）。 */
data class RecentCallRecord(
    @Embedded val record: LlmCallRecordEntity,
    val providerName: String?
)

@Dao
interface LlmCallRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: LlmCallRecordEntity)

    @Query("DELETE FROM llm_call_records")
    suspend fun deleteAll()

    /**
     * 按天聚合（本地时区：把 createdAt 平移 [tzOffsetMillis] 后再按 86400000 取整切天）。
     * [tzOffsetMillis] 由调用方按当前系统时区提供（TimeZone.getDefault().getOffset(now)）。
     */
    @Query(
        """
        SELECT (createdAt + :tzOffsetMillis) / 86400000 AS day,
               SUM(inputTokens) AS inputTokens,
               SUM(outputTokens) AS outputTokens,
               SUM(cachedInputTokens) AS cachedInputTokens,
               COUNT(*) AS calls,
               SUM(CASE WHEN status = 'error' THEN 1 ELSE 0 END) AS errors,
               SUM(CASE WHEN status = 'cancelled' THEN 1 ELSE 0 END) AS cancelled,
               AVG(ttfbMillis) AS avgTtfbMillis,
               AVG(durationMillis) AS avgDurationMillis,
               SUM(retryCount) AS retryCount
        FROM llm_call_records
        WHERE createdAt >= :start
          AND (:providerId IS NULL OR providerId = :providerId)
          AND (:model IS NULL OR model = :model)
        GROUP BY day
        ORDER BY day ASC
        """
    )
    fun getDayStats(
        start: Long,
        tzOffsetMillis: Long,
        providerId: String? = null,
        model: String? = null
    ): Flow<List<DayCallStats>>

    /** 按小时聚合（本地时区），「今天」周期用小时粒度展示趋势。 */
    @Query(
        """
        SELECT (createdAt + :tzOffsetMillis) / 3600000 AS day,
               SUM(inputTokens) AS inputTokens,
               SUM(outputTokens) AS outputTokens,
               SUM(cachedInputTokens) AS cachedInputTokens,
               COUNT(*) AS calls,
               SUM(CASE WHEN status = 'error' THEN 1 ELSE 0 END) AS errors,
               SUM(CASE WHEN status = 'cancelled' THEN 1 ELSE 0 END) AS cancelled,
               AVG(ttfbMillis) AS avgTtfbMillis,
               AVG(durationMillis) AS avgDurationMillis,
               SUM(retryCount) AS retryCount
        FROM llm_call_records
        WHERE createdAt >= :start
          AND (:providerId IS NULL OR providerId = :providerId)
          AND (:model IS NULL OR model = :model)
        GROUP BY day
        ORDER BY day ASC
        """
    )
    fun getHourStats(
        start: Long,
        tzOffsetMillis: Long,
        providerId: String? = null,
        model: String? = null
    ): Flow<List<DayCallStats>>

    /** 按渠道聚合，LEFT JOIN ai_providers 取渠道名（渠道被删除后记录仍显示）。 */
    @Query(
        """
        SELECT r.providerId AS providerId,
               p.name AS providerName,
               COUNT(*) AS calls,
               SUM(r.inputTokens) AS inputTokens,
               SUM(r.outputTokens) AS outputTokens,
               SUM(r.cachedInputTokens) AS cachedInputTokens,
               SUM(CASE WHEN r.status = 'error' THEN 1 ELSE 0 END) AS errors,
               SUM(r.retryCount) AS retryCount
        FROM llm_call_records r
        LEFT JOIN ai_providers p ON p.id = r.providerId
        WHERE r.createdAt >= :start
          AND (:providerId IS NULL OR r.providerId = :providerId)
          AND (:model IS NULL OR r.model = :model)
        GROUP BY r.providerId
        ORDER BY (SUM(r.inputTokens) + SUM(r.outputTokens)) DESC
        """
    )
    fun getProviderStats(
        start: Long,
        providerId: String? = null,
        model: String? = null
    ): Flow<List<ProviderCallStats>>

    /** 按模型聚合，按总消耗倒序。 */
    @Query(
        """
        SELECT model AS model,
               COUNT(*) AS calls,
               SUM(inputTokens) AS inputTokens,
               SUM(outputTokens) AS outputTokens,
               SUM(cachedInputTokens) AS cachedInputTokens,
               SUM(cacheCreationTokens) AS cacheCreationTokens,
               AVG(ttfbMillis) AS avgTtfbMillis,
               AVG(durationMillis) AS avgDurationMillis,
               SUM(CASE WHEN status = 'error' THEN 1 ELSE 0 END) AS errors,
               SUM(retryCount) AS retryCount
        FROM llm_call_records
        WHERE createdAt >= :start AND model IS NOT NULL AND model != ''
          AND (:providerId IS NULL OR providerId = :providerId)
          AND (:model IS NULL OR model = :model)
        GROUP BY model
        ORDER BY (SUM(inputTokens) + SUM(outputTokens)) DESC
        """
    )
    fun getModelStats(
        start: Long,
        providerId: String? = null,
        model: String? = null
    ): Flow<List<ModelCallStats>>

    /** 按「渠道 + 模型」聚合 token 用量，供周期总费用估算（费用必须带渠道才算得到自定义单价）。 */
    @Query(
        """
        SELECT providerId AS providerId,
               model AS model,
               SUM(inputTokens) AS inputTokens,
               SUM(cachedInputTokens) AS cachedInputTokens,
               SUM(outputTokens) AS outputTokens,
               SUM(cacheCreationTokens) AS cacheCreationTokens
        FROM llm_call_records
        WHERE createdAt >= :start AND model IS NOT NULL AND model != ''
          AND (:providerId IS NULL OR providerId = :providerId)
          AND (:model IS NULL OR model = :model)
        GROUP BY providerId, model
        """
    )
    fun getModelProviderCostStats(
        start: Long,
        providerId: String? = null,
        model: String? = null
    ): Flow<List<ModelProviderCostStats>>

    /** 周期整体汇总（无 GROUP BY，恒返回一行）。 */
    @Query(
        """
        SELECT COUNT(*) AS calls,
               SUM(inputTokens) AS inputTokens,
               SUM(outputTokens) AS outputTokens,
               SUM(cachedInputTokens) AS cachedInputTokens,
               SUM(cacheCreationTokens) AS cacheCreationTokens,
               SUM(CASE WHEN status = 'error' THEN 1 ELSE 0 END) AS errors,
               AVG(ttfbMillis) AS avgTtfbMillis,
               AVG(durationMillis) AS avgDurationMillis,
               SUM(retryCount) AS retryCount,
               1000.0 * SUM(CASE WHEN kind = 'chat' AND status = 'success' AND outputTokens > 0 AND ttfbMillis IS NOT NULL AND durationMillis > ttfbMillis THEN outputTokens ELSE 0 END) /
                   NULLIF(SUM(CASE WHEN kind = 'chat' AND status = 'success' AND outputTokens > 0 AND ttfbMillis IS NOT NULL AND durationMillis > ttfbMillis THEN durationMillis - ttfbMillis ELSE 0 END), 0)
                   AS outputTokensPerSecond
        FROM llm_call_records
        WHERE createdAt >= :start
          AND (:providerId IS NULL OR providerId = :providerId)
          AND (:model IS NULL OR model = :model)
        """
    )
    fun getSummary(
        start: Long,
        providerId: String? = null,
        model: String? = null
    ): Flow<CallSummary>

    /** 当前周期调用总数，供分页显示总页数。 */
    @Query(
        """
        SELECT COUNT(*) FROM llm_call_records
        WHERE createdAt >= :start
          AND (:providerId IS NULL OR providerId = :providerId)
          AND (:model IS NULL OR model = :model)
          AND (:kind IS NULL OR kind = :kind)
          AND (:status IS NULL OR status = :status)
        """
    )
    fun getCallsCount(
        start: Long,
        providerId: String? = null,
        model: String? = null,
        kind: String? = null,
        status: String? = null
    ): Flow<Int>

    /** 调用明细分页（倒序，[offset] 起取 [limit] 条），LEFT JOIN 渠道名。 */
    @Query(
        """
        SELECT r.*, p.name AS providerName
        FROM llm_call_records r
        LEFT JOIN ai_providers p ON p.id = r.providerId
        WHERE r.createdAt >= :start
          AND (:providerId IS NULL OR r.providerId = :providerId)
          AND (:model IS NULL OR r.model = :model)
          AND (:kind IS NULL OR r.kind = :kind)
          AND (:status IS NULL OR r.status = :status)
        ORDER BY r.createdAt DESC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getRecentCalls(
        start: Long,
        limit: Int,
        offset: Int,
        providerId: String? = null,
        model: String? = null,
        kind: String? = null,
        status: String? = null
    ): Flow<List<RecentCallRecord>>
}
