package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zaijian.zhoumuyun.data.db.entity.RelationshipEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RelationshipDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(relationship: RelationshipEntity)

    @Query("SELECT * FROM relationship_states WHERE fromId = :fromId AND toId = :toId LIMIT 1")
    suspend fun get(fromId: String, toId: String): RelationshipEntity?

    /** 观察某一方（用户或角色）的所有出向关系，实时更新 */
    @Query("SELECT * FROM relationship_states WHERE fromId = :fromId ORDER BY updatedAt DESC")
    fun observeFrom(fromId: String): Flow<List<RelationshipEntity>>

    /** 取某角色的所有入向关系（谁 → 这个角色）*/
    @Query("SELECT * FROM relationship_states WHERE toId = :toId")
    suspend fun getIncoming(toId: String): List<RelationshipEntity>

    /** Phase 14：取所有角色间关系（isInterCharacter=1） */
    @Query("SELECT * FROM relationship_states WHERE isInterCharacter = 1")
    suspend fun getAllInterCharacter(): List<RelationshipEntity>

    /** Phase 14：取两个角色之间的关系（双向）*/
    @Query("""
        SELECT * FROM relationship_states
        WHERE isInterCharacter = 1
          AND ((fromId = :idA AND toId = :idB) OR (fromId = :idB AND toId = :idA))
    """)
    suspend fun getBetween(idA: String, idB: String): List<RelationshipEntity>

    /** 批量更新六维数值和阶段（用户↔角色） */
    @Query("""
        UPDATE relationship_states
        SET trust = :trust,
            respect = :respect,
            affection = :affection,
            curiosity = :curiosity,
            dependence = :dependence,
            conflict = :conflict,
            stage = :stage,
            sourceEventId = :sourceEventId,
            updatedAt = :updatedAt
        WHERE fromId = :fromId AND toId = :toId
    """)
    suspend fun updateAll(
        fromId: String, toId: String,
        trust: Int, respect: Int, affection: Int,
        curiosity: Int, dependence: Int, conflict: Int,
        stage: String, sourceEventId: String?, updatedAt: Long,
    )

    /**
     * P1-6-3 修复：批量更新六维数值 + suppression，在同一 SQL 语句内完成，
     * 消除原先 updateAll → updateSuppression 两步之间的竞态窗口。
     * applyDelta 在 deltaMutex 内调用此方法，suppression 的读改写也被串行化。
     */
    @Query("""
        UPDATE relationship_states
        SET trust = :trust,
            respect = :respect,
            affection = :affection,
            curiosity = :curiosity,
            dependence = :dependence,
            conflict = :conflict,
            suppression = :suppression,
            stage = :stage,
            sourceEventId = :sourceEventId,
            updatedAt = :updatedAt
        WHERE fromId = :fromId AND toId = :toId
    """)
    suspend fun updateAllWithSuppression(
        fromId: String, toId: String,
        trust: Int, respect: Int, affection: Int,
        curiosity: Int, dependence: Int, conflict: Int,
        suppression: Int,
        stage: String, sourceEventId: String?, updatedAt: Long,
    )

    /** Phase 14：更新角色间关系的 jealousy + tension */
    @Query("""
        UPDATE relationship_states
        SET jealousy = :jealousy,
            tension  = :tension,
            updatedAt = :updatedAt
        WHERE fromId = :fromId AND toId = :toId AND isInterCharacter = 1
    """)
    suspend fun updateInterCharacterDynamics(
        fromId: String, toId: String,
        jealousy: Int, tension: Int,
        updatedAt: Long,
    )

    /** 衰减：按规则批量减少（§8.3），由 WorldSimulation 定期触发 */
    @Query("""
        UPDATE relationship_states
        SET curiosity  = MAX(0, curiosity  - :curiosityDecay),
            affection  = MAX(0, affection  - :affectionDecay),
            conflict   = MAX(0, conflict   - :conflictDecay),
            trust      = MAX(0, trust      - :trustDecay),
            updatedAt  = :now
        WHERE updatedAt < :decayBefore
    """)
    suspend fun applyDecay(
        curiosityDecay: Int,
        affectionDecay: Int,
        conflictDecay: Int,
        trustDecay: Int = 0,
        decayBefore: Long,
        now: Long,
    )

    /**
     * M1 修复：按角色精确衰减 Trust（仅对 toId = characterId 的行生效）。
     * 原 applyDecay 是全局 UPDATE（无 toId 过滤），在 forEach 循环里对每个角色
     * 触发时会把所有关系的 trust 都减掉 intDecay，而不是只减该角色的 trust。
     * 此方法只对指定角色的关系行做 trust 衰减，其余关系不受影响。
     */
    @Query("""
        UPDATE relationship_states
        SET trust     = MAX(0, trust - :trustDecay),
            updatedAt = :now
        WHERE toId = :characterId AND isInterCharacter = 0
    """)
    suspend fun applyTrustDecayForCharacter(characterId: String, trustDecay: Int, now: Long)

    /** Phase 2（zaijian）：更新压抑感（HeuristicRelTracker 专用）。全量赋值，仅供知道精确新值的调用方使用。 */
    @Query("""
        UPDATE relationship_states
        SET suppression = :suppression,
            updatedAt   = :now
        WHERE fromId = :fromId AND toId = :toId
    """)
    suspend fun updateSuppression(fromId: String, toId: String, suppression: Int, now: Long)

    /**
     * P-4 修复：suppression 增量式更新，消除 WorldSimulation.runSuppressionRelaxation
     * 的 read-modify-write 竞态。上限由调用方传入（coerceAtMost 已在 SQL 中实现）。
     */
    @Query("""
        UPDATE relationship_states
        SET suppression = MIN(:cap, suppression + :delta),
            updatedAt   = :now
        WHERE fromId = :fromId AND toId = :toId
    """)
    suspend fun incrementSuppression(fromId: String, toId: String, delta: Int, cap: Int, now: Long)

    /** Phase 14：嫉妒和紧张度自然衰减（每轮 -2） */
    @Query("""
        UPDATE relationship_states
        SET jealousy = MAX(0, jealousy - :decay),
            tension  = MAX(0, tension  - :tensionDecay),
            updatedAt = :now
        WHERE isInterCharacter = 1 AND updatedAt < :decayBefore
    """)
    suspend fun applyInterCharacterDecay(
        decay: Int = 2,
        tensionDecay: Int = 1,
        decayBefore: Long,
        now: Long,
    )
}
