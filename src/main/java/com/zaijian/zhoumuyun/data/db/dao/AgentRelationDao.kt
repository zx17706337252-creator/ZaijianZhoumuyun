package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.zaijian.zhoumuyun.data.db.entity.AgentRelationEntity
import com.zaijian.zhoumuyun.data.model.AgentRelationStage
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentRelationDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: AgentRelationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AgentRelationEntity)

    @Query("SELECT * FROM agent_relation WHERE daughterId = :daughterId LIMIT 1")
    suspend fun get(daughterId: Int): AgentRelationEntity?

    @Query("SELECT * FROM agent_relation WHERE daughterId = :daughterId LIMIT 1")
    fun observe(daughterId: Int): Flow<AgentRelationEntity?>

    /** 某母亲的所有女儿关系记录 */
    @Query("SELECT * FROM agent_relation WHERE motherCharacterId = :motherCharacterId ORDER BY createdAt ASC")
    fun observeByMother(motherCharacterId: Int): Flow<List<AgentRelationEntity>>

    @Query("SELECT * FROM agent_relation WHERE motherCharacterId = :motherCharacterId ORDER BY createdAt ASC")
    suspend fun getByMother(motherCharacterId: Int): List<AgentRelationEntity>

    /** 累计交互次数 +1（D5 阶段切换判定用） */
    @Query("UPDATE agent_relation SET interactionCount = interactionCount + 1 WHERE daughterId = :daughterId")
    suspend fun incrementInteraction(daughterId: Int)

    /** 升阶（D5 触发条件满足时调用） */
    @Query("UPDATE agent_relation SET stage = :stage, lastStageUpAt = :now WHERE daughterId = :daughterId")
    suspend fun updateStage(daughterId: Int, stage: AgentRelationStage, now: Long = System.currentTimeMillis())
}
