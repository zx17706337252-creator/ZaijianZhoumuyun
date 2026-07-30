package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zaijian.zhoumuyun.data.db.entity.PendingEventEntity

/**
 * 灵活自动化编排 · 待处理事件 DAO（§11.1）
 *
 * 对照 ChainDefinitionDao / ChainRunDao 的写法风格。
 * 高频查询路径：[findUnprocessed]（ZaijianApp.onCreate 时一次性扫描重放）。
 */
@Dao
interface PendingEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: PendingEventEntity)

    /**
     * §11.1 开机/重启恢复：查所有未处理的待处理事件，按创建时间排序。
     * processPendingEvents() 逐条重放给 ChainTriggerMatcher。
     */
    @Query("SELECT * FROM pending_events WHERE processed = 0 ORDER BY createdAt ASC")
    suspend fun findUnprocessed(): List<PendingEventEntity>

    /**
     * §11.1 重放成功后标记已处理。
     */
    @Query("UPDATE pending_events SET processed = 1 WHERE id = :id")
    suspend fun markProcessed(id: String)

    /**
     * 清理已处理的旧记录（可选维护操作，防表无限增长）。
     */
    @Query("DELETE FROM pending_events WHERE processed = 1 AND createdAt < :before")
    suspend fun deleteProcessedBefore(before: Long)
}
