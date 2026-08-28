package com.aicode.feature.settings.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.aicode.feature.settings.data.local.entity.AIProviderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AIProviderDao {
    @Query("SELECT * FROM ai_providers ORDER BY sortOrder ASC, id ASC")
    fun getAllProviders(): Flow<List<AIProviderEntity>>

    @Query("SELECT * FROM ai_providers ORDER BY sortOrder ASC, id ASC")
    suspend fun getAllProvidersOnce(): List<AIProviderEntity>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM ai_providers")
    suspend fun getMaxSortOrder(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllProviders(providers: List<AIProviderEntity>)

    /** 仅更新排序值，避免整行 REPLACE 覆盖并发修改的其它字段。 */
    @Query("UPDATE ai_providers SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: String, sortOrder: Int)

    @Query("SELECT * FROM ai_providers WHERE id = :id")
    suspend fun getProviderById(id: String): AIProviderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProvider(provider: AIProviderEntity)

    @Update
    suspend fun updateProvider(provider: AIProviderEntity)

    @Query("DELETE FROM ai_providers WHERE id = :id")
    suspend fun deleteProvider(id: String)

    @Query("UPDATE ai_providers SET selectedModel = :model WHERE id = :id")
    suspend fun setSelectedModel(id: String, model: String)

    @Query("UPDATE ai_providers SET models = :models WHERE id = :id")
    suspend fun setModels(id: String, models: String)

    @Query("UPDATE ai_providers SET isEnabled = :isEnabled WHERE id = :id")
    suspend fun setProviderEnabled(id: String, isEnabled: Boolean)
}
