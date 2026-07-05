package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zaijian.zhoumuyun.data.db.entity.SystemSuggestionEntity
import kotlinx.coroutines.flow.Flow

/**
 * SystemSuggestion DAO（P6 专长进化系统 · 第8节 AI 自我提案）
 */
@Dao
interface SystemSuggestionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(suggestion: SystemSuggestionEntity)

    @Query("""
        SELECT * FROM system_suggestions
        WHERE specialtyId = :specialtyId AND status = 'PENDING'
        ORDER BY createdAt DESC
    """)
    fun observePending(specialtyId: String): Flow<List<SystemSuggestionEntity>>

    @Query("SELECT COUNT(*) FROM system_suggestions WHERE specialtyId = :specialtyId AND status = 'PENDING'")
    suspend fun countPending(specialtyId: String): Int

    @Query("UPDATE system_suggestions SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)
}
