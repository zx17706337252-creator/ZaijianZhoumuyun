package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zaijian.zhoumuyun.data.db.entity.WorldEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WorldEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun append(event: WorldEventEntity)

    @Query("SELECT * FROM world_events ORDER BY createdAt DESC LIMIT :limit")
    suspend fun queryLatest(limit: Int = 50): List<WorldEventEntity>

    @Query("SELECT * FROM world_events WHERE actorId = :actorId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun queryByActor(actorId: String, limit: Int = 50): List<WorldEventEntity>

    @Query("SELECT * FROM world_events WHERE domain = :domain ORDER BY createdAt DESC LIMIT :limit")
    suspend fun queryByDomain(domain: String, limit: Int = 50): List<WorldEventEntity>

    /** Phase 10 Project Engine 使用 */
    @Query("SELECT * FROM world_events WHERE projectId = :projectId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun queryByProject(projectId: String, limit: Int = 50): List<WorldEventEntity>

    @Query("SELECT * FROM world_events WHERE type = :type ORDER BY createdAt DESC LIMIT :limit")
    suspend fun queryByType(type: String, limit: Int = 50): List<WorldEventEntity>

    /** 实时观察最近事件（Timeline 用） */
    @Query("SELECT * FROM world_events ORDER BY createdAt DESC LIMIT :limit")
    fun observeLatest(limit: Int = 30): Flow<List<WorldEventEntity>>

    @Query("SELECT COUNT(*) FROM world_events")
    suspend fun count(): Int

    // ── 批次8 8-1修复：定期清理方法（world_events 只增不删）──────────

    /** 删除 [beforeMs] 时间戳之前的所有事件（按时间裁剪，保留最近 N 天）。 */
    @Query("DELETE FROM world_events WHERE createdAt < :beforeMs")
    suspend fun deleteBefore(beforeMs: Long): Int

    /**
     * 每个 domain 只保留最近 [keepPerDomain] 条，其余删除（按 domain 分组裁剪）。
     * 用子查询实现：保留每 domain 按 createdAt DESC 排序的前 keepPerDomain 条 id，
     * 其余全部删除。
     */
    @Query("""
        DELETE FROM world_events
        WHERE id NOT IN (
            SELECT id FROM world_events we1
            WHERE (SELECT COUNT(*) FROM world_events we2
                   WHERE we2.domain = we1.domain
                     AND (we2.createdAt > we1.createdAt
                          OR (we2.createdAt = we1.createdAt AND we2.id > we1.id))
            ) < :keepPerDomain
        )
    """)
    suspend fun trimByDomain(keepPerDomain: Int): Int
}
