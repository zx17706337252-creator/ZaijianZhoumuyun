package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zaijian.zhoumuyun.data.db.entity.AgentActivityEventEntity
import kotlinx.coroutines.flow.Flow

/**
 * 「心迹」事件 DAO。见《Window B 执行方案 v1.1》2.2.2。
 *
 * 参照 [WorkflowStepResultDao] 写法。写入侧供降级策略状态机（2.1）、三处 UI
 * 集成点（2.2.3）、WorkflowEngine 镜像埋点（2.1.4）调用；读取侧供
 * [com.zaijian.zhoumuyun.data.repository.AgentActivityRepository] 合并视图消费。
 *
 * `@Insert(onConflict = REPLACE)` 与 [WorkflowStepResultDao.insert] 同策略——
 * 主键为 UUID，正常路径不会冲突，REPLACE 仅作"同 id 重写"的兜底（如重放）。
 */
@Dao
interface AgentActivityDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: AgentActivityEventEntity)

    /**
     * 观察某角色最近 N 条「心迹」事件（按 createdAt 倒序），供面板时间线消费。
     * 默认 limit=50，与 [JobResultDao.observeByCharacter] 的默认值一致。
     */
    @Query("SELECT * FROM agent_activity_events WHERE characterId = :characterId ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecentByCharacter(characterId: Int, limit: Int = 50): Flow<List<AgentActivityEventEntity>>

    /**
     * 按某次回复（messageId / roundtableMessageId / workflowJobId）聚合查询全部事件，
     * 按 createdAt 升序，供"查看这次回复背后做了哪些事"的详情视图使用。
     */
    @Query("SELECT * FROM agent_activity_events WHERE sessionRef = :sessionRef ORDER BY createdAt ASC")
    suspend fun getBySession(sessionRef: String): List<AgentActivityEventEntity>
}
