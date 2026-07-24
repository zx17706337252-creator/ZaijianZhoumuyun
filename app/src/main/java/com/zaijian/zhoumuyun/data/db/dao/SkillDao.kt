package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.zaijian.zhoumuyun.data.db.entity.SkillEditLogEntity
import com.zaijian.zhoumuyun.data.db.entity.SkillEntity
import kotlinx.coroutines.flow.Flow

/**
 * Window C · 技能系统 DAO。
 *
 * ## 与设计方案 v1.2 §4.1 草案的差异（以源码为准）
 *
 * - **`abstract class` 而非 `interface`**：本工程记忆主表 [MemoryDao] 即
 *   `abstract class MemoryDao`（`MemoryDao.kt:22`），原因是 `@Transaction` 标注的
 *   组合写法（先写主表再写日志、原子删除主表+日志）需要带方法体的默认实现，
 *   `interface` 不支持。技能同样有"写主表 + 写变更日志"的原子需求，故沿用 abstract class。
 * - 新增了草案里没列、但 Repository/工具/护栏实际需要的方法：
 *   [getById]（skill_expand 读取 fullContent / skill_edit 定位）、
 *   [countAgentCreatedSince]（§5 节流"单角色单日 5 条"判定）、
 *   [incrementUsage] / [adjustFeedback]（§3.5 步骤 3/6 的计数器自增，原子 UPDATE）、
 *   [createWithLog] / [updateWithLog] / [deleteWithLogs]（主表+日志原子写）。
 *
 * 观察方法返回 `Flow`，范式对齐 `MemoryDao.observeCoreMemories()` 等
 * （`MemoryDao.kt:105,150`）；一次性查询为 `suspend`，对齐 `MemoryDao.getCoreMemories()`
 * （`MemoryDao.kt:98`）。
 */
@Dao
abstract class SkillDao {

    // ── 观察接口（供 UI 订阅，§4.1）──────────────────────────────

    @Query("SELECT * FROM skills WHERE characterId = :characterId ORDER BY updatedAt DESC")
    abstract fun observeSkills(characterId: Int): Flow<List<SkillEntity>>

    @Query("SELECT * FROM skill_edit_log WHERE skillId = :skillId ORDER BY timestamp DESC")
    abstract fun observeEditLog(skillId: String): Flow<List<SkillEditLogEntity>>

    // ── 一次性查询（供目录生成 / 去重 / 展开）─────────────────────

    @Query("SELECT * FROM skills WHERE characterId = :characterId AND status = 'ACTIVE'")
    abstract suspend fun getActiveSkills(characterId: Int): List<SkillEntity>

    @Query("SELECT * FROM skills WHERE id = :skillId")
    abstract suspend fun getById(skillId: String): SkillEntity?

    /**
     * §5 节流：统计某角色自 [sinceTimestamp] 起 Agent 自主新建的技能数。
     * `sourceType = 'AGENT_AUTONOMOUS'` 对应 [SkillSourceType.AGENT_AUTONOMOUS.name]。
     */
    @Query(
        "SELECT COUNT(*) FROM skills " +
            "WHERE characterId = :characterId AND sourceType = 'AGENT_AUTONOMOUS' AND createdAt >= :sinceTimestamp"
    )
    abstract suspend fun countAgentCreatedSince(characterId: Int, sinceTimestamp: Long): Int

    // ── 写入 ─────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(skill: SkillEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertLog(log: SkillEditLogEntity)

    @Update
    abstract suspend fun update(skill: SkillEntity)

    @Query("DELETE FROM skills WHERE id = :skillId")
    abstract suspend fun deleteById(skillId: String)

    @Query("DELETE FROM skill_edit_log WHERE skillId = :skillId")
    abstract suspend fun deleteLogsBySkillId(skillId: String)

    /**
     * §3.5 步骤 3：skill_expand 调用时原子自增 usageCount 并刷新 lastUsedAt/updatedAt。
     */
    @Query(
        "UPDATE skills SET usageCount = usageCount + 1, lastUsedAt = :now, updatedAt = :now " +
            "WHERE id = :skillId"
    )
    abstract suspend fun incrementUsage(skillId: String, now: Long)

    /**
     * §3.5 步骤 6：skill_feedback 调用时原子调整成功/失败计数。Repository 传
     * (successDelta=1,failureDelta=0) 或 (0,1)。
     */
    @Query(
        "UPDATE skills SET successCount = MAX(0, successCount + :successDelta), " +
            "failureCount = MAX(0, failureCount + :failureDelta), updatedAt = :now WHERE id = :skillId"
    )
    abstract suspend fun adjustFeedback(
        skillId: String,
        successDelta: Int,
        failureDelta: Int,
        now: Long,
    )

    // ── 组合写（原子事务，主表 + 变更日志）────────────────────────

    /** 创建技能并同步写第一条变更日志，同一事务内完成。 */
    @Transaction
    open suspend fun createWithLog(skill: SkillEntity, log: SkillEditLogEntity) {
        insert(skill)
        insertLog(log)
    }

    /** 编辑/废弃/恢复：更新主表行并写一条变更日志，同一事务内完成。 */
    @Transaction
    open suspend fun updateWithLog(skill: SkillEntity, log: SkillEditLogEntity) {
        update(skill)
        insertLog(log)
    }

    /** 用户彻底删除：主表 + 该技能的全部变更日志在同一事务内删除。 */
    @Transaction
    open suspend fun deleteWithLogs(skillId: String) {
        deleteLogsBySkillId(skillId)
        deleteById(skillId)
    }
}
