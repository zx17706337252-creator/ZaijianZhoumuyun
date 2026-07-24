package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zaijian.zhoumuyun.data.db.entity.MemoryCandidateEntity

/**
 * MemoryCandidateDao — 记忆候选层 DAO
 *
 * Phase 11 补全：新增 deleteProcessed(characterId) 和 getRecent()，
 * 解决 MemoryDao.kt 中重复定义 MemoryCandidateDao 的 Bug。
 */
@Dao
interface MemoryCandidateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(candidate: MemoryCandidateEntity)

    /** 取指定角色尚未处理的候选，按创建时间升序 */
    @Query("""
        SELECT * FROM memory_candidates
        WHERE characterId = :characterId AND isProcessed = 0
        ORDER BY createdAt ASC
        LIMIT :limit
    """)
    suspend fun getPending(characterId: Int, limit: Int = 200): List<MemoryCandidateEntity>

    /** 标记候选已处理，记录关联的 Memory ID */
    @Query("""
        UPDATE memory_candidates
        SET isProcessed = 1, resultMemoryId = :resultMemoryId
        WHERE id = :candidateId
    """)
    suspend fun markProcessed(candidateId: String, resultMemoryId: String?)

    /** 获取最近 N 条候选（调试和展示用） */
    @Query("""
        SELECT * FROM memory_candidates
        WHERE characterId = :characterId
        ORDER BY createdAt DESC
        LIMIT :limit
    """)
    suspend fun getRecent(characterId: Int, limit: Int = 20): List<MemoryCandidateEntity>

    /**
     * 删除指定角色所有已处理的候选（Phase 11 Tier 3 清理用）。
     */
    @Query("DELETE FROM memory_candidates WHERE characterId = :characterId AND isProcessed = 1")
    suspend fun deleteProcessed(characterId: Int)

    /**
     * 清理所有角色的已处理且超过指定时间的旧候选（全局清理）。
     */
    @Query("DELETE FROM memory_candidates WHERE isProcessed = 1 AND createdAt < :before")
    suspend fun cleanOldProcessed(before: Long)
}
