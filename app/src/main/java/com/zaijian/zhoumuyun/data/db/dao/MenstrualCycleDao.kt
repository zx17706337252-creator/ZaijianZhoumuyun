package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.zaijian.zhoumuyun.data.db.entity.MenstrualCycleEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class MenstrualCycleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(entity: MenstrualCycleEntity)

    @Query("SELECT * FROM menstrual_cycle WHERE characterId = :characterId LIMIT 1")
    abstract suspend fun get(characterId: Int): MenstrualCycleEntity?

    @Query("SELECT * FROM menstrual_cycle WHERE characterId = :characterId LIMIT 1")
    abstract fun observe(characterId: Int): Flow<MenstrualCycleEntity?>

    @Query("SELECT * FROM menstrual_cycle")
    abstract suspend fun getAll(): List<MenstrualCycleEntity>

    /** 检查某角色是否已有记录 */
    @Query("SELECT COUNT(*) FROM menstrual_cycle WHERE characterId = :characterId")
    abstract suspend fun count(characterId: Int): Int

    /**
     * P2-10 修复：原子化 initIfAbsent。
     * 先查 count，若为 0 则 insert（IGNORE 策略），在单个事务内完成。
     * 消除 count→insert 间的 TOCTOU 窗口——两个并发 initIfAbsent
     * 都读到 count=0 后各自 insert，IGNORE 让第二个静默跳过。
     */
    @Transaction
    open suspend fun initIfAbsent(entity: MenstrualCycleEntity) {
        if (count(entity.characterId) == 0) {
            insertIgnore(entity)
        }
    }

    /** P2-10 修复：INSERT OR IGNORE，返回 -1 表示冲突未插入 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIgnore(entity: MenstrualCycleEntity): Long

    /**
     * 审查报告问题14修复：原 Repository.resetAnchorToToday() 是"先 get() 读取，
     * 再 upsert() 写入"两步操作，无事务保护——两个并发重置可能互相覆盖
     * （TOCTOU）。参照 initIfAbsent 的 @Transaction 模式，把"读取现有记录
     * （若无则用默认值）→ 只替换 cycleAnchorAt → 写回"合并为单个原子事务。
     */
    @Transaction
    open suspend fun resetAnchorToToday(characterId: Int, now: Long) {
        val existing = get(characterId)
        upsert(
            (existing ?: MenstrualCycleEntity(characterId = characterId, cycleAnchorAt = now))
                .copy(cycleAnchorAt = now)
        )
    }

    // 批次3 3-1修复：女儿生成回滚时清理 menstrual_cycle 孤儿行
    @Query("DELETE FROM menstrual_cycle WHERE characterId = :characterId")
    abstract suspend fun deleteByCharacterId(characterId: Int)
}
