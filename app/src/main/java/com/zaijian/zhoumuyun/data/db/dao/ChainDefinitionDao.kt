package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zaijian.zhoumuyun.data.db.entity.ChainDefinitionEntity
import kotlinx.coroutines.flow.Flow

/**
 * 灵活自动化编排 · 链条定义 DAO
 *
 * 对照 WorkflowJobDao / ScheduledJobDao 的写法风格。
 * §6 ChainTriggerMatcher 的高频查询路径：findByTriggerEventEnabled()。
 */
@Dao
interface ChainDefinitionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(def: ChainDefinitionEntity)

    @Query("SELECT * FROM chain_definitions WHERE id = :id")
    suspend fun findById(id: String): ChainDefinitionEntity?

    /**
     * §6 ChainTriggerMatcher 高频查询：按事件名 + enabled=1 筛选所有匹配的链条定义。
     *
     * §11.12：characterId=-1 的项目级链条也会被事件命中（ChainTriggerMatcher 匹配时
     * 除了查该事件对应角色的定义，也一并匹配 characterId=-1 的项目级定义）。
     * 此查询返回所有匹配事件名的启用定义，由调用方按 characterId 做二次过滤。
     */
    @Query("SELECT * FROM chain_definitions WHERE triggerEventName = :eventName AND enabled = 1")
    suspend fun findByTriggerEventEnabled(eventName: String): List<ChainDefinitionEntity>

    @Query("SELECT * FROM chain_definitions WHERE characterId = :characterId ORDER BY createdAt DESC")
    fun observeByCharacter(characterId: Int): Flow<List<ChainDefinitionEntity>>

    @Query("SELECT * FROM chain_definitions WHERE enabled = 1 ORDER BY createdAt DESC")
    fun observeAllEnabled(): Flow<List<ChainDefinitionEntity>>

    @Query("UPDATE chain_definitions SET enabled = :enabled WHERE id = :id")
    suspend fun updateEnabled(id: String, enabled: Boolean)

    @Query("DELETE FROM chain_definitions WHERE id = :id")
    suspend fun deleteById(id: String)
}
